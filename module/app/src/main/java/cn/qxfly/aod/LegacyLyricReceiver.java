package cn.qxfly.aod;

import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.ISuperLyricManager;
import com.hchen.superlyricapi.ISuperLyricReceiver;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

/**
 * Direct legacy (3.x) SuperLyric receiver.
 *
 * <p>Why not use SuperLyricApi's Receiver: that path converts 3.x payloads with
 * {@code LegacyV37.lift()}. A current-line-only payload (no allLyrics) becomes a
 * STATE frame carrying only index/position; {@code LyricCache.resolve()} needs a
 * stored document to combine it with, returns null without one, and its
 * self-heal also comes back empty - so {@code LyricListener.onLyric} is never
 * invoked. Result: the line text exists in the binder payload but never reaches
 * the listener.
 *
 * <p>This class registers on the raw AIDL instead and reads
 * {@code SuperLyricData.getLyric().getText()} immediately, which works for both
 * line-only (3.x publisher) and full payloads.
 */
public final class LegacyLyricReceiver {

    public interface Sink {
        void onLine(@Nullable String text, @Nullable String title, @Nullable String artist);

        /**
         * Current line plus its translation (both may be null).
         *
         * <p>Publishers that ship bilingual lyrics fill
         * {@code SuperLyricLine.getTranslation()}; players that only publish the
         * original text leave it null, in which case the overlay shows the
         * original line alone.
         */
        default void onLine(@Nullable String text, @Nullable String translation,
                            @Nullable String title, @Nullable String artist) {
            onLine(text, title, artist);
        }

        void onStop();

        /**
         * Track metadata for the current line: title/artist (raw, not the
         * combined label), the next line if the payload carries a full
         * document, and the line index/total.
         *
         * <p>Called even when the line text itself did not change, so the
         * "next line" preview stays correct while a long line plays.
         */
        default void onMeta(@Nullable String title, @Nullable String artist,
                            @Nullable String next, int index, int total) {
        }

        /**
         * Playback metadata carried by the same payload: duration/position (ms)
         * and the stable lyric id. 3.x services DO fill these (see
         * {@code LyricDocAdapter.legacyMetadata} in SuperLyric: title, artist,
         * album, id, duration, position), which is the only place a hook
         * process can get a progress value when the v4 stream never fires.
         *
         * <p>{@code fullPayload} reports whether the payload carries the whole
         * document ({@code allLyrics}); only then a "next line" exists.
         */
        default void onPlayback(@Nullable String lyricId, boolean hasDuration,
                                long durationMs, boolean hasPosition, long positionMs,
                                boolean fullPayload, int allCount, int upcomingCount) {
        }
    }

    @Nullable
    private volatile ISuperLyricManager mManager;

    @Nullable
    private volatile ISuperLyricReceiver mStub;

    @Nullable
    private volatile String mLastText;

    private volatile boolean mRegistered;

