package cn.qxfly.aod;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static cn.qxfly.aod.AnchorIds.ANCHOR_CLOCK;
import static cn.qxfly.aod.AnchorIds.ANCHOR_IMAGE;
import static cn.qxfly.aod.AnchorIds.ANCHOR_NOTIFY;
import static cn.qxfly.aod.AnchorIds.ANCHOR_ORDER;
import static cn.qxfly.aod.AnchorIds.ANCHOR_TIMEDATE;
import static cn.qxfly.aod.AnchorIds.canonicalCandidates;
import static cn.qxfly.aod.AnchorIds.legacyToCanonical;
import static cn.qxfly.aod.ComponentCollector.collectComponents;
import static cn.qxfly.aod.ComponentCollector.containsBounds;
import static cn.qxfly.aod.ComponentCollector.containsId;
import static cn.qxfly.aod.MamlBridge.bumpSurfaceFrameRate;
import static cn.qxfly.aod.MamlBridge.clockText;
import static cn.qxfly.aod.MamlBridge.driveFramerate;
import static cn.qxfly.aod.MamlBridge.forceTraversal;
import static cn.qxfly.aod.MamlBridge.harvestMamlRoots;
import static cn.qxfly.aod.MamlBridge.readField;
import static cn.qxfly.aod.MamlBridge.startRootMotion;
import static cn.qxfly.aod.MamlBridge.stopRootMotion;

/**
 * Self-drawn lyric overlay for the AOD.
 *
 * <p>Anchor: DozeServicePluginImpl#onDreamingStarted(ViewGroup) hands us the
 * AOD's root view group exactly when the息屏 starts rendering; a lyric
 * TextView is added as its last child (on top of the theme content) and
 * removed in onDreamingStopped. Because the view lives INSIDE the AOD's own
 * hierarchy, it works on every息屏 style - no theme cooperation needed - and
 * it never outlives the AOD window.
 *
 * <p>Text source: the UI app process receives full lyrics from SuperLyric and
 * publishes them into the state provider; the poller in this process refreshes
 * the current line every second and the overlay displays it.
 */
public final class AodOverlayController {

    private static final AtomicReference<TextView> sView = new AtomicReference<>();

    /**
     * Every overlay TextView this process has created.
     *
     * <p>A deferred UI runnable from a dead AOD session used to be able to
     * re-parent its OLD view into the NEW session: it resolved the static
     * clock container at execution time, so after detach()+attach() it could
     * {@code addView()} a view nobody updates any more. On screen that is a
     * frozen, misplaced second lyric that survives pause and never clears.
     * Keeping a registry lets attach / idle / kicker sweep such strays away.
     */
    private static final java.util.List<java.lang.ref.WeakReference<TextView>> sAllViews =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private final XposedInterface mXp;
    @NonNull
    private final ProviderLrcSource mProviderLrc;

    private static volatile boolean sHooked;
    private static volatile boolean sAodVisible;

    /** True while the AOD window is showing (used by the adaptive poll). */
    static boolean isAodVisible() {
        return sAodVisible;
    }

    /**
     * True when the style draws the position/duration row. The poller then
     * refreshes every tick instead of every few seconds, and a new position
     * value is part of the render signature (see {@code setLyric}).
     */
    static boolean progressShown() {
        LyricStyle style = sStyle;
        return style != null && style.showProgress;
    }

    /** True while a line is actually rendered (idle AOD = nothing to tick). */
    static boolean hasContent() {
        return sActive && sLastText != null && !sLastText.isEmpty();
    }

    /** Remembers an overlay view so strays can be swept on later sessions. */
    private static void registerView(@NonNull TextView tv) {
        synchronized (sAllViews) {
            for (int i = sAllViews.size() - 1; i >= 0; i--) {
                if (sAllViews.get(i).get() == null) {
                    sAllViews.remove(i);
                }
            }
            sAllViews.add(new java.lang.ref.WeakReference<>(tv));
        }
    }

    /**
     * Removes every overlay view except {@code keep} and the current view.
     *
     * <p>Main thread only. Strays are views a dead session's queued runnable
     * re-attached; they no longer receive text updates, so they must not stay
     * in the AOD tree (they look like a frozen, offset second lyric).
     */
    private static void removeStrayViews(@Nullable TextView keep) {
        TextView current = sView.get();
        java.util.List<TextView> strays = null;
        synchronized (sAllViews) {
            for (int i = sAllViews.size() - 1; i >= 0; i--) {
                TextView v = sAllViews.get(i).get();
                if (v == null) {
                    sAllViews.remove(i);
                    continue;
                }
                if (v == keep || v == current) {
                    continue;
                }
                if (strays == null) {
                    strays = new java.util.ArrayList<>();
                }
                strays.add(v);
                sAllViews.remove(i);
            }
        }
        if (strays == null) {
            return;
        }
        for (TextView v : strays) {
            android.view.ViewParent p = v.getParent();
            try {
                if (p instanceof ViewGroup) {
                    ((ViewGroup) p).removeView(v);
                }
                v.setText("");
            } catch (Throwable t) {
                AodLyricModule.w("stray overlay cleanup failed: " + t);
            }
            AodLyricModule.w("stray overlay removed (parent="
                    + (p == null ? "null" : p.getClass().getSimpleName()) + ")");
        }
    }

    /** Process-wide module interface, for static helpers (touch watch, pulses). */
    @Nullable
    private static volatile XposedInterface sXp;

    public AodOverlayController(@NonNull XposedInterface xp,
                                @NonNull ProviderLrcSource providerLrc) {
        mXp = xp;
        mProviderLrc = providerLrc;
        sXp = xp;
    }

    /** Called by the loadClass watcher when any loader defines the plugin impl. */
    public void onPluginImplLoaded(@NonNull Class<?> pluginImpl) {
        if (sHooked) {
            return;
        }
        sHooked = true;
        hookLifecycle(pluginImpl);
    }

