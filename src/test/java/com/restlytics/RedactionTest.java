package com.restlytics;

import java.util.List;

/** Dependency-free privacy boundary tests; runs under javac/java and Maven CI. */
public final class RedactionTest {

    public void testUrlRemovesCredentialsFragmentAndEveryQueryValue() {
        String value = Redaction.redactUrl(
                "https://alice:password@example.test/orders?token=abc&unknown=customer-secret#raw",
                List.of("token"));
        for (String secret : List.of("alice", "password", "abc", "customer-secret", "raw")) {
            Assert.isTrue(!value.contains(secret), "URL leaked " + secret + ": " + value);
        }
    }

    public void testSpanBoundaryDropsContentBearingFields() {
        Span span = new Span("a".repeat(32), "b".repeat(16), null,
                "GET /users/{id}", Span.KIND_SERVER, 1L, 2L);
        span.setString("http.request.method", "GET")
                .setString("http.request.header.authorization", "Bearer abc.def.ghi")
                .setString("spring.request.body", "password=hunter2")
                .setString("log.body", "alice@example.test")
                .setString("url.full", "https://example.test/?unknown=customer-secret")
                .setStatus(Span.STATUS_ERROR,
                        "login failed for alice@example.test password=hunter2");

        String payload = Otlp.build("test", "test", List.of(span));
        for (String secret : List.of(
                "hunter2", "alice@example.test", "customer-secret", "authorization")) {
            Assert.isTrue(!payload.contains(secret), "span leaked " + secret + ": " + payload);
        }
        Assert.isTrue(!payload.contains("\"message\""), "exception message must be omitted");
        Assert.isTrue(Redaction.isSensitiveAttributeKey("hibernate.request.payload"),
                "framework payload key must be sensitive");
        Assert.isTrue(!Redaction.isSensitiveAttributeKey("restlytics.bindings_count"),
                "binding count is safe; only values are forbidden");
    }

    public static void main(String[] args) {
        Assert.run(RedactionTest.class);
    }
}
