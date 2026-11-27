package cn.qxfly.aod;

import android.graphics.Color;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Lyric appearance settings, mirroring the original module's customisation
 * (font, size, colour, outline, weight, alignment, offset).
 *
 * <p>Values are pushed from the UI app process through the state provider and
 * applied to the overlay TextView in the hook process.
 */
public final class LyricStyle {

    public float textSizeSp = 20f;
    public int textColor = Color.WHITE;
    @Nullable
    public String typefaceName;
    /**
     * Outline thickness in dp, measured OUTSIDE the glyph edge (0 = no outline).
     *
     * <p>The hook converts it to px with the display density and the draw pass
     * doubles it for the centred stroke, so the visible outline is exactly this
     * thick. Older builds stored raw px (0..6); the clamp keeps those loadable.
     */
    public float strokeWidth;
    public static final float STROKE_MAX_DP = 4f;
    public int strokeColor = Color.BLACK;

    /** Clamps an outline width into the supported range. */
    public static float clampStrokeDp(float dp) {
        if (Float.isNaN(dp)) {
            return 0f;
        }
        return Math.max(0f, Math.min(STROKE_MAX_DP, dp));
    }

    /** This style's outline thickness in dp, already clamped. */
    public float resolvedStrokeDp() {
        return clampStrokeDp(strokeWidth);
    }
    /** 0 bold, 1 normal, 2 light. */
    public int weight;
    public boolean italic;
    public float offsetRatio = 0.42f;
    /** 0 centre, 1 left, 2 right. */
    public int align;
    /**
     * Anchor component resource entry name (e.g. "gradient_layout"). When
     * non-empty, the lyric is placed just above/below that component's real
     * bounds instead of using {@link #offsetRatio}.
     */
    @Nullable
    public String anchorId;
    /** 0 = above the anchor, 1 = below the anchor. */
    public int anchorPos = 1;
    /** Gap between the anchor edge and the lyric, in px. */
    public float anchorGap = 12f;
    /**
     * true = embed the lyric INTO the AOD component tree at the anchor's slot
     * so the native layout engine places it (position = tree position), instead
     * of pinning it with translationY.
     */
    public boolean treeInsert;

    /**
     * true = render the publisher's translation under the original line.
     * Lines without a translation always fall back to the original text.
     */
    public boolean showTranslation;

    /** true = draw a rounded pill behind every lyric line. */
    public boolean bgEnabled;
    /** Pill colour; multiplied by {@link #bgAlpha} when drawn. */
    public int bgColor = Color.BLACK;
    /** Pill opacity multiplier, 0..1. */
    public float bgAlpha = 0.45f;
    /**
     * Pill corner radius in dp. 0 = square panel, larger = rounder; values are
     * clamped to {@code 0..BG_RADIUS_MAX_DP} both here and when drawing, and
     * the draw pass additionally limits it to half the panel's short side so a
     * huge radius becomes a stadium instead of a distorted shape.
     */
    public float bgRadiusDp = BG_RADIUS_DEFAULT_DP;

    /** Default corner radius in dp (matches the pre-slider hardcoded look). */
    public static final float BG_RADIUS_DEFAULT_DP = 24f;
    /** Upper bound offered by the editor; keeps the panel from degenerating. */
    public static final float BG_RADIUS_MAX_DP = 40f;

    /** Clamps any radius value into the supported range. */
    public static float clampBgRadiusDp(float dp) {
        if (Float.isNaN(dp)) {
            return BG_RADIUS_DEFAULT_DP;
        }
        return Math.max(0f, Math.min(BG_RADIUS_MAX_DP, dp));
    }

    /** This style's radius, already clamped to the supported range. */
    public float resolvedBgRadiusDp() {
        return clampBgRadiusDp(bgRadiusDp);
    }

    /** true = show "title - artist" above the lyric. */
    public boolean showTrack;
    /** true = show the next line under the lyric, dimmed. */
    public boolean showNext;
    /** true = show "1:23 / 3:45" under the lyric. */
    public boolean showProgress;
    /** true = keep the line (dimmed) while playback is paused. */
    public boolean pauseKeep = true;

    /**
     * 沉浸模式：0 关闭 / 1 纯黑遮罩 / 2 保留壁纸（只隐藏组件）。
     * 见 {@link StateKeys#STYLE_IMMERSIVE}。
     */
    public int immersive;

    /** true = 设备横放时把歌词转 90°（AOD 窗口本身不跟随时）。 */
    public boolean landscape;

    /**
     * 沉浸模式下的九宫格位置：0..8 行优先，4 = 居中。
     * 见 {@link StateKeys#STYLE_IMMERSIVE_GRID}。
     */
    public int immersiveGrid = StateKeys.IMMERSIVE_GRID_DEFAULT;

    /**
     * 沉浸模式的旋转方式：0 自动(重力) / 1 0° / 2 90° / 3 180° / 4 270°。
     * 旧配置没有这个键时为 {@link StateKeys#IMMERSIVE_ROT_UNSET}（-1），
     * 由 {@link #resolvedRotMode()} 按 {@link #landscape} 推断。
     */
    public int immersiveRot = StateKeys.IMMERSIVE_ROT_UNSET;

