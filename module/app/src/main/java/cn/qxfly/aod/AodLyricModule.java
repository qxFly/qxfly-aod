package cn.qxfly.aod;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 101/102 entry (targetApiVersion=101 so frameworks that only
 * implement 101 still load the module; the only 102-only call,
 * HookBuilder.setId, is guarded by getApiVersion()).
 *
 * <p>All hooking happens in onPackageReady. Logging goes to BOTH channels:
 * android.util.Log (adb logcat) and the framework log (LSPosed manager).
 */
public final class AodLyricModule extends XposedModule {

    public static final String TAG = "AodLyric";
    public static final String TARGET_PACKAGE_AOD = "com.miui.aod";

    /**
     * The AOD doze renderer on MIUI/HyperOS is a plugin loaded by SystemUI
     * (DozeServicePluginImpl in com.miui.aod is the plugin INTERFACE; the host
     * that loads it is com.android.systemui). The MAML MusicControl element
     * that renders lyrics therefore lives in SystemUI's process, so both
     * packages must be hooked. Evidence: the original module's MainHook had
     * two package branches - one for the AOD package, one for SystemUI.
     */
    public static final String TARGET_PACKAGE_SYSTEMUI = "com.android.systemui";

    @Nullable
    private static volatile XposedInterface sXp;

    @Nullable
    private static volatile LyricBridge sBridge;

    private static volatile boolean sContextHookInstalled;

    public AodLyricModule() {
        super();
        sXp = this;
        // Attach the process-safe log facade: app-process code calls AodLog
        // (which must not reference libxposed), hook processes get the extra
        // framework-log channel through this delegate.
        AodLog.attachXposed(this);
    }

    // ── log facade (delegates to AodLog) ─────────────────────────────

    public static void d(@NonNull String msg) {
        AodLog.d(msg);
    }

    public static void i(@NonNull String msg) {
        AodLog.i(msg);
    }

    public static void w(@NonNull String msg) {
        AodLog.w(msg);
    }

    public static void e(@NonNull String msg, @NonNull Throwable t) {
        AodLog.e(msg, t);
    }

    // ── hook builder helper ──────────────────────────────────────────

    /**
     * Opens a hook builder with an id attached when the framework supports
     * API 102. setId is 102-only; on a 101 framework it would resolve to an
     * unimplemented abstract method and throw NoSuchMethodError.
     */
    @NonNull
    public static XposedInterface.HookBuilder buildHook(@NonNull XposedInterface xp,
                                                        @NonNull java.lang.reflect.Executable target,
                                                        @NonNull String id) {
        XposedInterface.HookBuilder builder = xp.hook(target)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
        if (xp.getApiVersion() >= XposedInterface.API_102) {
            try {
                builder.setId(id);
            } catch (Throwable ignored) {
                // Framework claims 102 but does not implement setId.
            }
        }
        return builder;
    }

    // ── lifecycle ────────────────────────────────────────────────────

    /**
     * Build identity of THIS dex. Printed at every load so the user can tell
     * which build is actually injected into a host process: without it, testing
     * after an update is guesswork (a host that was never restarted keeps
     * running the previous build and produces no new logs at all).
     */
    public static final String BUILD = "7.4.0/46";

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        i("===== AodLyric BUILD " + BUILD + " loaded into " + param.getProcessName()
                + " (api=" + getApiVersion() + " framework=" + getFrameworkName()
                + " " + getFrameworkVersion() + ") =====");
    }

    @Override
    public void onPackageLoaded(@NonNull XposedModuleInterface.PackageLoadedParam param) {
        i("onPackageLoaded: pkg=" + param.getPackageName());
        if (!TARGET_PACKAGE_AOD.equals(param.getPackageName())
                && !TARGET_PACKAGE_SYSTEMUI.equals(param.getPackageName())) {
            return;
        }
        i("package loaded: " + param.getPackageName()
                + " firstPackage=" + param.isFirstPackage());
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        i("onPackageReady: pkg=" + param.getPackageName()
                + " (targets=" + TARGET_PACKAGE_AOD + ", " + TARGET_PACKAGE_SYSTEMUI + ")");
        if (!TARGET_PACKAGE_AOD.equals(param.getPackageName())
                && !TARGET_PACKAGE_SYSTEMUI.equals(param.getPackageName())) {
            return;
        }
        i("package ready: " + param.getPackageName()
                + " firstPackage=" + param.isFirstPackage()
                + " BUILD=" + BUILD
                + " loader=" + param.getClassLoader().getClass().getName());

        long props = getFrameworkProperties();
        i("frameworkProperties=" + props
                + " hiddenApiRestricted="
                + ((props & XposedInterface.PROP_RT_API_PROTECTION) != 0));

        ModuleReporter reporter = new ModuleReporter(this);
        installContextCapture(reporter);
        reporter.reportModule(
                String.valueOf(getApiVersion()),
                getFrameworkName() + " " + getFrameworkVersion());

        try {
            LyricBridge bridge = new LyricBridge(this, param.getClassLoader());
            sBridge = bridge;
            bridge.install();
            i("lyric bridge installed");
        } catch (Throwable t) {
            reporter.reportStatus(false, "install failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage(), null);
            e("install failed", t);
        }
    }

    private void installContextCapture(@NonNull final ModuleReporter reporter) {
        if (sContextHookInstalled) {
            return;
        }
        sContextHookInstalled = true;

        try {
            java.lang.reflect.Method m = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            buildHook(this, m, "aodlyric-app-create")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object app = chain.getArg(0);
                        if (app instanceof Context) {
                            reporter.onHostContext((Context) app);
                        }
                        return result;
                    });
            i("context anchor: Instrumentation.callApplicationOnCreate");
        } catch (Throwable t) {
            w("Instrumentation anchor unavailable: " + t);
        }

        try {
            java.lang.reflect.Method m = Application.class.getDeclaredMethod(
                    "attach", Context.class);
            m.setAccessible(true);
            buildHook(this, m, "aodlyric-app-attach")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object arg = chain.getArg(0);
                        if (arg instanceof Context) {
                            reporter.onHostContext((Context) arg);
                        }
                        return result;
                    });
            i("context anchor: Application.attach");
        } catch (Throwable t) {
            w("Application.attach anchor unavailable: " + t);
        }
    }

    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        param.setSavedInstanceState(LyricBridge.snapshot());
        i("hot reloading");
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        LyricBridge.restore(param.getSavedInstanceState());
        i("hot reloaded, oldHandles=" + param.getOldHookHandles().size());
    }

    @Nullable
    public static LyricBridge bridge() {
        return sBridge;
    }
}
