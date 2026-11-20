package cn.qxfly.aod;

/**
 * Minimal assertion helper for the plain-JVM tests.
 *
 * <p>No JUnit: the project builds with a hand-rolled toolchain (aapt2 / kotlinc
 * / d8), so pulling a test framework in would mean another .deps jar for no
 * real gain. {@link AllTests} compiles these sources against the base classes
 * and runs them with the ordinary JDK.
 */
final class T {

    private static int sPassed;
    private static int sFailed;

    private T() {
    }

    static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            sPassed++;
            return;
        }
        sFailed++;
        System.out.println("FAIL " + what
                + "\n     expected: " + expected
                + "\n     actual  : " + actual);
    }

    static void ok(String what, boolean condition) {
        if (condition) {
            sPassed++;
            return;
        }
        sFailed++;
        System.out.println("FAIL " + what);
    }

    /** @return process exit code: 0 when every assertion passed. */
    static int report() {
        if (sFailed == 0) {
            System.out.println("ok - " + sPassed + " assertions passed");
            return 0;
        }
        System.out.println("FAILED - " + sFailed + " of " + (sPassed + sFailed)
                + " assertions failed");
        return 1;
    }
}
