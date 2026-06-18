package com.restlytics;

/**
 * Ships a fully-built OTLP/JSON {@code ExportTraceServiceRequest} (already serialized
 * to a JSON string) to the ingestion service.
 *
 * <p>Implementations MUST be fire-and-forget and MUST NOT throw — telemetry must never
 * be able to fail (or slow) the host application's request. Any transport error is
 * swallowed, never surfaced.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public interface Transport {

    /**
     * Send a pre-serialized OTLP/JSON body. Implementations gzip and POST it to
     * {@code {ingestUrl}/v1/traces} off the request thread.
     *
     * @param jsonBody the OTLP/JSON {@code ExportTraceServiceRequest} as a string
     */
    void send(String jsonBody);

    /** Release any background resources (executor, client). Best-effort, never throws. */
    default void close() {
    }
}
