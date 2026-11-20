package cn.qxfly.aod;

import android.content.Context;
import android.media.MediaMetadata;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.libxposed.api.XposedInterface;

/**
 * Injection hooks for the new AOD lyric pipeline:
 * MediaMetadata#getString (primary), MusicLyricParser#parseLyric (race guard),
 * MusicControlScreenElement#<init> (theme compatibility probe).
 */
public final class LyricBridge {

    public static final String LYRIC_KEY = "android.media.metadata.LYRIC";

    private static final String MAML_CLASS = "com.miui.maml.elements.MusicControlScreenElement";

    @NonNull
    private final XposedInterface mXp;

    @NonNull
    private final SuperLyricSource mSource;

    @NonNull
    private final ModuleReporter mReporter;

    @Nullable
    private final ClassLoader mHostLoader;

    /**
     * Fallback lyric source: the UI app process receives FULL payloads from
     * SuperLyric (its manifest declares superlyricapi_version_code=40; this
     * process's package com.miui.aod does not, so its own stream only carries
     * the current line). The provider bridges the two. Static because
     * currentLrc() is reached from static contexts.
     */
    @Nullable
    private static volatile ProviderLrcSource sProviderLrc;

    @Nullable
    private static java.util.concurrent.ScheduledExecutorService sPoller;

    @Nullable
    private static volatile SuperLyricSource sActiveSource;

    @Nullable
    private static volatile String sRestoredLrc;

    /** One-shot "inject empty LRC" marker (see {@link #currentLrc()}). */
    private static volatile boolean sForceEmptyLrc;

    private static volatile boolean sHooksInstalled;

    public LyricBridge(@NonNull XposedInterface xp, @NonNull ClassLoader hostClassLoader) {
        mXp = xp;
        mHostLoader = hostClassLoader;
        mSource = new SuperLyricSource(xp);
        mReporter = new ModuleReporter(xp);
        // Let the overlay publish its state for the app UI.
        Reporter.attach(mReporter);
    }

    public void install() {
        sActiveSource = mSource;
        HiddenApi.exempt();
        mSource.register();
        startProviderPolling();
        if (sHooksInstalled) {
            AodLog.i("hooks already installed, skipping re-hook");
            return;
        }
        installMetadataHook();
        installParserHook();
        sHooksInstalled = true;
        // Theme probe moved to ThemeProbeV2: it must watch ClassLoader#loadClass
        // because the AOD theme engine is loaded from a plugin classloader, and
        // the PathClassLoader copy of MusicControlScreenElement is never used.
        new ThemeProbeV2(mXp, sProviderLrc, mHostLoader).install();
        mReporter.reportStatus(true, "getString + parseLyric + overlay", null);
    }

