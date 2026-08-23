package com.restlytics;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/**
 * Default transport: gzip the JSON body and POST it with {@link java.net.http.HttpClient},
 * off the request thread via a small {@link ExecutorService}.
 *
 * <p>Design constraints (all in service of "telemetry must never hurt the host app",
 * SPEC §6):
 * <ul>
 *   <li>Fire-and-forget: {@link #send} enqueues onto a bounded executor and returns
 *       immediately, so request latency is unaffected even if ingest is slow/down.</li>
 *   <li>Hard short timeouts (connect + request) so a slow/unreachable endpoint can't
 *       pile up worker time.</li>
 *   <li>Every error path is swallowed. We never throw into the host application.</li>
 *   <li>Bounded queue with a discard policy: under a flood we drop batches rather than
 *       grow memory or block the caller.</li>
 * </ul>
 *
 * <p>Wire format (must match the ingestion contract exactly):
 * <pre>
 *   POST {ingestUrl}/v1/traces
 *   X-Restlytics-Key: {key}
 *   Content-Type: application/json
 *   Content-Encoding: gzip
 *   body = gzip(json)
 * </pre>
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class HttpTransport implements Transport {

    private final String url;
    private final String key;
    private final int timeoutMs;
    private final HttpClient client;
    private final ThreadPoolExecutor executor;
    private final Consumer<String> onError;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public HttpTransport(String ingestUrl, String key, int timeoutMs) {
        this(ingestUrl, key, timeoutMs, 64, null);
    }

    public HttpTransport(String ingestUrl, String key, int timeoutMs, int queueCapacity,
                         Consumer<String> onError) {
        this.url = stripTrailingSlash(ingestUrl) + "/v1/traces";
        this.key = key == null ? "" : key;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 2000;
        this.onError = onError;

        // Single daemon worker, bounded queue, drop-on-overflow: telemetry must never
        // block the host nor grow unbounded.
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queueCapacity)),
                runnable -> {
                    Thread t = new Thread(runnable, "restlytics-transport");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        exec.allowCoreThreadTimeOut(true);
        this.executor = exec;

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(this.timeoutMs, 1000)))
                .build();
    }

    @Override
    public void send(String jsonBody) {
        // The request path performs only a bounded, non-blocking enqueue. Gzip,
        // URL construction and network I/O stay on the single daemon worker.
        if (closed.get() || jsonBody == null || jsonBody.isEmpty() || key.isEmpty()) {
            recordDrop("restlytics: batch dropped because transport is closed or unconfigured");
            return;
        }
        try {
            pending.incrementAndGet();
            executor.execute(() -> deliver(jsonBody));
            accepted.incrementAndGet();
        } catch (RejectedExecutionException ignored) {
            pending.decrementAndGet();
            recordDrop("restlytics: batch dropped because transport queue is full");
        } catch (Throwable error) {
            pending.decrementAndGet();
            recordDrop("restlytics: enqueue failed: " + error.getClass().getSimpleName());
        }
    }

    /** Return a payload-free delivery-health snapshot for logs and health checks. */
    @Override
    public TransportDiagnostics diagnostics() {
        return new TransportDiagnostics(
                accepted.get(), delivered.get(), dropped.get(), failed.get(),
                executor.getQueue().size(), executor.getActiveCount(),
                executor.getQueue().remainingCapacity() + executor.getQueue().size(),
                closed.get());
    }

    /** Wait for accepted work to settle. Safe to call without closing the transport. */
    @Override
    public boolean flush(int waitMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, waitMs));
        while (pending.get() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void deliver(String jsonBody) {
        try {
            byte[] body = gzip(jsonBody.getBytes(StandardCharsets.UTF_8));
            if (post(body)) {
                delivered.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
        } catch (Throwable error) {
            failed.incrementAndGet();
            report("restlytics: failed to encode or send payload: " + error.getClass().getSimpleName());
        } finally {
            pending.decrementAndGet();
        }
    }

    private boolean post(byte[] body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .header("X-Restlytics-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            // We do not care about the response: any 2xx/4xx/5xx, timeout, or error is
            // treated as "move on". Discard the body to free the connection.
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Throwable error) {
            report("restlytics: send failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    private void recordDrop(String message) {
        dropped.incrementAndGet();
        report(message);
    }

    private void report(String message) {
        if (onError == null) {
            return;
        }
        try {
            onError.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must never throw into the host application.
        }
    }

    private static byte[] gzip(byte[] data) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                int abandoned = executor.shutdownNow().size();
                if (abandoned > 0) {
                    pending.addAndGet(-abandoned);
                    dropped.addAndGet(abandoned);
                    report("restlytics: queued batches dropped at shutdown deadline: " + abandoned);
                }
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }
}