    /** 实际生效的旋转档：旧配置按 landscape 开关推断，其余原样（已 clamp）。 */
    public int resolvedRotMode() {
        if (immersiveRot >= StateKeys.IMMERSIVE_ROT_MIN
                && immersiveRot <= StateKeys.IMMERSIVE_ROT_MAX) {
            return immersiveRot;
        }
        return landscape ? StateKeys.IMMERSIVE_ROT_AUTO : StateKeys.IMMERSIVE_ROT_NONE;
    }

    @NonNull
    public static LyricStyle fromBundle(@Nullable android.os.Bundle b) {
        LyricStyle s = new LyricStyle();
        if (b == null) {
            return s;
        }
        s.textSizeSp = parseFloat(b.getString(StateKeys.STYLE_TEXT_SIZE), s.textSizeSp);
        s.textColor = parseInt(b.getString(StateKeys.STYLE_TEXT_COLOR), s.textColor);
        String tf = b.getString(StateKeys.STYLE_TYPEFACE);
        s.typefaceName = tf == null || tf.isEmpty() ? null : tf;
        s.strokeWidth = parseFloat(b.getString(StateKeys.STYLE_STROKE_WIDTH), s.strokeWidth);
        s.strokeColor = parseInt(b.getString(StateKeys.STYLE_STROKE_COLOR), s.strokeColor);
        s.weight = parseInt(b.getString(StateKeys.STYLE_WEIGHT), s.weight);
        s.italic = "1".equals(b.getString(StateKeys.STYLE_ITALIC));
        s.offsetRatio = parseFloat(b.getString(StateKeys.STYLE_OFFSET_RATIO), s.offsetRatio);
        s.align = parseInt(b.getString(StateKeys.STYLE_ALIGN), s.align);
        s.anchorId = b.getString(StateKeys.STYLE_ANCHOR_ID);
        s.anchorPos = parseInt(b.getString(StateKeys.STYLE_ANCHOR_POS), s.anchorPos);
        s.anchorGap = parseFloat(b.getString(StateKeys.STYLE_ANCHOR_GAP), s.anchorGap);
        s.treeInsert = "1".equals(b.getString(StateKeys.STYLE_TREE_INSERT));
        s.showTranslation = "1".equals(b.getString(StateKeys.STYLE_SHOW_TRANSLATION));
        s.bgEnabled = "1".equals(b.getString(StateKeys.STYLE_BG_ENABLED));
        s.bgColor = parseInt(b.getString(StateKeys.STYLE_BG_COLOR), s.bgColor);
        s.bgAlpha = parseFloat(b.getString(StateKeys.STYLE_BG_ALPHA), s.bgAlpha);
        s.bgRadiusDp = clampBgRadiusDp(
                parseFloat(b.getString(StateKeys.STYLE_BG_RADIUS), s.bgRadiusDp));
        s.showTrack = "1".equals(b.getString(StateKeys.STYLE_SHOW_TRACK));
        s.showNext = "1".equals(b.getString(StateKeys.STYLE_SHOW_NEXT));
        s.showProgress = "1".equals(b.getString(StateKeys.STYLE_SHOW_PROGRESS));
        s.pauseKeep = !"0".equals(b.getString(StateKeys.STYLE_PAUSE_KEEP));
        s.immersive = clampImmersive(parseDecimal(b.getString(StateKeys.STYLE_IMMERSIVE), 0));
        s.landscape = "1".equals(b.getString(StateKeys.STYLE_LANDSCAPE));
        s.immersiveGrid = clampGrid(parseDecimal(b.getString(StateKeys.STYLE_IMMERSIVE_GRID),
                StateKeys.IMMERSIVE_GRID_DEFAULT));
        s.immersiveRot = clampRot(parseDecimal(b.getString(StateKeys.STYLE_IMMERSIVE_ROT),
                StateKeys.IMMERSIVE_ROT_UNSET));
        return s;
    }

    /** 旋转档只接受 -1(未设置) 及 0..4，越界按未设置处理（再退回 landscape）。 */
    static int clampRot(int raw) {
        return raw >= StateKeys.IMMERSIVE_ROT_UNSET && raw <= StateKeys.IMMERSIVE_ROT_MAX
                ? raw : StateKeys.IMMERSIVE_ROT_UNSET;
    }

    /** 沉浸模式只接受 0..2，越界一律当作关闭。 */
    private static int clampImmersive(int raw) {
        return raw >= 0 && raw <= StateKeys.IMMERSIVE_WALLPAPER ? raw : StateKeys.IMMERSIVE_OFF;
    }

    /** 九宫格只接受 0..8，越界回落到居中（旧配置没有这个键时也是居中）。 */
    static int clampGrid(int raw) {
        return raw >= StateKeys.IMMERSIVE_GRID_MIN && raw <= StateKeys.IMMERSIVE_GRID_MAX
                ? raw : StateKeys.IMMERSIVE_GRID_DEFAULT;
    }

