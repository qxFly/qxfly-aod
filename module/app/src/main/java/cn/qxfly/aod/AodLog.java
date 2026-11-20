package cn.qxfly.aod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;

/**
 * Dual-channel log facade with a bounded in-process ring buffer.
 *
 * <p>This class must NOT extend or reference any libxposed type in its own
 * class hierarchy: it is loaded in the UI app process too, where libxposed
 * classes do not exist. Logging through AodLyricModule's statics from the app
 * process triggered NoClassDefFoundError (the module class's superclass
 * XposedModule is absent outside hook processes) - which crashed the app and
 * silently killed SuperLyric registration.
 *
 * <p>The XposedInterface delegate is attached only in hook processes.
 *
 * <p>Ring buffer: the last {@link #RING_MAX} lines are kept so the diagnostics
 * screen can show what happened in the hook (SystemUI / com.miui.aod)
 * processes, which the UI process cannot read from logcat. The hook pushes its
 * tail into the state provider on demand - see {@link #tail(int)}.
 */
public final class AodLog {

    public static final String TAG = "AodLyric";

    private static final int RING_MAX = 240;

    private static final java.util.ArrayDeque<String> RING = new java.util.ArrayDeque<>();

    /** Debug lines are only recorded/emitted when this is on. */
    private static volatile boolean sDebug;

    @Nullable
    private static volatile XposedInterface sXp;

    private AodLog() {
    }

    /** Called from AodLyricModule's constructor (hook processes only). */
    public static void attachXposed(@Nullable XposedInterface xp) {
        sXp = xp;
    }

    public static void setDebug(boolean enabled) {
        sDebug = enabled;
    }

    public static boolean isDebug() {
        return sDebug;
    }

    public static void d(@NonNull String msg) {
        if (!sDebug) {
            return;
        }
        emit(Log.DEBUG, 'D', msg);
    }

    public static void i(@NonNull String msg) {
        emit(Log.INFO, 'I', msg);
    }

    public static void w(@NonNull String msg) {
        emit(Log.WARN, 'W', msg);
    }

    public static void w(@NonNull String msg, @NonNull Throwable t) {
        emit(Log.WARN, 'W', msg + ": " + t);
    }

    public static void e(@NonNull String msg, @NonNull Throwable t) {
        emit(Log.ERROR, 'E', msg + ": " + t);
    }

    private static void emit(int priority, char level, @NonNull String msg) {
        try {
            Log.println(priority, TAG, msg);
        } catch (Throwable ignored) {
            // Logging must never take the host process down.
        }
        remember(level, msg);
        XposedInterface xp = sXp;
        if (xp != null) {
            try {
                xp.log(priority, TAG, msg);
            } catch (Throwable ignored) {
                // Framework log is best effort.
            }
        }
    }

    private static void remember(char level, @NonNull String msg) {
        synchronized (RING) {
            RING.addLast(level + " " + time() + " " + msg);
            while (RING.size() > RING_MAX) {
                RING.removeFirst();
            }
        }
    }

    /** Last {@code maxLines} buffered lines, oldest first, newline separated. */
    @NonNull
    public static String tail(int maxLines) {
        StringBuilder sb = new StringBuilder();
        synchronized (RING) {
            int skip = Math.max(0, RING.size() - Math.max(1, maxLines));
            int i = 0;
            for (String line : RING) {
                if (i++ < skip) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** Drops the buffer (used after the diagnostics handshake was served). */
    public static void clearRing() {
        synchronized (RING) {
            RING.clear();
        }
    }

    @NonNull
    private static String time() {
        long now = android.os.SystemClock.elapsedRealtime();
        long totalSeconds = now / 1000L;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d",
                totalSeconds / 3600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L,
                now % 1000L);
    }
}
