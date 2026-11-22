package cn.qxfly.aod;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-side hook installer (historically "theme probe").
 *
 * <p>The AOD renderer loads the MIUI doze plugin and the MAML theme engine from
 * dedicated PLUGIN classloaders, so classes are tracked through a process-wide
 * {@code ClassLoader#loadClass(String, boolean)} watcher instead of being
 * resolved from the app's PathClassLoader. Each interesting class is then
 * hooked in the loader that actually produced it:
 *
 * <ul>
 *   <li>{@code DozeServicePluginImpl} / {@code DozeService}: overlay lifecycle;</li>
 *   <li>{@code DozeMachine}: fingerprint crash guard + pulse-aware double-tap
 *       wake retry;</li>
 *   <li>{@code CommonUtils}: the double-tap wake entry point;</li>
 *   <li>{@code ScreenElementRoot}: registers the MAML roots whose framerate the
 *       overlay must drive so the AOD panel keeps recompositing.</li>
 * </ul>
 */
public final class ThemeProbeV2 {

    public static final String TARGET_ROOT = "com.miui.maml.ScreenElementRoot";
    public static final String TARGET_DOZE_PLUGIN =
            "com.miui.aod.doze.DozeServicePluginImpl";
    /** Doze service: its lifecycle hooks bring the overlay up and down. */
    public static final String TARGET_DOZE_SERVICE = "com.miui.aod.doze.DozeService";
    /** Crash guard target: MIUI's doze state machine (see installDozeMachineGuard). */
    public static final String TARGET_DOZE_MACHINE = "com.miui.aod.doze.DozeMachine";
    /** Double-tap wake target: its requestWakeUp is the ONLY caller of the
     *  double-tap path (DozeHost.onDoubleTap -> CommonUtils.requestWakeUp). */
    public static final String TARGET_COMMON_UTILS = "com.miui.aod.utils.CommonUtils";

    /**
     * Renamed-copy fallbacks. Different MIUI/HyperOS builds move the AOD plugin
     * between packages (com.miui.aod / com.xiaomi.aod, or a vendor copy) while
     * keeping the same class names, so matching by the tail keeps those devices
     * working without touching the canonical fast path.
     */
    private static final String TAIL_DOZE_PLUGIN = ".doze.DozeServicePluginImpl";
    private static final String TAIL_DOZE_SERVICE = ".doze.DozeService";
    private static final String TAIL_DOZE_MACHINE = ".doze.DozeMachine";
    private static final String TAIL_COMMON_UTILS = ".utils.CommonUtils";
    private static final String TAIL_MAML_ROOT = ".maml.ScreenElementRoot";

    /** Alternate packages probed eagerly when the canonical one is absent. */
    private static final String[] ALT_AOD_PACKAGES = {
            "com.xiaomi.aod", "com.miui.aod",
    };

    private static boolean isAodFamily(@NonNull String name) {
        return name.startsWith("com.miui.aod.") || name.startsWith("com.xiaomi.aod.");
    }

    private static boolean matchesTarget(@NonNull String name, @NonNull String canonical,
                                         @NonNull String tail) {
        if (canonical.equals(name)) {
            return true;
        }
        // MAML lives outside the AOD package family; the others must stay
        // inside it so an unrelated CommonUtils cannot be hooked.
        if (TAIL_MAML_ROOT.equals(tail)) {
            return name.endsWith(tail);
        }
        return isAodFamily(name) && name.endsWith(tail);
    }

    private static final AtomicBoolean sRootHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sPluginHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sMachineHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sWakeHooked = new AtomicBoolean(false);

    // ---- doze machine state + double-tap wake retry -----------------------
    // Lyric refresh pulses every 4s keep the machine cycling; a double-tap
    // wakeUp issued during a pulse is a no-op and the screen drops back to
    // doze. Track the state and re-issue once the machine is idle.
    private static volatile String sMachineState = "";
    private static volatile long sMachineStateAt;
    /** Context captured from requestWakeUp - also used to re-issue the wake. */
    private static volatile Context sWakeContext;
    private static final Object sWakeGate = new Object();
    /** elapsedRealtime of a swallowed double-tap wake awaiting re-issue, 0 = none. */
    private static long sPendingWakeAt;
    /** Delayed re-issue armed by the requestWakeUp hook, cancellable. */
    @Nullable
    private static Runnable sWakeRetry;
    private static volatile android.os.Handler sMainHandler;

    private final XposedInterface mXp;
    @Nullable
    private final AodOverlayController mOverlay;
    @Nullable
    private final ClassLoader mHostLoader;

    /** Process instance for static entries such as {@link #doubleTapWake}. */
    @Nullable
    private static volatile ThemeProbeV2 sSelf;

    public ThemeProbeV2(@NonNull XposedInterface xp,
                        @Nullable ProviderLrcSource providerLrc,
                        @Nullable ClassLoader hostLoader) {
        mXp = xp;
        mOverlay = new AodOverlayController(xp, providerLrc);
        mHostLoader = hostLoader;
        sSelf = this;
    }

    /**
     * Module-side double-tap wake, called by the overlay's own touch detector
     * (see AodOverlayController.watchDoubleTap).
     *
     * <p>MIUI's gate in DozeHost.onTouch
     * ({@code mSupportGestureWakeup && mOpenDoubleTapGoToSleep &&
     * needKeepScreenOnAtFirst()}) stays closed for non-super-wallpaper AOD
     * styles - measured on-device: taps reach the AOD window while
     * CommonUtils.requestWakeUp is never called and PowerManager never logs a
     * NODOZE wake. So this bypasses the gate and reuses the pulse-aware retry
     * machinery: if the wake is swallowed by an in-flight lyric-refresh pulse
     * it is re-issued the moment the doze machine is idle again.
     */
    public static void doubleTapWake(@Nullable Context ctx) {
        ThemeProbeV2 self = sSelf;
        if (self == null) {
            return;
        }
        if (ctx != null) {
            sWakeContext = ctx;
        }
        String st = sMachineState;
        AodLog.w("module double-tap wake request (doze state "
                + (st.isEmpty() ? "unknown" : st) + ")");
        boolean pulseCycle = "DOZE_REQUEST_PULSE".equals(st)
                || "DOZE_PULSING".equals(st)
                || "DOZE_PULSE_DONE".equals(st);
        // Fire IMMEDIATELY, even during a pulse: PowerManager.wakeUp forces
        // the device to AWAKE and terminates the doze pulse, so waiting for
        // the pulse to end first only adds seconds of latency (measured:
        // 9s late, which reads as "double-tap does nothing"). The pulse cycle
        // additionally arms the retry as a safety net in case this particular
        // state really does swallow the request.
        self.wakeNow("module double-tap");
        if (pulseCycle) {
            self.armWakeRetry();
        } else {
            self.cancelWakeRetry();
        }
    }

    /** Issues PowerManager.wakeUp on the next main-loop turn. */
    private void wakeNow(@NonNull String reason) {
        if (sWakeContext == null) {
            AodLog.w("wake (" + reason + ") skipped: no context yet");
            return;
        }
        final String why = reason;
        mainHandler().post(() -> {
            Context ctx = sWakeContext;
            if (ctx == null) {
                return;
            }
            try {
                android.os.PowerManager pm =
                        (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm.isInteractive()) {
                    // Already awake: a late retry must not re-light the screen
                    // after the user has deliberately turned it off again.
                    AodLog.i("wake (" + why + ") skipped: already interactive");
                    return;
                }
                long now = android.os.SystemClock.uptimeMillis();
                // wakeUp(...) is hidden API: not in the public SDK stub, but
                // present on the device. Prefer the 2-arg form (its reason
                // shows up in PowerManager logs), fall back to 1-arg.
                java.lang.reflect.Method wake2 = null;
                try {
                    wake2 = pm.getClass().getMethod("wakeUp", long.class, String.class);
                } catch (NoSuchMethodException e) {
                    wake2 = null;
                }
                if (wake2 != null) {
                    wake2.invoke(pm, now, "com.android.systemui:NODOZE");
                } else {
                    pm.getClass().getMethod("wakeUp", long.class).invoke(pm, now);
                }
                AodLog.w("woke screen (" + why + ")");
            } catch (Throwable t) {
                AodLog.w("wake (" + why + ") failed: " + t);
            }
        });
    }

    /** Installs the process-wide loadClass watcher and probes already-loaded classes. */
    public void install() {
        try {
            java.lang.reflect.Method loadClass = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            loadClass.setAccessible(true);
            AodLyricModule.buildHook(mXp, loadClass, "aodlyric-loadclass-watch")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        watchLoaded(result);
                        return result;
                    });
            AodLog.i("theme probe v2: loadClass watcher installed");
        } catch (Throwable t) {
            AodLog.w("loadClass watcher unavailable: " + t);
        }

        // The doze plugin is often loaded BEFORE this hook is installed (it
        // comes up with SystemUI's doze service), so the loadClass watcher would
        // never see it. Probe for it directly and hook on sight.
        probeAlreadyLoaded(TARGET_DOZE_PLUGIN);
        probeAlreadyLoaded(TARGET_DOZE_SERVICE);
        probeAlreadyLoaded(TARGET_DOZE_MACHINE);
        probeAlreadyLoaded(TARGET_COMMON_UTILS);
        probeAlreadyLoaded(TARGET_ROOT);

        // The display-state lever. MIUI parks the AOD panel in DOZE_SUSPEND,
        // where the compositor stops sending frames: text written into the
        // window buffer never reaches the physical screen (screencap still
        // reads the buffer, which is why it looked like it worked). While a
        // lyric is live we remap that state to DOZE - exactly what MIUI's own
        // DozeSuspendScreenStatePreventingAdapter does on devices without
        // suspend support - so the panel keeps compositing our updates.
        hookDreamDozeState();
    }

    /**
     * Resolves a target class through the host loader without initializing it.
     * Success means the class is already defined in this process, so its
     * loadClass moment has passed and we must hook it right now.
     */
    private void probeAlreadyLoaded(@NonNull String className) {
        if (probeLoadedOnAnyLoader(className)) {
            return;
        }
        // Renamed vendor packages (com.xiaomi.aod.* etc.): try the same class
        // path under each alternate AOD package.
        if (className.startsWith("com.miui.aod.")) {
            String rest = className.substring("com.miui.aod.".length());
            for (String pkg : ALT_AOD_PACKAGES) {
                String alt = pkg + "." + rest;
                if (!alt.equals(className) && probeLoadedOnAnyLoader(alt)) {
                    return;
                }
            }
        }
        AodLog.i("not loaded yet (will catch via watcher): " + className);
    }

    private boolean probeLoadedOnAnyLoader(@NonNull String className) {
        // The AOD plugin classes can live on the host loader OR on the dedicated
        // MIUIAod plugin loader recorded by the watcher - try both.
        for (ClassLoader loader : new ClassLoader[]{mHostLoader,
                AodOverlayController.pluginLoader()}) {
            if (loader == null) {
                continue;
            }
            try {
                Class<?> loaded = Class.forName(className, false, loader);
                AodLog.i("already loaded: " + className + " by "
                        + loader.getClass().getSimpleName());
                watchLoaded(loaded);
                return true;
            } catch (Throwable ignored) {
                // Not on this loader; try the next one.
            }
        }
        return false;
    }

    /** Counts AOD-package classes seen loading, so the log stays bounded. */
    private static final java.util.concurrent.atomic.AtomicInteger sAodSeen =
            new java.util.concurrent.atomic.AtomicInteger();

    private void watchLoaded(@Nullable Object result) {
        if (!(result instanceof Class)) {
            return;
        }
        Class<?> loaded = (Class) result;
        String name = loaded.getName();

        // Visibility: which com.miui.aod.* classes ever enter this process.
        // Bounded so a large plugin cannot flood the log.
        if (name.startsWith("com.miui.aod.")) {
            if (sAodSeen.get() < 40 && sAodSeen.incrementAndGet() <= 40) {
                AodLog.i("AOD class loaded: " + name);
            }
            AodOverlayController.notePluginLoader(loaded.getClassLoader());
        }

        if (matchesTarget(name, TARGET_ROOT, TAIL_MAML_ROOT)
                && sRootHooked.compareAndSet(false, true)) {
            hookAddElement(loaded);
        }
        if (matchesTarget(name, TARGET_DOZE_PLUGIN, TAIL_DOZE_PLUGIN)
                && sPluginHooked.compareAndSet(false, true)) {
            AodLog.i("DozeServicePluginImpl loaded by " + loaded.getClassLoader()
                    + "; hooking AOD lifecycle");
            mOverlay.onPluginImplLoaded(loaded);
        }
        if (matchesTarget(name, TARGET_DOZE_SERVICE, TAIL_DOZE_SERVICE)) {
            AodLog.i("DozeService loaded by " + loaded.getClassLoader());
            mOverlay.onDozeServiceLoaded(loaded);
        }
        if (matchesTarget(name, TARGET_DOZE_MACHINE, TAIL_DOZE_MACHINE)
                && sMachineHooked.compareAndSet(false, true)) {
            AodLog.i("DozeMachine loaded by " + loaded.getClassLoader()
                    + "; installing fingerprint crash guard");
            installDozeMachineGuard(loaded);
        }
        if (matchesTarget(name, TARGET_COMMON_UTILS, TAIL_COMMON_UTILS)
                && sWakeHooked.compareAndSet(false, true)) {
            AodLog.i("CommonUtils loaded by " + loaded.getClassLoader()
                    + "; hooking double-tap wake");
            hookRequestWakeUp(loaded);
        }
    }

    /**
     * Crash guard for the fingerprint sensor.
     *
     * <p>Root cause (crash log 2026-11-14): while lyrics update, the overlay
     * requests a doze pulse roughly every 4s (the only working panel refresh).
     * After a pulse completes, MIUI's DozeMachine can be left parked in
     * DOZE_PULSE_DONE (the native pulse flow returns to DOZE via callbacks the
     * synthetic broadcast does not fully drive). A fingerprint press then asks
     * for DOZE_PULSE_DONE -&gt; DOZE_PULSING, which
     * {@code DozeMachine.validateTransition} rejects with an
     * IllegalStateException and the AOD UI process dies.
     *
     * <p>Two hooks: (1) swallow exactly that rejected transition, (2) let
     * transitionPolicy hand out the pulse from PULSE_DONE so the state actually
     * recovers instead of being stuck.
     */
    private void installDozeMachineGuard(@NonNull Class<?> machine) {
        try {
            java.lang.reflect.Method validate = null;
            for (java.lang.reflect.Method m : machine.getDeclaredMethods()) {
                if ("validateTransition".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    validate = m;
                    break;
                }
            }
            if (validate == null) {
                AodLog.w("DozeMachine.validateTransition not found; crash guard skipped");
                return;
            }
            validate.setAccessible(true);
            AodLyricModule.buildHook(mXp, validate, "aodlyric-doze-machine-guard")
                    .intercept(chain -> {
                        try {
                            return chain.proceed();
                        } catch (Throwable t) {
                            String msg = t.getMessage();
                            if (t instanceof IllegalStateException && msg != null
                                    && msg.contains("DOZE_PULSE_DONE ->")) {
                                AodLog.w("suppressed illegal doze transition (" + msg
                                        + ") - pulse-cooldown state, proceeding");
                                return null;
                            }
                            throw t;
                        }
                    });
            AodLog.i("hooked DozeMachine.validateTransition (fingerprint crash guard)");
        } catch (Throwable t) {
            AodLog.w("doze machine guard failed: " + t);
        }

        // The repulse hook: after a lyric pulse finishes, MIUI is parked in
        // DOZE_PULSE_DONE and the next requestPulse is refused by the policy.
        // Log the tracked state on every transition and re-issue a pending
        // double-tap wake as soon as the machine is back in a doze state.
        try {
            Class<?> stateCls = null;
            for (Class<?> c : machine.getDeclaredClasses()) {
                if ("State".equals(c.getSimpleName())) {
                    stateCls = c;
                    break;
                }
            }
            java.lang.reflect.Method policy = null;
            for (java.lang.reflect.Method m : machine.getDeclaredMethods()) {
                if ("transitionPolicy".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && stateCls != null
                        && stateCls == m.getParameterTypes()[0]) {
                    policy = m;
                    break;
                }
            }
            java.lang.reflect.Field stateField = machine.getDeclaredField("mState");
            stateField.setAccessible(true);
            if (policy != null && stateCls != null) {
                final Object reqPulse = stateCls.getDeclaredField("DOZE_REQUEST_PULSE").get(null);
                final Object pulseDone = stateCls.getDeclaredField("DOZE_PULSE_DONE").get(null);
                final java.lang.reflect.Field curState = stateField;
                policy.setAccessible(true);
                AodLyricModule.buildHook(mXp, policy, "aodlyric-doze-repulse-allow")
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                String stateName = "";
                                boolean stable = false;
                                if (self != null) {
                                    Object cur = curState.get(self);
                                    if (cur instanceof Enum) {
                                        stateName = ((Enum<?>) cur).name();
                                        stable = "DOZE".equals(stateName)
                                                || "DOZE_AOD".equals(stateName);
                                    }
                                    if (!stateName.equals(sMachineState)) {
                                        sMachineState = stateName;
                                        sMachineStateAt =
                                                android.os.SystemClock.elapsedRealtime();
                                        AodLog.i("doze state -> " + stateName);
                                    }
                                }
                                // A wake issued during a pulse is swallowed;
                                // fire the pending one the moment we are stable.
                                if (stable) {
                                    firePendingWake("pulse finished");
                                }
                                // Allow the specific re-pulse MIUI refuses
                                // after our synthetic pulses: same requested
                                // state, policy returned PULSE_DONE, machine
                                // still in PULSE_DONE.
                                if (self != null && chain.getArgs().get(0) == reqPulse
                                        && result == pulseDone
                                        && curState.get(self) == pulseDone) {
                                    AodLog.w("allowed re-pulse from DOZE_PULSE_DONE"
                                            + " (wake gesture was being dropped)");
                                    return reqPulse;
                                }
                            } catch (Throwable ignored) {
                            }
                            return result;
                        });
                AodLog.i("hooked DozeMachine.transitionPolicy (re-pulse from PULSE_DONE)");
            }
        } catch (Throwable t) {
            AodLog.w("doze re-pulse hook failed: " + t);
        }
    }

    /**
     * Hooks {@code CommonUtils.requestWakeUp(Context)} - the exact point where
     * the AOD's double-tap-to-wake fires (DozeHost.onDoubleTap). In the idle
     * AOD state the wakeUp goes through and the screen lights. But when a
     * lyric-refresh pulse is in flight (machine REQUEST_PULSE/PULSING/
     * PULSE_DONE), the display is already lit for the pulse, wakeUp is a
     * no-op, and when the pulse ends the screen drops back to doze - the
     * double-tap is silently swallowed. In that case arm a delayed re-issue
     * that fires once the machine is idle again (the policy hook fast-paths
     * it) or after a fixed timeout as a fallback.
     */
    private void hookRequestWakeUp(@NonNull Class<?> utils) {
        try {
            java.lang.reflect.Method wake = null;
            for (java.lang.reflect.Method m : utils.getDeclaredMethods()) {
                if ("requestWakeUp".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && Context.class == m.getParameterTypes()[0]) {
                    wake = m;
                    break;
                }
            }
            if (wake == null) {
                AodLog.w("CommonUtils.requestWakeUp not found; wake retry unavailable");
                return;
            }
            wake.setAccessible(true);
            AodLyricModule.buildHook(mXp, wake, "aodlyric-doubletap-wake")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object arg = chain.getArgs().get(0);
                            if (arg instanceof Context) {
                                sWakeContext = (Context) arg;
                            }
                            String st = sMachineState;
                            boolean pulseCycle =
                                    "DOZE_REQUEST_PULSE".equals(st)
                                            || "DOZE_PULSING".equals(st)
                                            || "DOZE_PULSE_DONE".equals(st);
                            AodLog.i("double-tap wake request (doze state "
                                    + (st.isEmpty() ? "unknown" : st) + ")");
                            if (pulseCycle) {
                                armWakeRetry();
                            } else {
                                cancelWakeRetry();
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
            AodLog.i("hooked CommonUtils.requestWakeUp (double-tap wake retry)");
        } catch (Throwable t) {
            AodLog.w("requestWakeUp hook failed: " + t);
        }
    }

    private static android.os.Handler mainHandler() {
        android.os.Handler h = sMainHandler;
        if (h == null) {
            h = new android.os.Handler(android.os.Looper.getMainLooper());
            sMainHandler = h;
        }
        return h;
    }

    /** Armed by the wake hook: re-issue the swallowed wake once, later. */
    private void armWakeRetry() {
        synchronized (sWakeGate) {
            if (sPendingWakeAt != 0) {
                return; // already armed
            }
            sPendingWakeAt = android.os.SystemClock.elapsedRealtime();
        }
        AodLog.w("double-tap wake swallowed by in-flight doze pulse; retry armed");
        Runnable retry = new Runnable() {
            @Override
            public void run() {
                // A wakeUp issued while REQUEST_PULSE/PULSING is in flight is
                // swallowed AGAIN (display still lit for the pulse). Instead of
                // firing blindly on a fixed timer - which can land mid-pulse
                // and consume the pending wake for nothing - poll the tracked
                // machine state and fire at the first idle moment (the policy
                // hook fast-paths this too; this is the fallback).
                String st = sMachineState;
                boolean pulseInFlight = "DOZE_REQUEST_PULSE".equals(st)
                        || "DOZE_PULSING".equals(st);
                boolean pending;
                synchronized (sWakeGate) {
                    pending = sPendingWakeAt != 0;
                }
                if (pending && pulseInFlight
                        && android.os.SystemClock.elapsedRealtime() - sPendingWakeAt
                                < 20000L) {
                    mainHandler().postDelayed(this, 800L);
                    return;
                }
                if (pending) {
                    synchronized (sWakeGate) {
                        sPendingWakeAt = 0;
                        sWakeRetry = null;
                    }
                    wakeNow("retry timer");
                }
            }
        };
        synchronized (sWakeGate) {
            sWakeRetry = retry;
        }
        mainHandler().postDelayed(retry, 2600L);
    }

    private void cancelWakeRetry() {
        Runnable r;
        synchronized (sWakeGate) {
            if (sPendingWakeAt == 0) {
                return;
            }
            sPendingWakeAt = 0;
            r = sWakeRetry;
            sWakeRetry = null;
        }
        if (r != null) {
            mainHandler().removeCallbacks(r);
        }
    }

    /**
     * Fires the pending wake exactly once (the pending flag is consumed under
     * the gate), posted to the next main-loop turn so we never re-enter the
     * doze machine from inside one of its transitions.
     */
    private void firePendingWake(@NonNull String reason) {
        boolean fire = false;
        Runnable r = null;
        synchronized (sWakeGate) {
            if (sPendingWakeAt != 0) {
                sPendingWakeAt = 0;
                r = sWakeRetry;
                sWakeRetry = null;
                fire = true;
            }
        }
        if (r != null) {
            mainHandler().removeCallbacks(r);
        }
        if (fire) {
            wakeNow(reason);
        }
    }

    private void hookAddElement(@NonNull Class<?> root) {
        try {
            java.lang.reflect.Method add = root.getDeclaredMethod(
                    "addElement", String.class, WeakReference.class);
            add.setAccessible(true);
            AodLyricModule.buildHook(mXp, add, "aodlyric-add-element")
                    .intercept(chain -> {
                        // Feeds the framerate pump: the AOD panel only
                        // recomposites while one of these roots is driven.
                        AodOverlayController.setMamlRoot(chain.getThisObject());
                        return chain.proceed();
                    });
            AodLog.i("ScreenElementRoot.addElement hook installed (MAML roots)");
        } catch (Throwable t) {
            AodLog.w("addElement hook unavailable: " + t);
        }
    }

    // ── panel refresh hold (DOZE_SUSPEND -> DOZE) ───────────────────────
    //
    // AOD power states: DOZE (3) keeps the display on and compositing, so new
    // frames reach the panel; DOZE_SUSPEND (4) hands the image to the panel's
    // own self-refresh and the compositor stops sending frames. MIUI parks the
    // AOD in DOZE_SUSPEND a few seconds after it settles, which is why lyric
    // text (and even the progress clock) froze on the physical screen while
    // screencap kept returning fresh buffers.
    //
    // AOSP ships DozeSuspendScreenStatePreventingAdapter for devices that
    // cannot doze-suspend: it rewrites 4 -> 3. We do the same, but only while
    // a lyric is actually playing, and we restore 4 when playback stops so the
    // panel goes back to its low-power cadence.

    /** Display.STATE_DOZE - screen on, dozing, compositing. */
    private static final int STATE_DOZE = 3;
    /** Display.STATE_DOZE_SUSPEND - panel self-refresh, no new frames. */
    private static final int STATE_DOZE_SUSPEND = 4;

    /** True while the lyric overlay wants continuous panel refresh. */
    private static volatile boolean sPanelAwake;

    /** The live doze DreamService (MiuiDozeService), captured from the hook. */
    @Nullable
    private static volatile Object sDreamService;

    /** The live SystemUI DozeMachine, captured from its constructor. */
    @Nullable
    private static volatile Object sDozeMachine;

    /** Cached {@code DozeMachine.mState} field. */
    @Nullable
    private static volatile java.lang.reflect.Field sDozeStateField;

    /** True when we rewrote a suspend request to DOZE and owe a restore. */
    private static volatile boolean sSuspendRemapped;

    /**
     * True while our own {@code setDozeScreenState} call is in flight. The hook
     * must not treat it as a machine request, otherwise our periodic re-assert
     * (always DOZE) would clear the "owe a restore" bookkeeping.
     */
    private static volatile boolean sSelfApply;

    /** One-shot guards so a failing lever cannot spam the log. */
    private static final java.util.concurrent.atomic.AtomicBoolean sDozeHookWarned =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean sDozeApplyWarned =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Asks for (or releases) the continuous-refresh hold.
     *
     * @param force re-assert even when the requested state did not change: the
     *              doze machine can move the display behind our back.
     */
    public static void setPanelAwake(boolean awake, boolean force) {
        boolean changed = sPanelAwake != awake;
        sPanelAwake = awake;
        if (!awake) {
            if (changed) {
                restoreSuspend();
            }
            return;
        }
        if (changed || force) {
            requestDoze();
        }
    }

    /** True while the hold is on (used by diagnostics). */
    public static boolean isPanelAwake() {
        return sPanelAwake;
    }

    /**
     * Hooks {@code DreamService.setDozeScreenState(int)} - the single funnel
     * every doze display-state change goes through (MiuiDozeService inherits
     * it from DreamService, so there is no SystemUI override to hook).
     */
    private void hookDreamDozeState() {
        try {
            Class<?> dream = Class.forName("android.service.dreams.DreamService");
            java.lang.reflect.Method m = dream.getDeclaredMethod(
                    "setDozeScreenState", int.class);
            m.setAccessible(true);
            AodLyricModule.buildHook(mXp, m, "aodlyric-dream-doze-state")
                    .intercept(chain -> {
                        Object self = chain.getThisObject();
                        Object arg = chain.getArg(0);
                        if (!(arg instanceof Integer)) {
                            return chain.proceed();
                        }
                        int state = (Integer) arg;
                        if (self != null && self.getClass().getName().contains("Doze")) {
                            noteDreamService(self);
                        }
                        // Our own call: no machine bookkeeping, no remap.
                        if (sSelfApply) {
                            return chain.proceed();
                        }
                        if (state == STATE_DOZE_SUSPEND) {
                            if (sPanelAwake) {
                                sSuspendRemapped = true;
                                AodLog.i("panel hold: DOZE_SUSPEND -> DOZE");
                                return chain.proceed(new Object[]{STATE_DOZE});
                            }
                            sSuspendRemapped = false;
                        } else if (state == STATE_DOZE) {
                            sSuspendRemapped = false;
                        }
                        return chain.proceed();
                    });
            AodLog.i("hooked DreamService.setDozeScreenState (panel refresh hold)");
        } catch (Throwable t) {
            if (sDozeHookWarned.compareAndSet(false, true)) {
                AodLog.w("DreamService.setDozeScreenState hook failed: " + t);
            }
        }

        // Capture the service as soon as the dream starts, so the hold works
        // immediately instead of waiting for the next system-initiated
        // display-state change (the minute tick).
        try {
            Class<?> miui = Class.forName("com.android.keyguard.doze.MiuiDozeService",
                    false, mHostLoader);
            java.lang.reflect.Method onCreate = miui.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            AodLyricModule.buildHook(mXp, onCreate, "aodlyric-doze-service-capture")
                    .intercept(chain -> {
                        noteDreamService(chain.getThisObject());
                        return chain.proceed();
                    });
            AodLog.i("hooked MiuiDozeService.onCreate (doze service capture)");
        } catch (Throwable t) {
            AodLog.i("MiuiDozeService capture hook skipped: " + t);
        }

        // The machine's state tells us what the display SHOULD be. Without it
        // the restore has to guess: after we rewrite one suspend request the
        // machine believes the display already is DOZE_SUSPEND and never asks
        // again, so a release would leave the panel compositing (and draining
        // battery) long after playback stopped.
        try {
            Class<?> machine = Class.forName("com.android.systemui.doze.DozeMachine",
                    false, mHostLoader);
            sDozeStateField = machine.getDeclaredField("mState");
            sDozeStateField.setAccessible(true);
            for (java.lang.reflect.Constructor<?> c : machine.getDeclaredConstructors()) {
                c.setAccessible(true);
                AodLyricModule.buildHook(mXp, c, "aodlyric-doze-machine-capture")
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object self = chain.getThisObject();
                            if (self != null) {
                                sDozeMachine = self;
                                AodLog.i("doze machine captured: "
                                        + self.getClass().getName());
                            }
                            return result;
                        });
            }
            AodLog.i("hooked DozeMachine <init> (doze state capture)");
        } catch (Throwable t) {
            AodLog.w("DozeMachine capture hook failed: " + t);
        }
    }

    /** Records the live doze service, replacing a stale one from a past cycle. */
    private static void noteDreamService(@Nullable Object self) {
        if (self == null || self == sDreamService) {
            return;
        }
        sDreamService = self;
        sSuspendRemapped = false;
        AodLog.i("doze service captured: " + self.getClass().getName());
        if (sPanelAwake) {
            // The panel may already be parked in DOZE_SUSPEND; nudge it after
            // the dream window has had a moment to attach.
            mainHandler().postDelayed(ThemeProbeV2::requestDoze, 600L);
        }
    }

    /** Puts the captured doze display into DOZE (compositing) now. */
    private static void requestDoze() {
        applyDozeState(STATE_DOZE);
    }

    /** Hands the panel back to self-refresh after the hold is released. */
    private static void restoreSuspend() {
        if (!machineWantsSuspend()) {
            sSuspendRemapped = false;
            return;
        }
        sSuspendRemapped = false;
        applyDozeState(STATE_DOZE_SUSPEND);
    }

    /**
     * True when the doze machine's current state maps to a suspended display
     * (AOSP: DOZE_AOD_PAUSED -&gt; STATE_DOZE_SUSPEND) or when no machine has
     * been captured yet but we know we rewrote a suspend request.
     */
    private static boolean machineWantsSuspend() {
        Object machine = sDozeMachine;
        java.lang.reflect.Field field = sDozeStateField;
        if (machine != null && field != null) {
            try {
                Object state = field.get(machine);
                if (state instanceof Enum) {
                    String name = ((Enum<?>) state).name();
                    return "DOZE_AOD_PAUSED".equals(name);
                }
            } catch (Throwable ignored) {
            }
        }
        return sSuspendRemapped;
    }

    /**
     * Invokes {@code setDozeScreenState} on the captured service, on the main
     * thread (the window plumbing expects it).
     */
    private static void applyDozeState(int state) {
        final Object svc = sDreamService;
        if (svc == null) {
            return;
        }
        mainHandler().post(() -> {
            try {
                java.lang.reflect.Method m = svc.getClass()
                        .getMethod("setDozeScreenState", int.class);
                sSelfApply = true;
                try {
                    m.invoke(svc, state);
                } finally {
                    sSelfApply = false;
                }
                AodLog.i("panel state -> " + (state == STATE_DOZE ? "DOZE" : "DOZE_SUSPEND"));
            } catch (Throwable t) {
                sSelfApply = false;
                if (sDozeApplyWarned.compareAndSet(false, true)) {
                    AodLog.w("setDozeScreenState(" + state + ") failed: " + t);
                }
            }
        });
    }
}
