package cn.qxfly.aod;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.superlyricapi.LyricListener;
import com.hchen.superlyricapi.LyricView;
import com.hchen.superlyricapi.Receiver;
import com.hchen.superlyricapi.SuperLyric;
import com.hchen.superlyricapi.model.Line;
import com.hchen.superlyricapi.model.Lyric;
import com.hchen.superlyricapi.model.Stop;

/**
 * Process-wide SuperLyric publisher.
 *
 * <p>This used to live in {@link MainActivity} and unregister in
 * {@code onDestroy()}, which meant closing the app (or MIUI killing the
 * activity) silently dropped the ONLY full-LRC source in the system: the hook
 * processes would fall back to line-only payloads and native injection would
 * degrade to a single line.
 *
 * <p>It now lives in the Application: registration happens as soon as the app
 * process exists (including when the hook's provider call wakes it) and only
 * ends with the process. {@link KeepAliveService} can additionally pin the
 * process for users who want it to survive aggressive background killing.
 */
public final class LyricPublisher {

    private static final LyricPublisher INSTANCE = new LyricPublisher();

    @NonNull
    public static LyricPublisher get() {
        return INSTANCE;
    }

    private final AppReporter mReporter = new AppReporter();

    private volatile boolean mRegistered;

    /** SuperLyric 接入状态：null=尚未注册，true=已接入，false=失败/不可用。 */
    @Nullable
    private volatile Boolean mOk;

    @NonNull
    private volatile String mDetail = "";

    /** Non-null once the process started (for the UI to show a live state). */
    @Nullable
    public Boolean ok() {
        return mOk;
    }

    @NonNull
    public String detail() {
        return mDetail;
    }

    @NonNull
    public AppReporter reporter() {
        return mReporter;
    }

    /** Idempotent: safe to call from Application, the service and the UI. */
    public synchronized void ensureRegistered(@NonNull Context context) {
        AppContextHolder.set(context.getApplicationContext());
        if (mRegistered) {
            return;
        }
        mRegistered = true;
        try {
            Receiver.RegisterResult result = SuperLyric.receiver().register(new LyricListener() {
                @Override
                public void onLyric(@NonNull LyricView view) {
                    try {
                        Lyric doc = view.getLyric();
                        mReporter.publishLyric(view, LrcBuilder.build(doc));
                        Line line = view.getLine();
                        if (line != null) {
                            AodLog.d("published line: " + line.getText());
                        }
                    } catch (Throwable t) {
                        AodLog.w("publishLyric crashed: " + t);
                    }
                }

                @Override
                public void onStop(@NonNull Stop stop) {
                    // Clear the provider's lyric record (line, translation and
                    // full LRC): the app is the only full-LRC publisher, so a
                    // stop MUST wipe LYRIC_LRC here or the AOD keeps the last
                    // song's lyrics after the player stops (ghost lyrics bug).
                    try {
                        mReporter.publishStop();
                    } catch (Throwable t) {
                        AodLog.w("publishStop crashed: " + t);
                    }
                    AodLog.i("SuperLyric stopped: " + stop.getReason());
                }
            });
            mRegistered = result == Receiver.RegisterResult.OK;
            int slApi = -1;
            try {
                slApi = SuperLyric.serverApiVersion();
            } catch (Throwable ignored) {
            }
            mOk = mRegistered;
            mDetail = slApi > 0
                    ? "API " + slApi
                    : (mRegistered ? "旧版协议" : result.name());
            AodLog.i("SuperLyric publisher registered: " + result.name()
                    + " serverApiVersion=" + slApi
                    + " available=" + SuperLyric.isAvailable()
                    + " process=" + context.getPackageName());
        } catch (Throwable t) {
            mRegistered = false;
            mOk = Boolean.FALSE;
            try {
                mDetail = SuperLyric.isAvailable()
                        ? t.getClass().getSimpleName() : "未安装或未启用";
            } catch (Throwable ignored) {
                mDetail = t.getClass().getSimpleName();
            }
            AodLog.w("SuperLyric registration failed: " + t);
        }
    }
}
