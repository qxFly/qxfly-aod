package cn.qxfly.aod;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;

/**
 * Best-effort bridge from the hook process to the module UI. Reports are
 * deferred until the host Application exists, then flushed.
 */
public final class ModuleReporter {

    private static final long MIN_LYRIC_GAP_MS = 200L;

    @NonNull
    private final XposedInterface mXp;

    @Nullable
    private static volatile Context sHostContext;

    @Nullable
    private static volatile Bundle sPendingModule;

    @Nullable
    private static volatile Bundle sPendingStatus;

    private volatile long mLastLyricPush;
    private volatile boolean mStatusPushed;

    public ModuleReporter(@NonNull XposedInterface xp) {
        mXp = xp;
    }

    /** The host context captured by the Application-creation anchor, if any. */
    @Nullable
    public static Context capturedContext() {
        return sHostContext;
    }

    /** Called when the host Application is constructed. Flushes deferred reports. */
    public static void onHostContext(@NonNull Context context) {
        sHostContext = context;
        AodLog.i("host context captured: " + context.getPackageName());
        flushPending();
    }

    @Nullable
    private static Context context() {
        Context captured = sHostContext;
        if (captured != null) {
            return captured;
        }
        return currentApplication();
    }

    private static void flushPending() {
        Context context = context();
        if (context == null) {
            return;
        }
        Bundle module = sPendingModule;
        if (module != null && push(context, module, "module(flushed)")) {
            sPendingModule = null;
        }
        Bundle status = sPendingStatus;
        if (status != null && push(context, status, "status(flushed)")) {
            sPendingStatus = null;
        }
    }

    /**
     * Retry hook for the deferred reports.
     *
     * <p>The Application-creation anchors do not fire in every host process
     * (HyperOS 3 / Android 16 SystemUI never called back), which left the
     * module/status reports pending forever while lyric reports went through
     * via {@code currentApplication()}. The lyric poll thread calls this once
     * a second, so the UI reflects "activated" as soon as a context exists.
     */
    public static void flushPendingIfReady() {
        if (sPendingModule == null && sPendingStatus == null) {
            return;
        }
        flushPending();
    }

    public void reportModule(String api, String framework) {
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_MODULE);
        b.putString(StateKeys.MODULE_API, api);
        b.putString(StateKeys.MODULE_FRAMEWORK, framework);
        if (context() == null) {
            sPendingModule = b;
            AodLog.i("report module deferred: host application not created yet");
            return;
        }
        push(context(), b, "module");
    }

    public void reportStatus(boolean hooksInstalled, String hookDetail, String registerResult) {
        if (mStatusPushed && !hooksInstalled) {
            return;
        }
        mStatusPushed = true;
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_STATUS);
        b.putBoolean(StateKeys.HOOKS_INSTALLED, hooksInstalled);
        b.putString(StateKeys.HOOK_DETAIL, hookDetail);
        b.putString(StateKeys.REGISTER_RESULT, registerResult);
        if (context() == null) {
            sPendingStatus = b;
            AodLog.i("report status deferred: host application not created yet");
            return;
        }
        push(context(), b, "status");
    }

    public void reportLyric(@Nullable String track, @Nullable String line,
                            @Nullable String index, @Nullable String total,
                            @Nullable String chars) {
        reportLyric(track, line, null, index, total, chars);
    }

    public void reportLyric(@Nullable String track, @Nullable String line,
                            @Nullable String translation, @Nullable String index,
                            @Nullable String total, @Nullable String chars) {
        long now = System.currentTimeMillis();
        if (now - mLastLyricPush < MIN_LYRIC_GAP_MS) {
            return;
        }
        Context context = context();
        if (context == null) {
            return;
        }
        flushPendingIfReady();
        mLastLyricPush = now;
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        b.putString(StateKeys.LYRIC_TRACK, track);
        b.putString(StateKeys.LYRIC_LINE, line);
        b.putString(StateKeys.LYRIC_TRANSLATION, translation);
        b.putString(StateKeys.LYRIC_INDEX, index);
        b.putString(StateKeys.LYRIC_TOTAL, total);
        b.putString(StateKeys.LYRIC_CHARS, chars);
        push(context, b, "lyric");
    }

    /**
     * Playback stopped: clears the whole lyric record in the provider (line,
     * translation AND the full LRC) so no ghost lyrics survive a stop for the
     * overlay or the native theme injection to pick up.
     */
    public void reportLyricStop() {
        Context context = context();
        if (context == null) {
            return;
        }
        flushPendingIfReady();
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        b.putString(StateKeys.LYRIC_LINE, "");
        b.putString(StateKeys.LYRIC_TRANSLATION, "");
        b.putString(StateKeys.LYRIC_LRC, "");
        b.putString(StateKeys.LYRIC_INDEX, "-1");
        b.putString(StateKeys.LYRIC_TOTAL, "0");
        b.putString(StateKeys.LYRIC_CHARS, "0");
        push(context, b, "lyric stop");
    }

    /**
     * Hook -> app: the enumerated AOD components (id|class|bounds lines) that
     * can serve as anchor targets for lyric placement.
     */
    public void reportComponents(@Nullable java.util.List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        Context context = context();
        if (context == null) {
            AodLog.w("report components dropped: no host context");
            return;
        }
        flushPendingIfReady();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < 60; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_COMPONENTS);
        b.putString(StateKeys.COMPONENTS_LIST, sb.toString());
        b.putString(StateKeys.COMPONENTS_AT, String.valueOf(System.currentTimeMillis()));
        push(context, b, "components");
    }

    private static boolean push(@NonNull Context context, @NonNull Bundle payload,
                                @NonNull String what) {
        try {
            context.getContentResolver().call(
                    StateKeys.stateUri(), StateKeys.METHOD_UPDATE, null, payload);
            AodLog.i("report " + what + " -> provider ok");
            return true;
        } catch (Throwable t) {
            AodLog.w("report " + what + " -> provider FAILED: "
                    + t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    @Nullable
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getDeclaredMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

