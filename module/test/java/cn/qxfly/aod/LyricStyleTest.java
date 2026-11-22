package cn.qxfly.aod;

/**
 * Style-model tests that can run on a plain JVM: {@link LyricStyle}'s field
 * defaults and the background-radius clamp. No Bundle is touched here (the
 * Android stubs throw at runtime), only the pure helpers.
 */
final class LyricStyleTest {

    private LyricStyleTest() {
    }

    static void run() {
        defaults();
        clamp();
    }

    private static void defaults() {
        LyricStyle s = new LyricStyle();
        T.eq("style default radius", 24f, s.bgRadiusDp);
        T.eq("style default radius (resolved)", 24f, s.resolvedBgRadiusDp());
        T.eq("style default alpha", 0.45f, s.bgAlpha);
        T.ok("style default bg disabled", !s.bgEnabled);
    }

    private static void clamp() {
        T.eq("radius 0 kept", 0f, LyricStyle.clampBgRadiusDp(0f));
        T.eq("radius 12 kept", 12f, LyricStyle.clampBgRadiusDp(12f));
        T.eq("radius 40 kept", 40f, LyricStyle.clampBgRadiusDp(40f));
        T.eq("radius negative clamped to 0", 0f, LyricStyle.clampBgRadiusDp(-8f));
        T.eq("radius above max clamped", 40f, LyricStyle.clampBgRadiusDp(120f));
        T.eq("radius NaN falls back to default", 24f,
                LyricStyle.clampBgRadiusDp(Float.NaN));

        LyricStyle s = new LyricStyle();
        s.bgRadiusDp = 999f;
        T.eq("resolved radius clamps the field", 40f, s.resolvedBgRadiusDp());
        s.bgRadiusDp = -3f;
        T.eq("resolved radius clamps negatives", 0f, s.resolvedBgRadiusDp());
    }
}