    /**
     * Alternate anchor: DozeService#dreamingStarted(ViewGroup). The plugin
     * delegates to this class, which owns the AOD content views; whichever
     * anchor fires first attaches the overlay (attach() is idempotent).
     */
    public void onDozeServiceLoaded(@NonNull Class<?> dozeService) {
        try {
            java.lang.reflect.Method started = null;
            for (java.lang.reflect.Method m : dozeService.getDeclaredMethods()) {
                if ("dreamingStarted".equals(m.getName())
                        && m.getParameterTypes().length == 1
                        && ViewGroup.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    started = m;
                    break;
                }
            }
            if (started == null) {
                AodLyricModule.w("DozeService.dreamingStarted(ViewGroup) not found");
                return;
            }
            started.setAccessible(true);
            AodLyricModule.buildHook(mXp, started, "aodlyric-doze-dreaming-started")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object root = chain.getArg(0);
                        if (root instanceof ViewGroup) {
                            AodLyricModule.i("DozeService.dreamingStarted(ViewGroup) fired");
                            attach((ViewGroup) root);
                        }
                        return result;
                    });
            AodLyricModule.i("hooked DozeService.dreamingStarted(ViewGroup)");
        } catch (Throwable t) {
            // Expected outside SystemUI: resolving DozeService's members needs
            // the SystemUI plugin classes, which only exist in that process.
            AodLyricModule.d("DozeService anchor unavailable here: "
                    + t.getClass().getSimpleName());
        }
    }

    private void hookLifecycle(@NonNull Class<?> pluginImpl) {
        // Proof-of-life: tells us the plugin is actually instantiated in this
        // process, separating "plugin path unused" from "hook missed".
        try {
            java.lang.reflect.Method onCreate = pluginImpl.getDeclaredMethod(
                    "onCreate", Context.class, Context.class);
            onCreate.setAccessible(true);
            AodLyricModule.buildHook(mXp, onCreate, "aodlyric-plugin-create")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        AodLyricModule.i("DozeServicePluginImpl.onCreate called "
                                + "(AOD plugin is alive in this process)");
                        return result;
                    });
            AodLyricModule.i("hooked plugin onCreate(Context, Context)");
        } catch (Throwable t) {
            AodLyricModule.w("plugin onCreate hook failed: " + t);
        }

        try {
            java.lang.reflect.Method started = pluginImpl.getDeclaredMethod(
                    "onDreamingStarted", ViewGroup.class);
            started.setAccessible(true);
            AodLyricModule.buildHook(mXp, started, "aodlyric-dream-start")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object root = chain.getArg(0);
                        if (root instanceof ViewGroup) {
                            attach((ViewGroup) root);
                        }
                        return result;
                    });
            AodLyricModule.i("hooked onDreamingStarted(ViewGroup)");
        } catch (Throwable t) {
            AodLyricModule.w("onDreamingStarted hook failed: " + t);
        }

        try {
            java.lang.reflect.Method stopped = pluginImpl.getDeclaredMethod(
                    "onDreamingStopped");
            stopped.setAccessible(true);
            AodLyricModule.buildHook(mXp, stopped, "aodlyric-dream-stop")
                    .intercept(chain -> {
                        detach();
                        return chain.proceed();
                    });
            AodLyricModule.i("hooked onDreamingStopped()");
        } catch (Throwable t) {
            AodLyricModule.w("onDreamingStopped hook failed: " + t);
        }
    }

    /** Adds the lyric TextView to the AOD root. Runs on the host main thread. */
    private void attach(@NonNull ViewGroup root) {
        // Watch the AOD container's own touch stream: it fires BEFORE the panel
        // lights, which is exactly when we must stop pulsing (a pulse racing the
        // under-display fingerprint handshake kills the AOD UI).
        installTouchWatch(root);
        // Visible on every AOD entry: proves the module follows the user's
        // system double-tap-wake setting (Settings.System "gesture_wakeup").
        AodLyricModule.i("double-tap wake setting (gesture_wakeup)="
                + (systemDoubleTapWakeEnabled(root.getContext()) ? "on" : "off"));
        TextView existing = sView.get();
        if (existing != null) {
            // Same AOD window and the view is still in a tree: keep it.
            if (sAodVisible && existing.getParent() != null) {
                removeStrayViews(existing);
                return;
            }
            // A view left over from a closed/rebuilt window (or pulled out by
            // hideOverlay) must not be reused blindly: drop it before creating
            // the new session's view, otherwise the old line stays on screen.
            android.view.ViewParent oldParent = existing.getParent();
            if (oldParent instanceof ViewGroup) {
                ((ViewGroup) oldParent).removeView(existing);
            }
            existing.setText("");
            sView.compareAndSet(existing, null);
            AodLyricModule.w("stale overlay view replaced on AOD attach");
        }
        try {
            Context context = root.getContext();
            TextView tv = new TextView(context);
            // Own subclass drawing an optional outline; falls back to a plain
            // TextView when the stroke is disabled.
            tv = new LyricTextView(context);
            tv.setLineSpacing(0, 1.1f);
            applyStyle(tv, sStyle);
            // Must never swallow a touch/gesture on the lock screen.
            tv.setClickable(false);
            tv.setFocusable(false);
            tv.setFocusableInTouchMode(false);
            // Re-align in the same layout pass that first applies the full
            // width, so the first full-width frame is already centred.
            tv.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob)
                    -> alignFullWidth((TextView) v));

            // Attach INSIDE the clock container so the lyric is part of the AOD
            // clock component: AODUpdatePositionController translates that view
            // for anti-burn-in drift, and anything parented to it moves along.
            // Falling back to the AOD root would pin the text in place - which is
            // exactly the "fixed in one spot" complaint.
            ViewGroup host = resolveClockContainer(root);
            sClockContainer = new java.lang.ref.WeakReference<>(host);
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            host.addView(tv, params);
            applyFullWidth(tv);
            // 沉浸模式：把刚挂上的歌词搬进整屏舞台，并隐藏/遮住其它组件。
            // 非沉浸时 sync 什么也不做（sStyle==null 或 immersive==0）。
            ImmersiveStage.sync(root, tv, sStyle);

            // The theme is inflated AFTER onDreamingStarted, so clock_container
            // usually does not exist yet at this point and we land on the root -
            // which is exactly why the lyric sat at the very top and never drifted.
            // Retry until the real container shows up and reparent into it.
            scheduleReparent(root, 600);
            scheduleReparent(root, 1500);
            scheduleReparent(root, 3000);
            scheduleReparent(root, 6000);
            scheduleReparent(root, 10000);

            // Component enumeration on EVERY AOD start (theme inflates ~2s in,
            // containers laid out by then). Re-reported each time so the app's
            // anchor dropdown follows theme/AOD-style changes - the old
            // process-lifetime one-shot kept serving a stale list until the
            // com.miui.aod process happened to die.
            root.postDelayed(() -> {
                if (sRootRef == null || sRootRef.get() != root) {
                    return; // tree belongs to a closed AOD window
                }
                TextView tvNow = sView.get();
                if (tvNow == null || tvNow.getParent() == null) {
                    return; // AOD already stopped; skip a dead-tree report
                }
                ViewGroup c = sClockContainer == null ? null : sClockContainer.get();
                java.util.List<String> lines = new java.util.ArrayList<>();
                collectComponents(c != null ? c : root, lines, 0, 0, 0);
                ModuleReporter componentsReporter = reporter();
                if (componentsReporter != null) {
                    componentsReporter.reportComponents(lines);
                }
                if (!sDumpedTree) {
                    sDumpedTree = true;
                    AodLyricModule.i("AOD tree dump (anchor candidates):");
                    dumpTree(c != null ? c : root, 0);
                }
            }, 5000);

            // The SurfaceView was removed: it drew a SECOND lyric window (slow,
            // 1s loop) and, being setZOrderOnTop(true), it sat above the
            // under-display fingerprint layer - touching the sensor flashed the
            // screen and crashed the AOD UI. 5.4.0 proved the doze pulse alone is
            // enough to refresh the TextView, so a separate surface is not needed.
            sView.set(tv);
            registerView(tv);
            removeStrayViews(tv);
            sAodVisible = true;
            // A fresh session must not inherit the previous render cache,
            // otherwise the first setText() can no-op on an identical
            // signature and the new view keeps whatever stale spanned text
            // the last session left behind.
            sLastSignature = null;
            sLastSpanned = null;
            sLastText = null;
            sActive = false;
            sPaused = false;
            sAttachedAt = android.os.SystemClock.elapsedRealtime();

            // The AOD's own content is added by dozeService.dreamingStarted(),
            // which runs on a delayed runnable AFTER this callback - so a view
            // added here ends up UNDER the theme. Re-add/re-front on a couple of
            // delays to end up on top regardless of that ordering.
            scheduleRefront(root, tv, 0);
            scheduleRefront(root, tv, 1200);
            scheduleRefront(root, tv, 2600);

            // Capture the MAML root that actually belongs to this AOD window.
            harvestMamlRoots(root);

            // NOTE (proven on device): the panel is refreshed ONLY by a doze
            // pulse. invalidate(), ViewRootImpl.scheduleTraversals(), animators,
            // structural rebuilds, Surface.setFrameRate, an independent
            // SurfaceView and Xiaomi's touch modes (19/11) were all applied
            // successfully and all left the panel frozen. The pulse is issued
            // from setText() via requestPulse(). Everything else was removed to
            // avoid pointless frame work and battery drain.

            // Keep the light kicker: it re-attaches the view if the AOD rebuilds
            // its hierarchy, and it repairs a stopped pulse animation. It no
            // longer pulses on a timer - see the class note above.
            startKicker(root, tv);

            // One-shot full-screen AOD probe: when "AOD same as lock screen"
            // hides the whole AOD content subtree (alpha 0), log the ancestor
            // chain up to the window top and dump the VISIBLE tree, so the
            // shadow-mode attach point can be identified from the field.
            postOnMainDelayed(() -> logFullScreenAodState(root), 6500);

            AodLyricModule.i("lyric overlay attached to AOD root "
                    + root.getClass().getName());

            // Pull fresh state first: the attach can happen long after the last
            // poll, and a first draw with stale/empty text looks like a failure.
            // displayLine() applies the master-switch + staleness gates, so a
            // attach right after a reboot (persisted-but-dead lyric state) shows
            // nothing instead of the ghost line from the previous session.
            mProviderLrc.refresh();
            String line = mProviderLrc.displayLine();
            AodLyricModule.i("overlay initial line="
                    + (line == null ? "null" : line)
                    + " track=" + mProviderLrc.track());
            setText(line == null ? "" : line);
        } catch (Throwable t) {
            AodLyricModule.w("lyric overlay attach failed: " + t);
        }
    }

    /**
     * Brings the overlay to the front after a delay, re-adding it if the AOD
     * container has reordered children. Also re-applies the vertical offset once
     * the container has a measured height.
     */
    private static void scheduleRefront(@NonNull ViewGroup root, @NonNull TextView tv,
                                        long delayMs) {
        try {
            root.postDelayed(() -> {
                try {
                    if (sView.get() != tv) {
                        return;
                    }
                    ViewGroup parent = currentParent(tv);
                    if (tv.getParent() == null) {
                        (parent == null ? root : parent).addView(tv,
                                new ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                    ensureFront(tv);
                    applyOffset(tv, root);
                    ViewGroup owner = currentParent(tv);
                    if (owner != null) {
                        owner.invalidate();
                    }
                } catch (Throwable t) {
                    AodLyricModule.w("overlay refront failed: " + t);
                }
            }, delayMs);
        } catch (Throwable t) {
            AodLyricModule.w("overlay refront schedule failed: " + t);
        }
    }

    /**
     * Lets the lyric use the FULL screen width in every AOD theme.
     *
     * <p>The theme's content column is narrower than the panel (measured:
     * 521px of a 1200px screen) and usually offset from the left edge, so
     * {@code MATCH_PARENT} silently caps the line length and wraps long lyrics
     * early. Two things are needed:
     *
     * <ul>
     *   <li>an explicit pixel width - a positive {@code LayoutParams.width} is
     *       measured EXACTLY, so the parent's narrower measure spec no longer
     *       clamps it;</li>
     *   <li>a translationX that cancels the parent's on-screen left edge, so the
     *       view really starts at the screen's left edge instead of the
     *       column's.</li>
     * </ul>
     *
     * <p>Clipping is already relaxed along the chain ({@link #relaxClipping}),
     * so the overflow is drawn. Re-applied every kicker tick because MIUI drifts
     * the AOD container for anti-burn-in and themes rebuild their params.
     *
     * <p>Two parts, deliberately kept apart:
     * <ul>
     *   <li>the explicit pixel width is a layout param (takes the next layout
     *       pass);</li>
     *   <li>the horizontal offset is a translationX (render property, takes the
     *       next draw, never feeds back into layout).</li>
     * </ul>
     * The offset is applied ONLY once the view really measures full width. The
     * theme's content column is horizontally centred, so an unshifted narrow
     * view already looks centred - shifting it early is exactly what produced
     * "lyric starts left-aligned, centres ~1s later". A layout-change listener
     * re-aligns in the same pass that finally applies the full width.
     *
     * <p>A negative leftMargin was tried too and is WRONG here: the column is
     * wrap_content and centred, so a negative margin shrinks the column itself,
     * which moves it back by roughly half the offset. The error then decays by
     * ~0.55x per kicker tick (measured: a 6-second rightward staircase, with
     * the first frame 70-85px left of centre).
     */
    private static void applyFullWidth(@NonNull TextView tv) {
        try {
            if (ImmersiveStage.active()) {
                // 舞台画布自己就是整屏宽度，再按屏幕宽度写死会撑破画布。
                return;
            }
            int full = fullWidthPx(tv);
            if (full <= 0) {
                return;
            }
            android.view.ViewParent parent = tv.getParent();
            if (!(parent instanceof ViewGroup)) {
                return;
            }
            ViewGroup g = (ViewGroup) parent;
            ViewGroup.LayoutParams lp = tv.getLayoutParams();
            if (lp == null) {
                return;
            }
            boolean changed = lp.width != full;
            lp.width = full;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                // Margins must stay neutral: see the note above.
                if (mlp.leftMargin != 0 || mlp.rightMargin != 0) {
                    mlp.leftMargin = 0;
                    mlp.rightMargin = 0;
                    changed = true;
                }
            }
            if (changed) {
                tv.setLayoutParams(lp);
                g.requestLayout();
                forceTraversal(g);
            }
            alignFullWidth(tv);
        } catch (Throwable t) {
            AodLyricModule.w("full-width apply failed: " + t);
        }
    }

    /** Screen width the lyric should span (the AOD root may be wider). */
    private static int fullWidthPx(@NonNull TextView tv) {
        int full = tv.getResources().getDisplayMetrics().widthPixels;
        ViewGroup root = sRootRef == null ? null : sRootRef.get();
        if (root != null && root.getWidth() > full) {
            full = root.getWidth();
        }
        return full;
    }

    /**
     * Cancels the lyric's laid-out left edge with a translation, but only once
     * it measures full width. A translation is a render property, so it cannot
     * change the parent's size or position (unlike a margin or a width).
     */
    private static void alignFullWidth(@NonNull TextView tv) {
        try {
            if (ImmersiveStage.active()) {
                // 画布里父容器左边缘就是 0，再取消一遍位移会把歌词推出屏幕。
                return;
            }
            int full = fullWidthPx(tv);
            if (full <= 0 || tv.getWidth() < full) {
                // Still the theme's narrow view: the column is centred, so it
                // already looks centred. Shifting now would be the old jump.
                if (tv.getTranslationX() != 0f) {
                    tv.setTranslationX(0f);
                }
                return;
            }
            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            // getLocationOnScreen() already includes our translation: subtract
            // it to recover the laid-out left edge, then cancel that edge.
            float laidOutX = loc[0] - tv.getTranslationX();
            float dx = -laidOutX;
            if (Math.abs(tv.getTranslationX() - dx) > 0.5f) {
                tv.setTranslationX(dx);
            }
        } catch (Throwable t) {
            AodLyricModule.w("full-width align failed: " + t);
        }
    }

    /**
     * Relaxes the clip on every container from the lyric up to the AOD root.
     *
     * <p>The theme's content column has a fixed height; a large lyric font
     * makes the flow column taller than it and Android's default
     * clipChildren=true cuts everything past that edge, so the max font size
     * looked "cropped". The AOD window itself is full-screen with large empty
     * margins, so letting the content overflow its container just uses empty
     * space. Setters are idempotent, safe for the per-second kicker.
     */
    private static void relaxClipping(@NonNull ViewGroup root, @NonNull android.view.View child) {
        try {
            android.view.ViewParent p = child.getParent();
            while (p instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) p;
                g.setClipChildren(false);
                g.setClipToPadding(false);
                if (g == root) {
                    break;
                }
                p = g.getParent();
            }
        } catch (Throwable t) {
            AodLyricModule.w("relax clipping failed: " + t);
        }
    }

    /**
     * Engages shadow mode when an ancestor fades the whole AOD content out.
     *
     * <p>Full-screen AOD keeps the MIUIAod subtree permanently at alpha 0
     * while the keyguard renders the lock screen; two consecutive 1s kicker
     * ticks below alpha 0.05 make this stick (one tick could still catch a
     * transient host fade-in).
     */
    private static void ensureShadowMode(@NonNull TextView tv) {
        if (sShadowMode) {
            return;
        }
        boolean hidden = false;
        android.view.ViewParent p = tv.getParent();
        int depth = 0;
        while (p instanceof View && depth < 8) {
            View a = (View) p;
            if (a.getAlpha() < 0.05f) {
                hidden = true;
                break;
            }
            p = a.getParent();
            depth++;
        }
        if (hidden) {
            sHiddenStreak++;
            if (sHiddenStreak >= 2) {
                sShadowMode = true;
                sInClockContainer = false;
                AodLyricModule.i("full-screen AOD: content tree faded out ("
                        + sHiddenStreak + " ticks) -> shadow mode on keyguard root");
            }
        } else {
            sHiddenStreak = 0;
        }
    }

    /**
     * Shadow-mode placement: the lyric becomes a direct child of the keyguard
     * AodView root (alpha 1, visible inside the NotificationShade window) and
     * is positioned relative to the VISIBLE keyguard clock.
     *
     * <p>The AOD component anchors live in the faded subtree, so the
     * configured anchor above/below and gap are applied against the visible
     * clock instead; the align and all text styling keep working unchanged.
     */
    private static void applyShadowPlacement(@NonNull TextView tv, @NonNull ViewGroup root) {
        try {
            if (tv.getParent() != root) {
                ViewGroup old = currentParent(tv);
                if (old != null && old != root) {
                    old.removeView(tv);
                }
                View neighbour = root.getChildCount() > 0
                        ? root.getChildAt(root.getChildCount() - 1) : null;
                ViewGroup.LayoutParams lp = copyParamsFor(root, neighbour);
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
                }
                // Appended last: drawn above the faded AOD subtree. The
                // keyguard's own clock/notification views are SIBLINGS of this
                // root, so only the (hidden) AOD content can be overlapped.
                root.addView(tv, lp);
                applyFullWidth(tv);
                relaxClipping(root, tv);
                AodLyricModule.i("shadow mode: lyric parented to "
                        + root.getClass().getSimpleName());
            }
            int gap = Math.max(0, (int) (sStyle == null ? 12f : sStyle.anchorGap));
            boolean above = sStyle != null && sStyle.anchorPos == 0;
            String anchorId = sStyle == null ? null : sStyle.anchorId;
            boolean anchored = anchorId != null && !anchorId.isEmpty();
            int[] clock = visibleClockSpan(root);
            int[] rl = new int[2];
            root.getLocationOnScreen(rl);
            float ratio = sStyle == null ? 0.42f : sStyle.offsetRatio;
            float target;
            if (clock != null && anchored) {
                // Anchor mode: the AOD component anchors are invisible here,
                // so the visible keyguard clock stands in for them.
                target = above
                        ? clock[0] - rl[1] - gap - tv.getHeight()
                        : clock[1] - rl[1] + gap;
            } else if (clock != null) {
                // Ratio mode: map the 垂直位置 slider over the space from the
                // visible clock's top to the screen bottom - the keyguard
                // clock is the top of the content area in this mode, just
                // like the AOD clock container is in normal AOD. The slider
                // covers the full usable range and stays predictable.
                float top = clock[0] - rl[1];
                target = top + ratio * Math.max(0f, root.getHeight() - top);
            } else {
                // No visible clock found: ratio applies against the screen.
                int h = root.getHeight() > 0 ? root.getHeight()
                        : root.getResources().getDisplayMetrics().heightPixels;
                target = h * ratio;
            }
            if (Math.abs(tv.getTranslationY() - target) >= 0.5f) {
                tv.setTranslationY(target);
            }
        } catch (Throwable t) {
            AodLyricModule.w("shadow placement failed: " + t);
        }
    }

    /**
     * On-screen top/bottom of the VISIBLE keyguard clock, or null.
     *
     * <p>HyperOS lock screen: {@code miui_keyguard_clock_container} hosts the
     * big AllInOne clocks; their {@code time_accessibility_overlay} marks the
     * real clock region (the container itself spans the whole screen).
     */
    @Nullable
    private static int[] visibleClockSpan(@NonNull View root) {
        try {
            android.view.ViewParent p = root.getParent();
            if (!(p instanceof View)) {
                return null;
            }
            View clock = findVisibleByIdName((View) p, "keyguard_clock_container");
            if (clock == null) {
                return null;
            }
            View ref = findVisibleByIdName(clock, "time_accessibility");
            if (ref == null || ref.getHeight() <= 0) {
                ref = clock;
                if (ref.getHeight() <= 0 || ref.getHeight() >= ref.getResources()
                        .getDisplayMetrics().heightPixels) {
                    return null; // container spans the screen: no usable bounds
                }
            }
            int[] loc = new int[2];
            ref.getLocationOnScreen(loc);
            return new int[]{loc[1], loc[1] + ref.getHeight()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** First laid-out VISIBLE descendant whose id entry name contains {@code part}. */
    @Nullable
    private static View findVisibleByIdName(@NonNull View v, @NonNull String part) {
        if (v.getVisibility() == android.view.View.VISIBLE && idNameContains(v, part)) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null) {
                    continue;
                }
                View found = findVisibleByIdName(c, part);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean idNameContains(@NonNull View v, @NonNull String part) {
        try {
            if (v.getId() == android.view.View.NO_ID) {
                return false;
            }
            return v.getResources().getResourceEntryName(v.getId()).contains(part);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Places the lyric at the configured offset.
     *
     * <p>Inside clock_container the view is laid out in the container's own
     * coordinate space; applying the ratio against the container height puts the
     * text under the clock instead of at the very top of the screen.
     *
     * <p>Anchor mode: when the style carries an anchor component id, the lyric
     * is placed just above/below that component's real bounds (converted into
     * the container's coordinate space), so it tracks the same layout and
     * anti-burn-in drift as the anchor.
     */
    private static void applyOffset(@NonNull TextView tv, @NonNull ViewGroup root) {
        if (ImmersiveStage.active()) {
            // 沉浸模式自己管位置（含横屏旋转与防烧屏漂移），
            // 不能再用锚点/百分比/树插入那套。
            ImmersiveStage.layout(tv);
            return;
        }
        try {
            relaxClipping(root, tv);
            ensureShadowMode(tv);
            if (sShadowMode) {
                applyShadowPlacement(tv, root);
                return;
            }
            ViewGroup parent = currentParent(tv);
            ViewGroup reference = parent != null ? parent : root;
            String anchorId = sStyle == null ? null : sStyle.anchorId;
            boolean treeMode = sStyle != null && sStyle.treeInsert;
            if (anchorId != null && !anchorId.isEmpty()) {
                if (treeMode && applyTreeInsert(tv, root, anchorId)) {
                    return;
                }
                if (applyAnchorOffset(tv, reference, anchorId)) {
                    return;
                }
            }
            // Ratio mode (anchor off): the placement is a floating overlay
            // positioned by translationY. If the view is still sitting in a
            // layout flow slot left over from anchor mode, release it first -
            // otherwise the slot keeps occupying space and the slider moves
            // the text relative to a stale baseline. The clock container
            // itself is never a release target/susppect: releasing into the
            // same container would churn every tick.
            java.lang.ref.WeakReference<ViewGroup> clockRef = sClockContainer;
            ViewGroup clock = clockRef == null ? null : clockRef.get();
            if (clock == null) {
                clock = findClockContainer(root);
            }
            if (parent != null && parent != clock && isVerticalFlow(parent)) {
                restoreTouchedMargin();
                parent.removeView(tv);
                ViewGroup home = clock != null ? clock : root;
                home.addView(tv, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                AodLyricModule.i("lyric released from flow (ratio mode)");
                reference = home;
            }
            int h = reference.getHeight();
            if (h <= 0) {
                h = reference.getMeasuredHeight();
            }
            if (h <= 0) {
                h = root.getResources().getDisplayMetrics().heightPixels;
            }
            if (h > 0) {
                tv.setTranslationY(h * (sStyle == null ? 0.42f : sStyle.offsetRatio));
            }
        } catch (Throwable t) {
            AodLyricModule.w("applyOffset failed: " + t);
        }
    }

    /**
     * True while the style asks for in-tree placement (drives the guards that
     * keep bringToFront() from destroying the slot inside the layout flow).
     */
    static boolean treeModeActive() {
        LyricStyle s = sStyle;
        return s != null && s.treeInsert
                && s.anchorId != null && !s.anchorId.isEmpty();
    }

    /**
     * In-tree placement with document-flow semantics: the lyric becomes a REAL
     * child of the nearest VERTICAL LinearLayout ancestor of the anchor, at the
     * sibling slot just above/below the anchor's own block. The vertical layout
     * engine then measures the lyric, positions it, and pushes everything below
     * it down - the lyric OCCUPIES layout space and covers nothing.
     *
     * <p>Two-tier strategy:
     * <ol>
     *   <li>Climb from the anchor towards the AOD root; the first ancestor that
     *   is a vertical LinearLayout becomes the flow container and the lyric is
     *   inserted as a sibling of the child on that path (the configured gap
     *   becomes a facing flow margin, all other margins cleared).</li>
     *   <li>Otherwise, when the anchor itself is a vertical LinearLayout (e.g.
     *   gradient_layout hanging directly under a FrameLayout), the lyric is
     *   inserted INSIDE it at the end (below) or the start (above).</li>
     * </ol>
     *
     * <p>When neither is possible the method returns false and the caller
     * falls back to the translationY anchor mode so the lyric never disappears.
     *
     * <p>Idempotent: same parent + same index + same facing margin = no-op for
     * the per-second kicker tick; a theme rebuild re-inserts automatically.
     *
     * @return true when the lyric is in place in the layout flow; false falls
     *     back to the translationY anchor mode.
     */
    private static boolean applyTreeInsert(@NonNull TextView tv, @NonNull ViewGroup root,
            @NonNull String anchorId) {
        try {
            // No active lyric: the slot must NOT occupy layout space. Pull the
            // view out of the flow entirely so the components close up the gap
            // (attach / setStyle / scheduleRefront would otherwise re-insert an
            // empty-text view whose margins push everything down).
            if (!sActive || sLastText == null || sLastText.isEmpty()) {
                restoreTouchedMargin();
                android.view.ViewParent p0 = tv.getParent();
                if (p0 instanceof ViewGroup) {
                    ((ViewGroup) p0).removeView(tv);
                    AodLyricModule.i("lyric pulled out of flow (no active lyric)");
                }
                return true;
            }
            android.view.View anchor = resolveAnchor(root, anchorId);
            if (anchor == null || anchor == tv) {
                return false;
            }
            boolean above = sStyle.anchorPos == 0;
            int gap = Math.max(0, (int) sStyle.anchorGap);
            // (1) nearest vertical LinearLayout ancestor: sibling insertion.
            //     "child" walks up from the anchor; when its parent turns out
            //     to be a vertical flow, the lyric takes the sibling slot.
            android.view.View child = anchor;
            while (true) {
                android.view.ViewParent p = child.getParent();
                if (!(p instanceof ViewGroup)) {
                    break;
                }
                ViewGroup parent = (ViewGroup) p;
                if (parent == root) {
                    break; // never insert into/past the AOD root itself
                }
                if (isVerticalFlow(parent)) {
                    if (parent.indexOfChild(child) < 0) {
                        return false;
                    }
                    return insertIntoFlowAt(tv, parent, child, above, gap);
                }
                child = parent;
            }
            // (2) the anchor itself is a vertical flow container: insert inside.
            if (anchor instanceof ViewGroup && isVerticalFlow((ViewGroup) anchor)) {
                ViewGroup flow = (ViewGroup) anchor;
                return insertIntoFlowAt(tv, flow, null, above, gap);
            }
            return false;
        } catch (Throwable t) {
            AodLyricModule.w("tree insert failed: " + t);
            return false;
        }
    }

    /**
     * True for a LinearLayout laid out top-to-bottom (a real flow container).
     *
     * <p>GradientLinearLayout is excluded when it carries a gradient overlay:
     * its dispatchDraw re-composites ALL children through a PorterDuff SRC_IN
     * gradient (that is how the theme paints its alpha-mask text), which would
     * flatten everything the lyric draws - the custom colour, the stroke, the
     * emoji - into the theme's gradient colour. For such containers we climb
     * one level higher and use the parent as the flow container instead.
     */
    private static boolean isVerticalFlow(@NonNull ViewGroup v) {
        if (!(v instanceof android.widget.LinearLayout)
                || ((android.widget.LinearLayout) v).getOrientation()
                        != android.widget.LinearLayout.VERTICAL) {
            return false;
        }
        // Per-instance memo: the kicker climbs this chain every second and the
        // gradient check below is reflective.
        Boolean memo = sFlowVerdict.get(v);
        if (memo != null) {
            return memo;
        }
        boolean verdict = isVerticalFlowUncached(v);
        sFlowVerdict.put(v, verdict);
        return verdict;
    }

    /** Per-instance verdicts of {@link #isVerticalFlow} (theme rebuilds make new instances). */
    private static final java.util.WeakHashMap<ViewGroup, Boolean> sFlowVerdict =
            new java.util.WeakHashMap<>();

    private static boolean isVerticalFlowUncached(@NonNull ViewGroup v) {
        if (v.getClass().getName().endsWith("GradientLinearLayout")) {
            try {
                java.lang.reflect.Method m = v.getClass()
                        .getMethod("getGradientOverlayDrawable");
                if (m.invoke(v) != null) {
                    AodLyricModule.i("skip gradient-composited container (SRC_IN overlay)");
                    return false;
                }
            } catch (Throwable t) {
                return false; // can't verify -> assume the overlay is present
            }
        }
        return true;
    }

    /**
     * Inserts {@code tv} into {@code flow} directly above/below {@code sibling}
     * (a direct child of {@code flow}), or - when {@code sibling} is null -
     * at the flow's first/last slot (the anchor itself is the container).
     *
     * <p>Idempotency is expressed RELATIVE to the sibling, not as an absolute
     * index: inserting the lyric shifts every following child down by one, so
     * an absolute-index check can never be satisfied twice and the per-second
     * kicker used to re-insert the view forever (above -&gt; below -&gt; above),
     * restoring/re-expanding the container heights every tick - the "whole AOD
     * flickers and shakes vertically when the lyric sits above the components"
     * bug.
     *
     * <p>Sizing is MATCH_PARENT/WRAP_CONTENT, margins start CLEAN (the anchor's
     * values must not leak in) and only the gap side of the slot is set - the
     * flow engine decides everything else, including pushing the siblings below
     * the slot further down.
     */
    private static boolean insertIntoFlowAt(@NonNull TextView tv, @NonNull ViewGroup flow,
            @Nullable android.view.View sibling, boolean above, int gap) {
        int curIndex = flow.indexOfChild(tv);
        int sibIndex = sibling == null ? -1 : flow.indexOfChild(sibling);
        boolean inPlace;
        if (sibIndex >= 0) {
            inPlace = curIndex == (above ? sibIndex - 1 : sibIndex + 1);
        } else if (curIndex >= 0) {
            inPlace = above ? curIndex == 0 : curIndex == flow.getChildCount() - 1;
        } else {
            inPlace = false;
        }
        if (inPlace && flowMarginsOk(tv, above, gap)) {
            return true; // already in place; kicker tick is a no-op
        }
        // Copy the params class of a neighbouring child so the flow container
        // accepts the view (LinearLayout requires its own LayoutParams type).
        android.view.View neighbour = sibling != null
                ? sibling
                : (flow.getChildCount() > 0 ? flow.getChildAt(0) : null);
        ViewGroup.LayoutParams lp = copyParamsFor(flow, neighbour);
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.setMargins(0, 0, 0, 0);
            if (above) {
                mlp.bottomMargin = gap; // gap between the lyric and the block below it
            } else {
                mlp.topMargin = gap;    // gap between the block above and the lyric
            }
        }
        // A copied weight would stretch the lyric across the flow's spare space.
        if (lp instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) lp).weight = 0f;
        }
        if (curIndex >= 0) {
            flow.removeView(tv);
        } else if (tv.getParent() instanceof ViewGroup) {
            ((ViewGroup) tv.getParent()).removeView(tv);
        }
        // Recompute the slot AFTER removing the lyric: if it was already in
        // this flow its removal shifted the sibling's index.
        int index;
        if (sibling != null && sibling.getParent() == flow) {
            int sib = flow.indexOfChild(sibling);
            index = above ? sib : sib + 1;
        } else {
            index = above ? 0 : flow.getChildCount();
        }
        index = Math.max(0, Math.min(index, flow.getChildCount()));
        flow.addView(tv, index, lp);
        applyFullWidth(tv);
        tv.setTranslationY(0f);
        AodLyricModule.i("lyric inserted into flow " + flow.getClass().getSimpleName()
                + " at index " + index + " (" + (above ? "above" : "below")
                + ", gap " + gap + "px)");
        relaxNextBlockMargin(flow, index, gap);
        relaxSubtreeClipping(flow);
        // Expand AFTER the first layout pass settles (the text may still
        // re-wrap to a second line on the next frame; tv.getHeight() right
        // after insert can be one line short of the final height).
        postOnMainDelayed(() -> expandFlowSpace(tv, flow), 400L);
        return true;
    }

    /** The delta the flow chain was last expanded by, 0 = none. */
    private static volatile int sExpandedDelta;

    /**
     * Kicker-tick budget check: if the lyric's occupied height grew since the
     * flow chain was expanded (text re-wrapped to more lines, style change),
     * grow the chain again. Cheap - a couple of getters per second.
     */
    private static void maybeExpandBudget(@NonNull TextView tv) {
        // Shadow mode: the AOD flow containers are invisible, nothing to grow.
        if (sShadowMode) {
            return;
        }
        // 沉浸模式：父容器是我们自己的画布，按"布局流"去撑高会把它撑变形。
        if (ImmersiveStage.active()) {
            return;
        }
        if (!treeModeActive() || !sActive
                || sLastText == null || sLastText.isEmpty()) {
            return;
        }
        android.view.ViewParent p = tv.getParent();
        if (!(p instanceof ViewGroup)) {
            return;
        }
        LyricStyle st = sStyle;
        int gap = st == null ? 0 : Math.max(0, (int) st.anchorGap);
        int needed = tv.getHeight() + gap;
        if (needed > sExpandedDelta) {
            expandFlowSpace(tv, (ViewGroup) p);
        }
    }

    /** Fixed-height containers we grew to make room for the lyric -> original px. */
    private static final java.util.HashMap<android.view.View, Integer> sExpandedHeights =
            new java.util.HashMap<>();

    /**
     * The theme's content column has a FIXED height; once the lyric occupies
     * layout space the components below it get squeezed by the shortfall
     * (the budget-style custom AOD containers hand the remaining space to
     * the last children, so the battery row shrank 60-&gt;28px and the
     * notification icons 138-&gt;0px - the "timedate is clipped below the
     * image" complaint). Grow every non-wrap-height container on the path
     * from the flow container up to (excluding) the window root by the
     * lyric's occupied height: fixed-px containers get +delta, MATCH_PARENT
     * containers get pinned to measuredHeight+delta so the budget grows.
     * The AOD window has empty room below the column; the theme's own
     * heights are restored by {@link #restoreExpandedHeights} when the lyric
     * leaves the flow.
     */
    private static void expandFlowSpace(@NonNull TextView tv, @NonNull ViewGroup flow) {
        LyricStyle st = sStyle;
        int gap = st == null ? 0 : Math.max(0, (int) st.anchorGap);
        int h = tv.getHeight();
        if (h <= 0) {
            return; // not laid out yet; a later re-insert retries
        }
        int delta = h + gap;
        // Already expanded by exactly this much on the SAME view tree: restore +
        // re-pin would run two layout passes and visibly bounce the content.
        // (A theme rebuild creates new instances - the root check lets the new
        // tree expand again.)
        if (delta == sExpandedDelta && !sExpandedHeights.isEmpty() && sameRoot(flow)) {
            return;
        }
        restoreExpandedHeights();
        if (tv.getParent() != flow || !sActive
                || sLastText == null || sLastText.isEmpty()) {
            return;
        }
        StringBuilder trace = new StringBuilder();
        android.view.View child = flow;
        android.view.ViewParent p = flow.getParent();
        while (p instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) p;
            // Never resize the window root view itself.
            if (!(g.getParent() instanceof ViewGroup)) {
                break;
            }
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                sExpandedHeights.put(child, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.height = child.getHeight() + delta;
                child.setLayoutParams(lp);
                trace.append(pinName(child)).append("(match->").append(lp.height)
                        .append(") ");
            } else if (lp != null && lp.height > 0) {
                sExpandedHeights.put(child, lp.height);
                lp.height += delta;
                child.setLayoutParams(lp);
                trace.append(pinName(child)).append("(+").append(delta).append(") ");
            }
            child = g;
            p = g.getParent();
        }
        if (!sExpandedHeights.isEmpty()) {
            sExpandedDelta = delta;
            AodLog.i("expanded lyric budget by " + delta + "px: " + trace);
        } else {
            AodLog.w("budget expand found no fixed/match ancestor to grow");
        }
    }

    /** True when the containers we resized still live in {@code flow}'s view tree. */
    private static boolean sameRoot(@NonNull ViewGroup flow) {
        try {
            android.view.View root = flow.getRootView();
            for (android.view.View v : sExpandedHeights.keySet()) {
                return v.getRootView() == root;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Short, loggable name for a resized container. */
    private static String pinName(@NonNull android.view.View v) {
        String cls = v.getClass().getSimpleName();
        int id = v.getId();
        if (id != 0 && id != -1) {
            try {
                String n = v.getResources().getResourceEntryName(id);
                if (n != null && !n.isEmpty()) {
                    cls = n;
                }
            } catch (Throwable ignored) {
            }
        }
        return cls;
    }

    /** Puts the theme's own container heights back (lyric left the flow). */
    private static void restoreExpandedHeights() {
        sExpandedDelta = 0;
        if (sExpandedHeights.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<android.view.View, Integer> e : sExpandedHeights.entrySet()) {
            try {
                ViewGroup.LayoutParams lp = e.getKey().getLayoutParams();
                if (lp != null) {
                    lp.height = e.getValue();
                    e.getKey().setLayoutParams(lp);
                }
            } catch (Throwable t) {
                AodLyricModule.w("restore expanded height failed: " + t);
            }
        }
        sExpandedHeights.clear();
        AodLog.i("restored expanded container heights");
    }

    /**
     * Relaxes clipping on the WHOLE AOD view tree containing {@code flow}.
     *
     * <p>Relaxing only the lyric's own ancestor chain is not enough once the
     * lyric occupies layout space: the flow re-measures and components below
     * it land OUTSIDE their containers. The pushed components can be several
     * sibling levels away from the lyric (e.g. the clock gradient block keeps
     * its fixed height while the battery row overflows its bottom edge), so
     * sibling subtrees must be covered too - walk up to the top of the view
     * tree and relax every container below it. Idempotent, and only run on an
     * actual (re-)insertion, never on the kicker no-op.
     */
    private static void relaxSubtreeClipping(@NonNull ViewGroup flow) {
        try {
            ViewGroup top = flow;
            android.view.ViewParent p = flow.getParent();
            while (p instanceof ViewGroup) {
                top = (ViewGroup) p;
                p = top.getParent();
            }
            relaxDescendants(top);
        } catch (Throwable t) {
            AodLyricModule.w("relax subtree clipping failed: " + t);
        }
    }

    /** Recursively disables child clipping on every container below {@code v}. */
    private static void relaxDescendants(@NonNull android.view.View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            for (int i = 0; i < g.getChildCount(); i++) {
                relaxDescendants(g.getChildAt(i));
            }
        }
    }

    /**
     * The block that held the lyric's facing margin before we collapsed it,
     * plus the original value - so the theme's own spacing is restored the
     * moment the lyric leaves the flow (idle guard / hide).
     */
    @Nullable
    private static android.view.View sMarginTouched;
    private static int sMarginOriginal;

    /**
     * Themes space their components with generous margins (the image->clock
     * block gap is ~87px); once the lyric occupies the slot in between, that
     * margin lands BELOW the lyric and reads as "lyric is too far from the
     * time components". Collapse the top margin of the component directly
     * under the lyric down to the user's gap (never enlarging it) so the lyric
     * hugs the next component; {@link #restoreTouchedMargin} puts the theme
     * value back when the lyric goes away.
     */
    private static void relaxNextBlockMargin(@NonNull ViewGroup flow, int lyricIndex, int gap) {
        restoreTouchedMargin();
        int ni = lyricIndex + 1; // the component directly under the lyric
        if (ni >= flow.getChildCount()) {
            return;
        }
        android.view.View n = flow.getChildAt(ni);
        ViewGroup.LayoutParams lp = n.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        if (mlp.topMargin > gap) {
            sMarginTouched = n;
            sMarginOriginal = mlp.topMargin;
            mlp.topMargin = gap;
            n.requestLayout();
            AodLyricModule.i("relaxed next block top margin " + sMarginOriginal
                    + " -> " + gap + "px (lyric-to-component gap)");
        }
    }

    /** Puts the theme's own margin back on the block we collapsed earlier. */
    private static void restoreTouchedMargin() {
        restoreExpandedHeights();
        android.view.View n = sMarginTouched;
        if (n == null) {
            return;
        }
        sMarginTouched = null;
        try {
            ViewGroup.LayoutParams lp = n.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams
                    && ((ViewGroup.MarginLayoutParams) lp).topMargin != sMarginOriginal) {
                ((ViewGroup.MarginLayoutParams) lp).topMargin = sMarginOriginal;
                n.requestLayout();
                AodLyricModule.i("restored next block top margin " + sMarginOriginal + "px");
            }
        } catch (Throwable t) {
            AodLyricModule.w("restore next block margin failed: " + t);
        }
    }

    /** True when the lyric's facing flow margin already matches the gap. */
    private static boolean flowMarginsOk(@NonNull android.view.View tv, boolean above, int gap) {
        ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return gap == 0;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        return above ? mlp.bottomMargin == gap : mlp.topMargin == gap;
    }

    /**
     * Anchor's top edge in the container's coordinate space: top + translationY
     * summed up the ancestor chain (translationY matters: clock_horizontal etc.
     * may carry their own drift offsets).
     *
     * @return the top edge, or {@code Float.MAX_VALUE} when the anchor is not a
     *     descendant of the container.
     */
    private static float anchorTopInContainer(@NonNull android.view.View anchor,
            @NonNull ViewGroup container) {
        float top = 0f;
        android.view.View v = anchor;
        while (v != container && v != null) {
            top += v.getTop() + v.getTranslationY();
            android.view.ViewParent p = v.getParent();
            v = (p instanceof android.view.View) ? (android.view.View) p : null;
        }
        return v == container ? top : Float.MAX_VALUE;
    }

    /**
     * Builds layout params the container will accept for the new lyric slot.
     *
     * <p>{@code generateLayoutParams}/{@code generateDefaultLayoutParams} are
     * protected, so from outside the container the practical route is the copy
     * constructor of the anchor block's params class (LinearLayout etc. define
     * {@code LayoutParams(ViewGroup.LayoutParams)}), falling back to a
     * MarginLayoutParams clone and finally the container's own defaults.
     */
    @NonNull
    private static ViewGroup.LayoutParams copyParamsFor(@NonNull ViewGroup container,
            @Nullable android.view.View anchorBlock) {
        ViewGroup.LayoutParams src = anchorBlock == null ? null : anchorBlock.getLayoutParams();
        if (src != null) {
            try {
                java.lang.reflect.Constructor<?> c = src.getClass()
                        .getConstructor(ViewGroup.LayoutParams.class);
                ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) c.newInstance(src);
                // Start from clean sizing; margins carry the anchor gap.
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                return lp;
            } catch (Throwable ignored) {
                // fall through to the margin-params clone
            }
            try {
                ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(src);
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                return lp;
            } catch (Throwable ignored) {
                // fall through to the container's own defaults
            }
        }
        try {
            java.lang.reflect.Method m = ViewGroup.class
                    .getDeclaredMethod("generateDefaultLayoutParams");
            m.setAccessible(true);
            ViewGroup.LayoutParams lp = (ViewGroup.LayoutParams) m.invoke(container);
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            return lp;
        } catch (Throwable ignored) {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * Anchor placement: find the anchor view inside the lyric's container and
     * set translationY so the lyric sits {@code anchorGap}px above/below it.
     *
     * @return true when the anchor was found and the offset applied; false
     * falls back to the legacy ratio so the lyric never disappears.
     */
    private static boolean applyAnchorOffset(@NonNull TextView tv, @NonNull ViewGroup container,
            @NonNull String anchorId) {
        android.view.View anchor = resolveAnchor(container, anchorId);
        if (anchor == null || anchor == tv) {
            return false;
        }
        // Convert the anchor's top edge into the container's coordinate space.
        float anchorTop = anchorTopInContainer(anchor, container);
        if (anchorTop == Float.MAX_VALUE) {
            return false;
        }
        int tvH = tv.getHeight() > 0 ? tv.getHeight() : tv.getMeasuredHeight();
        if (tvH <= 0) {
            return false;
        }
        float target = sStyle.anchorPos == 0
                ? anchorTop - sStyle.anchorGap - tvH
                : anchorTop + anchor.getHeight() + sStyle.anchorGap;
        if (Math.abs(target - tv.getTranslationY()) < 0.5f) {
            return true; // already in place; avoid pointless invalidations
        }
        tv.setTranslationY(target);
        return true;
    }

    // ---- coarse anchor model ------------------------------------------------
    // The anchor picker offers exactly 4 regions (2026-11-15 user request):
    // big clock, image, time/date components, notifications. Everything below
    // is internal mapping: canonical ids resolve through per-theme candidate
    // lists, legacy fine-grained ids map to their nearest coarse region so old
    // configurations keep working. The id table itself lives in AnchorIds.

    /** Picker display order (kept here for readability of the call sites). */
    /**
     * Resolves any anchor id (canonical 4-region or legacy fine-grained) to a
     * concrete view in the current theme.
     */
    @Nullable
    private static android.view.View resolveAnchor(@NonNull ViewGroup root,
            @NonNull String anchorId) {
        // Canonical region id: pick the first candidate this theme actually has.
        String[] cands = canonicalCandidates(anchorId);
        if (cands != null) {
            for (String cand : cands) {
                android.view.View v = findViewByEntryName(root, cand, 0);
                if (v != null) {
                    return v;
                }
            }
            // Theme lacks every known candidate: try the id itself unchanged.
            return findViewByEntryName(root, anchorId, 0);
        }
        // Legacy fine-grained id: map to its nearest coarse region first.
        String canonical = legacyToCanonical(anchorId);
        if (canonical != null) {
            for (String cand : canonicalCandidates(canonical)) {
                android.view.View v = findViewByEntryName(root, cand, 0);
                if (v != null) {
                    AodLyricModule.i("legacy anchor '" + anchorId
                            + "' mapped to region '" + canonical
                            + "' via " + cand);
                    return v;
                }
            }
        }
        return findViewByEntryName(root, anchorId, 0);
    }

    /** DFS for a view whose resource entry name matches; skips the lyric itself. */
    @Nullable
    private static android.view.View findViewByEntryName(@NonNull ViewGroup parent,
            @NonNull String entryName, int depth) {
        if (depth > 8) {
            return null;
        }
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            android.view.View c = parent.getChildAt(i);
            if (c == null) {
                continue;
            }
            if (c instanceof LyricTextView) {
                continue;
            }
            try {
                if (c.getId() != android.view.View.NO_ID
                        && entryName.equals(c.getResources().getResourceEntryName(c.getId()))) {
                    return c;
                }
            } catch (Throwable ignored) {
            }
            if (c instanceof ViewGroup) {
                android.view.View found = findViewByEntryName((ViewGroup) c, entryName, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The stored hook-process reporter, or null in the app process. */
    @Nullable
    static ModuleReporter reporter() {
        ModuleReporter r = Reporter.access();
        return r;
    }

    /** True when the lyric is currently parented to the movable container. */
    public static boolean isFollowingClock() {
        return sInClockContainer;
    }

    /** The view's current parent, or the remembered clock container. */
    @Nullable
    private static ViewGroup currentParent(@NonNull TextView tv) {
        android.view.ViewParent p = tv.getParent();
        if (p instanceof ViewGroup) {
            return (ViewGroup) p;
        }
        java.lang.ref.WeakReference<ViewGroup> ref = sClockContainer;
        return ref == null ? null : ref.get();
    }

    /**
     * 把歌词放回普通模式的挂载点：优先时钟容器（跟随 MIUI 的防烧屏漂移），
     * 找不到就退回 AOD 根。由 {@link ImmersiveStage#release} 在退出沉浸时调用；
     * 必须放在这里，因为挂载点的解析逻辑属于本类。
     */
    static void reparentHome(@NonNull ViewGroup root, @NonNull TextView tv) {
        try {
            android.view.ViewParent p = tv.getParent();
            if (p instanceof ViewGroup) {
                ((ViewGroup) p).removeView(tv);
            }
            ViewGroup host = findClockContainer(root);
            if (host == null) {
                host = root;
            } else {
                sInClockContainer = true;
                sClockContainer = new java.lang.ref.WeakReference<>(host);
            }
            host.addView(tv, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            applyFullWidth(tv);
            AodLyricModule.i("lyric reparented home to "
                    + host.getClass().getSimpleName());
        } catch (Throwable t) {
            AodLyricModule.w("reparentHome failed: " + t);
        }
    }

    private void detach() {
        stopRootMotion();
        stopPulse();
        stopKicker();
        setPanelHold(false);
        ImmersiveStage.reset();
        sRootRef = null;
        sLastText = null;
        sLastSpanned = null;
        sLastSignature = null;
        sActive = false;
        sPaused = false;
        sContentAt = 0L;
        sShadowMode = false;
        sHiddenStreak = 0;
        // The next AOD session must not resolve placement through a container
        // that belongs to the window we are leaving.
        sClockContainer = null;
        sInClockContainer = false;

        TextView tv = sView.getAndSet(null);
        sAodVisible = false;
        if (tv != null) {
            try {
                android.view.ViewParent p = tv.getParent();
                if (p instanceof ViewGroup) {
                    ((ViewGroup) p).removeView(tv);
                }
                AodLyricModule.i("lyric overlay detached");
            } catch (Throwable t) {
                AodLyricModule.w("lyric overlay detach failed: " + t);
            }
        }
        // Nothing from this session may survive: any view the registry still
        // knows about is a stray by definition now.
        removeStrayViews(null);
    }

    /** Last text applied, so the log only reports real changes. */
    @Nullable
    private static volatile String sLastText;

    /** Composed (multi-line, spanned) content currently on screen. */
    @Nullable
    private static volatile CharSequence sLastSpanned;

    /** Change marker of the last rendered {@link LyricDisplay}. */
    @Nullable
    private static volatile String sLastSignature;

    /**
     * True only while a real lyric line is on screen. Everything that costs
     * power or perturbs the doze state machine (pulses, kicker work) is gated on
     * it, so a paused/stopped player leaves the AOD completely untouched.
     */
    private static volatile boolean sActive;

    /**
     * True while the visible line is a paused hold (dimmed). The kicker uses
     * it to keep the refresh hold released while nothing advances.
     */
    private static volatile boolean sPaused;

    /** Current appearance settings, pushed from the app process. */
    @NonNull
    private static volatile LyricStyle sStyle = new LyricStyle();

    /** Applies appearance settings to a lyric view. */
    static void applyStyle(@NonNull TextView tv, @NonNull LyricStyle style) {
        tv.setTextSize(style.textSizeSp);
        tv.setTextColor(style.textColor);
        tv.setTypeface(resolveTypeface(tv, style));
        // The old text glow was removed (2026-11-18): on the black AOD panel
        // it never produced a visible effect, so the UI option confused more
        // than it helped. Clear any layer a stale style may have applied.
        tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        tv.setGravity(switch (style.align) {
            case 1 -> Gravity.CENTER_VERTICAL | Gravity.START;
            case 2 -> Gravity.CENTER_VERTICAL | Gravity.END;
            default -> Gravity.CENTER_HORIZONTAL;
        });
        if (tv instanceof LyricTextView) {
            ((LyricTextView) tv).setStroke(style.strokeWidth, style.strokeColor);
            ((LyricTextView) tv).setLyricBackground(
                    style.bgEnabled, style.bgColor, style.bgAlpha,
                    style.resolvedBgRadiusDp());
        }
        tv.setTranslationY(0f);
    }

    /**
     * Typeface for the style: system selections resolve by name, the custom
     * font loads through the provider (falling back to default+weight when
     * nothing is imported).
     */
    @NonNull
    private static Typeface resolveTypeface(@NonNull TextView tv, @NonNull LyricStyle style) {
        Typeface base = style.isCustomFont()
                ? CustomFontLoader.load(tv.getContext())
                : style.baseTypeface();
        if (base == null) {
            base = Typeface.DEFAULT;
        }
        return style.styled(base);
    }

    /** Called by the poller when the app pushes new appearance settings. */
    public static void setStyle(@Nullable android.os.Bundle styleBundle) {
        LyricStyle style = LyricStyle.fromBundle(styleBundle);
        sStyle = style;
        if (style.isCustomFont()) {
            // Re-imports overwrite the same file: drop the cache so the new
            // font actually shows up on the next apply.
            CustomFontLoader.invalidate();
        }
        TextView tv = sView.get();
        if (tv != null) {
            postOnMain(() -> {
                // Ignore style pushes aimed at a view from a closed session.
                if (sView.get() != tv) {
                    return;
                }
                applyStyle(tv, style);
                ViewGroup root = sRootRef == null ? null : sRootRef.get();
                if (root != null) {
                    // 沉浸开关可能刚被切换：先同步舞台，再定位。
                    ImmersiveStage.sync(root, tv, style);
                    applyOffset(tv, root);
                }
                AodLyricModule.i("lyric style applied: size=" + style.textSizeSp
                        + " color=#" + Integer.toHexString(style.textColor)
                        + " typeface=" + style.typefaceName
                        + " stroke=" + style.strokeWidth
                        + " anchor=" + (style.anchorId == null || style.anchorId.isEmpty()
                        ? "<ratio>" : style.anchorId + "/" + style.anchorPos));
            });
        }
    }

    /** The AOD clock container: whatever it translates, we translate with. */
    @Nullable
    private static volatile java.lang.ref.WeakReference<ViewGroup> sClockContainer;

    /**
     * Finds {@code R.id.clock_container} inside the AOD view tree.
     *
     * <p>AODUpdatePositionController#updatePosition() moves that container to
     * drift the clock against burn-in; parenting our view to it makes the lyric a
     * real part of the AOD clock component instead of a fixed overlay.
     */
    /**
     * Finds the movable AOD container by walking the tree and comparing resource
     * entry names.
     *
     * <p>{@code getIdentifier(name, "id", "com.miui.aod")} returns 0 here even
     * though the view exists - the id is declared in a package other than the one
     * guessed. {@code getResourceEntryName()} resolves it correctly (the tree dump
     * relies on exactly that), so matching by name during a traversal is the
     * reliable route.
     */
    @Nullable
    private static ViewGroup findClockContainer(@NonNull ViewGroup root) {
        ViewGroup found = findGroupByName(root, "clock_container", 6);
        if (found != null) {
            AodLyricModule.i("found clock_container -> " + found.getClass().getName());
            return found;
        }
        ViewGroup anchor = findByClockAnchor(root);
        if (anchor != null) {
            AodLyricModule.i("container located via clock anchor -> "
                    + anchor.getClass().getName());
            return anchor;
        }
        return null;
    }

    /** Depth-limited traversal matching a view's resource entry name. */
    @Nullable
    private static ViewGroup findGroupByName(@NonNull ViewGroup root,
                                             @NonNull String name,
                                             int maxDepth) {
        try {
            java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
            queue.add(new Object[]{root, 0});
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 600) {
                Object[] item = queue.poll();
                ViewGroup g = (ViewGroup) item[0];
                int depth = (Integer) item[1];
                if (depth > maxDepth) {
                    continue;
                }
                for (int i = 0; i < g.getChildCount(); i++) {
                    android.view.View c = g.getChildAt(i);
                    if (c == null) {
                        continue;
                    }
                    if (name.equals(entryName(c)) && c instanceof ViewGroup) {
                        return (ViewGroup) c;
                    }
                    if (c instanceof ViewGroup) {
                        queue.add(new Object[]{c, depth + 1});
                    }
                }
            }
        } catch (Throwable t) {
            AodLyricModule.w("findGroupByName failed: " + t);
        }
        return null;
    }

    /** Resource entry name of a view, or null. */
    @Nullable
    private static String entryName(@NonNull android.view.View v) {
        try {
            if (v.getId() == android.view.View.NO_ID) {
                return null;
            }
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Finds the movable container without relying on a specific resource id.
     *
     * <p>Walks the tree for a view whose resource name is "clock" and returns its
     * nearest ancestor that is a direct child of the AOD root - that ancestor is
     * what AODUpdatePositionController shifts.
     */
    @Nullable
    private static ViewGroup findByClockAnchor(@NonNull ViewGroup root) {
        try {
            java.util.ArrayDeque<android.view.View> queue = new java.util.ArrayDeque<>();
            queue.add(root);
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 400) {
                android.view.View v = queue.poll();
                if (v == null) {
                    continue;
                }
                if (nameOf(v, "clock") != null) {
                    android.view.ViewParent p = v.getParent();
                    ViewGroup best = null;
                    while (p instanceof ViewGroup) {
                        ViewGroup g = (ViewGroup) p;
                        if (g == root) {
                            break;
                        }
                        best = g;
                        p = g.getParent();
                    }
                    if (best != null) {
                        return best;
                    }
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        queue.add(g.getChildAt(i));
                    }
                }
            }
        } catch (Throwable t) {
            AodLyricModule.w("clock anchor search failed: " + t);
        }
        return null;
    }

    @Nullable
    private static String nameOf(@NonNull android.view.View v, @NonNull String expect) {
        try {
            if (v.getId() == android.view.View.NO_ID) {
                return null;
            }
            String name = v.getResources().getResourceEntryName(v.getId());
            return expect.equals(name) ? name : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Logs the AOD view tree so the real container can be identified. */
    private static void dumpTree(@NonNull ViewGroup root, int depth) {
        if (depth > 6) {
            return;
        }
        try {
            int count = root.getChildCount();
            for (int i = 0; i < count && i < 24; i++) {
                android.view.View c = root.getChildAt(i);
                if (c == null) {
                    continue;
                }
                String name = "?";
                try {
                    if (c.getId() != android.view.View.NO_ID) {
                        name = c.getResources().getResourceEntryName(c.getId());
                    }
                } catch (Throwable ignored) {
                }
                // Bounds in the parent's coordinate space + visibility, so the
                // app UI can offer "place lyric above/below <component>".
                AodLyricModule.i("AOD tree " + "  ".repeat(depth) + c.getClass().getSimpleName()
                        + " id=" + name
                        + " bounds=[" + c.getLeft() + "," + c.getTop()
                        + " " + c.getWidth() + "x" + c.getHeight() + "]"
                        + " vis=" + (c.getVisibility() == android.view.View.VISIBLE ? "V" : "H")
                        + " alpha=" + String.format("%.2f", c.getAlpha()));
                if (c instanceof ViewGroup) {
                    dumpTree((ViewGroup) c, depth + 1);
                }
            }
        } catch (Throwable t) {
            AodLyricModule.w("tree dump failed: " + t);
        }
    }

    @NonNull
    private static ViewGroup resolveClockContainer(@NonNull ViewGroup root) {
        ViewGroup found = findClockContainer(root);
        if (found != null) {
            sInClockContainer = true;
            AodLyricModule.i("lyric parented to clock_container (moves with AOD)");
            return found;
        }
        // Normal on first attach: the container may not be laid out yet.
        // scheduleReparent() retries and moves the view once it appears.
        AodLyricModule.i("clock_container not found yet; will reparent when it appears");
        return root;
    }

    /** True while the lyric is parented to the real clock container. */
    private static volatile boolean sInClockContainer;

    /**
     * Full-screen AOD ("与锁屏界面一致") shadow mode: the host fades the whole
     * MIUIAod content subtree to alpha 0 and the keyguard draws the lock
     * screen inside the NotificationShade window instead. The lyric must then
     * be re-parented onto the keyguard AodView root (alpha 1, visible) and
     * positioned below the VISIBLE keyguard clock - the AOD component tree
     * (and every anchor inside it) is invisible in this mode.
     */
    private static volatile boolean sShadowMode;

    /** Consecutive kicker ticks that saw the AOD content faded out. */
    private static int sHiddenStreak;

    /** Guards the one-shot diagnostic tree dump. */
    private static volatile boolean sDumpedTree;

    /**
     * Moves the lyric into {@code clock_container} once the theme has inflated.
     *
     * <p>AODUpdatePositionController translates that container for anti-burn-in
     * drift; only a child of it moves with the clock. Attaching before inflation
     * (the normal case) lands on the root, so this is retried a few times.
     */
    private static void scheduleReparent(@NonNull ViewGroup root, long delayMs) {
        try {
            root.postDelayed(() -> {
                // A reparent scheduled by a previous AOD window must not pull
                // the new session's view into the old (or a dead) container.
                if (sRootRef == null || sRootRef.get() != root) {
                    return;
                }
                TextView tv = sView.get();
                if (tv == null || sInClockContainer || sShadowMode
                        || ImmersiveStage.active()) {
                    return;
                }
                try {
                    ViewGroup container = findClockContainer(root);
                    if (container == null) {
                        // Only diagnose on the final attempt, and only once.
                        if (delayMs >= 10000 && !sDumpedTree) {
                            sDumpedTree = true;
                            AodLyricModule.w("clock_container still missing after 10s; dumping tree");
                            dumpTree(root, 0);
                        }
                        return;
                    }
                    android.view.ViewParent current = tv.getParent();
                    if (current == container) {
                        sInClockContainer = true;
                        return;
                    }
                    if (current instanceof ViewGroup) {
                        ((ViewGroup) current).removeView(tv);
                    }
                    container.addView(tv, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    sClockContainer = new java.lang.ref.WeakReference<>(container);
                    sInClockContainer = true;
                    applyOffset(tv, root);
                    AodLyricModule.i("lyric moved into clock_container (now follows AOD drift)");
                } catch (Throwable t) {
                    AodLyricModule.w("reparent failed: " + t);
                }
            }, delayMs);
        } catch (Throwable t) {
            AodLyricModule.w("reparent schedule failed: " + t);
        }
    }

    /** True once the AOD container touch hook is installed in this process. */
    private static volatile boolean sTouchWatchInstalled;

    /**
     * Records every touch on the AOD container, before the screen lights up.
     *
     * <p>{@code PowerManager.isInteractive()} only turns true after the wake-up
     * has already started, so it reacted too late and a pulse could still collide
     * with the fingerprint handshake. The container's dispatchTouchEvent fires on
     * the very first touch-down, giving us a head start.
     */
    private static void installTouchWatch(@NonNull ViewGroup root) {
        if (sTouchWatchInstalled) {
            return;
        }
        sTouchWatchInstalled = true;
        final Class<?> aodViewClass = root.getClass();
        try {
            // AodView does not declare dispatchTouchEvent itself - it inherits it
            // from ViewGroup. Walk the hierarchy to find the declaring class
            // instead of only looking at the concrete type.
            java.lang.reflect.Method dispatch = null;
            for (Class<?> c = aodViewClass; c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if ("dispatchTouchEvent".equals(m.getName())
                            && m.getParameterTypes().length == 1
                            && android.view.MotionEvent.class.equals(m.getParameterTypes()[0])) {
                        dispatch = m;
                        break;
                    }
                }
                if (dispatch != null) {
                    break;
                }
            }
            if (dispatch == null) {
                AodLyricModule.w("dispatchTouchEvent not found on "
                        + aodViewClass.getName() + " or its superclasses");
                return;
            }
            dispatch.setAccessible(true);
            AodLyricModule.buildHook(sXp, dispatch, "aodlyric-touch-watch")
                    .intercept(chain -> {
                        // Only the AOD container matters; every other view in the
                        // process passes straight through (one cheap type check).
                        Object self = chain.getThisObject();
                        if (self != null && aodViewClass.isInstance(self)) {
                            markTouched();
                            Object evArg = chain.getArgs().get(0);
                            if (evArg instanceof android.view.MotionEvent) {
                                watchDoubleTap((android.view.MotionEvent) evArg,
                                        (android.view.View) self);
                            }
                        }
                        return chain.proceed();
                    });
            AodLyricModule.i("AOD touch watch installed (declared by "
                    + dispatch.getDeclaringClass().getName() + ")");
        } catch (Throwable t) {
            AodLyricModule.w("AOD touch watch failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /** Called on the earliest touch signal; suppresses pulses from then on. */
    private static void markTouched() {
        sLastInteractiveAt = android.os.SystemClock.elapsedRealtime();
        sTouched = true;
        // 黑幕会挡住屏下指纹：触摸时先把整屏让出来。
        ImmersiveStage.onTouch();
        if (sPulseJustFired) {
            // A pulse was in flight when the touch arrived - log it, this is the
            // collision that used to crash the AOD UI.
            AodLyricModule.w("touch arrived while a doze pulse was in flight");
            sPulseJustFired = false;
        }
    }

    private static volatile boolean sTouched;
    private static volatile boolean sPulseJustFired;

    // ---- module-side double-tap wake ---------------------------------------

    /**
     * Why this exists: MIUI feeds its {@code DoubleTapHelper} from
     * {@code DozeHost.onTouch} only when all three of
     * {@code mSupportGestureWakeup}, {@code SettingsHolder.mOpenDoubleTapGoToSleep}
     * (Settings.System "gesture_wakeup") and
     * {@code AODSettings.needKeepScreenOnAtFirst()} hold. The third follows the
     * AOD STYLE and is false for custom styles - measured on-device: the taps
     * DO reach the AOD window ({@code DozeHost: onTouch:0/1} x N) while
     * {@code CommonUtils.requestWakeUp} is never called and PowerManager never
     * logs a {@code NODOZE} wake. The gate is closed, not the touch path.
     *
     * <p>So recognise the double tap ourselves, in the already-installed AOD
     * container touch hook, and reuse {@link ThemeProbeV2#doubleTapWake} (which
     * handles the "pulse is in flight, wakeUp is swallowed" case with a retry).
     * This also explains the fingerprint correlation the user observed: with a
     * fingerprint enrolled MIUI keeps an independent touch-to-wake path alive
     * for the under-display sensor, so double taps woke the screen by that
     * route with high probability; without one, only the gated path existed.
     *
     * <p>IMPORTANT: we replace ONLY the style-dependent third term. The user's
     * system setting ({@code gesture_wakeup}) is still honoured - see
     * {@link #systemDoubleTapWakeEnabled}.
     */
    private static long sTapDownAt;
    private static float sTapDownX, sTapDownY;
    private static long sLastTapAt;
    private static float sLastTapX, sLastTapY;
    /** Last time the touch-arrival diagnostic was logged (ms, uptime). */
    private static long sTouchDiagAt;

    private static void watchDoubleTap(@NonNull android.view.MotionEvent ev,
                                       @NonNull android.view.View view) {
        final int action = ev.getActionMasked();
        final long now = android.os.SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            // Diagnostic: proves whether touches still reach the AOD container
            // while we hold the panel in DOZE. Throttled to one line per second
            // so a normal tap gesture cannot flood the log.
            if (ThemeProbeV2.isPanelAwake() && now - sTouchDiagAt > 1000L) {
                sTouchDiagAt = now;
                AodLyricModule.i("AOD touch down while holding DOZE ("
                        + (int) ev.getX() + "," + (int) ev.getY() + ")");
            }
            sTapDownAt = now;
            sTapDownX = ev.getX();
            sTapDownY = ev.getY();
            return;
        }
        if (action != android.view.MotionEvent.ACTION_UP) {
            if (action == android.view.MotionEvent.ACTION_CANCEL
                    || action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                sTapDownAt = 0;
                sLastTapAt = 0;
            }
            return;
        }
        boolean quick = sTapDownAt != 0 && now - sTapDownAt < 300L;
        boolean still = Math.abs(ev.getX() - sTapDownX)
                + Math.abs(ev.getY() - sTapDownY) < 80f;
        sTapDownAt = 0;
        if (!quick || !still) {
            sLastTapAt = 0;
            return;
        }
        boolean near = now - sLastTapAt < 300L
                && Math.abs(ev.getX() - sLastTapX)
                        + Math.abs(ev.getY() - sLastTapY) < 160f;
        if (!near) {
            sLastTapAt = now;
            sLastTapX = ev.getX();
            sLastTapY = ev.getY();
            return;
        }
        sLastTapAt = 0;
        android.content.Context ctx = view.getContext();
        if (ctx == null) {
            return;
        }
        // Never override the user's own choice: MIUI's gate reads
        // Settings.System "gesture_wakeup" (com.miui.aod.doze.SettingsHolder).
        // The module only replaces the style-dependent third term
        // (needKeepScreenOnAtFirst), so with double-tap-to-wake OFF in system
        // settings this detector must stay silent.
        if (!systemDoubleTapWakeEnabled(ctx)) {
            AodLyricModule.i("module double-tap ignored: gesture_wakeup is off");
            return;
        }
        AodLyricModule.i("module double-tap recognised on AOD touch");
        ThemeProbeV2.doubleTapWake(ctx.getApplicationContext());
    }

    /**
     * True when the user has double-tap-to-wake enabled in system settings.
     *
     * <p>This is the exact key MIUI's AOD reads for its own double-tap gate
     * ({@code Settings.System["gesture_wakeup"]}, see
     * {@code com.miui.aod.doze.SettingsHolder}). Reading it per gesture is a
     * cheap binder call and picks up a toggle immediately.
     *
     * <p>Fails closed: if the value cannot be read we do not wake, so a broken
     * read can never light the screen against the user's setting.
     */
    private static boolean systemDoubleTapWakeEnabled(
            @NonNull android.content.Context ctx) {
        try {
            return android.provider.Settings.System.getInt(
                    ctx.getContentResolver(), "gesture_wakeup", 0) != 0;
        } catch (Throwable t) {
            AodLyricModule.w("cannot read gesture_wakeup: " + t);
            return false;
        }
    }

    /** Removes the overlay from the AOD while nothing is playing. */
    private static void hideOverlay(@NonNull TextView tv) {
        try {
            // The style/layout restore touches real views: it MUST happen on
            // the host main thread (setLayoutParams from the poll thread threw
            // CalledFromWrongThread and left the containers expanded).
            postOnMain(() -> {
                // An idle update queued by an old session must never touch the
                // view that now belongs to a newer AOD window.
                if (sView.get() != tv) {
                    removeStrayViews(null);
                    return;
                }
                restoreTouchedMargin();
                tv.setText("");
                if (ImmersiveStage.active()) {
                    // 沉浸模式：舞台留着，只让出整屏（否则时钟/指纹一直看不到）。
                    // 视图不能从画布里摘掉，否则下一句回来时得重建整条链路。
                    ImmersiveStage.setContentVisible(false);
                    removeStrayViews(tv);
                    return;
                }
                android.view.ViewParent parent = tv.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(tv);
                }
                removeStrayViews(tv);
            });
        } catch (Throwable t) {
            AodLyricModule.w("hideOverlay failed: " + t);
        }
    }

    /** Applies text and requests a repaint. */
    /**
     * Posts a runnable on the main thread, WITHOUT going through
     * {@link View#post}: a detached view queues the runnable into the attach
     * run-queue where it never executes until the view is re-attached - and
     * re-attaching is exactly what these runnables are supposed to do. That
     * deadlock is what kept the lyric invisible after the idle pull-out.
     */
    private static void postOnMain(@NonNull Runnable r) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    /** Like {@link #postOnMain}, but always deferred by {@code delayMs}. */
    private static void postOnMainDelayed(@NonNull Runnable r, long delayMs) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(r, delayMs);
    }

    /** Separator between the original line and its translation in sLastText. */
    private static final char TRANSLATION_SEP = '\n';

    /**
     * Builds the whole overlay content from a {@link LyricDisplay}.
     *
     * <p>The overlay is a single TextView (the AOD layout, anchor offsets and
     * the budget-expansion logic all key off one view), so every extra line -
     * track info, translation, next line, progress - is composed into one
     * Spannable with its own size/colour. That keeps the placement logic and
     * the pulse/kicker machinery untouched.
     */
    @NonNull
    private static CharSequence compose(@NonNull TextView tv, @NonNull LyricDisplay d) {
        LyricStyle style = sStyle;
        float baseSp = style == null ? tv.getTextSize() : style.textSizeSp;
        int baseColor = style == null ? tv.getCurrentTextColor() : style.textColor;
        float density = tv.getResources().getDisplayMetrics().scaledDensity;

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();

        boolean showTrack = style != null && style.showTrack;
        if (showTrack) {
            String track = trackLine(d);
            if (track != null && !track.isEmpty()) {
                appendPart(sb, track, baseSp * 0.58f, baseColor, 0.55f, density, false);
            }
        }

        appendPart(sb, d.line == null ? "" : d.line, baseSp, baseColor, 1f, density,
                sb.length() > 0);

        if (style != null && style.showTranslation) {
            String tr = d.translation;
            if (tr != null && !tr.trim().isEmpty() && !tr.equals(d.line)) {
                appendPart(sb, tr, Math.max(10f, baseSp * 0.62f), baseColor, 0.70f, density, true);
            }
        }
        if (style != null && style.showNext) {
            String next = d.next;
            if (next != null && !next.trim().isEmpty() && !next.equals(d.line)) {
                appendPart(sb, next, Math.max(10f, baseSp * 0.60f), baseColor, 0.45f, density, true);
            }
        }
        if (style != null && style.showProgress && d.positionMs >= 0
                && (d.durationMs > 0 || d.positionMs > 0)) {
            // Duration is optional: an old transport may only give a position,
            // and showing "0:42" beats showing nothing at all.
            String progress = d.durationMs > 0
                    ? LyricDisplay.time(d.positionMs) + " / " + LyricDisplay.time(d.durationMs)
                    : LyricDisplay.time(d.positionMs);
            if (d.paused) {
                progress = progress + "  ⏸";
            }
            appendPart(sb, progress, Math.max(9f, baseSp * 0.52f), baseColor, 0.50f, density, true);
        }
        return sb;
    }

    /** "title - artist" trimmed for one line, or null when nothing is known. */
    @Nullable
    private static String trackLine(@NonNull LyricDisplay d) {
        String title = d.title == null ? "" : d.title.trim();
        String artist = d.artist == null ? "" : d.artist.trim();
        if (title.isEmpty() && artist.isEmpty()) {
            return null;
        }
        if (title.isEmpty()) {
            return artist;
        }
        if (artist.isEmpty() || artist.equals(title)) {
            return title;
        }
        // Some players publish the ARTIST tag as "artist - title"; showing
        // "artist - title - title" would be embarrassing.
        if (artist.contains(title)) {
            return artist;
        }
        return artist + " - " + title;
    }

    /**
     * Appends one line with its own size/colour. {@code alpha} is applied on
     * top of the base colour so every secondary line keeps the user's hue.
     */
    private static void appendPart(@NonNull android.text.SpannableStringBuilder sb,
                                   @NonNull String text, float sizeSp, int baseColor,
                                   float alpha, float density, boolean newLine) {
        if (newLine) {
            sb.append(TRANSLATION_SEP);
        }
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        if (end <= start) {
            return;
        }
        int color = (baseColor & 0x00FFFFFF) | (Math.round(alpha * 255f) << 24);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(Math.round(sizeSp * density)),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // Secondary lines must not inherit the pill background of the main
        // line; LyricTextView draws that itself, so nothing to do here.
    }

    private static void applyText(@NonNull TextView tv, @NonNull CharSequence content) {
        try {
            postOnMain(() -> {
                // CRITICAL: a runnable posted by the poller for the PREVIOUS
                // AOD session must not re-attach its old view into the new
                // session. currentParent() resolves the static clock container
                // at execution time, so without this guard the old view lands
                // in the new tree as a frozen, misplaced second lyric.
                if (sView.get() != tv) {
                    AodLyricModule.w("dropped stale overlay update (old AOD session)");
                    return;
                }
                removeStrayViews(tv);
                tv.setText(content);
                if (ImmersiveStage.active()) {
                    // 有歌词了：舞台重新盖上来（无歌词时它会让出整屏）。
                    ImmersiveStage.ensureAttached(tv);
                    ImmersiveStage.setContentVisible(true);
                }
                java.lang.ref.WeakReference<ViewGroup> rootRef = sRootRef;
                // Re-attach after hideOverlay() removed us.
                ViewGroup parent = currentParent(tv);
                if (tv.getParent() == null && parent != null) {
                    parent.addView(tv, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    // Restore the placement (tree slot, anchor offset or ratio);
                    // the default params above may have dropped gap margins.
                    ViewGroup rootAfter = rootRef == null ? null : rootRef.get();
                    if (rootAfter != null) {
                        applyOffset(tv, rootAfter);
                    }
                    // The default MATCH_PARENT params above also reset the
                    // full-screen width; re-assert it in the same pass.
                    applyFullWidth(tv);
                }
                // A plain redraw never reaches the panel: the AOD caches its
                // composition buffer and only re-captures after a STRUCTURAL
                // change. Re-inserting the view forces measure+layout on the
                // window, which is what makes the new line appear.
                // Tree mode already owns a layout slot: detach/attach there
                // rebuilt the slot (and made the whole AOD jump), and the
                // ticking progress row would now do it every second.
                if (!treeModeActive()) {
                    reinflate(tv);
                }
                ensureFront(tv);
                tv.requestLayout();
                tv.invalidate();
                ViewGroup owner = currentParent(tv);
                if (owner != null) {
                    owner.requestLayout();
                    owner.invalidate();
                    forceTraversal(owner);
                }
            });
        } catch (Throwable t) {
            AodLyricModule.w("applyText failed: " + t);
        }
    }

    /** Watchdog that keeps the overlay inside the AOD's redraw path. */
    @Nullable
    private static volatile java.util.concurrent.ScheduledExecutorService sKicker;

    private static final java.util.concurrent.atomic.AtomicInteger sTick =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Thread-safe text update; posts to the view's own thread.
     *
     * <p>MIUI's AOD switches to a low-power static mode a few seconds after the
     * screen settles and stops honouring普通 View redraws. The text is still
     * applied here, and {@link #startKicker(ViewGroup, TextView)} keeps nudging
     * the view so the new text actually reaches the display.
     */
    public static void setText(@Nullable String line) {
        setText(line, null);
    }

    /**
     * Thread-safe text update with an optional translation line.
     *
     * <p>When the style has "show translation" enabled and the publisher
     * supplies a translation, the overlay renders both: the original on the
     * main line and the translation under it, in a smaller, dimmer style.
     * Without a translation the original line is shown alone, so enabling the
     * option never hides anything.
     */
    public static void setText(@Nullable String line, @Nullable String translation) {
        LyricDisplay d = new LyricDisplay();
        d.line = line;
        d.translation = translation;
        // No playback metadata on this path: the style's extra lines simply
        // render nothing, which keeps legacy callers working unchanged.
        setLyric(d);
    }

    /**
     * Renders one line plus everything the style decorates it with.
     *
     * <p>State machine (unchanged from the single-line version, extended for
     * pause handling):
     * <ul>
     *   <li>no line and not paused-with-keep -&gt; idle: clear, hide, no pulses;</li>
     *   <li>paused and {@code pauseKeep} -&gt; keep the line, dim it, stop
     *       pulsing (a paused screen must not burn power);</li>
     *   <li>playing -&gt; full opacity, pulse for the panel, render.</li>
     * </ul>
     */
    public static void setLyric(@Nullable LyricDisplay display) {
        TextView tv = sView.get();
        if (tv == null || !sAodVisible) {
            return;
        }
        LyricDisplay d = display == null ? new LyricDisplay() : display;
        LyricStyle style = sStyle;
        boolean pausedKeep = d.paused && style != null && style.pauseKeep && d.hasLine();

        if (!d.hasLine() && !pausedKeep) {
            final boolean wasActive = sActive;
            if (sLastText != null && !sLastText.isEmpty()) {
                AodLyricModule.i("overlay idle (no lyric)");
            }
            sLastText = "";
            sLastSpanned = null;
            sLastSignature = null;
            sActive = false;
            sPaused = false;
            // Nothing plays: drop the continuous-refresh hold so the panel can
            // go back to its low-power once-a-minute cadence.
            setPanelHold(false);
            // One pulse on the active->idle transition: the panel only
            // re-renders on a pulse, and the NATIVE theme's own lyric pills
            // must re-query the (now empty) lyric to clear the ghost line -
            // without this the last line stayed frozen on photo/full-screen
            // AOD themes after playback stopped.
            //
            // MUST stay transition-gated. setLyric runs on EVERY poll tick, and
            // a phone whose player app pushes an update per second made this
            // fire a pulse every 4s forever (the throttle window): each pulse
            // lights the panel, so the AOD never settled back into
            // DOZE_SUSPEND and the screen stayed lit (measured on Android 17 /
            // HyperOS 4).
            if (wasActive) {
                requestPulse(swapContext());
            }
            // Hide rather than blank: an invisible, non-touchable view cannot
            // cover the fingerprint sensor while nothing is playing.
            postOnMain(() -> tv.setAlpha(1f));
            hideOverlay(tv);
            return;
        }

        String signature = d.signature(style != null && style.showProgress);
        if (signature.equals(sLastSignature)) {
            return;
        }
        boolean wasIdle = !sActive;
        sLastSignature = signature;
        sContentAt = android.os.SystemClock.elapsedRealtime();
        sLastText = d.line == null ? "" : d.line;
        sLastSpanned = compose(tv, d);
        sActive = true;

        boolean dim = d.paused;
        boolean wasPaused = sPaused;
        sPaused = dim;
        if (wasIdle) {
            AodLyricModule.i("overlay text -> " + sLastText
                    + (d.paused ? " (paused)" : "")
                    + (d.next == null ? "" : " | next=" + d.next));
        }
        // A pause transition changes only the alpha: one pulse is enough to
        // make the panel re-capture, no need to keep the lyric "active".
        if (dim) {
            // Paused content is static - release the refresh hold (battery).
            setPanelHold(false);
            // ...and stop the frame pump: the panel is about to suspend, so the
            // endless alpha animation would only burn CPU without ever reaching
            // the screen (measured ~130 fps while paused).
            stopPulse();
            stopRootMotion();
            // Transition-gated: a per-tick pulse here lit the panel every 4s
            // (throttle window) and the AOD never returned to DOZE_SUSPEND.
            if (!wasPaused) {
                requestPulse(swapContext());
            }
            postOnMain(() -> tv.setAlpha(0.55f));
        } else {
            // Playing: the line/progress changes every few seconds, and a
            // DOZE_SUSPEND panel ignores new frames (it only re-composites on
            // its minute tick). Hold the panel in DOZE so new content actually
            // reaches the physical screen - see setPanelHold().
            // While the screen is interactive it refreshes anyway, so do not
            // grab the hold there (the kicker reconciles within a second).
            setPanelHold(!isInteractive(swapContext()));
            requestPulse(swapContext());
            postOnMain(() -> tv.setAlpha(1f));
        }
        applyText(tv, sLastSpanned);
    }

    /**
     * Removes and re-adds the overlay at its old index.
     *
     * <p>Detach/attach is a structural change: the parent re-measures, the
     * window is flagged for a full redraw, and the AOD's composition buffer is
     * re-captured. Ordinary invalidate()/traversal requests are ignored by that
     * buffer, which is why the text stayed frozen before.
     */
    private static void reinflate(@NonNull TextView tv) {
        try {
            android.view.ViewParent parent = tv.getParent();
            if (!(parent instanceof ViewGroup)) {
                return;
            }
            ViewGroup group = (ViewGroup) parent;
            int index = group.indexOfChild(tv);
            if (index < 0) {
                return;
            }
            // Keep the view's own params: in tree mode they carry the gap
            // margins and the slot must stay inside the layout flow.
            ViewGroup.LayoutParams keep = tv.getLayoutParams();
            group.removeViewAt(index);
            group.addView(tv, index, keep != null ? keep : new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Throwable t) {
            AodLyricModule.w("overlay reinflate failed: " + t);
        }
    }

    /**
     * Z-order fix-up used outside tree mode.
     *
     * <p>bringToFront() reorders the view to the END of its parent's child
     * list, which in tree mode would rip the lyric out of its layout slot
     * (a vertical flow renders the last child last). In tree mode the slot
     * itself IS the z-order, so this becomes a no-op.
     */
    private static void ensureFront(@NonNull TextView tv) {
        if (ImmersiveStage.active()) {
            // 要压到最上面的是舞台，不是画布里的歌词。
            ImmersiveStage.bringToFront();
            return;
        }
        if (treeModeActive()) {
            return;
        }
        try {
            tv.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    static volatile boolean sFrameWarned2;

    static volatile boolean sTraversalOk;
    static volatile boolean sTraversalWarned;

    /**
     * Every MAML root seen in this process.
     *
     * <p>Capturing only the first one was wrong: the first root belongs to
     * whatever MAML surface loads earliest (lock screen / preview), while the
     * AOD's own root appears much later when the theme is inflated. Driving the
     * wrong tree's framerate leaves the AOD renderer in static mode, which is
     * exactly the "text updates but screen frozen" symptom.
     */
    static final java.util.List<java.lang.ref.WeakReference<Object>> sMamlRoots =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static volatile boolean sFrameWarned;
    static volatile boolean sFrameOk;

    /** Called from the MAML addElement hook with the root instance. */
    public static void setMamlRoot(@Nullable Object root) {
        if (root == null) {
            return;
        }
        synchronized (sMamlRoots) {
            for (java.lang.ref.WeakReference<Object> ref : sMamlRoots) {
                if (ref.get() == root) {
                    return;
                }
            }
            sMamlRoots.add(new java.lang.ref.WeakReference<>(root));
            while (sMamlRoots.size() > 8) {
                sMamlRoots.remove(0);
            }
            AodLyricModule.i("MAML root registered (total=" + sMamlRoots.size()
                    + "): " + root.getClass().getName());
        }
    }

    @Nullable
    private static android.content.Context swapContext() {
        return ModuleReporter.capturedContext();
    }

    static final java.util.Set<String> sLoggedFailures =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Weak reference to the AOD root, used for invalidation from the kicker. */
    @Nullable
    private static volatile java.lang.ref.WeakReference<ViewGroup> sRootRef;

    /**
     * Keeps an animation frame running so the AOD window keeps recompositing.
     *
     * <p>Evidence from the device: a new lyric line only appeared when some AOD
     * component was already moving. The window therefore caches its buffer while
     * idle and only re-latches on an animation frame. Driving MAML's framerate
     * did not help (its roots are not the AOD's renderer), so the animation is
     * produced by our own view instead - Choreographer frames from any view in
     * the window force the same recomposition.
     */
    @Nullable
    static volatile android.animation.ObjectAnimator sRootMotion;

    /** True while we are holding the panel in DOZE for continuous refresh. */
    private static volatile boolean sPanelHold;

    @Nullable
    static volatile Class<?> sUtilsClass;

    /** Last time a doze pulse was requested, to avoid spamming the panel. */
    private static volatile long sLastPulseAt;

    private static final long PULSE_INTERVAL_MS = 4000L;

    /** After any touch, stay completely silent for this long. */
    private static final long INTERACTIVE_GRACE_MS = 12000L;

    /** No pulses at all for this long after the overlay attaches. */
    private static final long ATTACH_SETTLE_MS = 5000L;

    private static volatile long sLastInteractiveAt;

    private static volatile long sAttachedAt;

    /**
     * Requests one doze pulse.
     *
     * <p>{@code DozeTriggers.TriggerReceiver} handles
     * {@code com.android.systemui.doze.pulse} by calling {@code requestPulse(0)},
     * which drives a real doze state transition. That state change is what makes
     * the panel recomposite - the same effect the user sees when touching the
     * under-display fingerprint sensor. It is throttled because each pulse costs
     * a brief screen wake-up.
     */
    private static void requestPulse(@Nullable android.content.Context context) {
        if (context == null) {
            return;
        }
        // While we hold the panel in DOZE the compositor is already delivering
        // every frame, so a pulse buys nothing - and it actively breaks touch:
        // the pulse drives the doze machine into REQUEST_PULSE/PULSING/
        // PULSE_DONE, where a PowerManager.wakeUp() issued by our double-tap
        // detector is swallowed. On device that showed up as "double-tap does
        // nothing for a second or two after every lyric update".
        if (ThemeProbeV2.isPanelAwake()) {
            sPulseWanted = false;
            if (!sPulseSkippedLogged) {
                sPulseSkippedLogged = true;
                AodLyricModule.i("doze pulse skipped while panel is held in DOZE"
                        + " (frames flow anyway; pulses swallow double-tap wake)");
            }
            return;
        }
        // Suppress pulses near any touch. A pulse drives a doze state transition;
        // firing one while the under-display fingerprint handshake is starting
        // makes the panel flash and kills the AOD UI. Two independent guards:
        //   1) PowerManager.isInteractive()  - true once the wake-up has begun
        //   2) the AOD container's touch hook - fires on the first touch-down,
        //      before the screen lights, which is the window that used to crash.
        if (isInteractive(context)) {
            sLastInteractiveAt = android.os.SystemClock.elapsedRealtime();
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastInteractiveAt < INTERACTIVE_GRACE_MS) {
            return;
        }
        // Right after attach the panel has just composed our text, so a pulse
        // would be pure interference.
        if (now - sAttachedAt < ATTACH_SETTLE_MS) {
            return;
        }
        if (now - sLastPulseAt < PULSE_INTERVAL_MS) {
            // Deferred, not dropped: on fast lyric changes the 4s throttle
            // used to swallow the pulse entirely and the line never reached
            // the panel. The kicker re-issues it as soon as the window opens.
            sPulseWanted = true;
            return;
        }
        sPulseWanted = false;
        sLastPulseAt = now;
        sPulseJustFired = true;
        try {
            android.content.Intent intent =
                    new android.content.Intent("com.android.systemui.doze.pulse");
            intent.setPackage("com.android.systemui");
            context.sendBroadcast(intent);
            if (!sPulseLogged) {
                sPulseLogged = true;
                AodLyricModule.i("doze pulse requested (refreshes the AOD panel)");
            }
        } catch (Throwable t) {
            AodLyricModule.w("doze pulse failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
        }
    }

    /**
     * True while a pulse was requested but throttled. The kicker flushes it as
     * soon as the minimum gap has elapsed, so a burst of fast lines still ends
     * with the final line painted.
     */
    private static volatile boolean sPulseWanted;

    private static volatile boolean sPulseLogged;

    /** One-shot so the "pulse skipped" note does not spam the log. */
    private static volatile boolean sPulseSkippedLogged;

    /** True while the screen is awake (touched, lit, or being unlocked). */
    private static boolean isInteractive(@Nullable android.content.Context context) {
        if (context == null) {
            return false;
        }
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) context.getSystemService(
                            android.content.Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Class loader of the AOD plugin, captured from a loaded com.miui.aod class. */
    @Nullable
    private static volatile ClassLoader sPluginLoader;

    /** Called by the probe when any com.miui.aod class is seen loading. */
    public static void notePluginLoader(@Nullable ClassLoader loader) {
        if (loader != null && sPluginLoader == null) {
            sPluginLoader = loader;
        }
    }

    /** The recorded AOD plugin classloader, or null before the first load. */
    @Nullable
    public static ClassLoader pluginLoader() {
        return sPluginLoader;
    }

    private static void setPanelHold(boolean hold) {
        setPanelHold(hold, false);
    }

    /**
     * Holds (or releases) continuous AOD panel refresh while a lyric is live.
     *
     * <p>Implemented entirely by {@link ThemeProbeV2#setPanelAwake}: MIUI parks
     * the panel in {@code DOZE_SUSPEND}, where the compositor stops sending
     * frames, and we remap that to {@code DOZE} for the duration of playback.
     *
     * <p>Do NOT reintroduce MIUI's {@code Utils.setTouchMode(0, 19|11, v)} here.
     * It never made the panel refresh (the display state is the real gate) and
     * on-device it swallowed AOD touch events, which broke double-tap-to-wake
     * entirely while a lyric was on screen.
     *
     * @param force re-assert even when our cached state already matches: the
     *              doze machine can move the display behind our back (pulse,
     *              system transitions).
     */
    private static void setPanelHold(boolean hold, boolean force) {
        boolean changed = sPanelHold != hold;
        sPanelHold = hold;
        ThemeProbeV2.setPanelAwake(hold, force);
        if (changed) {
            AodLyricModule.i("panel hold " + (hold ? "on (refresh)" : "off (battery)"));
        }
    }

    @Nullable
    private static volatile android.animation.ObjectAnimator sPulse;

    private static void startPulse(@NonNull TextView tv) {
        try {
            stopPulse();
            android.animation.ObjectAnimator pulse =
                    android.animation.ObjectAnimator.ofFloat(tv, "alpha", 1f, 0.98f);
            pulse.setDuration(700L);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.start();
            sPulse = pulse;
            AodLyricModule.i("overlay pulse started (forces per-frame recomposition)");
        } catch (Throwable t) {
            AodLyricModule.w("overlay pulse failed: " + t);
        }
    }

    private static void stopPulse() {
        android.animation.ObjectAnimator pulse = sPulse;
        sPulse = null;
        if (pulse != null) {
            try {
                pulse.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void startKicker(@NonNull ViewGroup root, @NonNull TextView tv) {
        stopKicker();
        sRootRef = new java.lang.ref.WeakReference<>(root);
        sTick.set(0);
        java.util.concurrent.ScheduledExecutorService kicker =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "aodlyric-overlay-kick");
                    t.setDaemon(true);
                    return t;
                });
        sKicker = kicker;
        kicker.scheduleWithFixedDelay(() -> {
            TextView view = sView.get();
            if (view == null || !sAodVisible) {
                return;
            }
            int tick = sTick.incrementAndGet();
            // Idle: nothing playing, nothing to refresh. Doing work here (and
            // especially pulsing) broke touch handling for no benefit - but a
            // park that was skipped (doze service restart) must still close,
            // otherwise the panel keeps compositing at ~120 fps for nothing.
            if (!sActive) {
                if (tick % 15 == 0) {
                    ThemeProbeV2.confirmPanelSuspended();
                }
                // Periodically sweep views left behind by a dead session even
                // while idle (normally there are none; this is the safety net
                // for an already-attached stray).
                if (tick % 5 == 0) {
                    postOnMain(() -> {
                        TextView current = sView.get();
                        if (current != null) {
                            removeStrayViews(current);
                        }
                    });
                }
                return;
            }
            try {
                // Keep the refresh hold in step with reality. The system can
                // drop it on its own (display transitions, pulses), so re-assert
                // it every few ticks; an interactive screen is already
                // refreshing, so release there instead of fighting the power
                // key. setPanelHold() no-ops on an unchanged state.
                boolean wantHold = !sPaused && !isInteractive(swapContext());
                setPanelHold(wantHold, tick % PANEL_REASSERT_TICKS == 0);
                // Paused: keep checking that the panel really suspended. A
                // skipped one-shot park would otherwise composite forever.
                if (!wantHold && tick % 5 == 0) {
                    ThemeProbeV2.confirmPanelSuspended();
                }
                // Cost tiers (was: full structural refresh every single second):
                //   - 1s cadence right after a change, and always while a
                //     deferred pulse is waiting (fast lyrics must not lag);
                //   - afterwards only every third tick runs the heavy path.
                // The pulse animator keeps the panel alive in between, so the
                // visible behaviour is unchanged while the steady-state main
                // thread work drops by ~3x.
                boolean fresh = sPulseWanted
                        || android.os.SystemClock.elapsedRealtime() - sContentAt < SETTLE_MS;
                if (!fresh && tick % IDLE_TICK_DIVISOR != 0) {
                    return;
                }
                if (sPulseWanted) {
                    // Throttled pulse from requestPulse(): flush it now.
                    requestPulse(swapContext());
                }
                // Paused content is static: never run the structural refresh
                // (reinflate) while parked. The pause transition already gets
                // one structural pass from applyText(); repeating it during the
                // DOZE_SUSPEND window only risks the panel latching a frame
                // captured mid-relayout.
                boolean heavy = fresh && !sPaused;
                postOnMain(() -> {
                    try {
                        if (sView.get() != view) {
                            // A queued tick from a session that already ended:
                            // its view must not be re-added to the new AOD tree.
                            AodLyricModule.w("dropped stale overlay tick (old AOD session)");
                            return;
                        }
                        removeStrayViews(view);
                        // 沉浸模式：舞台可能被主题/重建摘掉，先保证歌词还在画布里，
                        // 再做防烧屏漂移与组件隐藏的周期性校正。
                        if (ImmersiveStage.active()) {
                            ImmersiveStage.ensureAttached(view);
                            ImmersiveStage.tick();
                        }
                        // Prefer the real parent (the clock container) so a
                        // re-attach keeps the lyric inside the moving component.
                        ViewGroup parent = currentParent(view);
                        if (parent == null) {
                            parent = sRootRef == null ? null : sRootRef.get();
                        }
                        if (parent == null) {
                            return;
                        }
                        if (view.getParent() == null) {
                            parent.addView(view, new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                            AodLyricModule.i("overlay re-attached after detach");
                        }
                        // Re-assert placement whenever the view came back with
                        // default params: tree slot / anchor offset / ratio.
                        {
                            ViewGroup rootRef = sRootRef == null ? null : sRootRef.get();
                            if (rootRef != null) {
                                applyOffset(view, rootRef);
                            }
                        }
                        // Keep the flow budget tall enough for the current
                        // text (re-wraps and style changes raise the height).
                        maybeExpandBudget(view);
                        // Full-screen width, re-asserted: the theme may rebuild
                        // params and MIUI drifts the container for burn-in.
                        applyFullWidth(view);
                        if (view.getVisibility() != android.view.View.VISIBLE) {
                            view.setVisibility(android.view.View.VISIBLE);
                        }
                        // A cancelled animator (frequently on detach/re-attach)
                        // would silently stop the frame flow again.
                        //
                        // Paused is different: the panel is parked in
                        // DOZE_SUSPEND (self-refresh), so those frames never
                        // reach the screen - an endless alpha animation here
                        // just burns CPU (measured: ~130 fps / 20% SystemUI CPU
                        // while paused, vs ~10 fps when the screen is on and
                        // idle). Stop it and let the panel rest.
                        android.animation.ObjectAnimator pulse = sPulse;
                        if (sPaused) {
                            if (pulse != null && pulse.isRunning()) {
                                stopPulse();
                                stopRootMotion();
                            }
                        } else if (pulse == null || !pulse.isRunning()) {
                            startPulse(view);
                        }
                        CharSequence content = sLastSpanned;
                        view.setText(content == null ? "" : content);
                        // Tree mode: the view IS a layout participant holding its
                        // slot; remove+add (reinflate) rebuilt the slot every
                        // second and made the whole AOD jump. The slot is also
                        // the z-order there, so bringToFront must not run either.
                        if (heavy && !treeModeActive()) {
                            reinflate(view);
                            ensureFront(view);
                        }
                        view.invalidate();
                        parent.requestLayout();
                        parent.invalidate();
                        forceTraversal(parent);
                        // Anchor mode: component layouts pulse/animate, so
                        // recompute the anchored position every tick. The
                        // delta guard inside applyAnchorOffset makes this a
                        // no-op unless the anchor actually moved.
                        if (sStyle != null && sStyle.anchorId != null
                                && !sStyle.anchorId.isEmpty()) {
                            ViewGroup rootRef = sRootRef == null ? null : sRootRef.get();
                            if (rootRef != null) {
                                applyOffset(view, rootRef);
                            }
                        }
                        // Keep the AOD renderer animated; see driveFramerate().
                        // Only while playing: a paused panel is suspended and
                        // does not need (or receive) these frames.
                        if (heavy && !sPaused) {
                            driveFramerate();
                        }
                    } catch (Throwable t) {
                        AodLyricModule.w("overlay kick failed: " + t);
                    }
                });
                // Bounded state log: proves whether the view is still in the tree.
                if (tick % ALIVE_LOG_TICKS == 0) {
                    AodLyricModule.i("overlay alive: attached="
                            + (view.getParent() != null)
                            + " visible=" + (view.getVisibility() == android.view.View.VISIBLE)
                            + " textLen=" + (sLastText == null ? 0 : sLastText.length())
                            + " inClock=" + sInClockContainer
                            + " parent=" + (view.getParent() == null ? "null"
                                    : view.getParent().getClass().getSimpleName())
                            + " pulse=" + (sPulse != null && sPulse.isRunning())
                            + " aodVisible=" + sAodVisible);
                    // Publish so the app UI can report it without logcat.
                    Reporter.reportClockParenting(sInClockContainer);
                    logOverlayGeometry(view);
                }
            } catch (Throwable t) {
                AodLyricModule.w("overlay kicker iteration failed: " + t);
            }
        }, 1000, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
        AodLyricModule.i("overlay kicker started (1s, adaptive)");
    }

    /** Window after a content change that keeps the 1s heavy cadence. */
    private static final long SETTLE_MS = 4000L;

    /** When the rendered content last changed (SystemClock.elapsedRealtime). */
    private static volatile long sContentAt;

    /** Steady-state divisor: heavy refresh every Nth second. */
    private static final int IDLE_TICK_DIVISOR = 3;

    /**
     * Kicker cadence for forcing the panel hold back on. The doze machine can
     * move the display behind our back, and our cached flag would then be stale.
     */
    private static final int PANEL_REASSERT_TICKS = 15;

    /** "overlay alive" log cadence (seconds). */
    private static final int ALIVE_LOG_TICKS = 60;

    /**
     * Diagnostic: overlay geometry and the alpha/visibility chain above it.
     *
     * <p>Full-screen AOD ("与锁屏界面一致") paints a wallpaper through a
     * different window composition and may reparent or alpha-fade the AOD
     * content. When the lyric is attached yet invisible on screen, these values
     * tell which ancestor hides it: any alpha&nbsp;0 / visibility&nbsp;!=VISIBLE
     * / zero-height box in the chain is the culprit.
     */
    private static void logOverlayGeometry(@NonNull TextView view) {
        try {
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            StringBuilder chain = new StringBuilder();
            android.view.View p = view.getParent() instanceof android.view.View
                    ? (android.view.View) view.getParent() : null;
            int depth = 0;
            while (p != null && depth < 6) {
                String id = "";
                try {
                    int rid = p.getId();
                    if (rid != android.view.View.NO_ID) {
                        id = p.getResources().getResourceEntryName(rid);
                    }
                } catch (Throwable ignored) {
                }
                chain.append(" <- ").append(p.getClass().getSimpleName())
                        .append(id.isEmpty() ? "" : "#" + id)
                        .append("[a=").append(p.getAlpha())
                        .append(",v=").append(p.getVisibility())
                        .append(",h=").append(p.getHeight())
                        .append(",ty=").append(Math.round(p.getTranslationY()))
                        .append(']');
                p = p.getParent() instanceof android.view.View
                        ? (android.view.View) p.getParent() : null;
                depth++;
            }
            AodLyricModule.i("overlay geometry: xy=" + loc[0] + "," + loc[1]
                    + " size=" + view.getWidth() + "x" + view.getHeight()
                    + " alpha=" + view.getAlpha()
                    + " parentVis=" + (view.getParent() == null ? -1
                            : ((android.view.View) view.getParent()).getVisibility())
                    + " ancestors:" + chain);
        } catch (Throwable t) {
            AodLyricModule.w("overlay geometry failed: " + t);
        }
    }

    /**
     * One-shot probe: logs the ancestor chain up to the window top. In
     * full-screen AOD the MIUIAod content is faded to alpha 0 by the keyguard
     * (the visible lock screen lives in the NotificationShade window); this
     * confirms the fade state ~6.5s after attach, after transient host
     * animations have settled.
     */
    private static void logFullScreenAodState(@NonNull ViewGroup root) {
        try {
            if (root.getParent() == null) {
                return;
            }
            StringBuilder up = new StringBuilder("full-screen check up-chain");
            View v = root;
            int depth = 0;
            while (v != null && depth < 14) {
                up.append(" <- ").append(describeView(v))
                        .append("[a=").append(String.format("%.2f", v.getAlpha()))
                        .append(",v=").append(v.getVisibility()).append(']');
                View p = v.getParent() instanceof View ? (View) v.getParent() : null;
                if (p == null) {
                    break;
                }
                v = p;
                depth++;
            }
            AodLyricModule.i(up.toString());
        } catch (Throwable t) {
            AodLyricModule.w("full-screen check failed: " + t);
        }
    }

    /** "Class#id" label, id entry name resolved lazily and best effort. */
    @NonNull
    private static String describeView(@NonNull View v) {
        String name = "?";
        try {
            if (v.getId() != android.view.View.NO_ID) {
                name = v.getResources().getResourceEntryName(v.getId());
            }
        } catch (Throwable ignored) {
        }
        return v.getClass().getSimpleName() + "#" + name;
    }

    /** Depth-first dump of VISIBLE nodes only, bounded by {@code budget[0]}. */
    private static void dumpVisibleTree(@NonNull View v, int depth, int[] budget,
                                        boolean isRoot) {
        if (budget[0] > 90 || depth > 12) {
            return;
        }
        if (!isRoot && (v.getVisibility() != android.view.View.VISIBLE
                || v.getAlpha() < 0.05f)) {
            return;
        }
        budget[0]++;
        StringBuilder line = new StringBuilder("VTree ");
        for (int i = 0; i < depth; i++) {
            line.append("  ");
        }
        line.append(describeView(v))
                .append(" bounds=[").append(v.getLeft()).append(',').append(v.getTop())
                .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append("] a=").append(String.format("%.2f", v.getAlpha()));
        if (v instanceof TextView && v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.length() > 0) {
                String s = t.toString().replaceAll("\\s+", " ");
                line.append(" text=\"")
                        .append(s, 0, Math.min(24, s.length())).append('"');
            }
        }
        AodLyricModule.i(line.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int count = g.getChildCount();
            for (int i = 0; i < count && i < 30; i++) {
                View c = g.getChildAt(i);
                if (c != null) {
                    dumpVisibleTree(c, depth + 1, budget, false);
                }
            }
        }
    }

    private static void stopKicker() {        java.util.concurrent.ScheduledExecutorService kicker = sKicker;
        sKicker = null;
        if (kicker != null) {
            try {
                kicker.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }
}
