package com.restlytics;

import java.lang.reflect.Method;

/**
 * Tiny dependency-free assertion + runner harness for the offline contract tests.
 *
 * <p>Why hand-rolled instead of the JUnit API: JUnit cannot be fetched in the offline
 * build environment, so the two mandatory contract tests ({@link SqlTest},
 * {@link IntervalsTest}) must run with plain {@code javac}/{@code java}. This runner
 * reflects over all no-arg {@code test*} methods of a test class, invokes each, and
 * reports pass/fail — JUnit-style ergonomics, zero dependencies. Under a real
 * {@code mvn test} with JUnit available you can additionally annotate the methods, but
 * the {@code main} entrypoint here is the offline path the SPEC asks for.
 */
public final class Assert {

    private Assert() {
    }

    public static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected [" + expected + "] but was [" + actual + "]");
        }
    }

    public static void eqL(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("expected [" + expected + "] but was [" + actual + "]");
        }
    }

    public static void isTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    /** Run every no-arg {@code test*} method on a fresh instance and summarize. */
    public static void run(Class<?> testClass) {
        int passed = 0;
        int failed = 0;
        try {
            Object instance = testClass.getDeclaredConstructor().newInstance();
            for (Method m : testClass.getDeclaredMethods()) {
                if (m.getName().startsWith("test") && m.getParameterCount() == 0) {
                    try {
                        m.invoke(instance);
                        System.out.println("PASS " + testClass.getSimpleName() + "." + m.getName());
                        passed++;
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        System.out.println("FAIL " + testClass.getSimpleName() + "." + m.getName()
                                + " -> " + cause);
                        failed++;
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("ERROR instantiating " + testClass.getName() + ": " + t);
            failed++;
        }
        System.out.println(testClass.getSimpleName() + ": " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
