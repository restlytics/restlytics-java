package com.restlytics;

/**
 * Tests for {@link Tracer#startServerSpan} head-based sampling — "decide once per
 * trace" (SPEC §3).
 *
 * <p>Every case runs at {@code sampleRate = 0.0}, so a local re-roll would always
 * return {@code false}: a continued trace that survives proves we inherit the upstream
 * bit rather than re-deciding it.
 *
 * <p>Same dual-mode design as {@link SqlTest}: runs under {@code mvn test} and offline:
 * <pre>
 *   javac -d /tmp/out src/main/java/com/restlytics/*.java src/test/java/com/restlytics/TracerSamplingTest.java
 *   java  -cp /tmp/out com.restlytics.TracerSamplingTest
 * </pre>
 */
public final class TracerSamplingTest {

    private static final String SAMPLED_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String NOT_SAMPLED_TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00";

    /** A tracer that ships nothing and would drop every trace it decided for itself. */
    private static Tracer neverSamplingTracer() {
        return new Tracer(new NullTransport(), "test-svc", "test", 0.0, 2000);
    }

    public void testContinuedTraceInheritsUpstreamSampled() {
        // The upstream said "keep", so we keep — even though our own rate is 0.0.
        // Re-rolling here would break the distributed trace mid-chain.
        Tracer tracer = neverSamplingTracer();
        try {
            tracer.startServerSpan("GET /orders", SAMPLED_TRACEPARENT);
            Assert.isTrue(tracer.isSampled(),
                    "continued trace with sampled flag set must stay sampled at rate 0.0");
        } finally {
            tracer.remove();
        }
    }

    public void testContinuedTraceInheritsUpstreamNotSampled() {
        // The upstream said "drop", so we drop — inheritance cuts both ways.
        Tracer tracer = neverSamplingTracer();
        try {
            tracer.startServerSpan("GET /orders", NOT_SAMPLED_TRACEPARENT);
            Assert.isTrue(!tracer.isSampled(),
                    "continued trace with sampled flag clear must not be sampled");
        } finally {
            tracer.remove();
        }
    }

    public void testRootTraceStillRollsLocally() {
        // No traceparent: this service starts the trace, so the local head-based
        // decision applies — and at rate 0.0 that means drop.
        Tracer tracer = neverSamplingTracer();
        try {
            tracer.startServerSpan("GET /orders", null);
            Assert.isTrue(!tracer.isSampled(),
                    "root trace at rate 0.0 must not be sampled");
        } finally {
            tracer.remove();
        }
    }

    public static void main(String[] args) {
        Assert.run(TracerSamplingTest.class);
    }
}
