package cn.qxfly.aod;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

/**
 * Restarts the LSPosed scope apps on request from the module UI.
 *
 * <p>LSPosed injects the module when a process starts, so an updated APK only
 * takes effect after its host process is killed. A normal app cannot stop other
 * packages, but the hook runs inside SystemUI (uid 1000), which holds
 * {@code android.permission.FORCE_STOP_PACKAGES} — so the request travels
 * UI → state provider → hook (see {@link StateKeys#TYPE_RESTART}).
 *
 * <p>Requests are de-duplicated by their millisecond stamp: both hooked
 * processes poll the same snapshot, and a process that already handled a stamp
 * must not handle it again after it restarts.
 */
public final class ScopeRestarter {

    /** Delay before this process kills itself, so the others are stopped first. */
    private static final long SELF_KILL_DELAY_MS = 500L;

    /** Highest request stamp handled by this process (0 = none yet). */
    private static volatile long sLastAt;

    private ScopeRestarter() {
    }

    /**
     * Handles a pending restart request, if any.
     *
     * <p>Called from {@link ProviderLrcSource#refresh()} with the snapshot it
     * just fetched: that runs on every poll tick (even when nothing else
     * changed), so no extra binder call is needed.
     */
    static void maybeRestart(@NonNull Context context, @NonNull Bundle snap) {
        long at = snap.getLong(StateKeys.RESTART_AT, 0L);
        long done = snap.getLong(StateKeys.RESTART_DONE_AT, 0L);
        if (at <= 0L || at <= done || at <= sLastAt) {
            return;
        }
        sLastAt = at;
        String raw = snap.getString(StateKeys.RESTART_PACKAGES);
        String[] packages = raw == null ? new String[0] : raw.split(",");
        boolean self = false;
        int stopped = 0;
        for (String pkg : packages) {
            String name = pkg == null ? "" : pkg.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.equals(context.getPackageName())) {
                // Kill self LAST: this process has to finish stopping the others.
                self = true;
                continue;
            }
            if (forceStop(context, name)) {
                stopped++;
            }
        }
        AodLog.i("restart request: stopped " + stopped + " package(s)"
                + (self ? ", self will restart too" : ""));
        // Acknowledge BEFORE dying: the respawned process has no memory of
        // sLastAt and would otherwise execute the same request again.
        ack(context, at);
        if (self) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> killSelf(context),
                    SELF_KILL_DELAY_MS);
        }
    }

    /** Marks request {@code at} as executed so no process replays it. */
    private static void ack(@NonNull Context context, long at) {
        Bundle b = new Bundle();
        b.putString(StateKeys.TYPE, StateKeys.TYPE_RESTART);
        b.putLong(StateKeys.RESTART_AT, 0L);
        b.putLong(StateKeys.RESTART_DONE_AT, at);
        try {
            context.getContentResolver().call(StateKeys.stateUri(),
                    StateKeys.METHOD_UPDATE, null, b);
        } catch (Throwable t) {
            AodLog.w("restart: ack failed: " + t);
        }
    }

    /** Stops one package. Returns true when the platform accepted the call. */
    private static boolean forceStop(@NonNull Context context, @NonNull String pkg) {
        ActivityManager am = (ActivityManager) context.getSystemService(
                Context.ACTIVITY_SERVICE);
        if (am != null) {
            try {
                Method forceStop = ActivityManager.class.getDeclaredMethod(
                        "forceStopPackage", String.class);
                forceStop.setAccessible(true);
                forceStop.invoke(am, pkg);
                AodLog.i("restart: force-stopped " + pkg);
                return true;
            } catch (Throwable t) {
                AodLog.w("restart: forceStopPackage(" + pkg + ") failed: " + t);
            }
        }
        // Fallback: `am` inherits our uid (system), which also holds the permission.
        try {
            Runtime.getRuntime().exec(new String[]{"am", "force-stop", pkg});
            AodLog.i("restart: am force-stop " + pkg);
            return true;
        } catch (Throwable t) {
            AodLog.w("restart: am force-stop(" + pkg + ") failed: " + t);
            return false;
        }
    }

    /**
     * Ends this process. Persistent hosts (SystemUI) are respawned by the
     * system, which is exactly what "reload the module" means.
     */
    private static void killSelf(@NonNull Context context) {
        AodLog.i("restart: killing host process " + context.getPackageName());
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
            // Nothing else to try; the process is dying anyway.
        }
    }
}
