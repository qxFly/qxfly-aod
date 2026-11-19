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
    public float strokeWidth;
    public int strokeColor = Color.BLACK;
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

    @NonNull
    public static LyricStyle fromBundle(@Nullable android.os.Bundle b) {
        LyricStyle s = new LyricStyle();
        if (b == null) {
            return s;
        }
        s.textSizeSp = parseFloat(b.getString(StateKeys.STYLE_TEXT_SIZE), s.textSizeSp);
        s.textColor = parseInt(b.getString(StateKeys.STYLE_TEXT_COLOR), s.textColor);
        s.typefaceName = b.getString(StateKeys.STYLE_TYPEFACE);
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
        return s;
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
        if (typefaceName != null) {
            b.putString(StateKeys.STYLE_TYPEFACE, typefaceName);
        }
        b.putString(StateKeys.STYLE_STROKE_WIDTH, String.valueOf(strokeWidth));
        b.putString(StateKeys.STYLE_STROKE_COLOR, Integer.toHexString(strokeColor));
        b.putString(StateKeys.STYLE_WEIGHT, String.valueOf(weight));
        b.putString(StateKeys.STYLE_ITALIC, italic ? "1" : "0");
        b.putString(StateKeys.STYLE_OFFSET_RATIO, String.valueOf(offsetRatio));
        b.putString(StateKeys.STYLE_ALIGN, String.valueOf(align));
        if (anchorId != null && !anchorId.isEmpty()) {
            b.putString(StateKeys.STYLE_ANCHOR_ID, anchorId);
        }
        b.putString(StateKeys.STYLE_ANCHOR_POS, String.valueOf(anchorPos));
        b.putString(StateKeys.STYLE_ANCHOR_GAP, String.valueOf(anchorGap));
        b.putString(StateKeys.STYLE_TREE_INSERT, treeInsert ? "1" : "0");
        b.putString(StateKeys.STYLE_SHOW_TRANSLATION, showTranslation ? "1" : "0");
        b.putString(StateKeys.STYLE_BG_ENABLED, bgEnabled ? "1" : "0");
        b.putString(StateKeys.STYLE_BG_COLOR, Integer.toHexString(bgColor));
        b.putString(StateKeys.STYLE_BG_ALPHA, String.valueOf(bgAlpha));
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
