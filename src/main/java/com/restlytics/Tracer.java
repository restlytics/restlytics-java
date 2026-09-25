package com.restlytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-request tracer. Holds the active trace id, the root SERVER span, and the
 * in-request child span buffer in a {@link ThreadLocal}, so concurrent requests on a
 * servlet thread pool are fully isolated. Owns the head-based sampling decision and,
 * on finish, computes the self-time rollups and flushes the OTLP batch through the
 * {@link Transport} (fire-and-forget).
 *
 * <p>ThreadLocal note: a servlet container reuses worker threads across requests, so
 * {@link #remove()} MUST be called when a request completes (the filter does this in a
 * {@code finally}) to avoid leaking spans (and the trace id) between requests.
 *
 * <p>Timing model: we use {@link System#nanoTime()} (monotonic) for DURATIONS — it
 * isn't affected by NTP/clock adjustments — anchored to a single wall-clock reading so
 * we can emit absolute epoch-nanosecond timestamps. Each span's absolute time is
 * {@code wallAnchorNs + (nanoTime - monoAnchorNs)}.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Tracer {

    /** Mutable per-request state, kept off the Tracer instance so it is thread-isolated. */
    static final class State {
        boolean enabled;
        boolean sampled;
        String traceId = "";
        String rootParentSpanId;
        String rootSpanId;
        Span rootSpan;
        final List<Span> spans = new ArrayList<>();
        long wallAnchorNs;
        long monoAnchorNs;
        int dbQueryCount;
    }

    private final ThreadLocal<State> state = ThreadLocal.withInitial(State::new);

    private final Transport transport;
    private final String serviceName;
    private final String environment;
    private final double sampleRate;
    private final int maxSpans;

    public Tracer(Transport transport, String serviceName, String environment,
                  double sampleRate, int maxSpans) {
        this.transport = transport;
        this.serviceName = serviceName;
        this.environment = environment;
        this.sampleRate = sampleRate;
        this.maxSpans = maxSpans > 0 ? maxSpans : 2000;
    }

    /** Convenience constructor from a resolved config. */
    public Tracer(Transport transport, RestlyticsConfig config) {
        this(transport, config.getServiceName(), config.getEnv(), config.getSampleRate(), config.getMaxSpans());
    }

    public boolean isSampled() {
        State s = state.get();
        return s.enabled && s.sampled;
    }

    public String traceId() {
        return state.get().traceId;
    }

    public String rootSpanId() {
        return state.get().rootSpanId;
    }

    public Span rootSpan() {
        return state.get().rootSpan;
    }

    /**
     * Open the root SERVER span at request start.
     *
     * <p>Continues an incoming W3C traceparent if present (distributed tracing),
     * otherwise mints a fresh trace id. The sampling decision is HEAD-BASED and made
     * exactly once here, keyed off the trace id, so all spans in a trace share the same
     * fate (and a continued trace inherits the upstream sampled flag).
     */
    public void startServerSpan(String name, String traceparent) {
        State s = freshState();
        s.enabled = true;

        Ids.Traceparent incoming = Ids.parseTraceparent(traceparent);
        if (incoming != null) {
            s.traceId = incoming.traceId;
            s.rootParentSpanId = incoming.parentSpanId;
            // Honor the upstream sampled bit EXACTLY — no local re-roll. The decision is
            // made once per trace, by whoever started it. Re-rolling here would let this
            // service drop a trace its caller chose to keep, punching a hole in the middle
            // of every distributed trace whenever sampleRate < 1.0.
            s.sampled = incoming.sampled;
        } else {
            s.traceId = Ids.traceId();
            s.rootParentSpanId = null;
            s.sampled = sampleDecision(s.traceId);
        }

        // Anchor wall-clock <-> monotonic clocks together.
        s.wallAnchorNs = wallClockNs();
        s.monoAnchorNs = System.nanoTime();
        s.rootSpanId = Ids.spanId();

        if (!s.sampled) {
            return; // not sampled: stay cheap, record nothing
        }

        long now = nowNs(s);
        s.rootSpan = new Span(
                s.traceId,
                s.rootSpanId,
                s.rootParentSpanId,
                name,
                Span.KIND_SERVER,
                now,
                now);
    }

    /**
     * Create a CLIENT child span over an absolute {@code [startNs, endNs]} window.
     *
     * <p>DB/HTTP/cache instrumentation often only learns of a span AFTER it finished
     * (e.g. an executed query reports elapsed time), so callers back-date the start.
     * Returns {@code null} when not sampled or when the buffer cap is hit (telemetry
     * must never grow unbounded).
     */
    public Span addChildSpan(String name, long startNs, long endNs, int kind) {
        return addChildSpan(name, startNs, endNs, kind, null);
    }

    /** Record a child using a pre-minted propagated span id. */
    public Span addChildSpan(String name, long startNs, long endNs, int kind, String spanId) {
        State s = state.get();
        if (!(s.enabled && s.sampled) || s.rootSpan == null) {
            return null;
        }
        if (s.spans.size() >= maxSpans) {
            return null;
        }
        Span span = new Span(
                s.traceId,
                spanId == null ? Ids.spanId() : spanId,
                s.rootSpan.spanId(),
                name,
                kind,
                startNs,
                endNs);
        s.spans.add(span);
        return span;
    }

    /** Outbound CLIENT context, including non-recording unsampled traces. */
    public OutboundContext outboundContext() {
        State s = state.get();
        if (!s.enabled || s.traceId == null || s.traceId.isEmpty()) {
            return null;
        }
        String spanId = Ids.spanId();
        return new OutboundContext(
                Ids.traceparent(s.traceId, spanId, s.sampled),
                spanId);
    }

    public static final class OutboundContext {
        public final String traceparent;
        public final String spanId;

        OutboundContext(String traceparent, String spanId) {
            this.traceparent = traceparent;
            this.spanId = spanId;
        }
    }

    public void incrementDbQueryCount() {
        state.get().dbQueryCount++;
    }

    /** Current absolute epoch-ns reading, for instrumentation that times its own spans. */
    public long nowNs() {
        return nowNs(state.get());
    }

    /**
     * Close the root span, compute self-time rollups, and flush the batch.
     *
     * <p>Self-time = interval-union of child spans per category (db/http/cache), and
     * {@code app = root duration − union(ALL children)} (clamped ≥ 0). We attach these
     * to the root SERVER span as {@code restlytics.self_ns.*} so the dashboard's time
     * breakdown is correct even when children overlap.
     */
    public void finishServerSpan() {
        State s = state.get();
        if (!(s.enabled && s.sampled) || s.rootSpan == null) {
            return;
        }

        s.rootSpan.setEnd(nowNs(s));
        attachSelfTime(s);
        s.rootSpan.setInt("restlytics.db_query_count", s.dbQueryCount);
        s.rootSpan.setString("restlytics.category", "app");

        flush(s);
    }

    /**
     * Build the OTLP payload and hand it to the transport (fire-and-forget).
     * Resilient: any failure is swallowed so flushing telemetry can't break the app.
     */
    private void flush(State s) {
        if (s.rootSpan == null) {
            return;
        }
        try {
            List<Span> all = new ArrayList<>(s.spans.size() + 1);
            all.add(s.rootSpan);
            all.addAll(s.spans);
            String body = Otlp.build(serviceName, environment, all);
            transport.send(body);
        } catch (Throwable ignored) {
            // Telemetry must never throw into the host application.
        }
    }

    /**
     * Clear the ThreadLocal entry entirely. MUST be called at request completion so a
     * reused worker thread never leaks state into the next request.
     */
    public void remove() {
        state.remove();
    }

    private State freshState() {
        // Replace any leftover state defensively (in case remove() was missed).
        State s = new State();
        state.set(s);
        return s;
    }

    private long nowNs(State s) {
        return s.wallAnchorNs + (System.nanoTime() - s.monoAnchorNs);
    }

    /** Compute and attach {@code restlytics.self_ns.{db,http,cache,app}} to the root span. */
    private void attachSelfTime(State s) {
        Span root = s.rootSpan;
        if (root == null) {
            return;
        }
        long rootStart = root.startUnixNano();
        long rootDur = root.durationNs();

        List<long[]> db = new ArrayList<>();
        List<long[]> http = new ArrayList<>();
        List<long[]> cache = new ArrayList<>();
        List<long[]> app = new ArrayList<>();
        List<long[]> all = new ArrayList<>();

        for (Span child : s.spans) {
            long start = child.startUnixNano() - rootStart;
            long end = child.endUnixNano() - rootStart;
            if (end < start) {
                end = start; // clamp inverted interval (clock skew)
            }
            long[] iv = new long[] {start, end};
            all.add(iv);
            switch (categoryOf(child)) {
                case "db":
                    db.add(iv);
                    break;
                case "http":
                    http.add(iv);
                    break;
                case "cache":
                    cache.add(iv);
                    break;
                default:
                    app.add(iv);
                    break;
            }
        }

        long selfDb = Intervals.unionLength(toArray(db));
        long selfHttp = Intervals.unionLength(toArray(http));
        long selfCache = Intervals.unionLength(toArray(cache));
        // app self-time = explicit app-category child time + the root's own exclusive
        // (uncovered) time. Mirrors the ingestion service's computation.
        long selfApp = Intervals.unionLength(toArray(app))
                + Math.max(0L, rootDur - Intervals.unionLength(toArray(all)));

        root.setInt("restlytics.self_ns.db", selfDb);
        root.setInt("restlytics.self_ns.http", selfHttp);
        root.setInt("restlytics.self_ns.cache", selfCache);
        root.setInt("restlytics.self_ns.app", selfApp);
    }

    private static long[][] toArray(List<long[]> list) {
        return list.toArray(new long[0][]);
    }

    /**
     * Read a span's {@code restlytics.category} attribute for self-time bucketing.
     * Falls back to {@code app} so an uncategorized child still contributes sensibly.
     */
    private static String categoryOf(Span span) {
        String cat = span.getString("restlytics.category");
        if ("db".equals(cat) || "http".equals(cat) || "cache".equals(cat) || "app".equals(cat)) {
            return cat;
        }
        return "app";
    }

    /**
     * Head-based trace-id-ratio sampling. Deterministic in the trace id so the decision
     * is stable and unbiased: take the last 8 hex chars (32 bits) as entropy and keep
     * the trace if that fraction falls under the configured rate.
     */
    boolean sampleDecision(String traceId) {
        if (sampleRate >= 1.0) {
            return true;
        }
        if (sampleRate <= 0.0) {
            return false;
        }
        String tail = traceId.length() >= 8 ? traceId.substring(traceId.length() - 8) : traceId;
        long bucket;
        try {
            bucket = Long.parseLong(tail.isEmpty() ? "0" : tail, 16); // 0 .. 2^32-1
        } catch (NumberFormatException e) {
            bucket = 0L;
        }
        double ratio = bucket / (double) 0xFFFFFFFFL;
        return ratio < sampleRate;
    }

    /** Wall-clock epoch nanoseconds from {@link System#currentTimeMillis()} (ms resolution). */
    private static long wallClockNs() {
        // Millisecond resolution is plenty for span anchoring — sub-ms precision comes
        // from the monotonic (nanoTime) delta.
        return System.currentTimeMillis() * 1_000_000L;
    }
}
