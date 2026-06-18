package com.restlytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolved configuration for the SDK. Mirrors the shared config keys (SPEC §7) with a
 * language-native surface. Spring wires this from {@code application.properties}
 * ({@code restlytics.*}) in the auto-config; here we also offer env-var resolution so
 * the dependency-free core is usable and testable without Spring.
 *
 * <p>Config keys (env / property):
 * <ul>
 *   <li>{@code RESTLYTICS_KEY} / {@code restlytics.key}</li>
 *   <li>{@code RESTLYTICS_INGEST_URL} / {@code restlytics.ingest-url}</li>
 *   <li>{@code RESTLYTICS_SERVICE_NAME} / {@code restlytics.service-name}</li>
 *   <li>{@code RESTLYTICS_ENV} / {@code restlytics.env}</li>
 *   <li>{@code RESTLYTICS_SAMPLE_RATE} / {@code restlytics.sample-rate} (default 1.0)</li>
 *   <li>{@code RESTLYTICS_TRANSPORT} / {@code restlytics.transport} (http|null)</li>
 *   <li>{@code RESTLYTICS_TIMEOUT_MS} / {@code restlytics.timeout-ms} (default 2000)</li>
 *   <li>{@code RESTLYTICS_CAPTURE_SQL} / {@code restlytics.capture-sql} (default false)</li>
 *   <li>{@code RESTLYTICS_INSTRUMENT_DB|HTTP|CACHE} per-instrument toggles</li>
 *   <li>{@code RESTLYTICS_MAX_SPANS} / {@code restlytics.max-spans} (default 2000)</li>
 *   <li>ignore paths + redaction query keys + sensitive headers</li>
 * </ul>
 *
 * <p>Dependency-free: only {@code java.*} imports. Uses plain mutable fields with
 * getters/setters so the Spring {@code @ConfigurationProperties} binder can populate it.
 */
public final class RestlyticsConfig {

    private String key = "";
    private String ingestUrl = "https://ingest.restlytics.com";
    private String serviceName = "spring";
    private String env = "production";
    private double sampleRate = 1.0;
    private String transport = "http";
    private int timeoutMs = 2000;
    private boolean captureSql = false;
    private boolean instrumentDb = true;
    private boolean instrumentHttp = true;
    private boolean instrumentCache = true;
    private int maxSpans = 2000;

    /** Request paths to skip entirely (no span opened). Supports trailing {@code *}. */
    private List<String> ignorePaths = new ArrayList<>(Arrays.asList(
            "/actuator", "/actuator/*", "/health", "/healthz", "/favicon.ico"));

    /** Query-string keys scrubbed from {@code url.full} on outbound HTTP spans. */
    private List<String> redactQueryKeys = new ArrayList<>(Arrays.asList(
            "token", "api_key", "apikey", "password", "secret", "access_token", "key", "signature"));

    /** Request/response headers never captured (lowercased). */
    private List<String> sensitiveHeaders = new ArrayList<>(Arrays.asList(
            "authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization"));

    public RestlyticsConfig() {
    }

    /**
     * Build a config from environment variables (and defaults). Lets the
     * dependency-free core run/test without Spring. Spring's binder uses the
     * setters instead.
     */
    public static RestlyticsConfig fromEnv() {
        RestlyticsConfig c = new RestlyticsConfig();
        c.key = env("RESTLYTICS_KEY", c.key);
        c.ingestUrl = env("RESTLYTICS_INGEST_URL", c.ingestUrl);
        c.serviceName = env("RESTLYTICS_SERVICE_NAME", c.serviceName);
        c.env = env("RESTLYTICS_ENV", c.env);
        c.sampleRate = parseDouble(env("RESTLYTICS_SAMPLE_RATE", null), c.sampleRate);
        c.transport = env("RESTLYTICS_TRANSPORT", c.transport);
        c.timeoutMs = parseInt(env("RESTLYTICS_TIMEOUT_MS", null), c.timeoutMs);
        c.captureSql = parseBool(env("RESTLYTICS_CAPTURE_SQL", null), c.captureSql);
        c.instrumentDb = parseBool(env("RESTLYTICS_INSTRUMENT_DB", null), c.instrumentDb);
        c.instrumentHttp = parseBool(env("RESTLYTICS_INSTRUMENT_HTTP", null), c.instrumentHttp);
        c.instrumentCache = parseBool(env("RESTLYTICS_INSTRUMENT_CACHE", null), c.instrumentCache);
        c.maxSpans = parseInt(env("RESTLYTICS_MAX_SPANS", null), c.maxSpans);
        return c;
    }

    /** Whether the SDK is active. A blank key (or null transport via config) disables delivery. */
    public boolean isEnabled() {
        return key != null && !key.isEmpty();
    }

    /** True if the given request path matches an ignore pattern (trailing-{@code *} glob or exact). */
    public boolean isIgnored(String path) {
        if (path == null) {
            return false;
        }
        String p = "/" + stripLeadingSlash(path);
        for (String pattern : ignorePaths) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            String pat = "/" + stripLeadingSlash(pattern);
            if (pat.endsWith("*")) {
                String prefix = pat.substring(0, pat.length() - 1);
                if (p.startsWith(prefix)) {
                    return true;
                }
            } else if (pat.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private static String stripLeadingSlash(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return s.substring(i);
    }

    // ---- helpers ----

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    private static double parseDouble(String v, double fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String v, int fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBool(String v, boolean fallback) {
        if (v == null) {
            return fallback;
        }
        String s = v.trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }

    // ---- getters / setters (for Spring @ConfigurationProperties binding) ----

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getIngestUrl() {
        return ingestUrl;
    }

    public void setIngestUrl(String ingestUrl) {
        this.ingestUrl = ingestUrl;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isCaptureSql() {
        return captureSql;
    }

    public void setCaptureSql(boolean captureSql) {
        this.captureSql = captureSql;
    }

    public boolean isInstrumentDb() {
        return instrumentDb;
    }

    public void setInstrumentDb(boolean instrumentDb) {
        this.instrumentDb = instrumentDb;
    }

    public boolean isInstrumentHttp() {
        return instrumentHttp;
    }

    public void setInstrumentHttp(boolean instrumentHttp) {
        this.instrumentHttp = instrumentHttp;
    }

    public boolean isInstrumentCache() {
        return instrumentCache;
    }

    public void setInstrumentCache(boolean instrumentCache) {
        this.instrumentCache = instrumentCache;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = maxSpans;
    }

    public List<String> getIgnorePaths() {
        return ignorePaths;
    }

    public void setIgnorePaths(List<String> ignorePaths) {
        this.ignorePaths = ignorePaths;
    }

    public List<String> getRedactQueryKeys() {
        return redactQueryKeys;
    }

    public void setRedactQueryKeys(List<String> redactQueryKeys) {
        this.redactQueryKeys = redactQueryKeys;
    }

    public List<String> getSensitiveHeaders() {
        return sensitiveHeaders;
    }

    public void setSensitiveHeaders(List<String> sensitiveHeaders) {
        this.sensitiveHeaders = sensitiveHeaders;
    }
}
