package cn.qxfly.aod;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static cn.qxfly.aod.AodOverlayController.pluginLoader;
import static cn.qxfly.aod.AodOverlayController.sFrameOk;
import static cn.qxfly.aod.AodOverlayController.sFrameWarned;
import static cn.qxfly.aod.AodOverlayController.sFrameWarned2;
import static cn.qxfly.aod.AodOverlayController.sLoggedFailures;
import static cn.qxfly.aod.AodOverlayController.sMamlRoots;
import static cn.qxfly.aod.AodOverlayController.sRootMotion;
import static cn.qxfly.aod.AodOverlayController.sTraversalOk;
import static cn.qxfly.aod.AodOverlayController.sTraversalWarned;
import static cn.qxfly.aod.AodOverlayController.sUtilsClass;
import static cn.qxfly.aod.AodOverlayController.setMamlRoot;

/**
 * Reflection bridge into MIUI's MAML renderer plus the doze-panel refresh
 * levers (frame rate, forced traversal, root motion).
 *
 * <p>Everything here is best-effort: MIUI moves these internals between
 * builds, so every call is wrapped and failures are logged once each.
 */
public final class MamlBridge {

    private MamlBridge() {
    }

    @Nullable
    static Object readField(@NonNull Object owner, @NonNull String name) {
        Class<?> type = owner.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * Commands every known MAML root to render continuously.
     *
     * <p>Same lever the native lyric path uses: MusicControlScreenElement calls
     * {@code requestFramerate(mLyric != null ? 30f : MAX_TEXTSIZE)}, where
     * MAX_TEXTSIZE means "static, do not redraw". A theme without a music
     * element therefore parks its renderer in static mode; no invalidate() or
     * scheduleTraversals() from our overlay can make the window recomposite
     * until the renderer itself is animated again.
     */
    static void driveFramerate() {
        java.util.List<Object> live;
        synchronized (sMamlRoots) {
            live = new java.util.ArrayList<>(sMamlRoots.size());
            for (java.lang.ref.WeakReference<Object> ref : sMamlRoots) {
                Object root = ref.get();
                if (root != null) {
                    live.add(root);
                }
            }
        }
        if (live.isEmpty()) {
            return;
        }
        boolean anyOk = false;
        String clock = clockText();
        for (Object root : live) {
            // 1) Force the renderer's own update. This is the call the theme uses
            //    when it wants a repaint; an ordinary invalidate() never reaches
            //    that layer.
            boolean updateOk = invokeNoArg(root, "requestUpdate");
            // 2) Simulate a clock tick. The AOD provably repaints on its own time
            //    updates (the clock keeps running while our requests are ignored),
            //    so pushing a fresh time value is what actually refreshes the panel.
            boolean clockOk = invokeWithString(root, "updateDateTimeVariable", clock);
            if (updateOk || clockOk) {
                anyOk = true;
            }
            if (!sFrameOk && (updateOk || clockOk)) {
                AodLyricModule.i("AOD repaint via " + (clockOk ? "clock tick" : "requestUpdate")
                        + " (clock=" + clock + ", roots=" + live.size() + ")");
            }
        }
        if (anyOk) {
            sFrameOk = true;
        }
    }

    /**
     * Keeps the overlay alive inside the AOD's render path.
     *
     * <p>Rationale: the AOD settles into a static low-power state seconds after
     * lighting up; from then on it does not redraw ordinary views, so new lyric
     * lines would never appear. Nudging the view (re-attach when detached,
     * restore visibility, bring to front, invalidate) is enough to keep it in
     * the redraw path on this ROM.
     */
    /**
     * Walks the AOD view tree and registers the MAML root behind any
     * MamlView/MiAdvancedView found. Those are the views that actually paint
     * the theme, so their root is the one whose framerate must be raised.
     */
    static void harvestMamlRoots(@NonNull ViewGroup root) {
        try {
            java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>();
            queue.add(root);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 200) {
                android.view.View v = queue.poll();
                if (v == null) {
                    continue;
                }
                String cls = v.getClass().getName();
                if (cls.contains("MamlView") || cls.contains("MiAdvancedView")) {
                    Object mamlRoot = readField(v, "mRoot");
                    if (mamlRoot != null) {
                        AodLyricModule.i("MAML root found in AOD tree: " + cls);
                        setMamlRoot(mamlRoot);
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) v;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.add(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable t) {
            AodLyricModule.w("AOD tree harvest failed: " + t);
        }
    }

    /**
     * Enables the display-refresh touch modes MIUI itself uses on touch.
     *
     * <p>{@code com.miui.aod.Utils.setTouchMode(touchId, mode, value)} forwards to
     * Xiaomi's ITouchFeature service:
     * mode 19 = keep-screen-on (continuous refresh), mode 11 = AOD animate.
     * Touching the under-display fingerprint sensor turns these on, which is why
     * the panel refreshes then and freezes otherwise.
     */
    /**
     * Resolves {@code com.miui.aod.Utils}. It lives in MIUIAod.apk, loaded by a
     * plugin class loader, so the module's own loader cannot see it - walk the
     * known loaders, then fall back to scanning loaded classes.
     */
    @Nullable
    static Class<?> findUtilsClass() {
        try {
            Class<?> cached = sUtilsClass;
            if (cached != null) {
                return cached;
            }
        } catch (Throwable ignored) {
        }
        ClassLoader pluginLoader = pluginLoader();
        if (pluginLoader != null) {
            try {
                Class<?> c = Class.forName("com.miui.aod.Utils", false, pluginLoader);
                sUtilsClass = c;
                return c;
            } catch (Throwable ignored) {
            }
        }
        // Fall back: the object we already hold came from the plugin loader.
        Object root = sMamlRoots.isEmpty() ? null : sMamlRoots.get(0).get();
        if (root != null) {
            try {
                Class<?> c = Class.forName("com.miui.aod.Utils", false,
                        root.getClass().getClassLoader());
                sUtilsClass = c;
                return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @NonNull
    static String clockText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    static boolean invokeNoArg(@NonNull Object target, @NonNull String name) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            m.invoke(target);
            return true;
        } catch (Throwable t) {
            logReflectFailure(name, t);
            return false;
        }
    }

    static boolean invokeWithString(@NonNull Object target, @NonNull String name,
                                            @NonNull String arg) {
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(name, String.class);
            m.setAccessible(true);
            m.invoke(target, arg);
            return true;
        } catch (Throwable t) {
            logReflectFailure(name, t);
            return false;
        }
    }

    /** Logs each distinct reflective failure once, with its real cause. */
    static void logReflectFailure(@NonNull String name, @NonNull Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                && t.getCause() != null ? t.getCause() : t;
        String key = name + ":" + cause.getClass().getSimpleName();
        if (sLoggedFailures.add(key) && sLoggedFailures.size() <= 8) {
            AodLyricModule.w("reflect " + name + " failed: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /**
     * Schedules a real traversal on the window that owns the AOD.
     *
     * <p>The AOD's composition ignores ordinary {@code invalidate()} once it has
     * settled - the view keeps the new text but the display never re-composites.
     * Going through {@code ViewRootImpl#scheduleTraversals()} is the same path
     * the system uses to refresh its own clock, and it does force a redraw.
     */
    static void forceTraversal(@NonNull android.view.View anchor) {
        try {
            Object attachInfo = readField(anchor, "mAttachInfo");
            if (attachInfo == null) {
                return;
            }
            Object viewRootImpl = readField(attachInfo, "mViewRootImpl");
            if (viewRootImpl == null) {
                return;
            }
            java.lang.reflect.Method schedule = viewRootImpl.getClass()
                    .getDeclaredMethod("scheduleTraversals");
            schedule.setAccessible(true);
            schedule.invoke(viewRootImpl);
            sTraversalOk = true;
        } catch (Throwable t) {
            if (!sTraversalWarned) {
                sTraversalWarned = true;
                AodLyricModule.w("scheduleTraversals unavailable: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Raises the frame rate of the window's own Surface.
     *
     * <p>MIUI parks the doze window on a very low refresh cadence; asking the
     * Surface for a fixed 60 Hz is the lowest-level request available to us and
     * makes SurfaceFlinger treat the layer as actively updated.
     */
    static void bumpSurfaceFrameRate(@NonNull android.view.View anchor) {
        try {
            Object attachInfo = readField(anchor, "mAttachInfo");
            if (attachInfo == null) {
                return;
            }
            Object viewRootImpl = readField(attachInfo, "mViewRootImpl");
            if (viewRootImpl == null) {
                return;
            }
            Object surface = readField(viewRootImpl, "mSurface");
            if (!(surface instanceof android.view.Surface)) {
                return;
            }
            android.view.Surface s = (android.view.Surface) surface;
            if (!s.isValid()) {
                return;
            }
            // API 31+: setFrameRate(float, compatibility, changeStrategy)
            java.lang.reflect.Method m = android.view.Surface.class.getMethod(
                    "setFrameRate", float.class, int.class, int.class);
            m.invoke(s, 60f, 1 /* FIXED_SOURCE */, 0 /* seamless only */);
            if (!sFrameWarned2) {
                sFrameWarned2 = true;
                AodLyricModule.i("AOD surface frame rate raised to 60Hz");
            }
        } catch (Throwable t) {
            if (!sFrameWarned2) {
                sFrameWarned2 = true;
                AodLyricModule.i("surface frame rate unavailable: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    /**
     * Animates the AOD root by a sub-pixel offset.
     *
     * <p>Observed behaviour on this device: lyric text only appears when some AOD
     * component is already moving. Animating our own child view was not enough,
     * so the root view itself is put into an animated state - the smallest offset
     * that still marks the window as animating.
     */
    static void startRootMotion(@NonNull ViewGroup root) {
        try {
            stopRootMotion();
            android.animation.ObjectAnimator motion =
                    android.animation.ObjectAnimator.ofFloat(root, "translationX", 0f, 0.02f);
            motion.setDuration(320L);
            motion.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            motion.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            motion.start();
            sRootMotion = motion;
            AodLyricModule.i("AOD root motion started (window marked as animating)");
        } catch (Throwable t) {
            AodLyricModule.w("AOD root motion failed: " + t);
        }
    }

    static void stopRootMotion() {
        android.animation.ObjectAnimator motion = sRootMotion;
        sRootMotion = null;
        if (motion != null) {
            try {
                motion.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

}
