package cn.qxfly.aod;

import android.app.Application;

/**
 * Process entry point.
 *
 * <p>Two things must happen even when no activity is around:
 * <ul>
 *   <li>capture the Application Context, so provider/log access works from the
 *       hook-adjacent code paths;</li>
 *   <li>register the SuperLyric publisher, which is the only full-LRC source
 *       in the system. It used to live in MainActivity and died with it.</li>
 * </ul>
 */
public final class AodApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppContextHolder.set(this);
        try {
            LyricPublisher.get().ensureRegistered(this);
        } catch (Throwable t) {
            AodLog.w("publisher registration failed at Application.onCreate: " + t);
        }
        if (KeepAliveService.isEnabled(this)) {
            KeepAliveService.start(this);
        }
    }
}
