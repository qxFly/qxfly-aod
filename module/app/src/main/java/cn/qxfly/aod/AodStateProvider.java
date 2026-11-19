package cn.qxfly.aod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Holds the latest state reported by the hook processes and the UI's own
 * SuperLyric stream, for the UI to display and for the hook processes to fetch
 * injection LRC from.
 *
 * <p>The snapshot is PERSISTED to a file in the provider's private storage on
 * every update and reloaded in onCreate: the module app process is frequently
 * killed by MIUI, and an in-memory-only snapshot made the UI look dead (state
 * reset to "no reports" every time the process restarted).
 */
public final class AodStateProvider extends ContentProvider {

    private static final String[] COLUMNS = {"snapshot"};
    private static final String SNAPSHOT_FILE = "state_snapshot.parcel";

    private static volatile Bundle sSnapshot = new Bundle();

    @Override
    public boolean onCreate() {
        // A crash here kills the whole app process on EVERY start (hook
        // processes also start the provider by polling), which presents as
        // "app can't be opened". Never let the framework see a throwable.
        try {
            loadPersisted();
        } catch (Throwable t) {
            android.util.Log.w(AodLyricModule.TAG, "state load crashed: " + t);
        }
        return true;
    }

    @Override
    @Nullable
    public Bundle call(@NonNull String method, @Nullable String arg,
                       @Nullable Bundle extras) {        try {
            switch (method) {
                case StateKeys.METHOD_UPDATE:
                    if (extras != null) {
                        apply(extras);
                        persist();
                        notifyChanged();
                    }
                    return null;
                case StateKeys.METHOD_GET:
                    return snapshot();
                case StateKeys.METHOD_CLEAR:
                    sSnapshot = new Bundle();
                    persist();
                    notifyChanged();
                    return null;
                default:
                    return null;
            }
        } catch (Throwable t) {
            android.util.Log.w(AodLyricModule.TAG, "provider call crashed: " + t);
            return null;
        }
    }

