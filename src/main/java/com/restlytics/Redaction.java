package com.restlytics;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed privacy boundary (SPEC §6) shared by every instrumentation path.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Redaction {

    private static final String REDACTED = "REDACTED";
    private static final Set<String> SENSITIVE_SEGMENTS = Set.of(
            "authorization", "auth", "cookie", "cookies", "setcookie", "password", "passwd",
            "secret", "token", "accesstoken", "refreshtoken", "apikey", "credential",
            "credentials", "body", "payload", "form", "stack", "stacktrace", "log");

    private Redaction() {
    }

    /**
     * Remove credentials/fragments and replace every query value with {@code REDACTED}.
     * {@code sensitiveKeys} remains for configuration compatibility; unknown keys are
     * equally safe.
     */
    public static String redactUrl(String url, List<String> sensitiveKeys) {
        // Retain the parameter for source compatibility; all values are now redacted.
        if (url == null || url.isEmpty()) {
            return url;
        }
        try {
            String clean = url;
            int hash = clean.indexOf('#');
            if (hash >= 0) {
                clean = clean.substring(0, hash);
            }
            clean = stripCredentials(clean);
            int q = clean.indexOf('?');
            if (q < 0) {
                return clean;
            }
            String base = clean.substring(0, q);
            String query = clean.substring(q + 1);
            StringBuilder out = new StringBuilder(base.length() + query.length() + 8);
            out.append(base).append('?');
            String[] pairs = query.split("&", -1);
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    out.append('&');
                }
                String pair = pairs[i];
                int eq = pair.indexOf('=');
                String key = eq >= 0 ? pair.substring(0, eq) : pair;
                out.append(key).append('=').append(REDACTED);
            }
            return out.toString();
        } catch (Throwable ignored) {
            String clean = url.split("[#?]", 2)[0];
            return stripCredentials(clean);
        }
    }

    public static boolean isSensitiveAttributeKey(String key) {
        if (key == null) {
            return true;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '.').replace('_', '.');
        if (normalized.equals("http.request.method")
                || normalized.equals("http.response.status.code")
                || normalized.equals("restlytics.bindings.count")) {
            return false;
        }
        for (String segment : normalized.split("\\.")) {
            if (SENSITIVE_SEGMENTS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Exception text is intentionally omitted; Restlytics is not a crash tracker. */
    public static String redactExceptionMessage(String message) {
        return null;
    }

    private static String stripCredentials(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int authorityStart = scheme + 3;
        int authorityEnd = url.indexOf('/', authorityStart);
        if (authorityEnd < 0) {
            authorityEnd = url.length();
        }
        int at = url.lastIndexOf('@', authorityEnd);
        return at >= authorityStart ? url.substring(0, authorityStart) + url.substring(at + 1) : url;
    }
}
