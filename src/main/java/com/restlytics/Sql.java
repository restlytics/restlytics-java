package com.restlytics;

import java.util.regex.Pattern;

/**
 * SQL normalization → a literal-free template string ({@code db.query.summary}).
 *
 * <p>Two jobs:
 * <ol>
 *   <li><b>PII / redaction</b> — strip every literal so we NEVER ship customer values
 *       (emails, tokens, ids) inside {@code db.query.summary}. Only the shape survives.</li>
 *   <li><b>N+1 grouping</b> — collapse the query to a stable fingerprint so that
 *       {@code SELECT * FROM users WHERE id = 1} and {@code ... id = 2} map to the
 *       same key. {@code IN (?, ?, ?)} lists of varying length collapse to
 *       {@code IN (?)} so a batched query and its single-row cousin don't fragment.</li>
 * </ol>
 *
 * <p>Matches SPEC §5 and the Laravel reference algorithm: replace string literals,
 * replace numeric literals, collapse {@code IN} lists, squash whitespace, trim,
 * lowercase. Deliberately a best-effort lexical normalizer, not a real SQL parser —
 * it must be fast (runs on every query) and never throw.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Sql {

    // String literals: single- and double-quoted, with escaped-quote and doubled-quote support.
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'(?:[^'\\\\]|\\\\.|'')*'", Pattern.DOTALL);
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"(?:[^\"\\\\]|\\\\.|\"\")*\"", Pattern.DOTALL);

    // Numeric literals (hex, decimal/scientific, integer) with word boundaries so
    // we don't mangle identifiers like `column2`.
    private static final Pattern HEX_NUM = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");
    private static final Pattern DECIMAL_NUM = Pattern.compile("\\b\\d+\\.\\d+(?:[eE][+-]?\\d+)?\\b");
    private static final Pattern INT_NUM = Pattern.compile("\\b\\d+\\b");

    // Existing placeholders → `?`.
    private static final Pattern NUMBERED_Q = Pattern.compile("\\?\\d+");      // ?1, ?2
    private static final Pattern NAMED_PARAM = Pattern.compile("[:$]\\w+");    // :name, $1

    // Collapse IN (?, ?, ?) → IN (?).
    private static final Pattern IN_LIST = Pattern.compile("\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)", Pattern.CASE_INSENSITIVE);

    // Collapse multi-row VALUES tuples: (?, ?), (?, ?) → (?) and (?, ?) → (?).
    private static final Pattern MULTI_TUPLE = Pattern.compile(
            "\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)(?:\\s*,\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\))+");
    private static final Pattern SINGLE_TUPLE = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Sql() {
    }

    /** Normalize a raw SQL string into a stable, literal-free template. */
    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql;

        // 1. Drop string literals.
        s = SINGLE_QUOTED.matcher(s).replaceAll("?");
        s = DOUBLE_QUOTED.matcher(s).replaceAll("?");

        // 2. Normalize existing positional/named placeholders to `?` BEFORE numeric
        // replacement, so `$1`/`:name`/`?1` collapse to a single `?` rather than the
        // numeric pass first turning `$1` into `$?`.
        s = NUMBERED_Q.matcher(s).replaceAll("?");
        s = NAMED_PARAM.matcher(s).replaceAll("?");

        // 3. Drop numeric literals (hex first, then decimal, then integer).
        s = HEX_NUM.matcher(s).replaceAll("?");
        s = DECIMAL_NUM.matcher(s).replaceAll("?");
        s = INT_NUM.matcher(s).replaceAll("?");

        // 4. Collapse IN lists and VALUES tuples.
        s = IN_LIST.matcher(s).replaceAll("IN (?)");
        s = MULTI_TUPLE.matcher(s).replaceAll("(?)");
        s = SINGLE_TUPLE.matcher(s).replaceAll("(?)");

        // 5. Squash whitespace, trim, lowercase.
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s.toLowerCase();
    }
}
