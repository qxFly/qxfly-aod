package cn.qxfly.aod;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.model.Line;
import com.hchen.superlyricapi.model.Lyric;
import com.hchen.superlyricapi.model.PlaybackState;

/**
 * UI-process reporter: the app process holds superlyricapi_version_code=40, so
 * ITS SuperLyric stream receives FULL lyric payloads (com.miui.aod, lacking the
 * meta, would only get the current line from the legacy transport). This class
 * publishes the full LRC, the live line and the playback metadata (title,
 * artist, next line, position, duration, play state) into the state provider,
 * from where the hook processes fetch it for injection and for the overlay's
 * extra lines.
 */
public final class AppReporter {

    /** Minimum gap between two pushes (position-only updates are batched). */
    private static final long MIN_PUSH_GAP_MS = 200L;

    /** Position changes smaller than this are not worth a binder call. */
    private static final long POSITION_PUSH_GAP_MS = 900L;

    private long mLastPush;
    @Nullable
    private String mLastLrc;
    private String mLastLine = "";
    private String mLastNext = "";
    @Nullable
    private String mLastState;
    private long mLastPositionPushed = -1L;

    /**
     * Publishes the lyric document AND the current line.
     *
     * <p>The current line is what the self-drawn AOD overlay displays. Pushing
     * only on LRC changes was wrong: within one song the LRC never changes, so
     * the line would never update - and the overlay reads LYRIC_LINE, which
     * nothing was writing at all.
     */
    public void publishLyric(@Nullable Lyric doc, @Nullable String lrc,
                             @Nullable String currentLine) {
        publishLyric(doc, lrc, currentLine, null);
    }

    /**
     * Same, with the translation of the current line. Publishers that ship
     * bilingual lyrics fill {@code Line.getTranslation()}; players without a
     * translation pass null, which clears any stale value in the provider.
     */
    public void publishLyric(@Nullable Lyric doc, @Nullable String lrc,
                             @Nullable String currentLine,
                             @Nullable String translation) {
        publish(doc, lrc, currentLine, translation, null, null, -1L, 0L);
    }

    /**
     * Full publish from a raw v4 view: line + translation + next line + track
     * metadata + position/duration/play-state.
     *
     * <p>State-only updates (position ticking within one line) are throttled to
     * ~1s; line/lrc changes always go through, so the overlay never lags.
     */
    public void publishLyric(@Nullable com.hchen.superlyricapi.LyricView view,
                             @Nullable String lrc) {
        if (view == null) {
            return;
        }
        Lyric doc = view.getLyric();
        Line line = view.getLine();
        String text = line == null ? null : line.getText();
        String translation = line == null ? null : line.getTranslation();
        String next = nextLine(view);
        PlaybackState state = view.getState();
        publish(doc, lrc, text, translation, next,
                state == null ? null : state, view.getPosition(), view.getSpeed());
    }

