package com.restlytics;

/**
 * Tests for {@link Intervals#unionLength} — interval-union self-time (SPEC §1).
 *
 * <p>Same dual-mode design as {@link SqlTest}: runs under {@code mvn test} and offline:
 * <pre>
 *   javac -d /tmp/out src/main/java/com/restlytics/*.java src/test/java/com/restlytics/IntervalsTest.java
 *   java  -cp /tmp/out com.restlytics.IntervalsTest
 * </pre>
 */
public final class IntervalsTest {

    public void testEmpty() {
        Assert.eqL(0L, Intervals.unionLength(new long[0][]));
        Assert.eqL(0L, Intervals.unionLength(null));
    }

    public void testSingle() {
        Assert.eqL(10L, Intervals.unionLength(new long[][] {{5, 15}}));
    }

    public void testDisjoint() {
        // [0,10] + [20,40] = 10 + 20 = 30
        Assert.eqL(30L, Intervals.unionLength(new long[][] {{0, 10}, {20, 40}}));
    }

    public void testOverlapping() {
        // [0,15] ∪ [5,20] = [0,20] = 20
        Assert.eqL(20L, Intervals.unionLength(new long[][] {{0, 15}, {5, 20}}));
    }

    public void testNested() {
        // [0,10] fully contains [2,5] → 10
        Assert.eqL(10L, Intervals.unionLength(new long[][] {{0, 10}, {2, 5}}));
    }

    public void testTouchingMerges() {
        // [0,10] and [10,20] touch at 10 → merged [0,20] = 20
        Assert.eqL(20L, Intervals.unionLength(new long[][] {{0, 10}, {10, 20}}));
    }

    public void testUnsortedInput() {
        // Same intervals as testOverlapping but out of order — must still be 20.
        Assert.eqL(20L, Intervals.unionLength(new long[][] {{5, 20}, {0, 15}}));
    }

    public void testParallelChildrenDoNotOvercount() {
        // Two overlapping "DB queries" running in parallel: 0-100 and 50-150.
        // Sum would be 200, but wall-clock union is 0-150 = 150.
        Assert.eqL(150L, Intervals.unionLength(new long[][] {{0, 100}, {50, 150}}));
    }

    public void testClampsInvertedInterval() {
        // An inverted [10,5] (clock skew) contributes 0, not negative.
        Assert.eqL(0L, Intervals.unionLength(new long[][] {{10, 5}}));
        // Mixed with a real interval: only the valid one counts.
        Assert.eqL(10L, Intervals.unionLength(new long[][] {{10, 5}, {0, 10}}));
    }

    public void testDoesNotMutateInput() {
        long[][] in = {{5, 20}, {0, 15}};
        Intervals.unionLength(in);
        // First element must be unchanged (we sort a copy, not the caller's array).
        Assert.eqL(5L, in[0][0]);
        Assert.eqL(20L, in[0][1]);
    }

    public static void main(String[] args) {
        Assert.run(IntervalsTest.class);
    }
}