    /**
     * Binds the legacy service (if present) and registers the receiver.
     *
     * @return true when bound and registered
     */
    public boolean register(@NonNull Sink sink) {
        if (mRegistered) {
            return true;
        }
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("getService", String.class)
                    .invoke(null, "super_lyric");
            if (binder == null) {
                AodLog.w("legacy receiver: super_lyric service not found");
                return false;
            }
            ISuperLyricManager manager =
                    ISuperLyricManager.Stub.asInterface(binder);
            if (manager == null) {
                AodLog.w("legacy receiver: asInterface returned null");
                return false;
            }

            ISuperLyricReceiver stub = new ISuperLyricReceiver.Stub() {
                @Override
                public void onLyric(String publisher, SuperLyricData data) {
                    handleLyric(sink, publisher, data);
                }

                @Override
                public void onStop(String publisher, SuperLyricData data) {
                    mLastText = null;
                    sink.onStop();
                }
            };

            manager.registerReceiver(stub);
            mManager = manager;
            mStub = stub;
            mRegistered = true;
            AodLog.i("legacy receiver registered, serverApiVersion="
                    + safeApiVersion(manager));

            // A session may already be playing: pull the current snapshot.
            try {
                SuperLyricData latest = manager.getLatestLyric();
                if (latest != null) {
                    handleLyric(sink, null, latest);
                }
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            AodLog.w("legacy receiver register failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return false;
        }
    }

    private static int safeApiVersion(@NonNull ISuperLyricManager manager) {
        try {
            return manager.getServerApiVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    private void handleLyric(@NonNull Sink sink, @Nullable String publisher,
                             @Nullable SuperLyricData data) {
        if (data == null) {
            return;
        }
        try {
            SuperLyricLine line = data.getLyric();
            String text = line == null ? null : line.getText();
            String translation = line == null ? null : textOrNull(line.getTranslation());
            if (text == null || text.isEmpty()) {
                // Full payloads without a current line: take the indexed line.
                if (data.getAllLyricsCount() > 0) {
                    SuperLyricLine at = data.getLyricAt(data.getCurrentLyricIndex());
                    text = at == null ? null : at.getText();
                    translation = at == null ? null : textOrNull(at.getTranslation());
                }
            }
            String title = data.getTitle();
            String artist = data.getArtist();
            int index = data.getCurrentLyricIndex();
            int total = data.getAllLyricsCount();
            boolean full = false;
            int upcomingCount = 0;
            String next = null;
            try {
                full = data.hasAllLyrics();
                if (full) {
                    // Authoritative when the payload is a full document.
                    java.util.List<SuperLyricLine> upcoming = data.getUpcomingLyrics();
                    upcomingCount = upcoming == null ? 0 : upcoming.size();
                    if (upcoming != null) {
                        for (SuperLyricLine after : upcoming) {
                            String t = after == null ? null : textOrNull(after.getText());
                            if (t != null) {
                                next = t;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Not every publisher exposes an indexed list.
            }
            if (next == null) {
                try {
                    if (total > 0 && index >= 0 && index + 1 < total) {
                        SuperLyricLine after = data.getLyricAt(index + 1);
                        next = after == null ? null : textOrNull(after.getText());
                    }
                } catch (Throwable ignored) {
                }
            }
            boolean hasDuration = false;
            long durationMs = -1L;
            boolean hasPosition = false;
            long positionMs = -1L;
            String lyricId = null;
            try {
                hasDuration = data.hasDuration();
                durationMs = hasDuration ? data.getDuration() : -1L;
                hasPosition = data.hasPosition();
                positionMs = hasPosition ? data.getPosition() : -1L;
                lyricId = data.hasLyricId() ? data.getLyricId() : null;
            } catch (Throwable ignored) {
            }
            // Meta/playback first: they must refresh even when the line text
            // repeats (long lines, position ticks).
            sink.onMeta(title, artist, next, index, total);
            sink.onPlayback(lyricId, hasDuration, durationMs, hasPosition, positionMs,
                    full, total, upcomingCount);

            if (text != null && text.equals(mLastText)) {
                return;
            }
            mLastText = text;
            AodLog.i("legacy lyric: publisher=" + publisher
                    + " track=" + title + "/" + artist
                    + " album=" + safeAlbum(data)
                    + " idx=" + index + "/" + total
                    + " full=" + full + " upcoming=" + upcomingCount
                    + " dur=" + durationMs + " pos=" + positionMs
                    + " id=" + lyricId
                    + " secondary=" + hasSecondary(data)
                    + " extra=" + extraKeys(data)
                    + " translation=" + translation
                    + " line=" + text);
            sink.onLine(text, translation, title, artist);
        } catch (Throwable t) {
            AodLog.w("legacy lyric handling failed: " + t);
        }
    }

    /** Blank/absent translations are normalised to null. */
    @Nullable
    private static String textOrNull(@Nullable String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Diagnostics: album string, or "-". */
    @NonNull
    private static String safeAlbum(@NonNull SuperLyricData data) {
        try {
            String album = data.getAlbum();
            return album == null ? "-" : album;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Diagnostics: whether a secondary (phonetic) line is present. */
    @NonNull
    private static String hasSecondary(@NonNull SuperLyricData data) {
        try {
            SuperLyricLine secondary = data.getSecondary();
            if (secondary == null || secondary.getText() == null) {
                return "-";
            }
            return String.valueOf(secondary.getText().length());
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Diagnostics: keys of the publisher's extra bundle ("-" when absent). */
    @NonNull
    private static String extraKeys(@NonNull SuperLyricData data) {
        try {
            android.os.Bundle extra = data.getExtra();
            return extra == null ? "-" : extra.keySet().toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    public void unregister() {
        ISuperLyricManager manager = mManager;
        ISuperLyricReceiver stub = mStub;
        mRegistered = false;
        if (manager != null && stub != null) {
            try {
                manager.unregisterReceiver(stub);
            } catch (Throwable ignored) {
            }
        }
        mManager = null;
        mStub = null;
    }
}
