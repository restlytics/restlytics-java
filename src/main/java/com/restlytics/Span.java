package com.restlytics;

import java.util.ArrayList;
import java.util.List;

/**
 * A single span, accumulated in-request and serialized to OTLP/JSON on flush.
 *
 * <p>Timestamps are kept as {@code long} nanoseconds internally and only stringified
 * at serialization time — the OTLP/JSON contract requires {@code *UnixNano} fields to
 * be decimal STRINGS (to preserve 64-bit precision through JSON).
 *
 * <p>Attribute values are kept as typed entries and converted to the OTLP AnyValue
 * wrapper ({@code {"stringValue"|"intValue"|...}}) at serialization. The single most
 * error-prone rule lives here: {@code intValue} MUST be a string.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Span {

    /** OTLP SpanKind enum values we use. */
    public static final int KIND_SERVER = 2;
    public static final int KIND_CLIENT = 3;

    /** OTLP status codes. */
    public static final int STATUS_UNSET = 0;
    public static final int STATUS_OK = 1;
    public static final int STATUS_ERROR = 2;

    /** AnyValue discriminators for attribute serialization. */
    enum AttrType { STRING, INT, DOUBLE, BOOL }

    /** A single typed attribute (insertion-ordered). */
    static final class Attr {
        final String key;
        final AttrType type;
        final String stringValue;
        final long intValue;
        final double doubleValue;
        final boolean boolValue;

        private Attr(String key, AttrType type, String s, long i, double d, boolean b) {
            this.key = key;
            this.type = type;
            this.stringValue = s;
            this.intValue = i;
            this.doubleValue = d;
            this.boolValue = b;
        }

        static Attr string(String k, String v) {
            return new Attr(k, AttrType.STRING, v, 0L, 0d, false);
        }

        static Attr integer(String k, long v) {
            return new Attr(k, AttrType.INT, null, v, 0d, false);
        }

        static Attr dbl(String k, double v) {
            return new Attr(k, AttrType.DOUBLE, null, 0L, v, false);
        }

        static Attr bool(String k, boolean v) {
            return new Attr(k, AttrType.BOOL, null, 0L, 0d, v);
        }
    }

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private String name;
    private final int kind;
    private final long startUnixNano;
    private long endUnixNano;

    private final List<Attr> attributes = new ArrayList<>();

    private int statusCode = STATUS_UNSET;
    private String statusMessage;

    public Span(String traceId, String spanId, String parentSpanId, String name, int kind,
                long startUnixNano, long endUnixNano) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.name = name;
        this.kind = kind;
        this.startUnixNano = startUnixNano;
        this.endUnixNano = endUnixNano;
    }

    public String spanId() {
        return spanId;
    }

    public String traceId() {
        return traceId;
    }

    public int kind() {
        return kind;
    }

    public long startUnixNano() {
        return startUnixNano;
    }

    public long endUnixNano() {
        return endUnixNano;
    }

    public Span setName(String name) {
        this.name = name;
        return this;
    }

    public Span setEnd(long endUnixNano) {
        this.endUnixNano = endUnixNano;
        return this;
    }

    public Span setString(String key, String value) {
        attributes.add(Attr.string(key, value));
        return this;
    }

    /** Record an int attribute. Serialized as {@code intValue} (a STRING) per the contract. */
    public Span setInt(String key, long value) {
        attributes.add(Attr.integer(key, value));
        return this;
    }

    public Span setDouble(String key, double value) {
        attributes.add(Attr.dbl(key, value));
        return this;
    }

    public Span setBool(String key, boolean value) {
        attributes.add(Attr.bool(key, value));
        return this;
    }

    public Span setStatus(int code, String message) {
        this.statusCode = code;
        if (message != null) {
            // Cap to keep payloads bounded; full stack traces don't belong on the wire.
            this.statusMessage = message.length() > 1024 ? message.substring(0, 1024) : message;
        }
        return this;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Read a string attribute by key (used for self-time category bucketing). */
    public String getString(String key) {
        for (Attr a : attributes) {
            if (a.key.equals(key) && a.type == AttrType.STRING) {
                return a.stringValue;
            }
        }
        return null;
    }

    /** Duration in nanoseconds (clamped non-negative against clock skew). */
    public long durationNs() {
        return Math.max(0L, endUnixNano - startUnixNano);
    }

    // ---- package-private accessors for the OTLP serializer ----

    String parentSpanId() {
        return parentSpanId;
    }

    String name() {
        return name;
    }

    String statusMessage() {
        return statusMessage;
    }

    List<Attr> attrs() {
        return attributes;
    }
}
