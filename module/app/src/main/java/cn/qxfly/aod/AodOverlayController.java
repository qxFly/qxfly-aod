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



    private final XposedInterface mXp;
    @NonNull
    private final ProviderLrcSource mProviderLrc;

    private static volatile boolean sHooked;
    private static volatile boolean sAodVisible;

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
        if (sView.get() != null) {
            return;
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
            sAodVisible = true;
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
    // configurations keep working.

    /** Canonical anchor ids (stored in styleAnchorId, shown in the picker). */
    static final String ANCHOR_CLOCK = "clock";
    static final String ANCHOR_IMAGE = "image";
    static final String ANCHOR_TIMEDATE = "timedate";
    static final String ANCHOR_NOTIFY = "notify";

    /** Picker display order. */
    private static final String[] ANCHOR_ORDER = {
            ANCHOR_CLOCK, ANCHOR_IMAGE, ANCHOR_TIMEDATE, ANCHOR_NOTIFY,
    };

    /** Candidate entry names per canonical anchor, best first. */
    @Nullable
    private static String[] canonicalCandidates(@NonNull String canonical) {
        switch (canonical) {
            case ANCHOR_CLOCK:
                return new String[]{
                        "gradient_layout", "clock_vertical_container",
                        "clock_container", "clock", "big_time",
                };
            case ANCHOR_IMAGE:
                return new String[]{
                        "image_shower", "image_shower_container",
                        "image_selector_shower", "classic_gallery",
                        "classic_image", "photo", "wallpaper",
                };
            case ANCHOR_TIMEDATE:
                return new String[]{
                        "clock_horizontal_container", "date_lunar",
                        "date_dual", "date", "data_dual_container",
                };
            case ANCHOR_NOTIFY:
                return new String[]{
                        "aod_icons_container", "icons", "fn_container",
                        "notification_container",
                };
            default:
                return null;
        }
    }

    /**
     * Maps a legacy fine-grained anchor id to its nearest coarse region, or
     * null when the id is not recognised (it is then used as-is, unchanged
     * behaviour for custom ids).
     */
    @Nullable
    static String legacyToCanonical(@NonNull String id) {
        String l = id.toLowerCase(java.util.Locale.US);
        if (l.contains("shower") || l.contains("image") || l.contains("gallery")
                || l.contains("photo") || l.contains("wallpaper")
                || l.contains("cover") || l.contains("album")) {
            return ANCHOR_IMAGE;
        }
        if (l.contains("gradient") || l.contains("clock_container")
                || l.contains("clock_vertical") || l.contains("big")) {
            return ANCHOR_CLOCK;
        }
        if (l.contains("icon") || l.contains("notif")) {
            return ANCHOR_NOTIFY;
        }
        if (l.contains("clock") || l.contains("date") || l.contains("time")
                || l.contains("lunar") || l.contains("battery")
                || l.contains("step")) {
            return ANCHOR_TIMEDATE;
        }
        return null;
    }

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
    private static ModuleReporter reporter() {
        ModuleReporter r = Reporter.access();
        return r;
    }

    /**
     * Collects anchor candidates for the app's anchor picker, as
     * "id|class|[absX,absY w h]" lines - now exactly the 4 coarse regions
     * (clock / image / timedate / notify). The raw traversal below is only
     * the discovery pass; its fine-grained entries are mapped onto the 4
     * regions and never reach the provider, so the dropdown stays short and
     * every entry is guaranteed meaningful for insertion.
     */
    private static void collectComponents(@NonNull ViewGroup parent,
            @NonNull List<String> out, int depth, int absX, int absY) {
        List<String> raw = new java.util.ArrayList<>(24);
        collectRawComponents(parent, raw, 0, 0, 0);
        for (String canonical : ANCHOR_ORDER) {
            String[] cands = canonicalCandidates(canonical);
            if (cands == null) {
                continue;
            }
            for (String cand : cands) {
                String line = findById(raw, cand);
                if (line != null) {
                    // Replace the fine-grained name with the canonical one but
                    // keep the discovered class and absolute bounds.
                    String rest = line.substring(cand.length() + 1);
                    out.add(canonical + rest);
                    break;
                }
            }
        }
        AodLyricModule.i("anchor regions reported: " + out);
    }

    /** First raw line whose id equals the given entry name, or null. */
    @Nullable
    private static String findById(@NonNull List<String> raw, @NonNull String id) {
        String prefix = id + "|";
        for (String line : raw) {
            if (line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    /**
     * Raw discovery pass (kept from the previous fine-grained picker): visible,
     * measured, named views in ABSOLUTE window coordinates, deduped by name
     * and by absolute position. Internal only - see {@link #collectComponents}.
     */
    private static void collectRawComponents(@NonNull ViewGroup parent,
            @NonNull List<String> out, int depth, int absX, int absY) {
        if (depth > 8 || out.size() >= 60) {
            return;
        }
        int count = parent.getChildCount();
        for (int i = 0; i < count && out.size() < 60; i++) {
            android.view.View c = parent.getChildAt(i);
            if (c == null || c instanceof LyricTextView) {
                continue;
            }
            try {
                String name = null;
                if (c.getId() != android.view.View.NO_ID) {
                    name = c.getResources().getResourceEntryName(c.getId());
                }
                boolean useful = c.getVisibility() == android.view.View.VISIBLE
                        && c.getWidth() > 0 && c.getHeight() > 0
                        && name != null && !name.isEmpty()
                        && !(c instanceof android.view.ViewStub);
                if (useful) {
                    String bounds = "[" + (absX + c.getLeft()) + "," + (absY + c.getTop())
                            + " " + c.getWidth() + "x" + c.getHeight() + "]";
                    if (!containsId(out, name) && !containsBounds(out, bounds)) {
                        out.add(name + "|"
                                + c.getClass().getSimpleName()
                                + "|" + bounds);
                    }
                }
                if (c instanceof ViewGroup) {
                    collectRawComponents((ViewGroup) c, out, depth + 1,
                            absX + c.getLeft(), absY + c.getTop());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** True when any collected line ends with the given absolute-bounds string. */
    private static boolean containsBounds(@NonNull List<String> lines, @NonNull String bounds) {
        for (String line : lines) {
            if (line.endsWith("|" + bounds)) {
                return true;
            }
        }
        return false;
    }

    /** True when any collected line starts with "name|". */
    private static boolean containsId(@NonNull List<String> lines, @NonNull String name) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(name + "|")) {
                return true;
            }
        }
        return false;
    }

    /** True when the lyric is currently parented to the movable container. */
    public static boolean isFollowingClock() {
        return sInClockContainer;
    }

    private static void unusedPlaceholder() {
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

    private void detach() {
        stopRootMotion();
        stopPulse();
        stopKicker();
        sRootRef = null;
        sLastText = null;
        sShadowMode = false;
        sHiddenStreak = 0;

        TextView tv = sView.getAndSet(null);
        sAodVisible = false;
        if (tv != null) {
            try {
                ViewGroup parent = (ViewGroup) tv.getParent();
                if (parent != null) {
                    parent.removeView(tv);
                }
                AodLyricModule.i("lyric overlay detached");
            } catch (Throwable t) {
                AodLyricModule.w("lyric overlay detach failed: " + t);
            }
        }
    }

    /** Last text applied, so the log only reports real changes. */
    @Nullable
    private static volatile String sLastText;

    /**
     * True only while a real lyric line is on screen. Everything that costs
     * power or perturbs the doze state machine (pulses, kicker work) is gated on
     * it, so a paused/stopped player leaves the AOD completely untouched.
     */
    private static volatile boolean sActive;

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
                    style.bgEnabled, style.bgColor, style.bgAlpha);
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
                applyStyle(tv, style);
                ViewGroup root = sRootRef == null ? null : sRootRef.get();
                if (root != null) {
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
                TextView tv = sView.get();
                if (tv == null || sInClockContainer || sShadowMode) {
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

    private static void watchDoubleTap(@NonNull android.view.MotionEvent ev,
                                       @NonNull android.view.View view) {
        final int action = ev.getActionMasked();
        final long now = android.os.SystemClock.uptimeMillis();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
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
            restoreTouchedMargin();
            postOnMain(() -> {
                tv.setText("");
                android.view.ViewParent parent = tv.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(tv);
                }
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
     * Styles the translation line separately from the original.
     *
     * <p>The overlay is a single TextView (the AOD layout, anchor offsets and
     * the budget-expansion logic all key off one view), so the translation
     * cannot be a second view. Instead the text carries a newline separator and
     * the part after it is re-styled here: smaller size, dimmer colour - the
     * usual bilingual lyric look. Text without a separator is returned as a
     * plain String so the common case allocates nothing.
     */
    @NonNull
    private static CharSequence styleTranslationSpan(@NonNull TextView tv, @NonNull String text) {
        int sep = text.indexOf(TRANSLATION_SEP);
        if (sep <= 0 || sep >= text.length() - 1) {
            return text;
        }
        LyricStyle style = sStyle;
        float baseSp = style == null ? tv.getTextSize() : style.textSizeSp;
        float translationSp = Math.max(10f, baseSp * 0.62f);
        int color = style == null ? tv.getCurrentTextColor() : style.textColor;
        int dimColor = (color & 0x00FFFFFF) | 0xB3000000; // ~70% alpha

        android.text.SpannableString span = new android.text.SpannableString(text);
        span.setSpan(new android.text.style.AbsoluteSizeSpan(
                        Math.round(translationSp * tv.getResources().getDisplayMetrics().scaledDensity)),
                sep, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        span.setSpan(new android.text.style.ForegroundColorSpan(dimColor),
                sep, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    private static void applyText(@NonNull TextView tv, @NonNull String text) {
        try {
            postOnMain(() -> {
                tv.setText(styleTranslationSpan(tv, text));
                java.lang.ref.WeakReference<ViewGroup> rootRef = sRootRef;
                ViewGroup root = rootRef == null ? null : rootRef.get();
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
                }
                // A plain redraw never reaches the panel: the AOD caches its
                // composition buffer and only re-captures after a STRUCTURAL
                // change. Re-inserting the view forces measure+layout on the
                // window, which is what makes the new line appear.
                reinflate(tv);
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
        TextView tv = sView.get();
        if (tv == null || !sAodVisible) {
            return;
        }
        String original = line == null ? "" : line;
        String trans = translation == null ? "" : translation.trim();
        LyricStyle style = sStyle;
        boolean wantTranslation = style != null && style.showTranslation
                && !trans.isEmpty() && !trans.equals(original);
        final String text = wantTranslation ? original + "\n" + trans : original;
        if (text.equals(sLastText)) {
            return;
        }
        sLastText = text;

        // Empty text means playback stopped or paused. Clear the line and go
        // completely idle: no display pulses, no kicker work. Pulsing while
        // nothing plays is what kept breaking touch handling, and it also wastes
        // power for no visible result.
        if (text.isEmpty()) {
            AodLyricModule.i("overlay idle (no lyric)");
            sActive = false;
            // One pulse on the active->idle transition: the panel only
            // re-renders on a pulse, and the NATIVE theme's own lyric pills
            // must re-query the (now empty) lyric to clear the ghost line -
            // without this the last line stayed frozen on photo/full-screen
            // AOD themes after playback stopped. sLastText dedupe keeps this
            // to a single pulse per transition (no idle-time pulsing).
            requestPulse(swapContext());
            // Hide rather than blank: an invisible, non-touchable view cannot
            // cover the fingerprint sensor while nothing is playing.
            hideOverlay(tv);
            return;
        }

        sActive = true;
        AodLyricModule.i("overlay text -> " + text);
        // The ONLY thing that refreshes the panel: a doze pulse.
        requestPulse(swapContext());
        applyText(tv, text);
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
        if (treeModeActive()) {
            return;
        }
        try {
            tv.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Raises the frame rate of the window's own Surface.
     *
     * <p>MIUI parks the doze window on a very low refresh cadence; asking the
     * Surface for a fixed 60 Hz is the lowest-level request available to us and
     * makes SurfaceFlinger treat the layer as actively updated.
     */
    private static void bumpSurfaceFrameRate(@NonNull android.view.View anchor) {
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

    private static volatile boolean sFrameWarned2;

    /**
     * Schedules a real traversal on the window that owns the AOD.
     *
     * <p>The AOD's composition ignores ordinary {@code invalidate()} once it has
     * settled - the view keeps the new text but the display never re-composites.
     * Going through {@code ViewRootImpl#scheduleTraversals()} is the same path
     * the system uses to refresh its own clock, and it does force a redraw.
     */
    private static void forceTraversal(@NonNull android.view.View anchor) {
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

    @Nullable
    private static Object readField(@NonNull Object owner, @NonNull String name) {
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

    private static volatile boolean sTraversalOk;
    private static volatile boolean sTraversalWarned;

    /**
     * Every MAML root seen in this process.
     *
     * <p>Capturing only the first one was wrong: the first root belongs to
     * whatever MAML surface loads earliest (lock screen / preview), while the
     * AOD's own root appears much later when the theme is inflated. Driving the
     * wrong tree's framerate leaves the AOD renderer in static mode, which is
     * exactly the "text updates but screen frozen" symptom.
     */
    private static final java.util.List<java.lang.ref.WeakReference<Object>> sMamlRoots =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static volatile boolean sFrameWarned;
    private static volatile boolean sFrameOk;

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
    private static void driveFramerate() {
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

    @Nullable
    private static android.content.Context swapContext() {
        return ModuleReporter.capturedContext();
    }

    @NonNull
    private static String clockText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private static boolean invokeNoArg(@NonNull Object target, @NonNull String name) {
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

    private static boolean invokeWithString(@NonNull Object target, @NonNull String name,
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

    private static final java.util.Set<String> sLoggedFailures =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** Logs each distinct reflective failure once, with its real cause. */
    private static void logReflectFailure(@NonNull String name, @NonNull Throwable t) {
        Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                && t.getCause() != null ? t.getCause() : t;
        String key = name + ":" + cause.getClass().getSimpleName();
        if (sLoggedFailures.add(key) && sLoggedFailures.size() <= 8) {
            AodLyricModule.w("reflect " + name + " failed: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /** Weak reference to the AOD root, used for invalidation from the kicker. */
    @Nullable
    private static volatile java.lang.ref.WeakReference<ViewGroup> sRootRef;

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
    private static void harvestMamlRoots(@NonNull ViewGroup root) {
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
    private static volatile android.animation.ObjectAnimator sRootMotion;

    /** True while the AOD touch modes are held on by us. */
    private static volatile boolean sTouchModesOn;

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
    private static Class<?> findUtilsClass() {
        try {
            Class<?> cached = sUtilsClass;
            if (cached != null) {
                return cached;
            }
        } catch (Throwable ignored) {
        }
        ClassLoader pluginLoader = sPluginLoader;
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

    @Nullable
    private static volatile Class<?> sUtilsClass;

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
            return;
        }
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

    private static volatile boolean sPulseLogged;

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

    private static void setTouchModes(boolean enable) {
        if (sTouchModesOn == enable) {
            return;
        }
        try {
            Class<?> utils = findUtilsClass();
            java.lang.reflect.Method m = utils.getMethod("setTouchMode",
                    int.class, int.class, int.class);
            int value = enable ? 1 : 0;
            boolean ok19 = (Boolean) m.invoke(null, 0, 19, value);
            boolean ok11 = (Boolean) m.invoke(null, 0, 11, value);
            sTouchModesOn = enable;
            AodLyricModule.i("touch modes " + (enable ? "enabled" : "restored")
                    + " (keepScreenOn=" + ok19 + ", aodAnimate=" + ok11 + ")");
        } catch (Throwable t) {
            AodLyricModule.w("touch mode " + (enable ? "enable" : "restore")
                    + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Nullable
    private static volatile android.animation.ObjectAnimator sPulse;

    /**
     * Animates the AOD root by a sub-pixel offset.
     *
     * <p>Observed behaviour on this device: lyric text only appears when some AOD
     * component is already moving. Animating our own child view was not enough,
     * so the root view itself is put into an animated state - the smallest offset
     * that still marks the window as animating.
     */
    private static void startRootMotion(@NonNull ViewGroup root) {
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

    private static void stopRootMotion() {
        android.animation.ObjectAnimator motion = sRootMotion;
        sRootMotion = null;
        if (motion != null) {
            try {
                motion.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

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
            // Idle: nothing playing, nothing to refresh. Doing work here (and
            // especially pulsing) broke touch handling for no benefit.
            if (!sActive) {
                return;
            }
            try {
                int tick = sTick.incrementAndGet();
                postOnMain(() -> {
                    try {
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
                        if (view.getVisibility() != android.view.View.VISIBLE) {
                            view.setVisibility(android.view.View.VISIBLE);
                        }
                        // A cancelled animator (frequently on detach/re-attach)
                        // would silently stop the frame flow again.
                        android.animation.ObjectAnimator pulse = sPulse;
                        if (pulse == null || !pulse.isRunning()) {
                            startPulse(view);
                        }
                        view.setText(sLastText == null ? "" : styleTranslationSpan(view, sLastText));
                        // Tree mode: the view IS a layout participant holding its
                        // slot; remove+add (reinflate) rebuilt the slot every
                        // second and made the whole AOD jump. The slot is also
                        // the z-order there, so bringToFront must not run either.
                        if (!treeModeActive()) {
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
                        driveFramerate();
                    } catch (Throwable t) {
                        AodLyricModule.w("overlay kick failed: " + t);
                    }
                });
                // Bounded state log: proves whether the view is still in the tree.
                if (tick % 10 == 0) {
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
        AodLyricModule.i("overlay kicker started (1s)");
    }

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
