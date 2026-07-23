package com.restlytics;

import java.util.List;

/**
 * Builds the top-level OTLP/JSON {@code ExportTraceServiceRequest} body as a JSON
 * string, using a tiny hand-rolled JSON writer (no external JSON library).
 *
 * <p>Shape (matches {@code packages/contract} {@code ExportTraceServiceRequest} and
 * SPEC §2 exactly):
 * <pre>
 * { "resourceSpans": [ {
 *     "resource":   { "attributes": [ ...resource KVs... ] },
 *     "scopeSpans": [ { "scope": {"name": "restlytics-spring", "version": "..."},
 *                       "spans": [ ... ] } ]
 * } ] }
 * </pre>
 *
 * <p>The three classic OTLP footguns are enforced here:
 * <ul>
 *   <li>trace/span ids are lowercase hex of the right length and never all-zero
 *       (guaranteed upstream in {@link Ids});</li>
 *   <li>{@code *UnixNano} fields are decimal STRINGS;</li>
 *   <li>{@code intValue} is a STRING.</li>
 * </ul>
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Otlp {

    /** Stable identifiers for the SDK, surfaced as resource attributes and the scope name. */
    public static final String SDK_NAME = "restlytics-spring";
    public static final String SDK_LANGUAGE = "java";
    public static final String SDK_VERSION = "0.1.1";

    private Otlp() {
    }

    /**
     * Build the full OTLP/JSON request body as a String.
     *
     * @param serviceName {@code service.name} resource attribute
     * @param environment {@code deployment.environment} resource attribute
     * @param spans       all spans for this request (root SERVER span first, by convention)
     */
    public static String build(String serviceName, String environment, List<Span> spans) {
        Json j = new Json();
        j.beginObject();
        j.key("resourceSpans").beginArray();
        j.beginObject();

        // resource
        j.key("resource").beginObject();
        j.key("attributes").beginArray();
        resourceAttr(j, "service.name", serviceName);
        resourceAttr(j, "deployment.environment", environment);
        resourceAttr(j, "telemetry.sdk.name", SDK_NAME);
        resourceAttr(j, "telemetry.sdk.language", SDK_LANGUAGE);
        resourceAttr(j, "telemetry.sdk.version", SDK_VERSION);
        j.endArray(); // attributes
        j.endObject(); // resource

        // scopeSpans
        j.key("scopeSpans").beginArray();
        j.beginObject();
        j.key("scope").beginObject();
        j.key("name").value(SDK_NAME);
        j.key("version").value(SDK_VERSION);
        j.endObject(); // scope
        j.key("spans").beginArray();
        for (Span s : spans) {
            writeSpan(j, s);
        }
        j.endArray(); // spans
        j.endObject(); // scopeSpans[0]
        j.endArray(); // scopeSpans

        j.endObject(); // resourceSpans[0]
        j.endArray(); // resourceSpans
        j.endObject(); // root
        return j.toString();
    }

    private static void resourceAttr(Json j, String key, String value) {
        j.beginObject();
        j.key("key").value(key);
        j.key("value").beginObject();
        j.key("stringValue").value(value == null ? "" : value);
        j.endObject();
        j.endObject();
    }

    private static void writeSpan(Json j, Span s) {
        j.beginObject();
        j.key("traceId").value(s.traceId());
        j.key("spanId").value(s.spanId());
        // parentSpanId is omitted for the root SERVER span (null/empty).
        String parent = s.parentSpanId();
        if (parent != null && !parent.isEmpty()) {
            j.key("parentSpanId").value(parent);
        }
        j.key("name").value(s.name());
        j.key("kind").rawValue(Integer.toString(s.kind()));
        // Decimal STRINGS — int64-safe in JSON.
        j.key("startTimeUnixNano").value(Long.toString(s.startUnixNano()));
        j.key("endTimeUnixNano").value(Long.toString(s.endUnixNano()));

        List<Span.Attr> attrs = s.attrs();
        if (!attrs.isEmpty()) {
            j.key("attributes").beginArray();
            for (Span.Attr a : attrs) {
                writeAttr(j, a);
            }
            j.endArray();
        }

        // Only attach status when it carries signal (OK/ERROR); UNSET is the default.
        if (s.statusCode() != Span.STATUS_UNSET) {
            j.key("status").beginObject();
            j.key("code").rawValue(Integer.toString(s.statusCode()));
            String msg = s.statusMessage();
            if (msg != null && !msg.isEmpty()) {
                j.key("message").value(msg);
            }
            j.endObject();
        }

        j.endObject();
    }

    /** Wrap a typed attribute in the OTLP AnyValue shape. {@code intValue} is a STRING. */
    private static void writeAttr(Json j, Span.Attr a) {
        j.beginObject();
        j.key("key").value(a.key);
        j.key("value").beginObject();
        switch (a.type) {
            case INT:
                // CONTRACT: intValue is a STRING, not a JSON number.
                j.key("intValue").value(Long.toString(a.intValue));
                break;
            case DOUBLE:
                j.key("doubleValue").rawValue(doubleString(a.doubleValue));
                break;
            case BOOL:
                j.key("boolValue").rawValue(a.boolValue ? "true" : "false");
                break;
            case STRING:
            default:
                j.key("stringValue").value(a.stringValue == null ? "" : a.stringValue);
                break;
        }
        j.endObject();
        j.endObject();
    }

    private static String doubleString(double d) {
        // Avoid NaN/Infinity, which are not valid JSON.
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        return Double.toString(d);
    }

    /**
     * Minimal streaming JSON writer. Tracks whether a comma is needed between
     * elements/members per nesting level. Escapes strings per RFC 8259. Intentionally
     * tiny — just enough to emit the OTLP shapes above with no external dependency.
     */
    static final class Json {
        private final StringBuilder sb = new StringBuilder(512);
        // For each open container, true once at least one element/member is written.
        private boolean[] hasItem = new boolean[16];
        private int depth = 0;
        // True when the next token is a value following a key (suppress separator logic).
        private boolean expectingValue = false;

        Json beginObject() {
            preValue();
            sb.append('{');
            push();
            return this;
        }

        Json endObject() {
            pop();
            sb.append('}');
            return this;
        }

        Json beginArray() {
            preValue();
            sb.append('[');
            push();
            return this;
        }

        Json endArray() {
            pop();
            sb.append(']');
            return this;
        }

        /** Write an object key. Must be followed by a value/begin call. */
        Json key(String k) {
            if (hasItem[depth]) {
                sb.append(',');
            }
            hasItem[depth] = true;
            writeString(k);
            sb.append(':');
            expectingValue = true;
            return this;
        }

        /** Write a JSON string value. */
        Json value(String v) {
            preValue();
            writeString(v);
            return this;
        }

        /** Write a raw, pre-formatted JSON token (number, true/false). Caller guarantees validity. */
        Json rawValue(String raw) {
            preValue();
            sb.append(raw);
            return this;
        }

        // Called before writing any value (string/raw/object/array). Handles the
        // array-element separator; object members are separated in key().
        private void preValue() {
            if (expectingValue) {
                expectingValue = false;
                return;
            }
            if (depth > 0) {
                if (hasItem[depth]) {
                    sb.append(',');
                }
                hasItem[depth] = true;
            }
        }

        private void push() {
            depth++;
            if (depth >= hasItem.length) {
                boolean[] grown = new boolean[hasItem.length * 2];
                System.arraycopy(hasItem, 0, grown, 0, hasItem.length);
                hasItem = grown;
            }
            hasItem[depth] = false;
        }

        private void pop() {
            depth--;
        }

        private void writeString(String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
