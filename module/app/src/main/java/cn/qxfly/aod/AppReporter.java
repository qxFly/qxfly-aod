package cn.qxfly.aod;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.model.Line;
import com.hchen.superlyricapi.model.Lyric;

/**
 * UI-process reporter: the app process holds superlyricapi_version_code=40, so
 * ITS SuperLyric stream receives FULL lyric payloads (com.miui.aod, lacking the
 * meta, would only get the current line from the legacy transport). This class
 * publishes the full LRC into the state provider, from where the hook processes
 * fetch it for injection.
 */
public final class AppReporter {

    private static final long MIN_PUSH_GAP_MS = 200L;

    private long mLastPush;
    @Nullable
    private String mLastLrc;
    private String mLastLine;

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
        long now = System.currentTimeMillis();
        boolean lrcChanged = lrc != null && !lrc.equals(mLastLrc);
        boolean lineChanged = currentLine != null && !currentLine.equals(mLastLine);
        if (!lrcChanged && !lineChanged) {
            return;
        }
        if (now - mLastPush < MIN_PUSH_GAP_MS) {
            return;
        }
        mLastPush = now;
        if (lrc != null) {
            mLastLrc = lrc;
        }
        if (currentLine != null) {
            mLastLine = currentLine;
        }

        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        b.putString(StateKeys.LYRIC_TRACK, label(doc));
        b.putString(StateKeys.LYRIC_ID, doc == null ? null : doc.getId());
        if (lrc != null) {
            b.putString(StateKeys.LYRIC_CHARS, String.valueOf(lrc.length()));
            b.putString(StateKeys.LYRIC_LRC, lrc);
        }
        // Authoritative live value for the overlay.
        if (currentLine != null) {
            b.putString(StateKeys.LYRIC_LINE, currentLine);
        }
        b.putString(StateKeys.LYRIC_TRANSLATION, translation);
        push(b);
    }

    /**
     * Playback stopped: clears line/translation/LRC in the provider. Unlike
     * {@link #publishLyric} this is NOT deduped or throttled - every stop must
     * reach the provider, otherwise the AOD keeps the last line forever.
     */
    public void publishStop() {
        mLastLrc = null;
        mLastLine = null;
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        b.putString(StateKeys.LYRIC_LINE, "");
        b.putString(StateKeys.LYRIC_TRANSLATION, "");
        b.putString(StateKeys.LYRIC_LRC, "");
        b.putString(StateKeys.LYRIC_INDEX, "-1");
        b.putString(StateKeys.LYRIC_TOTAL, "0");
        b.putString(StateKeys.LYRIC_CHARS, "0");
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
