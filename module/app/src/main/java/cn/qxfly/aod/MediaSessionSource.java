package cn.qxfly.aod;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Playback state straight from the platform MediaSession.
 *
 * <p>Why this exists: on this device SuperLyric speaks the 3.x protocol
 * ({@code serverApiVersion=0}) and its legacy transport only carries the
 * current line - no v4 {@code LyricView} callback ever fires, so the module's
 * {@code getPosition()/getDuration()/getPlaybackState()} never have data. The
 * AOD overlay therefore had no "progress" and no play/pause state at all.
 *
 * <p>The host process is SystemUI, which holds
 * {@code android.permission.MEDIA_CONTENT_CONTROL}, so it can enumerate the
 * active sessions directly. The values are read from MediaController's LOCAL
 * cache (updated by the session's own callbacks), so a 1 Hz poll costs no
 * binder round trips; only the session list is re-enumerated every
 * {@link #CONTROLLER_REFRESH_MS}.
 */
public final class MediaSessionSource {

    /** Re-enumerate the session list this often (covers new tracks/players). */
    private static final long CONTROLLER_REFRESH_MS = 15_000L;

    /** Don't post another enumeration more often than this when it fails. */
    private static final long REFRESH_RETRY_MS = 5_000L;

    @NonNull
    private volatile List<MediaController> mControllers = Collections.emptyList();

    private volatile long mControllersAt;

    private volatile long mRefreshRequestedAt;

    @Nullable
    private volatile MediaController mCurrent;

    /** True while the current session exposes a usable playback state. */
    private volatile boolean mValid;

    private volatile long mDurationMs;

    /** Position sample in ms and when it was taken (elapsedRealtime). */
    private volatile long mPositionMs = -1L;
    private volatile long mSampleAt;

    private volatile float mSpeed = 1f;

    @NonNull
    private volatile String mState = "";

    @Nullable
    private volatile String mTitle;

    @Nullable
    private volatile String mArtist;

    /** One-shot diagnostics for the enumeration outcome. */
    private volatile boolean mLoggedPick;

    @Nullable
    private volatile String mLastPickLog;

    /** Last metadata key set, logged once per change (diagnostics). */
    @Nullable
    private volatile String mLastKeySet;

    @Nullable
    private volatile String mLastTrackLog;

    /**
     * Polls the current session. Cheap: reads cached fields only. The session
     * list itself is re-enumerated asynchronously on the main thread, because
     * constructors there need a Looper.
     */
    public void refresh(@NonNull Context context) {
        long now = SystemClock.elapsedRealtime();
        if (needsEnumeration(now) && now - mRefreshRequestedAt > REFRESH_RETRY_MS) {
            mRefreshRequestedAt = now;
            postMain(() -> refreshControllers(context));
        }
        MediaController controller = mCurrent;
        if (controller == null) {
            mValid = false;
            return;
        }
        try {
            PlaybackState state = controller.getPlaybackState();
            MediaMetadata metadata = controller.getMetadata();
            if (state == null) {
                mValid = false;
                return;
            }
            mValid = true;
            mState = name(state.getState());
            mSpeed = state.getPlaybackSpeed();
            long base = state.getPosition();
            long updatedAt = state.getLastPositionUpdateTime();
            long elapsed = updatedAt > 0 ? Math.max(0L, now - updatedAt) : 0L;
            long position = base;
            if (state.getState() == PlaybackState.STATE_PLAYING && mSpeed > 0f) {
                position += (long) (elapsed * mSpeed);
            }
            mPositionMs = Math.max(0L, position);
            mSampleAt = now;

            long duration = metadata == null ? 0L
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            mDurationMs = Math.max(0L, duration);
            mTitle = string(metadata, MediaMetadata.METADATA_KEY_TITLE);
            String artist = string(metadata, MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) {
                artist = string(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            mArtist = artist;

            // Diagnostics: one line per track, showing which metadata keys the
            // player actually publishes. If a player ships the full LRC under a
            // media-metadata key this is where it becomes visible.
            String keys = metadata == null ? "none"
                    : new java.util.TreeSet<>(metadata.keySet()).toString();
            if (!keys.equals(mLastKeySet)) {
                mLastKeySet = keys;
                AodLog.i("media metadata keys=" + keys);
            }
            String trackLog = mTitle + " / " + mArtist + " / " + mDurationMs + "ms";
            if (!trackLog.equals(mLastTrackLog)) {
                mLastTrackLog = trackLog;
                AodLog.i("now playing: " + trackLog + " state=" + mState
                        + " pos=" + mPositionMs);
            }
        } catch (Throwable t) {
            mValid = false;
            AodLog.d("media session read failed: " + t);
        }
    }

    /**
     * True when the cached session list must be rebuilt: no session yet, a
     * session without state, or one parked in a terminal state for a while (the
     * user may have switched to another player since). A long timer also runs
     * for a live session so a killed player's frozen "playing" state heals.
     */
    private boolean needsEnumeration(long now) {
        MediaController controller = mCurrent;
        if (controller == null || controller.getSessionToken() == null) {
            return true;
        }
        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return true;
        }
        long age = now - mControllersAt;
        switch (state.getState()) {
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_ERROR:
            case PlaybackState.STATE_NONE:
                return age > CONTROLLER_REFRESH_MS;
            case PlaybackState.STATE_PLAYING:
                // Self-heal only: a killed player keeps reporting "playing"
                // from the cache, so re-check now and then.
                return age > 5 * 60_000L;
            default:
                return age > 60_000L;
        }
    }

    /** Enumerates the sessions and picks the one that is actually playing. */
    private void refreshControllers(@NonNull Context context) {
        try {
            MediaSessionManager manager = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                mValid = false;
                return;
            }
            List<MediaController> sessions = manager.getActiveSessions(null);
            List<MediaController> fresh = new ArrayList<>();
            MediaController best = null;
            int bestScore = -1;
            StringBuilder trace = new StringBuilder();
            for (MediaController c : sessions) {
                if (c == null) {
                    continue;
                }
                fresh.add(c);
                PlaybackState st = c.getPlaybackState();
                MediaMetadata md = c.getMetadata();
                int score = score(st, md);
                if (trace.length() > 0) {
                    trace.append(", ");
                }
                trace.append(c.getPackageName()).append(':').append(score);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            // NOTE: MediaController registers its own internal callback and has
            // no public release(), so we deliberately do NOT re-enumerate on a
            // timer - each enumeration would leak a callback binder per
            // session. We only enumerate when the current session died, never
            // existed, or is sitting in a terminal state for a while.
            mControllers = fresh;
            mCurrent = best;
            mControllersAt = SystemClock.elapsedRealtime();
            String pick = best == null ? "none" : best.getPackageName() + "/" + bestScore;
            if (!pick.equals(mLastPickLog)) {
                mLastPickLog = pick;
                AodLog.i("media session pick=" + pick + " candidates=[" + trace + "]");
            }
            mLoggedPick = true;
        } catch (Throwable t) {
            mValid = false;
            if (!mLoggedPick) {
                mLoggedPick = true;
                AodLog.w("media session enumeration failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Ranks a session: a playing session with track metadata wins, the
     * telecom "media button" session (no metadata, no state) loses.
     */
    private static int score(@Nullable PlaybackState st, @Nullable MediaMetadata md) {
        if (st == null) {
            return 0;
        }
        int score;
        switch (st.getState()) {
            case PlaybackState.STATE_PLAYING:
                score = 40;
                break;
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_PAUSED:
                score = 30;
                break;
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_NONE:
                score = 5;
                break;
            default:
                score = 10;
                break;
        }
        String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title != null && !title.trim().isEmpty()) {
            score += 8;
        }
        if (md != null && md.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0) {
            score += 2;
        }
        return score;
    }

    @Nullable
    private static String string(@Nullable MediaMetadata md, @NonNull String key) {
        if (md == null) {
            return null;
        }
        try {
            // getText first: some players store the value as a CharSequence
            // (Spannable), and Bundle#getString returns null for those - which
            // silently blanked the overlay's track line.
            CharSequence value = md.getText(key);
            if (value == null) {
                value = md.getString(key);
            }
            if (value == null) {
                return null;
            }
            String trimmed = value.toString().trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    private static String name(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return StateKeys.PLAY_PLAYING;
            case PlaybackState.STATE_PAUSED:
                return StateKeys.PLAY_PAUSED;
            case PlaybackState.STATE_BUFFERING:
                return StateKeys.PLAY_BUFFERING;
            case PlaybackState.STATE_STOPPED:
            case PlaybackState.STATE_ERROR:
            case PlaybackState.STATE_NONE:
                return StateKeys.PLAY_STOPPED;
            default:
                return "";
        }
    }

    private static void postMain(@NonNull Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    /** True while a session with a playback state is available. */
    public boolean valid() {
        return mValid;
    }

    @NonNull
    public String stateName() {
        return mState;
    }

    public boolean playing() {
        return mValid && (mState.isEmpty() || StateKeys.PLAY_PLAYING.equals(mState)
                || StateKeys.PLAY_BUFFERING.equals(mState));
    }

    public boolean paused() {
        return mValid && StateKeys.PLAY_PAUSED.equals(mState);
    }

    /** Track duration in ms, 0 when unknown. */
    public long durationMs() {
        return mDurationMs;
    }

    /** Extrapolated position in ms, -1 when unknown. */
    public long positionNow() {
        if (!mValid || mPositionMs < 0) {
            return -1L;
        }
        long pos = mPositionMs;
        if (StateKeys.PLAY_PLAYING.equals(mState)) {
            pos += Math.max(0L, SystemClock.elapsedRealtime() - mSampleAt);
        }
        if (mDurationMs > 0 && pos > mDurationMs) {
            pos = mDurationMs;
        }
        return pos;
    }

    @Nullable
    public String title() {
        return mTitle;
    }

    @Nullable
    public String artist() {
        return mArtist;
    }
}