    /**
     * Decimal int parse. The shared {@link #parseInt} tries hex first (it is
     * meant for colours); using it for a small enum would turn "10" into 16.
     */
    private static int parseDecimal(@Nullable String raw, int fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static float parseFloat(@Nullable String raw, float fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int parseInt(@Nullable String raw, int fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return (int) Long.parseLong(raw, 16);
        } catch (Throwable t) {
            try {
                return Integer.parseInt(raw);
            } catch (Throwable t2) {
                return fallback;
            }
        }
    }

    /** Serialises the style into a Bundle for the state provider. */
    @NonNull
    public android.os.Bundle toBundle() {
        android.os.Bundle b = new android.os.Bundle();
        b.putString(StateKeys.STYLE_TEXT_SIZE, String.valueOf(textSizeSp));
        b.putString(StateKeys.STYLE_TEXT_COLOR, Integer.toHexString(textColor));
        // Always written for the same reason as the anchor id below: a missing
        // key keeps the previous value in the provider snapshot.
        b.putString(StateKeys.STYLE_TYPEFACE, typefaceName == null ? "" : typefaceName);
        b.putString(StateKeys.STYLE_STROKE_WIDTH, String.valueOf(strokeWidth));
        b.putString(StateKeys.STYLE_STROKE_COLOR, Integer.toHexString(strokeColor));
        b.putString(StateKeys.STYLE_WEIGHT, String.valueOf(weight));
        b.putString(StateKeys.STYLE_ITALIC, italic ? "1" : "0");
        b.putString(StateKeys.STYLE_OFFSET_RATIO, String.valueOf(offsetRatio));
        b.putString(StateKeys.STYLE_ALIGN, String.valueOf(align));
        // ALWAYS written, even empty: the provider merges style keys with
        // copyIfPresent, so omitting the key left the PREVIOUS anchor in the
        // snapshot and the hook kept placing by anchor - the percentage slider
        // (ratio mode) then did nothing at all.
        b.putString(StateKeys.STYLE_ANCHOR_ID, anchorId == null ? "" : anchorId);
        b.putString(StateKeys.STYLE_ANCHOR_POS, String.valueOf(anchorPos));
        b.putString(StateKeys.STYLE_ANCHOR_GAP, String.valueOf(anchorGap));
        b.putString(StateKeys.STYLE_TREE_INSERT, treeInsert ? "1" : "0");
        b.putString(StateKeys.STYLE_SHOW_TRANSLATION, showTranslation ? "1" : "0");
        b.putString(StateKeys.STYLE_BG_ENABLED, bgEnabled ? "1" : "0");
        b.putString(StateKeys.STYLE_BG_COLOR, Integer.toHexString(bgColor));
        b.putString(StateKeys.STYLE_BG_ALPHA, String.valueOf(bgAlpha));
        b.putString(StateKeys.STYLE_BG_RADIUS, String.valueOf(resolvedBgRadiusDp()));
        b.putString(StateKeys.STYLE_SHOW_TRACK, showTrack ? "1" : "0");
        b.putString(StateKeys.STYLE_SHOW_NEXT, showNext ? "1" : "0");
        b.putString(StateKeys.STYLE_SHOW_PROGRESS, showProgress ? "1" : "0");
        b.putString(StateKeys.STYLE_PAUSE_KEEP, pauseKeep ? "1" : "0");
        b.putString(StateKeys.STYLE_IMMERSIVE, String.valueOf(immersive));
        b.putString(StateKeys.STYLE_LANDSCAPE, landscape ? "1" : "0");
        b.putString(StateKeys.STYLE_IMMERSIVE_GRID, String.valueOf(immersiveGrid));
        b.putString(StateKeys.STYLE_IMMERSIVE_ROT, String.valueOf(resolvedRotMode()));
        return b;
    }

    /**
     * Resolves the configured typeface, falling back to the system default.
     *
     * <p>For the custom font ({@code typefaceName == "custom"}) this cannot
     * resolve anything without a Context: the caller should use
     * {@link #isCustomFont()} plus the font loader and pass the loaded
     * Typeface into {@link #styled(Typeface)}.
     */
    @NonNull
    public Typeface typeface() {
        Typeface base = baseTypeface();
        return styled(base != null ? base : Typeface.DEFAULT);
    }

    /** True when the style selects the imported custom font. */
    public boolean isCustomFont() {
        return StateKeys.FONT_CUSTOM.equals(typefaceName);
    }

    /**
     * Base family for a system font selection, or null for the custom font
     * (which the caller resolves through the provider).
     */
    @Nullable
    public Typeface baseTypeface() {
        if (isCustomFont() || typefaceName == null || typefaceName.isEmpty()) {
            return null;
        }
        try {
            return Typeface.create(typefaceName, Typeface.NORMAL);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Applies the configured weight/italic to {@code base}. */
    @NonNull
    public Typeface styled(@NonNull Typeface base) {
        int style = switch (weight) {
            case 0 -> Typeface.BOLD;
            case 2 -> Typeface.NORMAL;
            default -> Typeface.NORMAL;
        };
        if (italic) {
            style |= Typeface.ITALIC;
        }
        return Typeface.create(base, style);
    }
}