    /**
     * Provider sync loop.
     *
     * <p>Changed in 7.5.0: a ContentObserver wakes this loop the moment the UI
     * app writes a snapshot, so line changes no longer wait for the next poll.
     * The loop itself is adaptive - 1s ticks, but the expensive binder call
     * only runs when dirty or when the heartbeat is due (5s while a lyric is
     * live, 30s otherwise). Before this, two host processes each did one
     * {@code content call} per second forever, including on a paused/blank AOD.
     */
    private void startProviderPolling() {
        try {
            // No Context here: install() runs before the host Application
            // exists, so the source resolves it lazily on each refresh.
            sProviderLrc = new ProviderLrcSource();
            sPoller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aodlyric-lrc-poll");
                t.setDaemon(true);
                return t;
            });
            final int[] tick = {0};
            sPoller.scheduleWithFixedDelay(() -> {
                try {
                    // Deferred module/status reports: retry here because the
                    // Application-creation anchors are unreliable in SystemUI.
                    ModuleReporter.flushPendingIfReady();
                    tick[0]++;
                    ProviderLrcSource source = sProviderLrc;
                    if (source == null) {
                        return;
                    }
                    Context ctx = currentContext();
                    if (ctx != null) {
                        ensureProviderObserver(ctx);
                    }
                    boolean dirty = sProviderDirty;
                    sProviderDirty = false;
                    long now = System.currentTimeMillis();
                    long lastFeed = Math.max(source.lastFeedAt(),
                            sActiveSource == null ? 0 : sActiveSource.lastEventAt());
                    boolean live = lastFeed > 0 && now - lastFeed < 60_000L;
                    int period = live ? 5 : (AodOverlayController.isAodVisible() ? 10 : 30);
                    if (!dirty && tick[0] % period != 0) {
                        return;
                    }
                    source.refresh();

                    // Diagnostics handshake: the UI bumped diagRequest, so push
                    // this process's log tail back through the provider.
                    String diag = source.diagRequest();
                    if (diag != null && !diag.equals(sLastDiagRequest)) {
                        sLastDiagRequest = diag;
                        mReporter.reportDiagnostics(sHooksInstalled,
                                "getString + parseLyric + overlay");
                    }

                    if (!source.moduleEnabled()) {
                        // Master switch off: force the overlay idle.
                        AodOverlayController.setLyric(null);
                        return;
                    }
                    // Watchdog: killed players never deliver a stop event, so a
                    // line frozen for >3 min with no feed anywhere is dead - hide
                    // it. The next real feed un-stales and restores the lyrics.
                    boolean stale = isStale(source);
                    if (stale) {
                        LyricBridge.requestForceEmptyLrc();
                        AodOverlayController.setLyric(null);
                        return;
                    }
                    AodOverlayController.setLyric(buildDisplay(source));
                } catch (Throwable t) {
                    // Never let the scheduled task die: an exception escaping
                    // here cancels all further executions silently.
                    AodLog.w("lyric poll iteration failed: " + t);
                }
            }, 500, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            AodLog.i("provider sync started (push + adaptive poll)");
        } catch (Throwable t) {
            AodLog.w("provider polling unavailable: " + t);
        }
    }

    /** UI-app snapshot changed: refresh on the next tick instead of waiting. */
    private static volatile boolean sProviderDirty = true;

    /** Called by the lyric sources when a new line/meta arrived in-process. */
    static void markDirty() {
        sProviderDirty = true;
    }

    private static volatile boolean sObserverRegistered;

    @Nullable
    private static volatile String sLastDiagRequest;

    private static void ensureProviderObserver(@NonNull Context context) {
        if (sObserverRegistered) {
            return;
        }
        sObserverRegistered = true;
        try {
            context.getContentResolver().registerContentObserver(
                    StateKeys.stateUri(), true,
                    new android.database.ContentObserver(
                            new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sProviderDirty = true;
                        }
                    });
            AodLog.i("provider observer registered (push updates)");
        } catch (Throwable t) {
            AodLog.w("provider observer unavailable, polling only: " + t);
        }
    }

    /**
     * Combines the provider snapshot (authoritative: position/duration/state
     * come from the UI app's v4 stream) with this process's own line stream
     * (lowest latency, works even while the UI app is frozen).
     */
    @NonNull
    private static LyricDisplay buildDisplay(@NonNull ProviderLrcSource source) {
        LyricDisplay d = new LyricDisplay();
        SuperLyricSource local = sActiveSource;
        String localLine = local == null ? null : local.currentLine();
        if (localLine != null && !localLine.isEmpty()) {
            d.line = localLine;
            d.translation = local.currentTranslation();
            d.title = local.title();
            d.artist = local.artist();
            d.next = local.nextLine();
        }
        if (d.line == null) {
            d.line = source.displayLine();
            d.translation = source.displayTranslation();
            d.title = source.title();
            d.artist = source.artist();
            d.next = source.displayNext();
        }
        if (d.next == null) {
            d.next = source.displayNext();
        }
        if (d.title == null) {
            d.title = source.title();
        }
        if (d.artist == null) {
            d.artist = source.artist();
        }
        d.positionMs = source.positionNow();
        d.durationMs = source.duration();
        String state = source.playState();
        d.paused = StateKeys.PLAY_PAUSED.equals(state);
        d.playing = state == null || state.isEmpty()
                || StateKeys.PLAY_PLAYING.equals(state)
                || StateKeys.PLAY_BUFFERING.equals(state);
        return d;
    }

    /**
     * Staleness check across BOTH feeds: the provider snapshot (UI app) and
     * this process's own SuperLyric stream. Whichever fed more recently proves
     * playback is alive; only when BOTH are silent past the watchdog window is
     * the lyric dead.
     */
    private static boolean isStale(@NonNull ProviderLrcSource source) {
        long last = Math.max(source.lastFeedAt(),
                sActiveSource == null ? 0 : sActiveSource.lastEventAt());
        return last > 0 && System.currentTimeMillis() - last
                > ProviderLrcSource.STALE_MS;
    }

    @Nullable
    private static Context currentContext() {
        Context ctx = ModuleReporter.capturedContext();
        if (ctx != null) {
            return ctx;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getDeclaredMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Crash guard for the fingerprint sensor.
     *
     * <p>Root cause (crash log 2026-11-14): while lyrics update, this module
     * requests a doze pulse roughly every 4s (the only working panel refresh).
     * After a pulse completes, MIUI's DozeMachine can be left parked in
     * DOZE_PULSE_DONE (the native pulse flow returns to DOZE via callbacks our
     * synthetic broadcast does not fully drive). A fingerprint press then asks
     * for DOZE_PULSE_DONE -&gt; DOZE_PULSING, which
     * {@code DozeMachine.validateTransition} rejects with
     * {@link IllegalStateException} and SystemUI crashes.
     *
     * <p>Swallowing illegal transitions that LEAVE DOZE_PULSE_DONE lets the
     * fingerprint press proceed into PULSING - exactly what the user wanted.
     * Transitions between all other state pairs still fail loudly, so genuine
     * doze bugs stay visible.
     *
     * <p>The guard itself lives in {@link ThemeProbeV2#installDozeMachineGuard}:
     * DozeMachine is loaded by the AOD plugin classloader, so it must be hooked
     * from the loadClass watcher, not with a host-loader Class.forName here.
     */

    private void installMetadataHook() {
        try {
            java.lang.reflect.Method method =
                    MediaMetadata.class.getMethod("getString", String.class);
            AodLyricModule.buildHook(mXp, method, "aodlyric-metadata-getstring")
                    .intercept(chain -> {
                        if (!LYRIC_KEY.equals(chain.getArg(0))) {
                            return chain.proceed();
                        }
                        String lrc = currentLrc();
                        return lrc != null ? lrc : chain.proceed();
                    });
            AodLog.i("hooked MediaMetadata#getString");
        } catch (Throwable t) {
            AodLog.e("hook MediaMetadata#getString failed", t);
        }
    }

    private void installParserHook() {
        try {
            Class<?> parser = findHostClass("com.miui.maml.elements.MusicLyricParser");
            java.lang.reflect.Method parse = parser.getDeclaredMethod("parseLyric", String.class);
            parse.setAccessible(true);
            AodLyricModule.buildHook(mXp, parse, "aodlyric-parser-parselyric")
                    .intercept(chain -> {
                        Object arg = chain.getArg(0);
                        if (arg instanceof String && !((String) arg).trim().isEmpty()) {
                            AodLog.d("parseLyric: host supplied "
                                    + ((String) arg).length() + " chars");
                            return chain.proceed();
                        }
                        String lrc = currentLrc();
                        if (lrc == null) {
                            return chain.proceed();
                        }
                        AodLog.d("parseLyric: injecting " + lrc.length() + " chars");
                        return chain.proceed(new Object[]{lrc});
                    });
            AodLog.i("hooked MusicLyricParser#parseLyric");
        } catch (Throwable t) {
            mReporter.reportStatus(false, "parseLyric hook failed: " + t.getMessage(), null);
            AodLog.w("MusicLyricParser fallback hook unavailable: " + t);
        }
    }

    @NonNull
    private Class<?> findHostClass(@NonNull String name) throws ClassNotFoundException {
        try {
            return Class.forName(name, false, mHostLoader);
        } catch (ClassNotFoundException e) {
            return Class.forName(name, false, AodLyricModule.class.getClassLoader());
        }
    }

    @Nullable
    private static String currentLrc() {
        // One-shot clear requested by a stop/watchdog/switch-off: inject an
        // empty document once so the native theme resets its lyric, instead of
        // re-serving the last song's LRC on the next theme rebuild.
        if (sForceEmptyLrc) {
            sForceEmptyLrc = false;
            AodLog.i("currentLrc: forced empty injection (clear ghost)");
            return "";
        }
        ProviderLrcSource provider = sProviderLrc;
        if (provider != null && !provider.moduleEnabled()) {
            // Master switch off: nothing may be injected.
            return "";
        }
        SuperLyricSource source = sActiveSource;
        String lrc = source == null ? null : source.currentLrc();
        if (lrc == null) {
            lrc = provider == null ? null : provider.lrc();
        }
        if (lrc == null) {
            lrc = sRestoredLrc;
        }
        return lrc;
    }

    /**
     * Requests one empty LRC injection (see {@link #currentLrc()}). Called on
     * playback stop and by the stale watchdog: without it the native theme
     * keeps the old song's lyrics until the next full lyric parse.
     */
    public static void requestForceEmptyLrc() {
        sForceEmptyLrc = true;
    }

    @Nullable
    public static String snapshot() {
        SuperLyricSource source = sActiveSource;
        return source == null ? null : source.currentLrc();
    }

    public static void restore(@Nullable Object state) {
        sRestoredLrc = state instanceof String ? (String) state : null;
        AodLog.i("restore: "
                + (sRestoredLrc == null ? "no lyrics held"
                        : sRestoredLrc.length() + " chars"));
    }
}

