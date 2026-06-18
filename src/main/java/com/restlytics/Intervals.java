package com.restlytics;

import java.util.Arrays;

/**
 * Interval-union (sweep-line) helper used to compute per-category "self time".
 *
 * <p>Why union and not a plain sum: child spans can overlap (parallel HTTP calls,
 * async queries, nested instrumentation). Summing their durations double-counts the
 * wall-clock time. The union of intervals gives the real wall-clock time actually
 * spent inside that category, which the dashboard breakdown and the ingestion
 * service's self-time rollups expect.
 *
 * <p>We work in plain {@code long} nanoseconds. Durations within a single request
 * comfortably fit a 64-bit signed integer.
 *
 * <p>Dependency-free: only {@code java.*} imports.
 */
public final class Intervals {

    private Intervals() {
    }

    /**
     * Total wall-clock length covered by the union of {@code [start, end]} intervals.
     *
     * @param intervals array of {@code {startNs, endNs}} pairs; may be empty
     * @return summed length of the merged (non-overlapping) intervals, never negative
     */
    public static long unionLength(long[][] intervals) {
        if (intervals == null || intervals.length == 0) {
            return 0L;
        }

        // Copy so we don't mutate the caller's array, then sort by start so a single
        // forward sweep can merge overlaps.
        long[][] sorted = Arrays.copyOf(intervals, intervals.length);
        Arrays.sort(sorted, (a, b) -> Long.compare(a[0], b[0]));

        long total = 0L;
        long curStart = sorted[0][0];
        long curEnd = sorted[0][1];
        if (curEnd < curStart) {
            curEnd = curStart; // clamp inverted intervals (clock skew)
        }

        for (int i = 1; i < sorted.length; i++) {
            long s = sorted[i][0];
            long e = sorted[i][1];
            if (e < s) {
                e = s; // clamp inverted interval
            }
            if (s > curEnd) {
                // Disjoint: bank the current run and start a new one.
                total += curEnd - curStart;
                curStart = s;
                curEnd = e;
            } else if (e > curEnd) {
                // Overlapping: extend the current run.
                curEnd = e;
            }
        }

        total += curEnd - curStart;
        return total;
    }
}