    /**
     * Serves the imported custom font to the hook (systemui) process:
     * {@code content://authority/font/custom.ttf}.
     *
     * <p>The font lives in this app's private storage, which systemui cannot
     * read directly; {@link android.content.ContentResolver#openFileDescriptor}
     * through the exported provider is the only clean bridge. Path traversal
     * is blocked by flattening the requested name to a bare file name and the
     * served set is restricted to the fonts directory.
     */
    @Override
    @NonNull
    public android.os.ParcelFileDescriptor openFile(@NonNull Uri uri,
                                                    @NonNull String mode)
            throws java.io.FileNotFoundException {
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && StateKeys.PATH_FONT.equals(seg.get(0))) {
            String name = new java.io.File(seg.get(1)).getName();
            Context context = getContext();
            if (context != null) {
                java.io.File dir = new java.io.File(context.getFilesDir(), "fonts");
                java.io.File file = new java.io.File(dir, name);
                if (file.isFile() && file.length() > 0) {
                    return android.os.ParcelFileDescriptor.open(file,
                            android.os.ParcelFileDescriptor.MODE_READ_ONLY);
                }
            }
        }
        throw new java.io.FileNotFoundException(uri.toString());
    }

    private static void apply(@NonNull Bundle in) {
        Bundle next = new Bundle(snapshot());
        String type = in.getString(StateKeys.TYPE);
        long now = System.currentTimeMillis();

        if (StateKeys.TYPE_MODULE.equals(type)) {
            // copyIfPresent (not putString): a master-switch write carries only
            // type+moduleEnabled and must not wipe the hook's api/framework
            // report; the hook's own report always carries both.
            copyIfPresent(next, in, StateKeys.MODULE_API);
            copyIfPresent(next, in, StateKeys.MODULE_FRAMEWORK);
            // Master switch: only overwritten when the writer actually sent it,
            // so the hook's own module reports (api/framework, no such key)
            // never clobber the user's choice.
            if (in.containsKey(StateKeys.MODULE_ENABLED)) {
                next.putBoolean(StateKeys.MODULE_ENABLED,
                        in.getBoolean(StateKeys.MODULE_ENABLED));
            }
            next.putLong(StateKeys.MODULE_READY_AT, now);
        } else if (StateKeys.TYPE_STATUS.equals(type)) {
            next.putBoolean(StateKeys.HOOKS_INSTALLED,
                    in.getBoolean(StateKeys.HOOKS_INSTALLED));
            next.putString(StateKeys.HOOK_DETAIL, in.getString(StateKeys.HOOK_DETAIL));
            next.putString(StateKeys.REGISTER_RESULT, in.getString(StateKeys.REGISTER_RESULT));
            next.putLong(StateKeys.STATUS_AT, now);
        } else if (StateKeys.TYPE_LYRIC.equals(type)) {
            copyIfPresent(next, in, StateKeys.LYRIC_TRACK);
            copyIfPresent(next, in, StateKeys.LYRIC_LINE);
            copyIfPresent(next, in, StateKeys.LYRIC_TRANSLATION);
            copyIfPresent(next, in, StateKeys.LYRIC_INDEX);
            copyIfPresent(next, in, StateKeys.LYRIC_TOTAL);
            copyIfPresent(next, in, StateKeys.LYRIC_CHARS);
            copyIfPresent(next, in, StateKeys.LYRIC_LRC);
            copyIfPresent(next, in, StateKeys.LYRIC_ID);
            next.putLong(StateKeys.LYRIC_AT, now);
        } else if (StateKeys.TYPE_STYLE.equals(type)) {
            for (String key : StateKeys.STYLE_KEYS) {
                copyIfPresent(next, in, key);
            }
        } else if (StateKeys.TYPE_COMPONENTS.equals(type)) {
            copyIfPresent(next, in, StateKeys.COMPONENTS_LIST);
            copyIfPresent(next, in, StateKeys.COMPONENTS_AT);
        }
        sSnapshot = next;
    }

    private static void copyIfPresent(@NonNull Bundle out, @NonNull Bundle in, String key) {
        String value = in.getString(key);
        if (value != null) {
            out.putString(key, value);
        }
    }

    private static Bundle snapshot() {
        return new Bundle(sSnapshot);
    }

    private void persist() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            Bundle snap = sSnapshot;
            StringBuilder sb = new StringBuilder();
            for (String key : snap.keySet()) {
                Object v = snap.get(key);
                if (v instanceof String) {
                    sb.append("S|").append(key).append('|')
                      .append(base64(((String) v).getBytes("UTF-8"))).append('\n');
                } else if (v instanceof Boolean) {
                    sb.append("B|").append(key).append('|').append(v).append('\n');
                } else if (v instanceof Long) {
                    sb.append("L|").append(key).append('|').append(v).append('\n');
                }
            }
            java.io.FileOutputStream stream = context.openFileOutput(
                    SNAPSHOT_FILE, Context.MODE_PRIVATE);
            try {
                stream.write(sb.toString().getBytes("UTF-8"));
            } finally {
                stream.close();
            }
        } catch (Throwable t) {
            android.util.Log.w(AodLyricModule.TAG, "persist failed: " + t);
        }
    }

    private void loadPersisted() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            byte[] raw;
            try (java.io.FileInputStream in = context.openFileInput(SNAPSHOT_FILE)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                raw = bo.toByteArray();
            }
            Bundle loaded = new Bundle();
            for (String line : new String(raw, "UTF-8").split("\n")) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3) {
                    continue;
                }
                // Runtime lyric state is NOT persisted across process restarts:
                // reloading LYRIC_LINE/LYRIC_LRC resurrected the last song's
                // lyrics on the AOD after a reboot with nothing playing - the
                // "ghost lyrics" bug. Settings (module/style/status) still
                // persist, which is what loadPersisted exists for.
                if (parts[1].startsWith("lyric")) {
                    continue;
                }
                if ("S".equals(parts[0])) {
                    loaded.putString(parts[1], new String(
                            java.util.Base64.getDecoder().decode(parts[2]), "UTF-8"));
                } else if ("B".equals(parts[0])) {
                    loaded.putBoolean(parts[1], Boolean.parseBoolean(parts[2]));
                } else if ("L".equals(parts[0])) {
                    loaded.putLong(parts[1], Long.parseLong(parts[2]));
                }
            }
            sSnapshot = loaded;
            android.util.Log.i(AodLyricModule.TAG,
                    "state loaded from disk: " + loaded.size() + " keys");
        } catch (Throwable ignored) {
            // First run or unreadable file: start with an empty snapshot.
        }
    }

    @NonNull
    private static String base64(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    private void notifyChanged() {
        Context context = getContext();
        if (context != null) {
            context.getContentResolver().notifyChange(StateKeys.stateUri(), null);
        }
    }

    @Override
    @Nullable
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return new MatrixCursor(COLUMNS);
    }

    @Override
    @Nullable
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.item/vnd.cn.qxfly.aod.state";
    }

    @Override
    @Nullable
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
