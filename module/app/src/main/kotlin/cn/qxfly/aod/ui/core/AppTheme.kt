package cn.qxfly.aod.ui.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Miuix 主题入口。
 *
 * 把 [AppSettingsState] 映射成 Miuix 的 [ThemeController]：
 * - `ColorSchemeMode.MonetSystem` + `keyColor == null` → 跟随系统壁纸动态取色；
 * - 指定 `keyColor` → 以该颜色为种子生成整套 Material You 配色；
 * - `Light` / `Dark` → 使用 Miuix 内置的静态配色。
 */
@Composable
fun MiuixAppTheme(
    state: AppSettingsState,
    content: @Composable () -> Unit,
) {
    val controller = remember(
        state.colorSchemeMode,
        state.keyColor,
    ) {
        ThemeController(
            colorSchemeMode = state.colorSchemeMode,
            keyColor = state.keyColor,
        )
    }

    CompositionLocalProvider {
        MiuixTheme(controller = controller, content = content)
    }
}
