package com.restlytics.spring;

import com.restlytics.Redaction;
import com.restlytics.RestlyticsConfig;
import com.restlytics.Span;
import com.restlytics.Tracer;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;

/**
 * REVIEW-ONLY (depends on spring-web; cannot compile offline without it — {@code provided}).
 *
 * <p>Outbound HTTP instrumentation for {@code RestTemplate} (best-effort). Records an
 * HTTP CLIENT span (SPEC §4: {@code kind=3}, {@code restlytics.category="http"}) around
 * each outbound call, with {@code http.request.method}, a REDACTED {@code url.full},
 * {@code server.address}, and {@code http.response.status_code}.
 *
 * <p>To instrument: register this interceptor on your {@code RestTemplate}
 * ({@code restTemplate.getInterceptors().add(...)}). For {@code WebClient}, the
 * equivalent is an {@code ExchangeFilterFunction} — see the README; the timing and
 * attribute rules are identical. MUST NOT throw into the caller.
 */
public final class RestlyticsClientHttpInterceptor implements ClientHttpRequestInterceptor {

    private final Tracer tracer;
    private final RestlyticsConfig config;

    public RestlyticsClientHttpInterceptor(Tracer tracer, RestlyticsConfig config) {
        this.tracer = tracer;
        this.config = config;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (!config.isInstrumentHttp()) {
            return execution.execute(request, body);
        }

        Tracer.OutboundContext context;
        try {
            context = tracer.outboundContext();
            if (context != null) {
                request.getHeaders().set("traceparent", context.traceparent);
            }
        } catch (Throwable ignored) {
            context = null;
        }
        if (context == null) {
            return execution.execute(request, body);
        }

        long start = safeNow();
        ClientHttpResponse response = null;
        int status = 0;
        Throwable error = null;
        try {
            response = execution.execute(request, body);
            try {
                status = response.getStatusCode().value();
            } catch (Throwable ignored) {
                status = 0;
            }
            return response;
        } catch (IOException | RuntimeException e) {
            error = e;
            throw e;
        } finally {
            try {
                record(request, start, status, error, context.spanId);
            } catch (Throwable ignored) {
                // never break the caller's HTTP request
            }
        }
    }

    private void record(HttpRequest request, long start, int status, Throwable error, String spanId) {
        long end = safeNow();
        URI uri = request.getURI();
        String method = request.getMethod() != null ? request.getMethod().name() : "GET";
        String host = uri != null ? uri.getHost() : null;
        String fullUrl = uri != null ? Redaction.redactUrl(uri.toString(), config.getRedactQueryKeys()) : "";

        Span span = tracer.addChildSpan(
                method + " " + (host == null ? "" : host),
                start,
                end,
                Span.KIND_CLIENT,
                spanId);
        if (span == null) {
            return;
        }
        span.setString("restlytics.category", "http");
        span.setString("http.request.method", method);
        span.setString("url.full", fullUrl);
        if (host != null) {
            span.setString("server.address", host);
        }
        if (status > 0) {
            span.setInt("http.response.status_code", status);
        }
        if (error != null || status >= 500) {
            span.setStatus(Span.STATUS_ERROR,
                    error != null ? error.getClass().getName() : "HTTP " + status);
        }
    }

    private long safeNow() {
        try {
            return tracer.nowNs();
        } catch (Throwable t) {
            return System.currentTimeMillis() * 1_000_000L;
        }
    }
}
