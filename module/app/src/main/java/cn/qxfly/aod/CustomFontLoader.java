package cn.qxfly.aod;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Loads the user-imported custom font in the hook (systemui) process.
 *
 * <p>The font file lives in the module app's private storage; systemui reads
 * it through the exported state provider
 * ({@code content://cn.qxfly.aod.state/font/custom.ttf}). The public Typeface
 * API has no InputStream/FileDescriptor constructor, so the stream is copied
 * into systemui's own cacheDir once and {@link Typeface#createFromFile}
 * loads it from there.
 *
 * <p>The copy and the loaded Typeface are cached process-wide;
 * {@link #invalidate()} drops both whenever the style changes so a re-import
 * takes effect without touching the AOD lifecycle.
 */
final class CustomFontLoader {

    private static final String CACHE_FILE = "aod_custom_font.ttf";

    private static volatile Typeface sCached;
    private static volatile boolean sFailed;

    private CustomFontLoader() {
    }

    /**
     * The imported font, or null when unavailable (not imported yet / provider
     * gone / parse failure). The caller falls back to the system default.
     */
    @Nullable
    static Typeface load(@NonNull Context context) {
        Typeface cached = sCached;
        if (cached != null) {
            return cached;
        }
        if (sFailed) {
            return null;
        }
        try {
            java.io.File dst = new java.io.File(context.getCacheDir(), CACHE_FILE);
            if (!dst.isFile() || dst.length() == 0) {
                // Copy once per import: the file must outlive createFromFile
                // (the Typeface mmaps it), so it stays until invalidate().
                java.io.File dir = dst.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                    sFailed = true;
                    return null;
                }
                java.io.InputStream in = context.getContentResolver()
                        .openInputStream(StateKeys.fontUri());
                if (in == null) {
                    sFailed = true;
                    AodLog.i("custom font: not imported");
                    return null;
                }
                try (java.io.InputStream ignored = in) {
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    } finally {
                        out.close();
                    }
                }
            }
            Typeface tf = Typeface.createFromFile(dst);
            if (tf == null) {
                sFailed = true;
                AodLog.w("custom font: parser returned null");
                return null;
            }
            sCached = tf;
            sFailed = false;
            AodLog.i("custom font loaded (" + CACHE_FILE + ")");
            return tf;
        } catch (Throwable t) {
            sFailed = true;
            AodLog.w("custom font load failed: " + t);
            return null;
        }
    }

    /** Drops the cache so the next {@link #load} re-copies the imported file. */
    static void invalidate() {
        sCached = null;
        sFailed = false;
    }
}
