package com.restlytics;

/** Payload-free, process-local delivery counters exposed by {@link HttpTransport}. */
public record TransportDiagnostics(
        long acceptedBatches,
        long deliveredBatches,
        long droppedBatches,
        long failedBatches,
        int queuedBatches,
        int inFlightBatches,
        int queueCapacity,
        boolean closed) {
}
