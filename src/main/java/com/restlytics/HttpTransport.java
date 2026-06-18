package com.restlytics;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Default transport: gzip the JSON body and POST it with {@link java.net.http.HttpClient},
 * off the request thread via a small {@link ExecutorService}.
 *
 * <p>Design constraints (all in service of "telemetry must never hurt the host app",
 * SPEC §6):
 * <ul>
 *   <li>Fire-and-forget: {@link #send} enqueues onto a bounded executor and returns
 *       immediately, so request latency is unaffected even if ingest is slow/down.</li>
 *   <li>Hard short timeouts (connect + request) so a slow/unreachable endpoint can't
 *       pile up worker time.</li>
 *   <li>Every error path is swallowed. We never throw into the host application.</li>
 *   <li>Bounded queue with a discard policy: under a flood we drop batches rather than
 *       grow memory or block the caller.</li>
 * </ul>
 *
 * <p>Wire format (must match the ingestion contract exactly):
 * <pre>
 *   POST {ingestUrl}/v1/traces
 *   X-Restlytics-Key: {key}
 *   Content-Type: application/json
 *   Content-Encoding: gzip
 *   body = gzip(json)
 * </pre>
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class HttpTransport implements Transport {

    private final String url;
    private final String key;
    private final int timeoutMs;
    private final HttpClient client;
    private final ExecutorService executor;

    public HttpTransport(String ingestUrl, String key, int timeoutMs) {
        this.url = stripTrailingSlash(ingestUrl) + "/v1/traces";
        this.key = key == null ? "" : key;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 2000;

        // Single daemon worker, bounded queue, drop-on-overflow: telemetry must never
        // block the host nor grow unbounded.
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                runnable -> {
                    Thread t = new Thread(runnable, "restlytics-transport");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        exec.allowCoreThreadTimeOut(true);
        this.executor = exec;

        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(this.timeoutMs, 1000)))
                .build();
    }

    @Override
    public void send(String jsonBody) {
        // Bail quietly if misconfigured — and never throw.
        if (jsonBody == null || jsonBody.isEmpty() || key.isEmpty()) {
            return;
        }
        try {
            final byte[] body = gzip(jsonBody.getBytes(StandardCharsets.UTF_8));
            executor.execute(() -> post(body));
        } catch (Throwable ignored) {
            // Enqueue/gzip failure must never propagate into the host app.
        }
    }

    private void post(byte[] body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Content-Encoding", "gzip")
                    .header("X-Restlytics-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            // We do not care about the response: any 2xx/4xx/5xx, timeout, or error is
            // treated as "move on". Discard the body to free the connection.
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Throwable ignored) {
            // Degrade silently on timeout / 503 / connection error — drop the batch.
        }
    }

    private static byte[] gzip(byte[] data) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    @Override
    public void close() {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (Throwable ignored) {
            // best-effort
        }
    }
}
