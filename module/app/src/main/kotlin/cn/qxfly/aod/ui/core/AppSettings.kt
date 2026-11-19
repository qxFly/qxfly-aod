package cn.qxfly.aod.ui.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 应用的可配置项集合（纯 UI 偏好）。
 *
 * 与骨架的差异：歌词外观（LyricStyle）不走这里 —— 它必须经 ContentProvider
 * 与 Hook 进程共享，仍由 Java 侧的 StateKeys / AodStateProvider 负责；
 * 这里只保存主题相关的界面偏好。
 */
data class AppSettingsState(
    /** 配色模式：跟随系统 / 强制浅色 / 强制深色 / Monet 动态取色 */
    val colorSchemeMode: ColorSchemeMode = ColorSchemeMode.MonetSystem,
    /** Monet 主题色种子；null 表示跟随系统壁纸取色 */
    val keyColor: Color? = null,
    /** 是否启用毛玻璃效果 */
    val blurEnabled: Boolean = true,
)

/**
 * 轻量设置仓库：SharedPreferences 持久化 + StateFlow 对外暴露。
 *
 * 与骨架的差异：骨架用 Preferences DataStore，这里换成 SharedPreferences，
 * 省掉 datastore 依赖与 KMP 变体解析；对外 API（state + update）保持一致，
 * 页面代码零改动。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("aodlyric_ui_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val editMutex = Mutex()

    private val _state = MutableStateFlow(prefsToState(prefs))
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    /** 以「变换」的方式更新设置，异步写回本地存储。 */
    fun update(transform: (AppSettingsState) -> AppSettingsState) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        scope.launch {
            editMutex.withLock {
                prefs.edit().apply { next.writeTo(this) }.apply()
            }
        }
    }

    private companion object {
        const val KEY_COLOR_SCHEME_MODE = "color_scheme_mode"
        const val KEY_KEY_COLOR = "key_color"
        const val KEY_BLUR = "blur_enabled"

        /** 未设置主题色时的哨兵值（合法 ARGB 不会取到该值） */
        const val NO_COLOR = Int.MIN_VALUE

        /** [SharedPreferences] → [AppSettingsState]，未知 / 损坏的值一律回退到默认。 */
        fun prefsToState(prefs: SharedPreferences): AppSettingsState {
            val defaults = AppSettingsState()
            return AppSettingsState(
                colorSchemeMode = prefs.getString(KEY_COLOR_SCHEME_MODE, null)
                    ?.let { name -> runCatching { ColorSchemeMode.valueOf(name) }.getOrNull() }
                    ?: defaults.colorSchemeMode,
                keyColor = if (prefs.contains(KEY_KEY_COLOR)) {
                    val argb = prefs.getInt(KEY_KEY_COLOR, NO_COLOR)
                    if (argb != NO_COLOR) Color(argb) else null
                } else {
                    null
                },
                blurEnabled = prefs.getBoolean(KEY_BLUR, defaults.blurEnabled),
            )
        }

        /** [AppSettingsState] → [SharedPreferences]，写全量字段。 */
        fun AppSettingsState.writeTo(editor: SharedPreferences.Editor) {
            editor.putString(KEY_COLOR_SCHEME_MODE, colorSchemeMode.name)
            if (keyColor != null) {
                editor.putInt(KEY_KEY_COLOR, keyColor.toArgb())
            } else {
                editor.putInt(KEY_KEY_COLOR, NO_COLOR)
            }
            editor.putBoolean(KEY_BLUR, blurEnabled)
        }
    }
}

/** 全局设置仓库，可在任意组件中通过 [LocalAppSettings] 访问。 */
val LocalAppSettings = androidx.compose.runtime.staticCompositionLocalOf<AppSettings> {
    error("LocalAppSettings 未提供，请在根界面中注入")
}
