package cn.qxfly.aod;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Application-context holder for the UI process. MainActivity registers itself
 * in onCreate; the provider (which may be created in a fresh process when a
 * hook process calls in) can also supply the context via attachInfo.
 */
public final class AppContextHolder {

    @Nullable
    private static volatile Context sContext;

    private AppContextHolder() {
    }

    public static void set(@NonNull Context context) {
        sContext = context.getApplicationContext();
    }

    @Nullable
    public static Context get() {
        return sContext;
    }
}