    private void publish(@Nullable Lyric doc, @Nullable String lrc,
                         @Nullable String currentLine, @Nullable String translation,
                         @Nullable String next, @Nullable PlaybackState state,
                         long position, float speed) {
        long now = System.currentTimeMillis();
        boolean lrcChanged = lrc != null && !lrc.equals(mLastLrc);
        String lineText = currentLine == null ? "" : currentLine;
        String nextText = next == null ? "" : next;
        boolean lineChanged = !lineText.equals(mLastLine);
        boolean nextChanged = !nextText.equals(mLastNext);
        String stateName = playStateName(state);
        boolean stateChanged = stateName != null && !stateName.equals(mLastState);
        boolean positionDue = position >= 0
                && (mLastPositionPushed < 0
                || Math.abs(position - mLastPositionPushed) >= POSITION_PUSH_GAP_MS);

        if (!lrcChanged && !lineChanged && !nextChanged && !stateChanged && !positionDue) {
            return;
        }
        if (now - mLastPush < MIN_PUSH_GAP_MS) {
            return;
        }
        mLastPush = now;
        if (lrc != null) {
            mLastLrc = lrc;
        }
        mLastLine = lineText;
        mLastNext = nextText;
        if (stateName != null) {
            mLastState = stateName;
        }
        if (position >= 0) {
            mLastPositionPushed = position;
        }

        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        b.putString(StateKeys.LYRIC_TRACK, label(doc));
        b.putString(StateKeys.LYRIC_ID, doc == null ? null : doc.getId());
        if (doc != null) {
            b.putString(StateKeys.LYRIC_TITLE, doc.getTitle());
            b.putString(StateKeys.LYRIC_ARTIST, doc.getArtist());
            if (doc.getDuration() > 0) {
                b.putString(StateKeys.LYRIC_DURATION, String.valueOf(doc.getDuration()));
            }
        }
        if (lrc != null) {
            b.putString(StateKeys.LYRIC_CHARS, String.valueOf(lrc.length()));
            b.putString(StateKeys.LYRIC_LRC, lrc);
        }
        // Authoritative live value for the overlay.
        if (currentLine != null) {
            b.putString(StateKeys.LYRIC_LINE, currentLine);
        }
        b.putString(StateKeys.LYRIC_TRANSLATION, translation);
        if (next != null) {
            b.putString(StateKeys.LYRIC_NEXT, next);
        }
        if (stateName != null) {
            b.putString(StateKeys.LYRIC_PLAY_STATE, stateName);
        }
        if (position >= 0) {
            b.putString(StateKeys.LYRIC_POSITION, String.valueOf(position));
            b.putString(StateKeys.LYRIC_POS_AT,
                    String.valueOf(SystemClock.elapsedRealtime()));
        }
        if (speed > 0f && speed != 1f) {
            // Recorded through the position clock: the hook extrapolates with
            // elapsed realtime, and a non-1x speed would drift. Ignored here;
            // kept in the bundle for diagnostics only.
            b.putString("lyricSpeed", String.valueOf(speed));
        }
        push(b);
    }

    /** First non-empty upcoming line text. */
    @Nullable
    private static String nextLine(@NonNull com.hchen.superlyricapi.LyricView view) {
        try {
            java.util.List<Line> upcoming = view.getUpcoming();
            if (upcoming == null) {
                return null;
            }
            for (Line l : upcoming) {
                if (l != null && l.getText() != null && !l.getText().trim().isEmpty()) {
                    return l.getText();
                }
            }
        } catch (Throwable ignored) {
            // Some publishers omit the upcoming list.
        }
        return null;
    }

    @Nullable
    private static String playStateName(@Nullable PlaybackState state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case PLAYING -> StateKeys.PLAY_PLAYING;
            case PAUSED -> StateKeys.PLAY_PAUSED;
            case BUFFERING -> StateKeys.PLAY_BUFFERING;
            case STOPPED, ERROR -> StateKeys.PLAY_STOPPED;
        };
    }

    /**
     * Playback stopped: clears line/translation/LRC in the provider. Unlike
     * {@link #publishLyric} this is NOT deduped or throttled - every stop must
     * reach the provider, otherwise the AOD keeps the last line forever.
     */
    public void publishStop() {
        mLastLrc = null;
        mLastLine = "";
        mLastNext = "";
        mLastState = StateKeys.PLAY_STOPPED;
        mLastPositionPushed = -1L;
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        b.putString(StateKeys.LYRIC_LINE, "");
        b.putString(StateKeys.LYRIC_TRANSLATION, "");
        b.putString(StateKeys.LYRIC_LRC, "");
        b.putString(StateKeys.LYRIC_NEXT, "");
        b.putString(StateKeys.LYRIC_INDEX, "-1");
        b.putString(StateKeys.LYRIC_TOTAL, "0");
        b.putString(StateKeys.LYRIC_CHARS, "0");
        b.putString(StateKeys.LYRIC_PLAY_STATE, StateKeys.PLAY_STOPPED);
        push(b);
    }

    @Nullable
    private static String label(@Nullable Lyric doc) {
        if (doc == null) {
            return null;
        }
        String artist = doc.getArtist();
        return artist == null || artist.isEmpty()
                ? doc.getTitle() : artist + " - " + doc.getTitle();
    }

    private void push(@NonNull Bundle payload) {
        try {
            Context context = AppContextHolder.get();
            if (context == null) {
                return;
            }
            context.getContentResolver().call(
                    StateKeys.stateUri(), StateKeys.METHOD_UPDATE, null, payload);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    public String lastLrc() {
        return mLastLrc;
    }

    @Nullable
    static String firstLine(@Nullable Lyric doc) {
        if (doc == null || doc.getLines().isEmpty()) {
            return null;
        }
        Line l = doc.getLines().get(0);
        return l == null ? null : l.getText();
    }
}
