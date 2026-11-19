package cn.qxfly.aod;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Publishes overlay facts that the app UI needs to display.
 *
 * <p>Kept separate from {@link AodOverlayController} so the controller does not
 * need a reference to the reporter, and so the app process (where the reporter is
 * inert) can call the same helpers defensively.
 */
public final class Reporter {

    private static volatile ModuleReporter sReporter;

    private Reporter() {
    }

    /** Called once during install with the hook process's reporter. */
    public static void attach(@NonNull ModuleReporter reporter) {
        sReporter = reporter;
    }

    /** Package-style accessor for the controller's one-shot component publish. */
    @Nullable
    public static ModuleReporter access() {
        return sReporter;
    }

    /**
     * Publishes whether the lyric is parented to the movable AOD container.
     *
     * <p>Encoded as {@code clock:yes} / {@code clock:no} inside the status detail
     * so no new provider key is needed.
     */
    public static void reportClockParenting(boolean following) {
        ModuleReporter reporter = sReporter;
        if (reporter == null) {
            return;
        }
        try {
            reporter.reportStatus(true,
                    "overlay parented to clock container: " + (following ? "clock:yes" : "clock:no"),
                    null);
        } catch (Throwable ignored) {
        }
    }
}
