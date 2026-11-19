package cn.qxfly.aod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;

/**
 * Dual-channel log facade.
 *
 * <p>This class must NOT extend or reference any libxposed type in its own
 * class hierarchy: it is loaded in the UI app process too, where libxposed
 * classes do not exist. Logging through AodLyricModule's statics from the app
 * process triggered NoClassDefFoundError (the module class's superclass
 * XposedModule is absent outside hook processes) - which crashed the app and
 * silently killed SuperLyric registration.
 *
 * <p>The XposedInterface delegate is attached only in hook processes.
 */
public final class AodLog {

    public static final String TAG = "AodLyric";

    @Nullable
    private static volatile XposedInterface sXp;

    private AodLog() {
    }

    /** Called from AodLyricModule's constructor (hook processes only). */
    public static void attachXposed(@Nullable XposedInterface xp) {
        sXp = xp;
    }

    public static void d(@NonNull String msg) {
        Log.d(TAG, msg);
        XposedInterface xp = sXp;
        if (xp != null) {
            xp.log(Log.DEBUG, TAG, msg);
        }
    }

    public static void i(@NonNull String msg) {
        Log.i(TAG, msg);
        XposedInterface xp = sXp;
        if (xp != null) {
            xp.log(Log.INFO, TAG, msg);
        }
    }

    public static void w(@NonNull String msg) {
        Log.w(TAG, msg);
        XposedInterface xp = sXp;
        if (xp != null) {
            xp.log(Log.WARN, TAG, msg);
        }
    }

    public static void w(@NonNull String msg, @NonNull Throwable t) {
        Log.w(TAG, msg, t);
        XposedInterface xp = sXp;
        if (xp != null) {
            xp.log(Log.WARN, TAG, msg + ": " + t);
        }
    }

    public static void e(@NonNull String msg, @NonNull Throwable t) {
        Log.e(TAG, msg, t);
        XposedInterface xp = sXp;
        if (xp != null) {
            xp.log(Log.ERROR, TAG, msg + ": " + t);
        }
    }
}
