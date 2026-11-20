package cn.qxfly.aod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.LyricListener;
import com.hchen.superlyricapi.LyricView;
import com.hchen.superlyricapi.Receiver;
import com.hchen.superlyricapi.SuperLyric;
import com.hchen.superlyricapi.model.Lyric;
import com.hchen.superlyricapi.model.Stop;

import io.github.libxposed.api.XposedInterface;

/**
 * Receives structured lyrics from the SuperLyric service, keeps the current
 * LRC for injection, and mirrors a readable view of the same events to the
 * module UI.
 */
public final class SuperLyricSource {

    private final XposedInterface mXp;
    @NonNull
    private final ModuleReporter mReporter;

    /** Raw 3.x receiver: the only path that survives line-only payloads. */
    @NonNull
    private final LegacyLyricReceiver mLegacyReceiver = new LegacyLyricReceiver();

    @Nullable
    private volatile String mCurrentLine;

    @Nullable
    public String currentLine() {
        return mCurrentLine;
    }

    /** Translation of {@link #currentLine()}, when the publisher provides one. */
    @Nullable
    private volatile String mCurrentTranslation;

    @Nullable
    public String currentTranslation() {
        return mCurrentTranslation;
    }

    @Nullable
    private volatile String mCurrentLrc;

    @Nullable
    private volatile String mCurrentTitle;

    @Nullable
    private volatile String mCurrentArtist;

    @Nullable
    private volatile String mTitle;

    @Nullable
    private volatile String mArtist;

    @Nullable
    private volatile String mNext;

    /** Raw song title (not the combined label). */
    @Nullable
    public String title() {
        return mTitle;
    }

    /** Raw artist name. */
    @Nullable
    public String artist() {
        return mArtist;
    }

    /** Next line text, when the payload carries a full document. */
    @Nullable
    public String nextLine() {
        return mNext;
    }

    // ---- playback metadata from the legacy (3.x) payload ----
    // The v4 stream (getPosition/getDuration/getPlaybackState) never fires on a
    // serverApiVersion=0 host, so the legacy payload is the only place this
    // process can learn the track length and the playing position.

    private volatile long mDurationMs;

    private volatile long mPositionMs = -1L;

    private volatile long mPositionAt;

    private volatile boolean mHasPosition;

    @Nullable
    private volatile String mLyricId;

    /** Line index / total from the last legacy payload (for the UI report). */
    private volatile int mIndex = -1;
    private volatile int mTotal;

    /** Track duration in ms (0 = unknown). */
    public long durationMs() {
        return mDurationMs;
    }

    @Nullable
    public String lyricId() {
        return mLyricId;
    }

    /**
     * Extrapolated position in ms, or -1 when the payload never carried one.
     * Play state is not part of the legacy payload, so the caller decides
     * whether to keep advancing it (see {@link #positionNow(boolean)}).
     */
    public long positionNow(boolean playing) {
        if (!mHasPosition) {
            return -1L;
        }
        long pos = mPositionMs;
        if (playing) {
            pos += Math.max(0L, android.os.SystemClock.elapsedRealtime() - mPositionAt);
        }
        if (mDurationMs > 0 && pos > mDurationMs) {
            pos = mDurationMs;
        }
        return pos;
    }

    private volatile boolean mRegistered;

    /** When this process's own SuperLyric stream last delivered anything
     *  (lyric or legacy line). Used with the provider's lyricAt to detect a
     *  killed player: whichever source fed more recently wins. */
    private volatile long mLastEventAt;

    public long lastEventAt() {
        return mLastEventAt;
    }

    public SuperLyricSource(@NonNull XposedInterface xp) {
        mXp = xp;
        mReporter = new ModuleReporter(xp);
    }

