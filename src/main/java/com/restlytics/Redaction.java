package com.restlytics;

import java.util.List;

/**
 * Redaction helpers (SPEC §6). Scrubs sensitive values from {@code url.full} before it
 * goes on the wire. Bindings, bodies, and sensitive headers are handled at their
 * capture sites; this covers outbound HTTP query strings.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Redaction {

    private static final String REDACTED = "REDACTED";

    private Redaction() {
    }

    /**
     * Return {@code url} with the values of any query parameter whose key is in
     * {@code sensitiveKeys} (case-insensitive) replaced by {@code REDACTED}. Best-effort
     * and never throws; on any parsing trouble it returns the URL with its query string
     * stripped entirely (the safe choice).
     */
    public static String redactUrl(String url, List<String> sensitiveKeys) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        try {
            String base = url.substring(0, q);
            String query = url.substring(q + 1);
            // Drop a fragment if present; we don't emit it.
            int hash = query.indexOf('#');
            String fragment = "";
            if (hash >= 0) {
                fragment = query.substring(hash);
                query = query.substring(0, hash);
            }
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
                if (isSensitive(key, sensitiveKeys)) {
                    out.append(key).append('=').append(REDACTED);
                } else {
                    out.append(pair);
                }
            }
            out.append(fragment);
            return out.toString();
        } catch (Throwable ignored) {
            // Safe fallback: strip the whole query string.
            return url.substring(0, q);
        }
    }

    private static boolean isSensitive(String key, List<String> sensitiveKeys) {
        if (sensitiveKeys == null) {
            return false;
        }
        for (String k : sensitiveKeys) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }
}
