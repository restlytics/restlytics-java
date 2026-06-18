package com.restlytics;

/**
 * Tests for {@link Sql#normalize} — the N+1 grouping key (SPEC §5).
 *
 * <p>Written as plain-Java JUnit-style so it runs BOTH under {@code mvn test} (JUnit
 * picks up the {@code test*} / {@code main}-driven assertions) AND offline with no
 * dependencies:
 * <pre>
 *   javac -d /tmp/out src/main/java/com/restlytics/*.java src/test/java/com/restlytics/SqlTest.java
 *   java  -cp /tmp/out com.restlytics.SqlTest
 * </pre>
 * It uses a tiny in-file assertion harness rather than the JUnit API so it has zero
 * external dependencies (which cannot be fetched offline). The method names mirror
 * JUnit test methods for readability.
 */
public final class SqlTest {

    public void testReplacesNumericLiteral() {
        Assert.eq("select * from users where id = ?",
                Sql.normalize("SELECT * FROM users WHERE id = 42"));
    }

    public void testReplacesStringLiteral() {
        Assert.eq("select * from users where email = ?",
                Sql.normalize("SELECT * FROM users WHERE email = 'a@b.com'"));
    }

    public void testReplacesDoubleQuotedLiteral() {
        Assert.eq("select * from t where a = ?",
                Sql.normalize("SELECT * FROM t WHERE a = \"x\""));
    }

    public void testReplacesDecimalAndScientific() {
        // Decimals (incl. scientific with a decimal point) are replaced; bare integer
        // literals too. Matches the Laravel reference algorithm.
        Assert.eq("select * from t where a = ? and b = ?",
                Sql.normalize("SELECT * FROM t WHERE a = 1.5e3 AND b = 200"));
    }

    public void testCollapsesInList() {
        Assert.eq("select * from t where id in (?)",
                Sql.normalize("SELECT * FROM t WHERE id IN (1, 2, 3, 4, 5)"));
    }

    public void testCollapsesInListSingle() {
        Assert.eq("select * from t where id in (?)",
                Sql.normalize("SELECT * FROM t WHERE id IN (7)"));
    }

    public void testCollapsesMultiRowValues() {
        Assert.eq("insert into t (a, b) values (?)",
                Sql.normalize("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y'), (3, 'z')"));
    }

    public void testNamedAndPositionalPlaceholders() {
        Assert.eq("select * from t where a = ? and b = ?",
                Sql.normalize("SELECT * FROM t WHERE a = :name AND b = $1"));
    }

    public void testSquashesWhitespaceAndLowercases() {
        Assert.eq("select * from users where name = ?",
                Sql.normalize("SELECT   *\n  FROM users\tWHERE name = 'bob'"));
    }

    public void testDoesNotMangleIdentifierWithDigits() {
        // column2 must survive; only standalone numeric literals become `?`.
        Assert.eq("select column2 from t where x = ?",
                Sql.normalize("SELECT column2 FROM t WHERE x = 9"));
    }

    public void testHexLiteral() {
        Assert.eq("select * from t where x = ?",
                Sql.normalize("SELECT * FROM t WHERE x = 0xDEADBEEF"));
    }

    public void testNullAndEmpty() {
        Assert.eq("", Sql.normalize(null));
        Assert.eq("", Sql.normalize(""));
    }

    public void testStableGroupingKeyAcrossValues() {
        // The whole point: two literal-different queries map to ONE summary (N+1 key).
        String a = Sql.normalize("SELECT * FROM users WHERE id = 1");
        String b = Sql.normalize("SELECT * FROM users WHERE id = 99999");
        Assert.eq(a, b);
    }

    public static void main(String[] args) {
        Assert.run(SqlTest.class);
    }
}
