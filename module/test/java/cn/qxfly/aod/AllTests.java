package cn.qxfly.aod;

/**
 * Entry point for the plain-JVM tests. Run through {@code work/run-tests.ps1};
 * exits non-zero when any assertion fails so it can gate a build.
 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) {
        AnchorIdsTest.run();
        LrcBuilderTest.run();
        LyricDisplayTest.run();
        LyricStyleTest.run();
        TrackInfoTest.run();
        MediaSessionSourceTest.run();
        System.exit(T.report());
    }
}
