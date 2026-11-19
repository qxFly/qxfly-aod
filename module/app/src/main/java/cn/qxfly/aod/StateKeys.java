package cn.qxfly.aod;

/**
 * Cross-process state contract between the hook processes (SystemUI / com.miui.aod)
 * and the module UI process. Values travel through AodStateProvider#call as
 * Bundles.
 *
 * <p>Data flow (1.7.0): the UI app process receives FULL lyrics from SuperLyric
 * (it holds superlyricapi_version_code=40, which unlocks full legacy payloads;
 * com.miui.aod has no such meta and would only get the current line). The app
 * writes the LRC into the provider; hook processes read it and inject. The hook
 * processes ALSO register their own SuperLyric listener and prefer a full
 * document from their own stream when one arrives.
 */
public final class StateKeys {

    private StateKeys() {
    }

    public static final String AUTHORITY = "cn.qxfly.aod.state";
    public static final String PATH_STATE = "state";

    /** Path segment serving the imported custom font file. */
    public static final String PATH_FONT = "font";
    public static final String METHOD_UPDATE = "update";
    public static final String METHOD_GET = "get";
    public static final String METHOD_CLEAR = "clear";

    public static final String TYPE = "type";
    public static final String TYPE_MODULE = "module";
    public static final String TYPE_STATUS = "status";
    public static final String TYPE_LYRIC = "lyric";
    public static final String TYPE_STYLE = "style";
    /** Hook -> app: enumerated AOD components with bounds (anchor picker data). */
    public static final String TYPE_COMPONENTS = "components";
    /** "id|class|bounds" lines, one per candidate component. */
    public static final String COMPONENTS_LIST = "componentsList";
    public static final String COMPONENTS_AT = "componentsAt";

    // ---- module ----
    /**
     * Master switch for every lyric feature. Written by the app UI, read by the
     * hook processes on each poll: when false, the overlay is force-cleared and
     * native LRC injection returns an empty document.
     */
    public static final String MODULE_ENABLED = "moduleEnabled";
    public static final String MODULE_API = "moduleApi";
    public static final String MODULE_FRAMEWORK = "moduleFramework";
    public static final String MODULE_READY_AT = "moduleReadyAt";

    // ---- status ----
    public static final String HOOKS_INSTALLED = "hooksInstalled";
    public static final String HOOK_DETAIL = "hookDetail";
    public static final String REGISTER_RESULT = "registerResult";
    public static final String STATUS_AT = "statusAt";

    // ---- lyric ----
    public static final String LYRIC_TRACK = "lyricTrack";
    public static final String LYRIC_LINE = "lyricLine";
    public static final String LYRIC_INDEX = "lyricIndex";
    public static final String LYRIC_TOTAL = "lyricTotal";
    public static final String LYRIC_CHARS = "lyricChars";
    public static final String LYRIC_AT = "lyricAt";

    /**
     * Full LRC text for injection. Written by whichever source first obtains a
     * complete document (UI app via V4/legacy-full, or the hook's own stream).
     */
    public static final String LYRIC_LRC = "lyricLrc";
    public static final String LYRIC_ID = "lyricId";

    // ---- appearance (mirrors the original module's customisation) ----
    /** Text size in sp. */
    public static final String STYLE_TEXT_SIZE = "styleTextSize";
    /** ARGB colour. */
    public static final String STYLE_TEXT_COLOR = "styleTextColor";
    /** Font family name, or null for the system default. */
    public static final String STYLE_TYPEFACE = "styleTypeface";
    /** Stroke width in px; 0 disables the outline. */
    public static final String STYLE_STROKE_WIDTH = "styleStrokeWidth";
    /** ARGB stroke colour. */
    public static final String STYLE_STROKE_COLOR = "styleStrokeColor";
    /** 0 = bold, 1 = normal, 2 = light. */
    public static final String STYLE_WEIGHT = "styleWeight";
    /** true = italic. */
    public static final String STYLE_ITALIC = "styleItalic";
    /** Vertical offset as a fraction of the clock container height. */
    public static final String STYLE_OFFSET_RATIO = "styleOffsetRatio";
    /** Horizontal alignment: 0 centre, 1 left, 2 right. */
    public static final String STYLE_ALIGN = "styleAlign";
    /**
     * Anchor component's resource entry name (e.g. "gradient_layout").
     * Empty/absent = legacy percentage offset (STYLE_OFFSET_RATIO).
     */
    public static final String STYLE_ANCHOR_ID = "styleAnchorId";
    /** 0 = place the lyric ABOVE the anchor, 1 = BELOW. */
    public static final String STYLE_ANCHOR_POS = "styleAnchorPos";
    /** Gap between the anchor edge and the lyric, in px. */
    public static final String STYLE_ANCHOR_GAP = "styleAnchorGap";
    /**
     * true = insert the lyric INTO the component tree at the anchor's slot
     * (participates in the layout flow, like a native component) instead of
     * offsetting with translationY.
     */
    public static final String STYLE_TREE_INSERT = "styleTreeInsert";

    /**
     * true = when the publisher supplies a translation for the current line,
     * render it under the original text (secondary, dimmer, smaller).
     */
    public static final String STYLE_SHOW_TRANSLATION = "styleShowTranslation";

    /**
     * Change marker for the imported custom font (mtime + size of the served
     * file). Not interpreted by the hook: it only rides in the style stamp so
     * a re-import triggers {@code setStyle} even though {@code typefaceName}
     * stays "custom".
     */
    public static final String STYLE_FONT_STAMP = "styleFontStamp";

    /** true = draw a rounded background pill behind every lyric line. */
    public static final String STYLE_BG_ENABLED = "styleBgEnabled";
    /** ARGB background colour for the pill. */
    public static final String STYLE_BG_COLOR = "styleBgColor";
    /** Background opacity multiplier, 0..1. */
    public static final String STYLE_BG_ALPHA = "styleBgAlpha";

    /**
     * Live translation of the current line, pushed alongside {@link #LYRIC_LINE}.
     * Not a style key: it changes with every line.
     */
    public static final String LYRIC_TRANSLATION = "lyricTranslation";

    /** Every appearance key, used by the provider to copy them as a group. */
    public static final String[] STYLE_KEYS = {
            STYLE_TEXT_SIZE, STYLE_TEXT_COLOR,
            STYLE_TYPEFACE, STYLE_STROKE_WIDTH, STYLE_STROKE_COLOR, STYLE_WEIGHT,
            STYLE_ITALIC, STYLE_OFFSET_RATIO, STYLE_ALIGN,
            STYLE_ANCHOR_ID, STYLE_ANCHOR_POS, STYLE_ANCHOR_GAP, STYLE_TREE_INSERT,
            STYLE_SHOW_TRANSLATION, STYLE_FONT_STAMP,
            STYLE_BG_ENABLED, STYLE_BG_COLOR, STYLE_BG_ALPHA,
    };

    public static android.net.Uri stateUri() {
        return new android.net.Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(PATH_STATE)
                .build();
    }

    /**
     * Value of {@link #STYLE_TYPEFACE} selecting the imported custom font.
     * The actual file lives in the app's private storage and is served to the
     * hook process through {@link #fontUri()}.
     */
    public static final String FONT_CUSTOM = "custom";

    /** File served for the custom font (fixed slot, overwritten on import). */
    public static final String FONT_FILE = "custom.ttf";

    /** Uri of the custom font: content://authority/font/custom.ttf */
    public static android.net.Uri fontUri() {
        return new android.net.Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(PATH_FONT)
                .appendPath(FONT_FILE)
                .build();
    }
}
