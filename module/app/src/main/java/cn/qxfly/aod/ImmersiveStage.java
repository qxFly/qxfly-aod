package cn.qxfly.aod;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 息屏「沉浸歌词」：播放音乐时让息屏只剩歌词。
 *
 * <p>做法是在 AOD 根视图里叠一个整屏舞台（stage），歌词放进舞台内的画布
 * （canvas）。两种模式（见 {@link StateKeys#STYLE_IMMERSIVE}）：
 *
 * <ul>
 *   <li><b>纯黑</b>：舞台用不透明黑铺满整屏，主题内容被完全盖住。
 *       不依赖任何组件名，换什么 AOD 主题都成立；纯黑在 OLED 上不发光。</li>
 *   <li><b>保留壁纸</b>：舞台透明，改为把 {@code clock_container} /
 *       {@code aod_icons_container} 等组件设成 INVISIBLE，背景仍是主题自己的。</li>
 * </ul>
 *
 * <p>三个必须由本类自己处理、不能靠主题的地方：
 *
 * <ul>
 *   <li><b>防烧屏</b>：普通模式下歌词挂在时钟容器里，蹭 MIUI 的漂移；
 *       搬到舞台后不再跟随，所以这里自己按 {@link #DRIFT} 缓慢平移。</li>
 *   <li><b>横屏</b>：AOD 窗口本身不跟随设备旋转（实测 1200x2608 固定），
 *       所以横放时把画布按 {@code -atan2(gx, gy)} 旋转 ±90°，画布尺寸对调，
 *       文字就沿屏幕长边排布。窗口本身就是横屏时（宽 &gt; 高）不再旋转。</li>
 *   <li><b>指纹/解锁</b>：黑幕会挡住屏下指纹。触摸时把舞台临时透明 4 秒，
 *       露出原来的息屏内容和指纹区。触摸信号复用 AOD 自己的
 *       {@code dispatchTouchEvent}，不额外加监听。</li>
 * </ul>
 */
public final class ImmersiveStage {

    /** 舞台最多向上找几层宿主：AOD 根通常是整屏，找不到就用传入的 root。 */
    private static final int MAX_HOST_UP = 2;

    /** 触摸后露出原息屏内容的时长。 */
    private static final long REVEAL_MS = 4000L;

    /** 防烧屏漂移间隔与步长（px）。 */
    private static final long DRIFT_PERIOD_MS = 45_000L;
    private static final int[][] DRIFT = {
            {0, 0}, {4, 0}, {4, 6}, {0, 6}, {-4, 6}, {-4, 0}, {-4, -6}, {0, -6},
    };

    /**
     * 只有设备接近"立起来"时，重力在屏幕平面上的投影方向才等于平面内朝向。
     * 实测：手机靠支架斜放（离平放约 27°）时重力是 (4.47, 0.51, 8.71)，
     * 投影长度只有 4.5 —— 用它会算出 96°，把斜放误判成横屏。
     * 因此要求投影长度 &gt; 6.0（约离竖直 52° 以内）才认横屏，否则按竖屏处理。
     */
    private static final float UPRIGHT_THRESHOLD = 6.0f;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Nullable
    private static volatile FrameLayout sStage;
    @Nullable
    private static volatile FrameLayout sCanvas;
    @Nullable
    private static volatile ViewGroup sHost;
    @Nullable
    private static java.lang.ref.WeakReference<TextView> sTv;
    @Nullable
    private static volatile LyricStyle sStyle;

    /** 模式 2 被隐藏起来的组件；离开时要恢复。 */
    private static final List<java.lang.ref.WeakReference<View>> sHidden = new ArrayList<>();

    private static volatile int sMode = StateKeys.IMMERSIVE_OFF;
    private static int sRotation;
    private static float sBaseY;
    private static long sDriftAt;
    private static int sDriftStep;
    private static int sLastLayoutSig = Integer.MIN_VALUE;

    @Nullable
    private static SensorManager sSensors;
    @Nullable
    private static SensorEventListener sListener;

    @Nullable
    private static Runnable sRevealEnd;

    private ImmersiveStage() {
    }

    /** True while a stage is installed (content may still be hidden). */
    static boolean active() {
        return sStage != null && sMode != StateKeys.IMMERSIVE_OFF;
    }

    /** True when the stage currently paints an opaque background. */
    private static boolean isBlack() {
        return sMode == StateKeys.IMMERSIVE_BLACK;
    }

    // ── lifecycle ────────────────────────────────────────────────────

    /**
     * Creates / updates / tears down the stage so it matches {@code style}.
     *
     * <p>Called on every attach and on every style push. Idempotent: an
     * unchanged style only re-applies the layout.
     */
    static void sync(@NonNull ViewGroup root, @NonNull TextView tv, @Nullable LyricStyle style) {
        int want = style == null ? StateKeys.IMMERSIVE_OFF : style.immersive;
        if (want == StateKeys.IMMERSIVE_OFF) {
            if (sStage != null) {
                release(root, tv);
            }
            return;
        }
        if (sStage != null && sMode != want) {
            // 模式切换：先把视图交还给普通挂载点，再按新模式重建。
            release(root, tv);
        }
        if (sStage == null) {
            create(root, tv, want);
        }
        sStyle = style;
        if (sCanvas != null && tv.getParent() != sCanvas) {
            detachFromParent(tv);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sCanvas.addView(tv, lp);
        }
        sTv = new java.lang.ref.WeakReference<>(tv);
        applyBackground(want);
        if (want == StateKeys.IMMERSIVE_WALLPAPER) {
            hideComponents();
        } else {
            restoreComponents();
        }
        if (style != null && style.resolvedRotMode() == StateKeys.IMMERSIVE_ROT_AUTO) {
            startSensor(root.getContext());
        } else {
            stopSensor();
            sRotation = fixedRotation(style == null ? StateKeys.IMMERSIVE_ROT_NONE
                    : style.resolvedRotMode());
        }
        layout(tv);
    }

    /**
     * Fixed rotation for the manual modes: 0° / 90° / 180° / -90°(=270°).
     * -90° instead of 270° keeps the canvas math (turned = ±90) simple.
     */
    private static int fixedRotation(int mode) {
        switch (mode) {
            case StateKeys.IMMERSIVE_ROT_90:
                return 90;
            case StateKeys.IMMERSIVE_ROT_180:
                return 180;
            case StateKeys.IMMERSIVE_ROT_270:
                return -90;
            default:
                return 0;
        }
    }

    /** True when {@link #sync} would install a stage for this style. */
    static boolean wants(@Nullable LyricStyle style) {
        return style != null && style.immersive != StateKeys.IMMERSIVE_OFF;
    }

    private static void create(@NonNull ViewGroup root, @NonNull TextView tv, int mode) {
        ViewGroup host = pickHost(root);
        Context ctx = host.getContext();
        FrameLayout stage = new FrameLayout(ctx);
        stage.setClickable(false);
        stage.setFocusable(false);
        stage.setFocusableInTouchMode(false);
        // 舞台本身不绘制任何东西（子视图先画），背景色由 applyBackground 设。
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        host.addView(stage, lp);

        FrameLayout canvas = new FrameLayout(ctx);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(1, 1, Gravity.CENTER);
        stage.addView(canvas, clp);

        sStage = stage;
        sCanvas = canvas;
        sHost = host;
        sMode = mode;
        sRotation = 0;
        sDriftAt = SystemClock.elapsedRealtime();
        sDriftStep = 0;
        sLastLayoutSig = Integer.MIN_VALUE;
        AodLyricModule.i("immersive stage created (mode=" + mode + ", host="
                + host.getClass().getSimpleName() + " " + host.getWidth() + "x"
                + host.getHeight() + ")");
    }

    /**
     * AOD 根通常已经是整屏（1200x2608）。万一拿到的是内层容器（例如
     * clock_container），整屏遮罩就会只盖住那一块 —— 向上找一到两层整屏祖先。
     */
    @NonNull
    private static ViewGroup pickHost(@NonNull ViewGroup root) {
        int screenW = root.getResources().getDisplayMetrics().widthPixels;
        ViewGroup best = root;
        View v = root;
        for (int i = 0; i < MAX_HOST_UP; i++) {
            ViewParent p = v.getParent();
            if (!(p instanceof ViewGroup)) {
                break;
            }
            ViewGroup g = (ViewGroup) p;
            if (g.getWidth() >= screenW * 0.9f) {
                best = g;
                v = g;
            } else {
                break;
            }
        }
        return best;
    }

    private static void applyBackground(int mode) {
        if (sStage == null) {
            return;
        }
        sStage.setAlpha(1f);
        sStage.setBackgroundColor(mode == StateKeys.IMMERSIVE_BLACK
                ? 0xFF000000 : 0x00000000);
    }

    /**
     * 把视图交还给普通模式（时钟容器或 AOD 根），并拆掉舞台。
     * 组件可见性、传感器、漂移状态全部复原。
     */
    static void release(@Nullable ViewGroup root, @NonNull TextView tv) {
        restoreComponents();
        stopSensor();
        detachFromParent(tv);
        FrameLayout stage = sStage;
        sStage = null;
        sCanvas = null;
        sMode = StateKeys.IMMERSIVE_OFF;
        sStyle = null;
        sTv = null;
        sRotation = 0;
        sBaseY = 0f;
        if (sRevealEnd != null) {
            MAIN.removeCallbacks(sRevealEnd);
            sRevealEnd = null;
        }
        ViewGroup host = sHost;
        sHost = null;
        if (stage != null) {
            ViewParent p = stage.getParent();
            if (p instanceof ViewGroup) {
                ((ViewGroup) p).removeView(stage);
            }
        }
        if (root != null) {
            // 交还挂载点：优先时钟容器（防烧屏漂移），否则 AOD 根。
            AodOverlayController.reparentHome(root, tv);
        } else if (host != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            host.addView(tv, lp);
        }
        AodLyricModule.i("immersive stage released");
    }

    /** AOD 会话结束时清空（root 已经失效，不再移动视图）。 */
    static void reset() {
        restoreComponents();
        stopSensor();
        if (sRevealEnd != null) {
            MAIN.removeCallbacks(sRevealEnd);
            sRevealEnd = null;
        }
        FrameLayout stage = sStage;
        sStage = null;
        sCanvas = null;
        sHost = null;
        sTv = null;
        sStyle = null;
        sMode = StateKeys.IMMERSIVE_OFF;
        sRotation = 0;
        sBaseY = 0f;
        if (stage != null) {
            ViewParent p = stage.getParent();
            if (p instanceof ViewGroup) {
                ((ViewGroup) p).removeView(stage);
            }
            AodLyricModule.i("immersive stage reset");
        }
    }

    // ── content visibility ───────────────────────────────────────────

    /**
     * 有没有歌词。没有歌词时必须让出整屏，否则息屏会被一块黑幕长期盖住
     * （时钟、通知、指纹全看不见）。
     */
    static void setContentVisible(boolean visible) {
        if (sStage == null) {
            return;
        }
        int target = visible ? View.VISIBLE : View.GONE;
        if (sStage.getVisibility() != target) {
            sStage.setVisibility(target);
            AodLyricModule.i("immersive stage " + (visible ? "shown" : "hidden"));
        }
        if (!visible) {
            restoreComponents();
        } else if (sMode == StateKeys.IMMERSIVE_WALLPAPER) {
            hideComponents();
        }
    }

    // ── placement ────────────────────────────────────────────────────

    /** Places the lyric inside the canvas (rotation + ratio + drift). */
    static void layout(@NonNull TextView tv) {
        FrameLayout stage = sStage;
        FrameLayout canvas = sCanvas;
        if (stage == null || canvas == null) {
            return;
        }
        int sw = stage.getWidth();
        int sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) {
            sw = stage.getResources().getDisplayMetrics().widthPixels;
            sh = stage.getResources().getDisplayMetrics().heightPixels;
        }
        boolean windowLandscape = sw > sh;
        int rot = windowLandscape ? 0 : sRotation;
        boolean turned = rot == 90 || rot == -90;
        int cw = turned ? sh : sw;
        int ch = turned ? sw : sh;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) canvas.getLayoutParams();
        if (lp.width != cw || lp.height != ch) {
            lp.width = cw;
            lp.height = ch;
            lp.gravity = Gravity.CENTER;
            canvas.setLayoutParams(lp);
        }
        canvas.setPivotX(cw / 2f);
        canvas.setPivotY(ch / 2f);
        canvas.setRotation(rot);

        // 九宫格：行 = 垂直（上/中/下），列 = 水平（左/中/右）。
        int grid = sStyle == null ? StateKeys.IMMERSIVE_GRID_DEFAULT : sStyle.immersiveGrid;
        if (grid < StateKeys.IMMERSIVE_GRID_MIN || grid > StateKeys.IMMERSIVE_GRID_MAX) {
            grid = StateKeys.IMMERSIVE_GRID_DEFAULT;
        }
        int col = grid % 3;
        int row = grid / 3;
        float driftX = DRIFT[sDriftStep][0];
        float driftY = DRIFT[sDriftStep][1];

        // 水平：用 gravity 而不是位移 —— 多行歌词才会整体贴着选中的那一侧，
        // 位移只能搬动单行文本。applyStyle() 会先按 style.align 设一次，
        // 这里在它之后覆盖（setStyle 的顺序是 applyStyle → sync → layout）。
        int gravity = switch (col) {
            case 0 -> Gravity.START;
            case 2 -> Gravity.END;
            default -> Gravity.CENTER_HORIZONTAL;
        };
        if (tv.getGravity() != gravity) {
            tv.setGravity(gravity);
        }
        // 贴边时留 6% 的安全距离，避免文字顶到屏幕边缘。
        int hInset = col == 1 ? 0 : Math.round(cw * 0.06f);
        ViewGroup.LayoutParams rawLp = tv.getLayoutParams();
        if (rawLp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) rawLp;
            if (tlp.leftMargin != hInset || tlp.rightMargin != hInset) {
                tlp.leftMargin = hInset;
                tlp.rightMargin = hInset;
                tv.setLayoutParams(tlp);
            }
        }
        // 垂直：上下各留 10%，中间精确居中（用实测高度，测不到时按字号估算）。
        int tvH = tv.getHeight();
        if (tvH <= 0) {
            tvH = tv.getMeasuredHeight();
        }
        if (tvH <= 0) {
            tvH = Math.round(tv.getTextSize() * 1.4f);
        }
        float vInset = ch * 0.10f;
        sBaseY = switch (row) {
            case 0 -> vInset;
            case 2 -> Math.max(vInset, ch - tvH - vInset);
            default -> (ch - tvH) / 2f;
        };
        tv.setTranslationX(driftX);
        tv.setTranslationY(sBaseY + driftY);
        if (tv.getWidth() != cw && tv.getLayoutParams() != null) {
            // 画布宽度变了（旋转/分辨率切换）：让 TextView 立刻按新宽度重排。
            tv.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
            tv.requestLayout();
        }
        // applyOffset 每秒会进来一两次，只有真的变了才打日志（AOD 上省电）。
        int sig = ((sw * 31 + sh) * 31 + rot) * 31 + grid;
        if (sig != sLastLayoutSig) {
            sLastLayoutSig = sig;
            AodLyricModule.i("immersive layout " + sw + "x" + sh + " rot=" + rot
                    + " canvas=" + cw + "x" + ch + " grid=" + grid
                    + " y=" + (int) (sBaseY + driftY));
        }
    }

    // ── hide / restore AOD components (mode 2) ───────────────────────

    private static final String[] HIDDEN_NAMES = {
            "clock_container", "aod_icons_container", "notification_animation_view",
    };

    private static void hideComponents() {
        FrameLayout stage = sStage;
        if (stage == null) {
            return;
        }
        ViewParent p = stage.getParent();
        if (!(p instanceof ViewGroup)) {
            return;
        }
        ViewGroup host = (ViewGroup) p;
        for (String name : HIDDEN_NAMES) {
            View v = findViewByIdName(host, name, 6);
            if (v == null || v == stage || v.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (!contains(sHidden, v)) {
                sHidden.add(new java.lang.ref.WeakReference<>(v));
            }
            v.setVisibility(View.INVISIBLE);
        }
    }

    private static void restoreComponents() {
        if (sHidden.isEmpty()) {
            return;
        }
        for (java.lang.ref.WeakReference<View> ref : sHidden) {
            View v = ref.get();
            if (v != null && v.getVisibility() != View.VISIBLE) {
                v.setVisibility(View.VISIBLE);
            }
        }
        sHidden.clear();
    }

    private static boolean contains(@NonNull List<java.lang.ref.WeakReference<View>> list,
                                    @NonNull View v) {
        for (java.lang.ref.WeakReference<View> ref : list) {
            if (ref.get() == v) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static View findViewByIdName(@NonNull ViewGroup root, @NonNull String name,
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
                    View c = g.getChildAt(i);
                    if (c == null) {
                        continue;
                    }
                    if (name.equals(entryName(c))) {
                        return c;
                    }
                    if (c instanceof ViewGroup) {
                        queue.add(new Object[]{c, depth + 1});
                    }
                }
            }
        } catch (Throwable t) {
            AodLyricModule.w("immersive lookup failed: " + t);
        }
        return null;
    }

    @Nullable
    private static String entryName(@NonNull View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) {
                return null;
            }
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── touch reveal ─────────────────────────────────────────────────

    /**
     * 触摸时临时让出整屏 4 秒：黑幕会挡住屏下指纹，必须能露出来。
     * 由 AOD 自己的 dispatchTouchEvent 调用，不额外加监听。
     */
    static void onTouch() {
        FrameLayout stage = sStage;
        if (stage == null || stage.getVisibility() != View.VISIBLE) {
            return;
        }
        if (sRevealEnd != null) {
            MAIN.removeCallbacks(sRevealEnd);
        }
        if (isBlack()) {
            stage.setAlpha(0f);
        } else {
            restoreComponents();
        }
        sRevealEnd = () -> {
            sRevealEnd = null;
            FrameLayout now = sStage;
            if (now == null) {
                return;
            }
            now.setAlpha(1f);
            if (sMode == StateKeys.IMMERSIVE_WALLPAPER) {
                hideComponents();
            }
        };
        MAIN.postDelayed(sRevealEnd, REVEAL_MS);
    }

    // ── periodic tick ────────────────────────────────────────────────

    /**
     * Kicker 每秒调用一次：做防烧屏漂移，并把被主题重置的可见性再压一次。
     */
    static void tick() {
        FrameLayout stage = sStage;
        if (stage == null) {
            return;
        }
        if (sMode == StateKeys.IMMERSIVE_WALLPAPER
                && stage.getVisibility() == View.VISIBLE
                && sRevealEnd == null) {
            hideComponents();
        }
        long now = SystemClock.elapsedRealtime();
        if (now - sDriftAt < DRIFT_PERIOD_MS) {
            return;
        }
        sDriftAt = now;
        sDriftStep = (sDriftStep + 1) % DRIFT.length;
        TextView tv = sTv == null ? null : sTv.get();
        if (tv != null) {
            layout(tv);
        }
    }

    /** Called by the kicker when the stage was re-parented / detached. */
    static boolean ensureAttached(@NonNull TextView tv) {
        FrameLayout canvas = sCanvas;
        if (canvas == null) {
            return false;
        }
        if (tv.getParent() != canvas) {
            detachFromParent(tv);
            canvas.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            layout(tv);
        }
        return true;
    }

    static void bringToFront() {
        FrameLayout stage = sStage;
        if (stage != null) {
            try {
                stage.bringToFront();
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    static TextView currentView() {
        return sTv == null ? null : sTv.get();
    }

    // ── orientation sensor ───────────────────────────────────────────

    private static void startSensor(@NonNull Context ctx) {
        if (sListener != null) {
            return;
        }
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                return;
            }
            Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (sensor == null) {
                sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (sensor == null) {
                AodLyricModule.w("immersive: no gravity sensor, landscape disabled");
                return;
            }
            SensorEventListener listener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    if (event.values == null || event.values.length < 3) {
                        return;
                    }
                    float gx = event.values[0];
                    float gy = event.values[1];
                    // 平放 / 斜靠时平面内朝向无定义：直接保持竖屏。
                    // （斜放时 gx 很大但那是"倾斜"不是"旋转"，不能当横屏。）
                    double horiz = Math.hypot(gx, gy);
                    if (horiz < UPRIGHT_THRESHOLD) {
                        if (sRotation != 0) {
                            sRotation = 0;
                            MAIN.post(() -> {
                                TextView tv0 = currentView();
                                if (tv0 != null) {
                                    layout(tv0);
                                }
                            });
                        }
                        return;
                    }
                    // 传感器坐标：X 向右、Y 向上、Z 朝用户。
                    // TYPE_GRAVITY 给的是「世界向上」方向的投影：
                    //   竖屏举起       g ≈ (0, +9.8)
                    //   逆时针横放 90° g ≈ (+9.8, 0)
                    //   顺时针横放 90° g ≈ (-9.8, 0)
                    // 所以 atan2(gx, gy) 就是设备相对竖屏转过的角度（正=逆时针）。
                    //
                    // 方向已在真机校正：文字要转的角与设备转角同号
                    // （上一版取了反号，用户反馈"横屏旋转方向反了"）。
                    double deg = Math.toDegrees(Math.atan2(gx, gy));
                    double abs = Math.abs(deg);
                    int rot;
                    if (abs > 45 && abs < 135) {
                        rot = deg > 0 ? 90 : -90;
                    } else {
                        // |deg|<45 竖屏；|deg|>=135 倒置 —— 都不翻转
                        rot = 0;
                    }
                    if (rot == sRotation) {
                        return;
                    }
                    sRotation = rot;
                    AodLyricModule.i("immersive orientation deg=" + Math.round(deg)
                            + " -> rot=" + rot);
                    MAIN.post(() -> {
                        TextView tv = currentView();
                        if (tv != null) {
                            layout(tv);
                        }
                    });
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            sSensors = sm;
            sListener = listener;
            AodLyricModule.i("immersive: orientation sensor on");
        } catch (Throwable t) {
            AodLyricModule.w("immersive sensor failed: " + t);
        }
    }

    private static void stopSensor() {
        try {
            if (sSensors != null && sListener != null) {
                sSensors.unregisterListener(sListener);
            }
        } catch (Throwable ignored) {
        }
        sSensors = null;
        sListener = null;
    }

    private static void detachFromParent(@NonNull View v) {
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(v);
        }
    }
}
