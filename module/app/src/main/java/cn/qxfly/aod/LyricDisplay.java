package cn.qxfly.aod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One render model for the AOD overlay: the current line plus everything the
 * style may decorate it with (track info, translation, next line, progress).
 *
 * <p>Built by {@link LyricBridge} from the provider snapshot (authoritative,
 * carries position/duration/play-state from the UI app's v4 stream) with the
 * hook's own SuperLyric stream as a line-level fallback.
 */
public final class LyricDisplay {

    @Nullable
    public String line;
    @Nullable
    public String translation;
    @Nullable
    public String next;
    @Nullable
    public String title;
    @Nullable
    public String artist;

    /** Extrapolated position in ms, -1 when unknown. */
    public long positionMs = -1L;
    /** Duration in ms, 0 when unknown. */
    public long durationMs;

    /** True while the publisher reports playing (or says nothing). */
    public boolean playing = true;
    /** True when the publisher explicitly reports a paused track. */
    public boolean paused;

    public boolean hasLine() {
        return line != null && !line.isEmpty();
    }

    /**
     * Change marker for the overlay's render cache.
     *
     * <p>Position is normally EXCLUDED: it is extrapolated on every poll, so
     * including it made the marker differ every second and forced a full
     * structural refresh (reinflate + forced traversal) even when the line was
     * unchanged. When the style actually draws progress, positions ARE part of
     * what the user sees, so the caller opts in.
     */
    @NonNull
    public String signature(boolean withPosition) {
        return (line == null ? "" : line) + '\u0001'
                + (translation == null ? "" : translation) + '\u0001'
                + (next == null ? "" : next) + '\u0001'
                + (title == null ? "" : title) + '\u0001'
                + (artist == null ? "" : artist) + '\u0001'
                + (withPosition ? positionMs + "/" + durationMs : "-") + '\u0001'
                + (paused ? "P" : (playing ? "R" : "X"));
    }

    /** copy() so a shared instance is never mutated after being handed over. */
    @NonNull
    public LyricDisplay copy() {
        LyricDisplay c = new LyricDisplay();
        c.line = line;
        c.translation = translation;
        c.next = next;
        c.title = title;
        c.artist = artist;
        c.positionMs = positionMs;
        c.durationMs = durationMs;
        c.playing = playing;
        c.paused = paused;
        return c;
    }

    /** mm:ss for a millisecond value; "-" when unknown. */
    @NonNull
    public static String time(long ms) {
        if (ms < 0) {
            return "-";
        }
        long total = ms / 1000L;
        return String.format(java.util.Locale.US, "%d:%02d", total / 60L, total % 60L);
    }
}
