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

    /**
     * Snapshot schema version. Bumped whenever a key is renamed or removed so
     * the provider can drop stale records instead of carrying them forever
     * (e.g. the long-dead {@code styleShadowRadius}).
     */
    public static final String SCHEMA = "schema";
    public static final int SCHEMA_VERSION = 2;

    /** Keys that older builds wrote and nothing reads any more. */
    public static final String[] OBSOLETE_KEYS = {
            "styleShadowRadius",
    };

    /** Path segment serving the imported custom font file. */
    public static final String PATH_FONT = "font";
    public static final String METHOD_UPDATE = "update";
    public static final String METHOD_GET = "get";
    public static final String METHOD_CLEAR = "clear";
    /** UI -> provider: ask every hook process to push its log tail. */
    public static final String METHOD_DIAG_REQUEST = "diagRequest";

    public static final String TYPE = "type";
    public static final String TYPE_MODULE = "module";
    public static final String TYPE_STATUS = "status";
    public static final String TYPE_LYRIC = "lyric";
    public static final String TYPE_STYLE = "style";
    /** Hook -> app: enumerated AOD components with bounds (anchor picker data). */
    public static final String TYPE_COMPONENTS = "components";    /** "id|class|bounds" lines, one per candidate component. */
    public static final String COMPONENTS_LIST = "componentsList";
    public static final String COMPONENTS_AT = "componentsAt";

    /** Hook -> app: diagnostics answer (log tail + build identity). */
    public static final String TYPE_DIAG = "diag";
    public static final String LOG_BUILD = "logBuild";
    public static final String LOG_PROCESS = "logProcess";
    public static final String LOG_AT = "logAt";

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
    /** true = hook processes record DEBUG lines too (diagnostics switch). */
    public static final String LOG_DEBUG = "logDebug";

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

    // ---- now playing metadata (published by the UI app's v4 stream) ----
    /** Song title, as reported by the publisher. */
    public static final String LYRIC_TITLE = "lyricTitle";
    /** Artist / singer. */
    public static final String LYRIC_ARTIST = "lyricArtist";
    /** Text of the line AFTER the current one, for the "next line" preview. */
    public static final String LYRIC_NEXT = "lyricNext";
    /** Playback position in ms at the instant {@link #LYRIC_POS_AT} was taken. */
    public static final String LYRIC_POSITION = "lyricPosition";
    /** Track duration in ms (0 / absent = unknown). */
    public static final String LYRIC_DURATION = "lyricDuration";
    /** Wall clock of the position sample: SystemClock.elapsedRealtime(). */
    public static final String LYRIC_POS_AT = "lyricPosAt";
    /** playing / paused / buffering / stopped. */
    public static final String LYRIC_PLAY_STATE = "lyricPlayState";

    /** Play-state values for {@link #LYRIC_PLAY_STATE}. */
    public static final String PLAY_PLAYING = "playing";
    public static final String PLAY_PAUSED = "paused";
    public static final String PLAY_BUFFERING = "buffering";
    public static final String PLAY_STOPPED = "stopped";

    /**
     * Diagnostics handshake: the UI bumps this counter, the hook processes
     * notice the change on their next poll and push a fresh status report
     * (including their log tail) back into the same snapshot.
     */
    public static final String DIAG_REQUEST = "diagRequest";
    /** Last N log lines from the hook process (newline separated). */
    public static final String LOG_TAIL = "logTail";

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
     * Background corner radius in dp, 0..{@link LyricStyle#BG_RADIUS_MAX_DP}.
     * Absent on older payloads: the hook then uses the 24 dp default.
     */
    public static final String STYLE_BG_RADIUS = "styleBgRadius";

    // ---- extra lines around the lyric ----
    /** true = show "title - artist" above the lyric. */
    public static final String STYLE_SHOW_TRACK = "styleShowTrack";
    /** true = show the next line below the lyric (dimmed). */
    public static final String STYLE_SHOW_NEXT = "styleShowNext";
    /** true = show the playback position/duration under the lyric. */
    public static final String STYLE_SHOW_PROGRESS = "styleShowProgress";
    /** true = keep the current line visible (dimmed) while playback is paused. */
    public static final String STYLE_PAUSE_KEEP = "stylePauseKeep";

    // ---- immersive (息屏只剩歌词) ----
    /**
     * 沉浸模式：
     * <ul>
     *   <li>{@link #IMMERSIVE_OFF} 关闭，沿用普通放置；</li>
     *   <li>{@link #IMMERSIVE_BLACK} 整屏纯黑遮罩，只留歌词；</li>
     *   <li>{@link #IMMERSIVE_WALLPAPER} 保留息屏背景，只隐藏时钟/日期/通知等组件。</li>
     * </ul>
     */
    public static final String STYLE_IMMERSIVE = "styleImmersive";
    /** 值：关闭。 */
    public static final int IMMERSIVE_OFF = 0;
    /** 值：纯黑沉浸（遮罩整屏，OLED 上最省电）。 */
    public static final int IMMERSIVE_BLACK = 1;
    /** 值：保留壁纸的沉浸（只隐藏组件）。 */
    public static final int IMMERSIVE_WALLPAPER = 2;
    /** true = 设备横放时把歌词转 90°（AOD 窗口本身不旋转时生效）。 */
    public static final String STYLE_LANDSCAPE = "styleLandscape";
    /**
     * 沉浸模式下的九宫格定位，0..8 行优先：
     * <pre>
     * 0 左上   1 中上   2 右上
     * 3 左中   4 居中   5 右中
     * 6 左下   7 中下   8 右下
     * </pre>
     * 行决定垂直（上/中/下），列决定水平（左/中/右）。只在沉浸模式下生效，
     * 普通模式仍用 {@link #STYLE_OFFSET_RATIO} + {@link #STYLE_ALIGN}。
     */
    public static final String STYLE_IMMERSIVE_GRID = "styleImmersiveGrid";
    /** 九宫格下标范围与默认值（居中）。 */
    public static final int IMMERSIVE_GRID_MIN = 0;
    public static final int IMMERSIVE_GRID_DEFAULT = 4;
    public static final int IMMERSIVE_GRID_MAX = 8;

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
            STYLE_BG_ENABLED, STYLE_BG_COLOR, STYLE_BG_ALPHA, STYLE_BG_RADIUS,
            STYLE_SHOW_TRACK, STYLE_SHOW_NEXT, STYLE_SHOW_PROGRESS, STYLE_PAUSE_KEEP,
            STYLE_IMMERSIVE, STYLE_LANDSCAPE, STYLE_IMMERSIVE_GRID,
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
