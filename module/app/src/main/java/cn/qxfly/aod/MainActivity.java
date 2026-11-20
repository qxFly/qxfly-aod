package cn.qxfly.aod;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import cn.qxfly.aod.ui.core.CardsData;
import cn.qxfly.aod.ui.core.ModuleData;
import cn.qxfly.aod.ui.core.UiHub;

/**
 * Module UI host.
 *
 * <p>Two responsibilities live here on the Java side (the Compose layer is
 * purely presentational and talks to it through {@link ModuleData}):
 *
 * <ul>
 *   <li>Reading the hook-side status snapshot from {@link AodStateProvider} so
 *       the app can show what the AOD process actually did.</li>
 *   <li>Registering SuperLyric in this process. The UI app holds
 *       {@code superlyricapi_version_code=40}, so it receives FULL lyric
 *       payloads; com.miui.aod does not, therefore the app republishes the LRC
 *       into the provider from where the hook processes fetch it.</li>
 * </ul>
 */
public final class MainActivity extends androidx.activity.ComponentActivity {

    @Nullable
    private ContentResolver mResolver;
    @Nullable
    private StateObserver mObserver;
    /** SuperLyric 接入状态：null=尚未注册，true=已接入，false=失败/不可用。 */
    @Nullable
    private volatile Boolean mSuperLyricOk;
    /** 接入详情文本（"API 4" / "旧版协议" / 失败原因）。 */
    @NonNull
    private volatile String mSuperLyricDetail = "";

    @Nullable
    private ModuleData mHub;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppContextHolder.set(this);
        mResolver = getContentResolver();
        HiddenApi.exempt();

        // ---- data hub: Compose reads this; Java owns all provider logic ----
        ModuleData hub = new ModuleData(new cn.qxfly.aod.ui.core.AppSettings(this));
        hub.setSaveStyle(style -> {
            Bundle b = style.toBundle();
            if (style.isCustomFont()) {
                // Marker for the hook's style stamp: a re-import overwrites the
                // same file while typefaceName stays "custom", so without this
                // the hook would keep its cached (old) Typeface forever.
                java.io.File font = new java.io.File(getFilesDir(),
                        "fonts/" + StateKeys.FONT_FILE);
                b.putString(StateKeys.STYLE_FONT_STAMP, font.isFile()
                        ? font.lastModified() + "_" + font.length()
                        : "none");
            }
            b.putString(StateKeys.TYPE, StateKeys.TYPE_STYLE);
            mResolver.call(StateKeys.stateUri(), StateKeys.METHOD_UPDATE, null, b);
            AodLog.i("style saved");
        });
        hub.setReadStyle(this::readStyleFromProvider);
        // Master switch: a plain module-type provider update; the hook reads
        // it on every poll and force-clears the overlay / injection when off.
        hub.setModuleEnabledSetter(enabled -> {
            try {
                Bundle b = new Bundle();
                b.putString(StateKeys.TYPE, StateKeys.TYPE_MODULE);
                b.putBoolean(StateKeys.MODULE_ENABLED, enabled);
                mResolver.call(StateKeys.stateUri(), StateKeys.METHOD_UPDATE, null, b);
                AodLog.i("master switch -> " + enabled);
            } catch (Throwable t) {
                AodLog.w("master switch write failed: " + t);
            }
        });
        mHub = hub;
        UiHub.INSTANCE.setData(hub);
        hub.setRefresher(this::refreshState);

        if (!mountMiuixUi()) {
            setContentView(fallbackView());
            AodLog.w("MiuiX UI unavailable; showing fallback message");
        }