    public void register() {
        if (mRegistered) {
            return;
        }
        mRegistered = true;

        Receiver receiver = SuperLyric.receiver();
        AodLog.i("SuperLyric.isAvailable=" + SuperLyric.isAvailable()
                + " serverApiVersion=" + SuperLyric.serverApiVersion());

        Receiver.RegisterResult result = receiver.register(new LyricListener() {
            @Override
            public void onLyric(@NonNull LyricView view) {
                mLastEventAt = System.currentTimeMillis();
                Lyric doc = view.getLyric();
                String lrc = LrcBuilder.build(doc);
                mCurrentLrc = lrc;
                mCurrentTitle = doc == null ? null : doc.getTitle();
                mCurrentArtist = doc == null ? null : doc.getArtist();
                if (doc != null) {
                    mTitle = doc.getTitle();
                    mArtist = doc.getArtist();
                }
                mNext = upcomingText(view);
                LyricBridge.markDirty();

                com.hchen.superlyricapi.model.Line line = view.getLine();
                // INFO level: this log is the discriminator between "full doc
                // delivered" (doc != null) and "legacy current-line stream"
                // (doc == null), which decides whether injection has anything
                // to work with in this process.
                AodLog.i("onLyric: doc="
                        + (doc == null ? "NULL(current-line stream)" : "full")
                        + " id=" + (doc == null ? "-" : doc.getId())
                        + " lines=" + (doc == null ? 0 : doc.getLineCount())
                        + " index=" + view.getIndex()
                        + " lrc=" + (lrc == null ? "null" : lrc.length() + " chars"));

                mReporter.reportLyric(
                        trackLabel(doc),
                        line == null ? null : line.getText(),
                        String.valueOf(view.getIndex()),
                        String.valueOf(doc == null ? 0 : doc.getLineCount()),
                        lrc == null ? "0" : String.valueOf(lrc.length()));
            }

            @Override
            public void onStop(@NonNull Stop stop) {
                mCurrentLrc = null;
                mCurrentLine = null;
                mCurrentTranslation = null;
                // Clear rather than writing a "(stopped)" placeholder: the
                // overlay renders LYRIC_LINE verbatim, so a sentinel string would
                // be displayed as if it were a lyric.
                mReporter.reportLyric(mCurrentTitle, "",
                        "-1", "0", "0");
                // Also clear the whole lyric record in the provider (line,
                // translation and the full LRC): without this the ghost
                // lines/LRC survived playback stops in the provider snapshot.
                mReporter.reportLyricStop();
                AodOverlayController.setText("");
                LyricBridge.requestForceEmptyLrc();
                AodLog.i("onStop: " + stop.getReason());
            }
        });

        mReporter.reportStatus(true, "getString + parseLyric + theme probe", result.name());

        // Second, independent path: read the raw 3.x payload so a current-line-only
        // stream still produces text. SuperLyricApi's Receiver drops those (see
        // LegacyLyricReceiver's javadoc), which is why nothing ever arrived here.
        boolean legacyOk = mLegacyReceiver.register(new LegacyLyricReceiver.Sink() {
            @Override
            public void onLine(@Nullable String text, @Nullable String title,
                               @Nullable String artist) {
                onLine(text, null, title, artist);
            }

            @Override
            public void onLine(@Nullable String text, @Nullable String translation,
                               @Nullable String title, @Nullable String artist) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                mLastEventAt = System.currentTimeMillis();
                mCurrentLine = text;
                mCurrentTranslation = translation;
                if (title != null && !title.isEmpty()) {
                    mTitle = title;
                    mArtist = artist;
                    mCurrentTitle = artist == null || artist.isEmpty()
                            ? title : artist + " - " + title;
                }
                // Direct to the AOD overlay in this process...
                LyricBridge.markDirty();
                // ...and to the app UI through the provider.
                mReporter.reportLyric(mCurrentTitle, text, translation,
                        String.valueOf(mIndex), String.valueOf(mTotal), "0");
            }

            @Override
            public void onMeta(@Nullable String title, @Nullable String artist,
                               @Nullable String next, int index, int total) {
                if (title != null && !title.isEmpty()) {
                    mTitle = title;
                    mArtist = artist;
                }
                if (next != null) {
                    mNext = next;
                }
                mIndex = index;
                mTotal = total;
                LyricBridge.markDirty();
            }

            @Override
            public void onPlayback(@Nullable String lyricId, boolean hasDuration,
                                   long durationMs, boolean hasPosition, long positionMs,
                                   boolean fullPayload, int allCount, int upcomingCount) {
                if (lyricId != null) {
                    mLyricId = lyricId;
                }
                if (hasDuration && durationMs > 0) {
                    mDurationMs = durationMs;
                }
                if (hasPosition && positionMs >= 0) {
                    mPositionMs = positionMs;
                    mPositionAt = android.os.SystemClock.elapsedRealtime();
                    mHasPosition = true;
                }
                // Mirror into the provider so the app UI's "now playing" /
                // progress rows show the same values the overlay uses.
                mReporter.reportPlayback(mTitle, mArtist, mNext,
                        hasDuration && durationMs > 0, durationMs,
                        hasPosition && positionMs >= 0, positionMs);
                LyricBridge.markDirty();
            }

            @Override
            public void onStop() {
                mCurrentLrc = null;
                mCurrentLine = null;
                mCurrentTranslation = null;
                mNext = null;
                mDurationMs = 0L;
                mPositionMs = -1L;
                mHasPosition = false;
                mIndex = -1;
                mTotal = 0;
                mReporter.reportLyricStop();
                AodOverlayController.setText("");
                LyricBridge.requestForceEmptyLrc();
                AodLog.i("legacy receiver: stopped");
            }
        });
        AodLog.i("direct legacy receiver: " + (legacyOk ? "registered" : "unavailable"));
        AodLog.i("SuperLyric register result: " + result);
        if (result != Receiver.RegisterResult.OK) {
            AodLog.w("SuperLyric unavailable (" + result + "). "
                    + "Check that the SuperLyric module is installed and enabled, "
                    + "and that com.miui.aod passes AccessPolicy (system app).");
        }
    }

    /** First non-empty upcoming line of a v4 view, or null. */
    @Nullable
    private static String upcomingText(@NonNull LyricView view) {
        try {
            java.util.List<com.hchen.superlyricapi.model.Line> upcoming = view.getUpcoming();
            if (upcoming == null) {
                return null;
            }
            for (com.hchen.superlyricapi.model.Line line : upcoming) {
                if (line != null && line.getText() != null
                        && !line.getText().trim().isEmpty()) {
                    return line.getText();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static String trackLabel(@Nullable Lyric doc) {        if (doc == null) {
            return null;
        }
        String title = doc.getTitle();
        String artist = doc.getArtist();
        if (artist == null || artist.isEmpty()) {
            return title;
        }
        return artist + " - " + title;
    }

    public void unregister() {
        if (mRegistered) {
            SuperLyric.receiver().unregisterAll();
            mRegistered = false;
            mCurrentLrc = null;
        }
    }

    @Nullable
    public String currentLrc() {
        return mCurrentLrc;
    }
}

