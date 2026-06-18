package com.restlytics;

import java.security.SecureRandom;

/**
 * Trace / span id generation and W3C {@code traceparent} handling.
 *
 * <p>OTLP/JSON wants lowercase-hex ids: 32 chars (16 bytes) for a trace id,
 * 16 chars (8 bytes) for a span id. The ingestion contract additionally rejects
 * all-zero ids, so we make sure the random bytes are never all-zero.
 *
 * <p>Dependency-free: only {@code java.*} imports, no JSON or framework types.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {
    }

    /** 32 lowercase hex chars (16 random bytes), never all-zero. */
    public static String traceId() {
        return randomHex(16);
    }

    /** 16 lowercase hex chars (8 random bytes), never all-zero. */
    public static String spanId() {
        return randomHex(8);
    }

    private static String randomHex(int numBytes) {
        // SecureRandom is the cryptographically strong source; the all-zero
        // probability is negligible, but the contract forbids it, so guard.
        byte[] buf = new byte[numBytes];
        String hex;
        do {
            RANDOM.nextBytes(buf);
            hex = toHex(buf);
        } while (isAllZero(hex));
        return hex;
    }

    /** Lowercase hex encode without allocating via String.format (hot path). */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static boolean isAllZero(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (hex.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * Parsed W3C {@code traceparent}: the continued trace id, the upstream span id
     * (which becomes the root span's parent), and the sampled flag.
     */
    public static final class Traceparent {
        public final String traceId;
        public final String parentSpanId;
        public final boolean sampled;

        public Traceparent(String traceId, String parentSpanId, boolean sampled) {
            this.traceId = traceId;
            this.parentSpanId = parentSpanId;
            this.sampled = sampled;
        }
    }

    /**
     * Parse a W3C {@code traceparent} header.
     *
     * <p>Format: {@code version-traceid-spanid-flags}, e.g.
     * {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}.
     *
     * <p>Returns {@code null} when absent or malformed so the caller falls back to a
     * fresh trace. Continuing an incoming traceparent stitches one distributed trace
     * across services (e.g. an upstream gateway → this Spring app).
     */
    public static Traceparent parseTraceparent(String header) {
        if (header == null) {
            return null;
        }
        String h = header.trim().toLowerCase();
        // 00-<32hex>-<16hex>-<2hex>
        if (h.length() != 55) {
            return null;
        }
        if (h.charAt(2) != '-' || h.charAt(35) != '-' || h.charAt(52) != '-') {
            return null;
        }
        String version = h.substring(0, 2);
        String traceId = h.substring(3, 35);
        String spanId = h.substring(36, 52);
        String flags = h.substring(53, 55);

        if (!isHex(version) || !isHex(traceId) || !isHex(spanId) || !isHex(flags)) {
            return null;
        }
        // Reject the invalid all-zero trace/parent ids per the W3C spec.
        if (isAllZero(traceId) || isAllZero(spanId)) {
            return null;
        }

        boolean sampled = (hexByte(flags) & 0x01) == 0x01;
        return new Traceparent(traceId, spanId, sampled);
    }

    /** Build a W3C {@code traceparent} value for outbound injection (optional). */
    public static String traceparent(String traceId, String spanId, boolean sampled) {
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static int hexByte(String twoHex) {
        return Integer.parseInt(twoHex, 16);
    }
}