        mObserver = new StateObserver(new Handler(Looper.getMainLooper()));
        mResolver.registerContentObserver(StateKeys.stateUri(), true, mObserver);
        refreshState();
        // The publisher also registers from Application.onCreate; this call is
        // the belt-and-braces path for processes started before the upgrade.
        LyricPublisher.get().ensureRegistered(this);
        syncPublisherState();
    }

    /** Mirrors the process-wide publisher state into the UI fields. */
    private void syncPublisherState() {
        mSuperLyricOk = LyricPublisher.get().ok();
        mSuperLyricDetail = LyricPublisher.get().detail();
    }

    /**
     * Mounts the Compose UI.
     *
     * @return true when the Compose screen is showing
     */
    private boolean mountMiuixUi() {
        try {
            return cn.qxfly.aod.ui.ModuleUi.INSTANCE.mount(this);
        } catch (Throwable t) {
            AodLog.w("mountMiuixUi failed: " + t);
            return false;
        }
    }

    /**
     * Minimal screen for the (unexpected) case where Compose cannot mount.
     * Carries no feature entry points - the functional UI is Compose-only.
     */
    @NonNull
    private android.view.View fallbackView() {
        TextView tv = new TextView(this);
        tv.setText("界面加载失败\n\n"
                + "本界面需要 Compose 运行时；请检查 LSPosed 模块是否启用，\n"
                + "并用 adb logcat -s AodLyric 查看详细日志。");
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    /** Reads the stored appearance so the UI opens with the current values. */
    @NonNull
    private LyricStyle readStyleFromProvider() {
        try {
            Bundle snap = mResolver.call(StateKeys.stateUri(), StateKeys.METHOD_GET, null, null);
            Bundle style = new Bundle();
            boolean any = false;
            if (snap != null) {
                for (String key : StateKeys.STYLE_KEYS) {
                    String v = snap.getString(key);
                    if (v != null) {
                        style.putString(key, v);
                        any = true;
                    }
                }
            }
            return any ? LyricStyle.fromBundle(style) : new LyricStyle();
        } catch (Throwable t) {
            return new LyricStyle();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // MIUI freezes the app process in the background: ContentObserver
        // callbacks fire only while unfrozen, so anything reported while
        // frozen (hook process re-enumeration after an AOD-style change,
        // heartbeat updates) is silently missed. Re-read the provider every
        // time the user comes back to the app.
        syncPublisherState();
        refreshState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mObserver != null) {
            mResolver.unregisterContentObserver(mObserver);
        }
        // The SuperLyric publisher intentionally stays registered for the life
        // of the process (see LyricPublisher): unregistering here used to drop
        // the only full-LRC source as soon as the activity was destroyed.
    }

    private void refreshState() {
        // The UI must survive any state-rendering bug: the process also serves
        // the provider and hook-side lyric delivery, and a crash loop here
        // made the whole app unopenable.
        try {
            refreshStateInner();
        } catch (Throwable t) {
            AodLog.w("refreshState crashed: " + t);
        }
    }

    private void refreshStateInner() {
        syncPublisherState();
        Bundle snap = null;
        try {
            snap = mResolver.call(StateKeys.stateUri(), StateKeys.METHOD_GET, null, null);
        } catch (Throwable ignored) {
        }
        if (snap == null || snap.size() == 0) {
            String module = "尚未收到 Hook 进程上报。\n\n"
                    + "排查顺序：\n"
                    + "1. LSPosed 里已启用本模块并勾选 com.miui.aod\n"
                    + "2. 已重启 com.miui.aod（或重启手机）\n"
                    + "3. adb logcat -s AodLyric 看有没有输出\n"
                    + "   （完全没输出 = 模块没被 LSPosed 加载）\n"
                    + "4. 给本 App 允许自启动 + 省电策略无限制\n"
                    + "   （MIUI 强杀后台后 Provider 拉不起来，上报全丢）\n"
                    + "5. 正在播放音乐并点亮息屏";
            applyCards(module, Boolean.FALSE, "");
            return;
        }

        StringBuilder s = new StringBuilder();
        String api = snap.getString(StateKeys.MODULE_API);
        String fw = snap.getString(StateKeys.MODULE_FRAMEWORK);
        if (api != null) {
            s.append("框架 API: ").append(api);
            if (fw != null) {
                s.append("  (").append(fw).append(")");
            }
            s.append('\n');
        }
        Boolean hooks = snap.getBoolean(StateKeys.HOOKS_INSTALLED, false);
        String detail = snap.getString(StateKeys.HOOK_DETAIL);
        s.append("Hook 状态: ").append(hooks ? "已安装" : "未安装/失败");
        if (detail != null) {
            s.append("  [").append(detail).append("]");
        }
        s.append('\n');
        String reg = snap.getString(StateKeys.REGISTER_RESULT);
        if (reg != null) {
            s.append("Hook 进程 SuperLyric: ").append(reg).append('\n');
        }
        Long statusAt = snap.getLong(StateKeys.STATUS_AT, 0);
        if (statusAt > 0) {
            s.append("上报时间: ").append(fmt(statusAt));
        }
        boolean moduleEnabled = snap.getBoolean(StateKeys.MODULE_ENABLED, true);

        StringBuilder h = new StringBuilder();
        String track = snap.getString(StateKeys.LYRIC_TRACK);
        String lineText = null;
        String feedTime = "";
        if (track == null) {
            // Empty = "no feeds yet"; the Compose layer substitutes its own
            // resource string, so the wording lives in exactly one place.
        } else {
            h.append("曲目: ").append(track).append('\n');
            lineText = snap.getString(StateKeys.LYRIC_LINE);
            h.append("歌词: ").append(lineText == null ? "(空)" : lineText).append('\n');
            feedTime = fmt(snap.getLong(StateKeys.LYRIC_AT, 0));
            h.append("更新时间: ").append(feedTime);
        }

        applyCards(s.toString(), hooks, h.toString());
        // Structured state for the redesigned home cards; publishes the full
        // data class so Compose never has to parse the display strings.
        ModuleData hub0 = mHub;
        if (hub0 != null) {
            hub0.publishCards(new CardsData(
                    s.toString(), hooks, h.toString(),
                    hooks,
                    api == null ? "" : api,
                    fw == null ? "" : fw,
                    track == null ? "" : track,
                    lineText == null ? "" : lineText,
                    feedTime,
                    moduleEnabled,
                    mSuperLyricOk,
                    mSuperLyricDetail));
        }
        publishComponents(snap);
    }

    /**
     * Parses the hook-side component enumeration ("id|class|bounds" lines) and
     * publishes it for the settings page's anchor dropdown. Unknown ids get a
     * readable Chinese label where we have one.
     */
    private void publishComponents(@Nullable Bundle snap) {
        if (snap == null) {
            return;
        }
        String raw = snap.getString(StateKeys.COMPONENTS_LIST);
        ModuleData hub = mHub;
        if (raw == null || raw.isEmpty() || hub == null) {
            return;
        }
        java.util.List<cn.qxfly.aod.ui.core.AnchorComponent> list =
                new java.util.ArrayList<>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length < 2 || parts[0].isEmpty()) {
                continue;
            }
            String id = parts[0];
            String cls = parts[1];
            String bounds = parts.length > 2 ? parts[2] : "";
            list.add(new cn.qxfly.aod.ui.core.AnchorComponent(id, componentLabel(id, cls, bounds)));
        }
        java.util.List<cn.qxfly.aod.ui.core.AnchorComponent> old =
                hub.getComponents().getValue();
        if (!list.equals(old)) {
            hub.publishComponents(list);
        }
    }

    /** "gradient_layout" -> "时钟区（渐变容器）" style labels. */
    @NonNull
    private static String componentLabel(@NonNull String id, @NonNull String cls,
                                         @NonNull String bounds) {
        String cn;
        switch (id) {
            case "clock" -> cn = "大时钟";
            case "image" -> cn = "图片";
            case "timedate" -> cn = "时间日期组件";
            case "notify" -> cn = "通知";
            case "clock_horizontal" -> cn = "时钟（横向）";
            case "date" -> cn = "日期";
            case "date_lunar" -> cn = "农历";
            case "gradient_layout" -> cn = "时钟区（渐变容器）";
            case "battery_step_container" -> cn = "电量";
            case "image_shower", "image_shower_container", "image_selector_shower" -> cn = "壁纸照片";
            case "aod_icons_container" -> cn = "通知图标";
            case "aod_content_container", "content" -> cn = "内容区";
            case "aod_bg" -> cn = "背景";
            case "clock_container" -> cn = "防烧屏容器";
            default -> {
                if (cls.contains("TextView")) {
                    cn = "文本";
                } else if (cls.contains("ImageView")) {
                    cn = "图片";
                } else {
                    cn = "组件";
                }
            }
        }
        return cn + " · " + id + " " + bounds;
    }

    /** Publishes the status card into the Compose state. */
    private void applyCards(@Nullable String module, @Nullable Boolean hooks,
                            @Nullable String hook) {
        ModuleData hub = mHub;
        if (hub == null) {
            return;
        }
        CardsData old = hub.getCards().getValue();
        // No snapshot here: carry the previous structured fields through so
        // the info cards don't blank out on a transient read failure.
        hub.publishCards(new CardsData(
                module != null ? module : old.getModule(),
                hooks != null ? hooks : old.getModuleOk(),
                hook != null ? hook : old.getHook(),
                old.getHooksInstalled(), old.getModuleApi(), old.getFramework(),
                old.getTrack(), old.getLyricLine(), old.getFeedTime(),
                old.getModuleEnabled(), old.getSuperLyricOk(),
                old.getSuperLyricDetail()));
    }

    private class StateObserver extends ContentObserver {
        StateObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    }

    private String fmt(long ms) {
        // Was DateFormat.getDateFormat(null): a null Context NPEs. This helper
        // only runs once report data exists - which is why earlier rounds never
        // crashed here and the first successful provider delivery did.
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                .format(new java.util.Date(ms));
    }
}
