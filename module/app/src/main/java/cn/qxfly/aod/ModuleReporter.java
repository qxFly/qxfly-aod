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
    private volatile long mLastPlaybackPush;
    private volatile boolean mStatusPushed;

    /** Minimum gap between two playback-metadata pushes (position ticks). */
    private static final long PLAYBACK_PUSH_GAP_MS = 900L;

    /** Minimum gap between deferred-report retries (see flushPendingIfReady). */
    private static final long FLUSH_RETRY_INTERVAL_MS = 5000L;

    private static volatile long sLastFlushAttemptMs;

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
        // Throttle: if the provider stays unreachable, one warning every few
        // seconds is enough and keeps the host process log readable.
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastFlushAttemptMs < FLUSH_RETRY_INTERVAL_MS) {
            return;
        }
        sLastFlushAttemptMs = now;
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

    /**
     * Answers a diagnostics request from the UI: pushes this process's log tail
     * and build identity so the app can show what happened inside SystemUI /
     * com.miui.aod without logcat access.
     *
     * <p>Also refreshes the status report, so a stale "hooksInstalled" is
     * corrected at the same time.
     */
    public void reportDiagnostics(boolean hooksInstalled, @Nullable String hookDetail) {
        Context context = context();
        if (context == null) {
            return;
        }
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_DIAG);
        b.putString(StateKeys.LOG_TAIL, AodLog.tail(30));
        b.putString(StateKeys.LOG_BUILD, AodLyricModule.BUILD);
        b.putString(StateKeys.LOG_PROCESS, processName());
        push(context, b, "diag");
        if (hookDetail != null) {
            reportStatus(hooksInstalled, hookDetail, null);
        }
    }

    @NonNull
    private static String processName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object name = at.getDeclaredMethod("currentProcessName").invoke(null);
            return name == null ? "?" : String.valueOf(name);
        } catch (Throwable t) {
            return "?";
        }
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
     * Publishes playback metadata (title/artist/next line/duration/position)
     * without touching the live line.
     *
     * <p>Used by the legacy (3.x) receiver: the v4 stream that {@link AppReporter}
     * relies on never fires against an old service, so without this the
     * provider's title/next/progress keys stayed unset and the overlay's
     * optional extra lines had nothing to draw.
     */
    public void reportPlayback(@Nullable String title, @Nullable String artist,
                               @Nullable String next, boolean hasDuration,
                               long durationMs, boolean hasPosition, long positionMs) {
        reportPlayback(title, artist, next, hasDuration, durationMs,
                hasPosition, positionMs, null);
    }

    /**
     * Same, with the play state ("playing"/"paused"/...). The legacy payload
     * carries no state, so the platform media session supplies it.
     */
    public void reportPlayback(@Nullable String title, @Nullable String artist,
                               @Nullable String next, boolean hasDuration,
                               long durationMs, boolean hasPosition, long positionMs,
                               @Nullable String playState) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - mLastPlaybackPush < PLAYBACK_PUSH_GAP_MS) {
            return;
        }
        Context context = context();
        if (context == null) {
            return;
        }
        boolean hasAny = (title != null && !title.isEmpty())
                || (artist != null && !artist.isEmpty())
                || (next != null && !next.isEmpty())
                || (hasDuration && durationMs > 0)
                || (hasPosition && positionMs >= 0)
                || (playState != null && !playState.isEmpty());
        if (!hasAny) {
            return;
        }
        flushPendingIfReady();
        mLastPlaybackPush = now;
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_LYRIC);
        if (title != null && !title.isEmpty()) {
            b.putString(StateKeys.LYRIC_TITLE, title);
        }
        if (artist != null && !artist.isEmpty()) {
            b.putString(StateKeys.LYRIC_ARTIST, artist);
        }
        if (next != null && !next.isEmpty()) {
            b.putString(StateKeys.LYRIC_NEXT, next);
        }
        if (hasDuration && durationMs > 0) {
            b.putString(StateKeys.LYRIC_DURATION, String.valueOf(durationMs));
        }
        if (hasPosition && positionMs >= 0) {
            b.putString(StateKeys.LYRIC_POSITION, String.valueOf(positionMs));
            b.putString(StateKeys.LYRIC_POS_AT, String.valueOf(now));
        }
        if (playState != null && !playState.isEmpty()) {
            b.putString(StateKeys.LYRIC_PLAY_STATE, playState);
        }
        push(context, b, "playback");
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
        b.putString(StateKeys.LYRIC_NEXT, "");
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
            AodLog.d("report " + what + " -> provider ok");
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

