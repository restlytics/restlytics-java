package com.restlytics.spring;

import com.restlytics.RestlyticsConfig;
import com.restlytics.Span;
import com.restlytics.Tracer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * REVIEW-ONLY (depends on Spring + Servlet API; cannot compile offline without those
 * jars on the classpath — they are {@code provided} in the pom).
 *
 * <p>Global {@link OncePerRequestFilter} that owns the root SERVER span.
 *
 * <p>Flow:
 * <ul>
 *   <li>Open the root span as early as possible (continuing an incoming W3C
 *       {@code traceparent} for distributed tracing).</li>
 *   <li>Run the rest of the chain.</li>
 *   <li>In a {@code finally}, after the response is produced, read the resolved route
 *       TEMPLATE from {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} (NEVER the
 *       raw URL — SPEC §4 #1 correctness rule), set HTTP attributes, mark errors, then
 *       finish the span (which computes self-time and fires the OTLP batch
 *       fire-and-forget through the transport).</li>
 *   <li>Always call {@link Tracer#remove()} so the worker thread's ThreadLocal never
 *       leaks into the next request (servlet thread-pool reuse).</li>
 * </ul>
 *
 * <p>Wrapped so a bug in our own instrumentation can never break a served request.
 */
public final class RestlyticsFilter extends OncePerRequestFilter {

    private final Tracer tracer;
    private final RestlyticsConfig config;

    public RestlyticsFilter(Tracer tracer, RestlyticsConfig config) {
        this.tracer = tracer;
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Skip ignored paths (health checks, actuator, etc.) before any work.
        String path = request.getRequestURI();
        if (config.isIgnored(path)) {
            chain.doFilter(request, response);
            return;
        }

        String method = safeMethod(request);
        // Provisional name; the real http.route template isn't known until the handler
        // has been resolved, so we finalize the name + route attribute below.
        try {
            tracer.startServerSpan(method + " " + path, request.getHeader("traceparent"));
        } catch (Throwable ignored) {
            // Never let instrumentation break the request.
        }

        Throwable thrown = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            try {
                finish(request, response, method, thrown);
            } catch (Throwable ignored) {
                // best-effort
            } finally {
                tracer.remove();
            }
        }
    }

    private void finish(HttpServletRequest request, HttpServletResponse response,
                        String method, Throwable thrown) {
        Span root = tracer.rootSpan();
        if (root == null) {
            return; // not sampled / ignored
        }

        // http.route MUST be the TEMPLATE (e.g. /users/{id}), never the raw URL.
        // BEST_MATCHING_PATTERN_ATTRIBUTE is populated by Spring MVC once the handler
        // is matched. Unmatched raw paths may contain identifiers/tokens, so use a wildcard.
        String template = routeTemplate(request);

        int status = response.getStatus();

        root.setName(method + " " + template);
        root.setString("http.request.method", method);
        root.setString("http.route", template);
        root.setInt("http.response.status_code", status);

        // Crash & error detection: an uncaught exception or 5xx becomes ERROR.
        if (thrown != null) {
            root.setStatus(Span.STATUS_ERROR, thrown.getClass().getName());
        } else if (status >= 500) {
            if (root.statusCode() != Span.STATUS_ERROR) {
                root.setStatus(Span.STATUS_ERROR, "HTTP " + status);
            }
        } else if (root.statusCode() == Span.STATUS_UNSET) {
            root.setStatus(Span.STATUS_OK, null);
        }

        tracer.finishServerSpan();
    }

    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String && !((String) pattern).isEmpty()) {
            return (String) pattern;
        }
        return "/*";
    }

    private static String safeMethod(HttpServletRequest request) {
        String m = request.getMethod();
        return m == null ? "GET" : m;
    }
}
