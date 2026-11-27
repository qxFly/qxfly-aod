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
        // 沉浸默认关闭：不改变现有用户看到的息屏。
        T.eq("style default immersive", 0, s.immersive);
        T.ok("style default landscape off", !s.landscape);
        T.eq("style default immersive grid", 4, s.immersiveGrid);
        // 旧配置没有旋转键：默认 -1，按 landscape 推断（默认关 -> 不旋转）。
        T.eq("style default rot unset", -1, s.immersiveRot);
        T.eq("legacy rot resolves to none", 1, s.resolvedRotMode());
        s.landscape = true;
        T.eq("legacy landscape resolves to auto", 0, s.resolvedRotMode());
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

        // 九宫格：0..8 原样保留，越界回落到居中（4）。
        T.eq("grid 0 kept", 0, LyricStyle.clampGrid(0));
        T.eq("grid 8 kept", 8, LyricStyle.clampGrid(8));
        T.eq("grid center kept", 4, LyricStyle.clampGrid(4));
        T.eq("grid negative falls back to center", 4, LyricStyle.clampGrid(-1));
        T.eq("grid above max falls back to center", 4, LyricStyle.clampGrid(9));

        // 描边：数值是「字外厚度」的 dp，范围 0..4；旧版本存的 px 值也能读。
        T.eq("stroke 0 kept", 0f, LyricStyle.clampStrokeDp(0f));
        T.eq("stroke 1.5 kept", 1.5f, LyricStyle.clampStrokeDp(1.5f));
        T.eq("stroke max kept", 4f, LyricStyle.clampStrokeDp(4f));
        T.eq("stroke negative clamped", 0f, LyricStyle.clampStrokeDp(-2f));
        T.eq("stroke above max clamped", 4f, LyricStyle.clampStrokeDp(9f));
        T.eq("stroke NaN becomes 0", 0f, LyricStyle.clampStrokeDp(Float.NaN));
        LyricStyle st = new LyricStyle();
        st.strokeWidth = 6f;
        T.eq("resolved stroke clamps the field", 4f, st.resolvedStrokeDp());

        // 旋转档：-1(未设置) 与 0..4 保留，越界回落为未设置。
        T.eq("rot unset kept", -1, LyricStyle.clampRot(-1));
        T.eq("rot auto kept", 0, LyricStyle.clampRot(0));
        T.eq("rot 90 kept", 2, LyricStyle.clampRot(2));
        T.eq("rot 270 kept", 4, LyricStyle.clampRot(4));
        T.eq("rot below range unset", -1, LyricStyle.clampRot(-2));
        T.eq("rot above range unset", -1, LyricStyle.clampRot(5));
        LyricStyle sr = new LyricStyle();
        sr.immersiveRot = 3;
        T.eq("rot 180 resolves", 3, sr.resolvedRotMode());
    }
}
