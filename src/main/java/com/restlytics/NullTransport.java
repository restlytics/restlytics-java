package com.restlytics;

/**
 * No-op transport: builds spans but ships nothing. Used for tests and for disabling
 * delivery while keeping instrumentation (e.g. local dev), and as the safe fallback
 * when no ingest key is configured.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class NullTransport implements Transport {

    @Override
    public void send(String jsonBody) {
        // Intentionally do nothing.
    }
}
