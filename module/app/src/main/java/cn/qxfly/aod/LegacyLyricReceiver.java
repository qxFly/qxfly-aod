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

            if (text != null && text.equals(mLastText)) {
                return;
            }
            mLastText = text;
            AodLog.i("legacy lyric: publisher=" + publisher
                    + " line=" + text
                    + " translation=" + translation
                    + " full=" + data.hasAllLyrics()
                    + " idx=" + data.getCurrentLyricIndex());
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
