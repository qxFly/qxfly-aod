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
 * line. The UI app, holding version 40, receives everything (line, translation,
 * next line, position, duration, play state, title/artist) and publishes it
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

    /** Next line text, for the "next line" preview. */
    @Nullable
    private volatile String mNext;

    @Nullable
    private volatile String mTitle;

    @Nullable
    private volatile String mArtist;

    /** Position sample in ms and the clock it was taken at. */
    private volatile long mPositionMs;
    private volatile long mPositionAt;
    private volatile long mDurationMs;
    /** playing / paused / buffering / stopped ("" = unknown). */
    @NonNull
    private volatile String mPlayState = "";

    /** Master switch mirrored from the provider snapshot (default: on). */
    private volatile boolean mModuleEnabled = true;

    /** When the provider last received a lyric feed (0 = never). */
    private volatile long mLyricAt;

    /** Last diagnostics handshake value seen, so the hook reports only once. */
    private volatile String mDiagRequest;

    /** A lyric feed older than this is considered dead: no stop event ever
     *  arrives when a player is killed, so this is the only way to clear the
     *  overlay. Generous enough that a slow UI-app refresh never trips it. */
    public static final long STALE_MS = 180_000L;

    /** Paused playback may legitimately be silent for a long time. */
    public static final long PAUSED_STALE_MS = 30 * 60_000L;

    @Nullable
    public String translation() {
        return mTranslation;
    }

    @Nullable
    private volatile String mTrack;

    @Nullable
    private volatile String mStyleStamp;

    private volatile boolean sWarnedNoContext;

    /** Pulls the latest snapshot from the provider. One binder call. */
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
            String title = snap.getString(StateKeys.LYRIC_TITLE);
            if (title != null) {
                mTitle = title;
            }
            String artist = snap.getString(StateKeys.LYRIC_ARTIST);
            if (artist != null) {
                mArtist = artist;
            }
            String line = snap.getString(StateKeys.LYRIC_LINE);
            // Empty = playback stopped: clear instead of keeping the stale line.
            mLine = line == null || line.isEmpty() ? null : line;
            String translation = snap.getString(StateKeys.LYRIC_TRANSLATION);
            // Translating line rides in the same snapshot; absent/empty = none.
            mTranslation = translation == null || translation.isEmpty()
                    ? null : translation;
            String next = snap.getString(StateKeys.LYRIC_NEXT);
            mNext = next == null || next.isEmpty() ? null : next;

            long position = parseLong(snap.getString(StateKeys.LYRIC_POSITION), -1L);
            long posAt = parseLong(snap.getString(StateKeys.LYRIC_POS_AT), 0L);
            if (position >= 0 && posAt > 0) {
                mPositionMs = position;
                mPositionAt = posAt;
            }
            mDurationMs = parseLong(snap.getString(StateKeys.LYRIC_DURATION), 0L);
            String state = snap.getString(StateKeys.LYRIC_PLAY_STATE);
            mPlayState = state == null ? "" : state;

            mLyricAt = snap.getLong(StateKeys.LYRIC_AT, 0);
            mModuleEnabled = snap.getBoolean(StateKeys.MODULE_ENABLED, true);
            AodLog.setDebug(snap.getBoolean(StateKeys.LOG_DEBUG, false));
            mDiagRequest = snap.getString(StateKeys.DIAG_REQUEST);
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
                String stamp = styleStamp(style);
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

    /**
     * Change marker for the whole appearance bundle: every key that affects
     * rendering is included, so a new toggle (track/next/progress/pause)
     * retriggers {@code setStyle} exactly like the older keys did.
     */
    @NonNull
    static String styleStamp(@NonNull Bundle style) {
        StringBuilder sb = new StringBuilder();
        for (String key : StateKeys.STYLE_KEYS) {
            sb.append(style.getString(key)).append('|');
        }
        return sb.toString();
    }

    private static long parseLong(@Nullable String raw, long fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (Throwable t) {
            return fallback;
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

    @Nullable
    public String title() {
        return mTitle;
    }

    @Nullable
    public String artist() {
        return mArtist;
    }

    @Nullable
    public String nextLine() {
        return mNext;
    }

    /** True when the publisher reported playing (or said nothing at all). */
    public boolean playing() {
        return mPlayState.isEmpty() || StateKeys.PLAY_PLAYING.equals(mPlayState);
    }

    public boolean paused() {
        return StateKeys.PLAY_PAUSED.equals(mPlayState);
    }

    /**
     * Extrapolated playback position: the sample plus the time elapsed since
     * it was taken, clamped to the track duration. Returns -1 when unknown.
     */
    public long positionNow() {
        if (mPositionAt <= 0) {
            return -1L;
        }
        long pos = mPositionMs;
        if (playing()) {
            pos += Math.max(0L, android.os.SystemClock.elapsedRealtime() - mPositionAt);
        }
        if (mDurationMs > 0 && pos > mDurationMs) {
            pos = mDurationMs;
        }
        return pos;
    }

    /** Track duration in ms, or 0 when unknown. */
    public long duration() {
        return mDurationMs;
    }

    @Nullable
    public String playState() {
        return mPlayState;
    }

    /** Master switch as mirrored from the last refresh (default: on). */
    public boolean moduleEnabled() {
        return mModuleEnabled;
    }

    /** When the provider last received a lyric feed (0 = never). */
    public long lastFeedAt() {
        return mLyricAt;
    }

    /** Current diagnostics handshake value ("" when never requested). */
    @Nullable
    public String diagRequest() {
        return mDiagRequest;
    }

    /**
     * True when no lyric feed arrived within {@link #STALE_MS}. Players killed
     * outright never deliver a stop event, so this watchdog is what eventually
     * clears the overlay; the next feed un-stales it (self-healing).
     *
     * <p>Paused playback is exempt for a much longer window: a paused track can
     * sit there for hours and the line must stay on screen.
     */
    public boolean isStale() {
        long at = mLyricAt;
        if (at <= 0) {
            return false;
        }
        long age = System.currentTimeMillis() - at;
        return age > (paused() ? PAUSED_STALE_MS : STALE_MS);
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

    @Nullable
    public String displayNext() {
        if (!mModuleEnabled || isStale()) {
            return null;
        }
        return mNext;
    }
}
