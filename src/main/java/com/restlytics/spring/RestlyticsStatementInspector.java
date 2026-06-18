package com.restlytics.spring;

import com.restlytics.RestlyticsConfig;
import com.restlytics.Span;
import com.restlytics.Sql;
import com.restlytics.Tracer;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * REVIEW-ONLY (depends on hibernate-core; cannot compile offline without it — it is
 * {@code provided}/optional in the pom).
 *
 * <p>Hibernate {@link StatementInspector}: invoked for every SQL statement Hibernate is
 * about to execute. We use it to record a DB CLIENT span (SPEC §4: {@code kind=3},
 * {@code restlytics.category="db"}) carrying the NORMALIZED, literal-free summary (the
 * N+1 grouping key) and a bindings count — never the binding VALUES.
 *
 * <p>Timing caveat: {@code inspect()} runs just BEFORE execution and only receives the
 * SQL string, so it cannot by itself measure execution time. This inspector records a
 * point-in-time span (start == end at inspection) and bumps the query count; for true
 * per-query durations a JDBC {@code DataSource}/{@code Connection}/{@code Statement}
 * proxy is the more accurate hook (see README). We keep this inspector because it is
 * the zero-config Hibernate path and still surfaces the N+1 grouping key + query count,
 * which is what the N+1 detector needs.
 *
 * <p>MUST NOT throw — Hibernate executes the returned SQL, so on any error we return the
 * original SQL unchanged and record nothing.
 */
public final class RestlyticsStatementInspector implements StatementInspector {

    private final Tracer tracer;
    private final RestlyticsConfig config;

    public RestlyticsStatementInspector(Tracer tracer, RestlyticsConfig config) {
        this.tracer = tracer;
        this.config = config;
    }

    @Override
    public String inspect(String sql) {
        // Return the original SQL no matter what — Hibernate runs whatever we return.
        try {
            if (sql == null || sql.isEmpty() || !config.isInstrumentDb() || !tracer.isSampled()) {
                return sql;
            }

            tracer.incrementDbQueryCount();

            long now = tracer.nowNs();
            String summary = Sql.normalize(sql);
            Span span = tracer.addChildSpan(operationName(summary) + " (db)", now, now, Span.KIND_CLIENT);
            if (span != null) {
                span.setString("restlytics.category", "db");
                span.setString("db.query.summary", summary);
                span.setString("db.operation.name", operationName(summary));
                span.setInt("restlytics.bindings_count", countBindings(sql));
                // Raw SQL only when explicitly opted in, capped at 2048 chars (SPEC §4).
                if (config.isCaptureSql()) {
                    span.setString("db.query.text", cap(sql, 2048));
                }
            }
        } catch (Throwable ignored) {
            // Telemetry must never break a query.
        }
        return sql;
    }

    /** First keyword of the normalized statement (select/insert/update/delete/...). */
    private static String operationName(String summary) {
        if (summary == null || summary.isEmpty()) {
            return "sql";
        }
        int sp = summary.indexOf(' ');
        return sp > 0 ? summary.substring(0, sp) : summary;
    }

    /** Count of bind placeholders. We count `?` occurrences — VALUES are NEVER sent. */
    private static int countBindings(String sql) {
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                n++;
            }
        }
        return n;
    }

    private static String cap(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
