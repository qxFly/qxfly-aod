package cn.qxfly.aod;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fetches the lyric state published by the UI app process.
 *
 * <p>Why this exists: SuperLyric's legacy transport (serverApiVersion=0) grants
 * full payloads only to clients whose manifest declares superlyricapi_version_code
 * >= 35. com.miui.aod has no such meta, so its stream carries only the current
 * line. The UI app, holding version 40, receives everything and publishes it
 * here; the hook processes read it back.
 *
 * <p>The Context is resolved on every call instead of being captured in the
 * constructor: construction happens in onPackageReady, BEFORE the host
 * Application exists, so an eagerly-stored Context is null forever and every
 * read fails silently - which is exactly what kept the AOD overlay blank.
 */
public final class ProviderLrcSource {

    @Nullable
    private volatile String mLrc;

    @Nullable
    private volatile String mLrcId;

    /** Current line text as published by the app's SuperLyric stream. */
    @Nullable
    private volatile String mLine;

    /** Translation of {@link #mLine}, when the app's stream provides one. */
    @Nullable
    private volatile String mTranslation;

    /** Master switch mirrored from the provider snapshot (default: on). */
    private volatile boolean mModuleEnabled = true;

    /** When the provider last received a lyric feed (0 = never). */
    private volatile long mLyricAt;

    /** A lyric feed older than this is considered dead: no stop event ever
     *  arrives when a player is killed, so this is the only way to clear the
     *  overlay. Generous enough that a slow UI-app refresh never trips it. */
    public static final long STALE_MS = 180_000L;

    @Nullable
    public String translation() {
        return mTranslation;
    }

    @Nullable
    private volatile String mTrack;

    @Nullable
    private volatile String mStyleStamp;

    private volatile boolean sWarnedNoContext;

    /** Pulls the latest snapshot from the provider. Cheap binder call. */
    public void refresh() {
        Context context = resolveContext();
        if (context == null) {
            if (!sWarnedNoContext) {
                sWarnedNoContext = true;
                AodLog.w("provider refresh skipped: no host context yet");
            }
            return;
        }
        try {
            Bundle snap = context.getContentResolver().call(
                    StateKeys.stateUri(), StateKeys.METHOD_GET, null, null);
            if (snap == null) {
                return;
            }
            String lrc = snap.getString(StateKeys.LYRIC_LRC);
            if (lrc != null) {
                if (lrc.isEmpty()) {
                    // Empty = explicit stop/stop-report: clear instead of
                    // keeping the last song's LRC for injection.
                    if (mLrc != null) {
                        AodLog.i("provider LRC cleared");
                        mLrc = null;
                    }
                } else if (!lrc.equals(mLrc)) {
                    AodLog.i("provider LRC picked up: " + lrc.length() + " chars");
                    mLrc = lrc;
                }
            }
            String id = snap.getString(StateKeys.LYRIC_ID);
            if (id != null) {
                mLrcId = id;
            }
            String track = snap.getString(StateKeys.LYRIC_TRACK);
            if (track != null) {
                mTrack = track;
            }
            String line = snap.getString(StateKeys.LYRIC_LINE);
            // Empty = playback stopped: clear instead of keeping the stale line.
            mLine = line == null || line.isEmpty() ? null : line;
            String translation = snap.getString(StateKeys.LYRIC_TRANSLATION);
            // Translating line rides in the same snapshot; absent/empty = none.
            mTranslation = translation == null || translation.isEmpty()
                    ? null : translation;
            mLyricAt = snap.getLong(StateKeys.LYRIC_AT, 0);
            mModuleEnabled = snap.getBoolean(StateKeys.MODULE_ENABLED, true);
            // Appearance settings ride in the same snapshot; apply on change.
            Bundle style = new Bundle();
            boolean hasStyle = false;
            for (String key : StateKeys.STYLE_KEYS) {
                String v = snap.getString(key);
                if (v != null) {
                    style.putString(key, v);
                    hasStyle = true;
                }
            }
            if (hasStyle) {
                // Include the placement keys: a new anchor / ratio / gap must
                // retrigger setStyle, which re-applies the offset.
                String stamp = style.getString(StateKeys.STYLE_TEXT_SIZE) + "|"
                        + style.getString(StateKeys.STYLE_TEXT_COLOR) + "|"
                        + style.getString(StateKeys.STYLE_TYPEFACE) + "|"
                        + style.getString(StateKeys.STYLE_STROKE_WIDTH) + "|"
                        + style.getString(StateKeys.STYLE_STROKE_COLOR) + "|"
                        + style.getString(StateKeys.STYLE_WEIGHT) + "|"
                        + style.getString(StateKeys.STYLE_ITALIC) + "|"
                        + style.getString(StateKeys.STYLE_OFFSET_RATIO) + "|"
                        + style.getString(StateKeys.STYLE_ALIGN) + "|"
                        + style.getString(StateKeys.STYLE_ANCHOR_ID) + "|"
                        + style.getString(StateKeys.STYLE_ANCHOR_POS) + "|"
                        + style.getString(StateKeys.STYLE_ANCHOR_GAP) + "|"
                        + style.getString(StateKeys.STYLE_TREE_INSERT) + "|"
                        + style.getString(StateKeys.STYLE_SHOW_TRANSLATION) + "|"
                        // Font re-imports keep typefaceName "custom": the stamp
                        // marker is what makes the change visible.
                        + style.getString(StateKeys.STYLE_FONT_STAMP) + "|"
                        + style.getString(StateKeys.STYLE_BG_ENABLED) + "|"
                        + style.getString(StateKeys.STYLE_BG_COLOR) + "|"
                        + style.getString(StateKeys.STYLE_BG_ALPHA);
                if (!stamp.equals(mStyleStamp)) {
                    mStyleStamp = stamp;
                    AodOverlayController.setStyle(style);
                }
            }
        } catch (Throwable t) {
            AodLog.w("provider refresh failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    @Nullable
    private static Context resolveContext() {
        Context captured = ModuleReporter.capturedContext();
        if (captured != null) {
            return captured;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getDeclaredMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public String lrc() {
        return mLrc;
    }

    @Nullable
    public String lrcId() {
        return mLrcId;
    }

    /** Latest current-line text (authoritative live lyric for the overlay). */
    @Nullable
    public String line() {
        return mLine;
    }

    @Nullable
    public String track() {
        return mTrack;
    }

    /** Master switch as mirrored from the last refresh (default: on). */
    public boolean moduleEnabled() {
        return mModuleEnabled;
    }

    /** When the provider last received a lyric feed (0 = never). */
    public long lastFeedAt() {
        return mLyricAt;
    }

    /**
     * True when no lyric feed arrived within {@link #STALE_MS}. Players killed
     * outright never deliver a stop event, so this watchdog is what eventually
     * clears the overlay; the next feed un-stales it (self-healing).
     */
    public boolean isStale() {
        long at = mLyricAt;
        return at > 0 && System.currentTimeMillis() - at > STALE_MS;
    }

    /**
     * The line the OVERLAY may show, after the master-switch and staleness
     * gates. Null = show nothing (caller passes "" to hide).
     */
    @Nullable
    public String displayLine() {
        if (!mModuleEnabled || isStale()) {
            return null;
        }
        return mLine;
    }

    /** Translation gated the same way as {@link #displayLine()}. */
    @Nullable
    public String displayTranslation() {
        if (!mModuleEnabled || isStale()) {
            return null;
        }
        return mTranslation;
    }
}
