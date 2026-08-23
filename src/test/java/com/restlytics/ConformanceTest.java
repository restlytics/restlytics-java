package com.restlytics;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Runs the shared transport-neutral SDK contract without third-party test dependencies. */
public final class ConformanceTest {

    private static final Path FIXTURES = Path.of("src/test/resources/conformance/v1");

    public void testSharedOtlpPropagationRedactionErrorAndSamplingFixture() throws Exception {
        Properties fixture = properties();
        Span span = new Span(
                fixture.getProperty("trace.id"),
                fixture.getProperty("span.id"),
                fixture.getProperty("span.parent_id"),
                fixture.getProperty("span.name"),
                Integer.parseInt(fixture.getProperty("span.kind")),
                Long.parseLong(fixture.getProperty("span.start_ns")),
                Long.parseLong(fixture.getProperty("span.end_ns")));
        span.setString(
                        fixture.getProperty("attribute.string.key"),
                        fixture.getProperty("attribute.string.value"))
                .setInt(
                        fixture.getProperty("attribute.int.key"),
                        Long.parseLong(fixture.getProperty("attribute.int.value")))
                .setBool(
                        fixture.getProperty("attribute.bool.key"),
                        Boolean.parseBoolean(fixture.getProperty("attribute.bool.value")))
                .setString(
                        fixture.getProperty("redaction.attribute_key"),
                        fixture.getProperty("redaction.attribute_value"))
                .setStatus(
                        Integer.parseInt(fixture.getProperty("error.status_code")),
                        fixture.getProperty("error.message"));

        String expected = Files.readString(FIXTURES.resolve("otlp.expected.json")).trim()
                .replace("${SDK_NAME}", Otlp.SDK_NAME)
                .replace("${SDK_LANGUAGE}", Otlp.SDK_LANGUAGE)
                .replace("${SDK_VERSION}", Otlp.SDK_VERSION);
        String actual = Otlp.build(
                fixture.getProperty("service.name"),
                fixture.getProperty("deployment.environment"),
                List.of(span));
        Assert.eq(expected, actual);

        Ids.Traceparent sampled = Ids.parseTraceparent(fixture.getProperty("propagation.sampled"));
        Assert.isTrue(sampled != null, "sampled traceparent must parse");
        Assert.eq(fixture.getProperty("trace.id"), sampled.traceId);
        Assert.eq(fixture.getProperty("span.id"), sampled.parentSpanId);
        Assert.isTrue(sampled.sampled, "sampled fixture flag must be inherited");
        Ids.Traceparent unsampled = Ids.parseTraceparent(fixture.getProperty("propagation.unsampled"));
        Assert.isTrue(unsampled != null && !unsampled.sampled, "unsampled fixture flag must be inherited");
        Assert.eq(null, Ids.parseTraceparent(fixture.getProperty("propagation.invalid")));

        Tracer zero = new Tracer(
                new NullTransport(), "fixture", "fixture",
                Double.parseDouble(fixture.getProperty("sampling.root_rate_zero")), 2000);
        zero.startServerSpan("fixture", null);
        Assert.isTrue(!zero.isSampled(), "root rate zero must drop");
        zero.remove();
        Tracer one = new Tracer(
                new NullTransport(), "fixture", "fixture",
                Double.parseDouble(fixture.getProperty("sampling.root_rate_one")), 2000);
        one.startServerSpan("fixture", null);
        Assert.isTrue(one.isSampled(), "root rate one must keep");
        one.remove();
    }

    private static Properties properties() throws Exception {
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(FIXTURES.resolve("vectors.properties"))) {
            values.load(input);
        }
        return values;
    }

    public static void main(String[] args) {
        Assert.run(ConformanceTest.class);
    }
}
