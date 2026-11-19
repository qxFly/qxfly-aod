package cn.qxfly.aod;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Method;

/**
 * Opens the hidden-API gate so android.os.ServiceManager (used by
 * SuperLyricApi's BinderHub) is reachable from a non-system app process.
 *
 * <p>Two strategies, tried in order:
 * <ol>
 *   <li>LSPosed HiddenApiBypass, invoked REFLECTIVELY so the library is not
 *       required inside the module dex (bundling it broke module loading on
 *       some LSPosed builds; see BISECT.md). If present, use it.</li>
 *   <li>Meta-reflection fallback: resolve getDeclaredMethod through
 *       java.lang.Class. Two arguments exactly - receiver separate.</li>
 * </ol>
 */
public final class HiddenApi {

    private static final String TAG = AodLyricModule.TAG;
    private static volatile boolean sDone;

    private HiddenApi() {
    }

    /** Idempotent. Failures are logged with the failing strategy named. */
    public static void exempt() {
        if (sDone) {
            return;
        }
        synchronized (HiddenApi.class) {
            if (sDone) {
                return;
            }
            if (tryHiddenApiBypassReflective()) {
                sDone = true;
                return;
            }
            if (tryMetaReflection()) {
                sDone = true;
                return;
            }
            Log.w(TAG, "hidden api bypass unavailable: both strategies failed");
        }
    }

    private static boolean tryHiddenApiBypassReflective() {
        try {
            Class<?> bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            Method add = bypass.getDeclaredMethod(
                    "addHiddenApiExemptions", String[].class);
            Object result = add.invoke(null, new Object[]{new String[]{"L"}});
            Log.i(TAG, "HiddenApiBypass.addHiddenApiExemptions(L) -> " + result);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass (reflective) unavailable: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean tryMetaReflection() {
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "setHiddenApiExemptions", new Class[]{String[].class});
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntime, "getRuntime", new Class[0]);
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            Log.i(TAG, "meta-reflection exemptions applied");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "meta-reflection failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
