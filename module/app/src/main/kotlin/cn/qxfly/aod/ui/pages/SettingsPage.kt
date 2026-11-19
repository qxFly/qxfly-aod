package cn.qxfly.aod.ui.pages

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.base.MiuixPage
import cn.qxfly.aod.ui.core.LocalAppSettings
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/** 配色模式选项，顺序与 [ColorSchemeMode] 一致。 */
private val ColorModeOptions = listOf(
    ColorSchemeMode.System,
    ColorSchemeMode.Light,
    ColorSchemeMode.Dark,
    ColorSchemeMode.MonetSystem,
    ColorSchemeMode.MonetLight,
    ColorSchemeMode.MonetDark,
)

/** 可选的主题种子色（ARGB），第一个为 null =「跟随壁纸」。 */
private val KeyColorChoices = listOf(
    null,
    0xFF3482FF.toInt(),
    0xFF36D167.toInt(),
    0xFF7C4DFF.toInt(),
    0xFFFFB21D.toInt(),
    0xFFFF5722.toInt(),
    0xFFE91E63.toInt(),
)

private fun colorModeLabel(mode: ColorSchemeMode, labels: Map<ColorSchemeMode, String>): String =
    labels[mode] ?: mode.name

@Composable
private fun keyColorLabel(argb: Int?): String = when (argb) {
    null -> stringResource(R.string.key_color_wallpaper)
    0xFF3482FF.toInt() -> stringResource(R.string.key_color_blue)
    0xFF36D167.toInt() -> stringResource(R.string.key_color_green)
    0xFF7C4DFF.toInt() -> stringResource(R.string.key_color_purple)
    0xFFFFB21D.toInt() -> stringResource(R.string.key_color_yellow)
    0xFFFF5722.toInt() -> stringResource(R.string.key_color_orange)
    0xFFE91E63.toInt() -> stringResource(R.string.key_color_pink)
    else -> "#${Integer.toHexString(argb)}"
}

/** 设置页：只保留 App 自身的设置（主题外观）。歌词外观在歌词页。 */
@Composable
fun SettingsPage(bottomPadding: Dp) {
    val settings = LocalAppSettings.current
    val state by settings.state.collectAsStateWithLifecycle()

    val isMonet = state.colorSchemeMode in setOf(
        ColorSchemeMode.MonetSystem,
        ColorSchemeMode.MonetLight,
        ColorSchemeMode.MonetDark,
    )

    // RuntimeShader 需要 Android 13（API 33）；低版本下该开关无实际效果
    val blurSupported = Build.VERSION.SDK_INT >= 33

    // 下拉框的选项文案：在组合期一次性解析，避免在非 Composable 的 map 里调用
    val colorModeLabels = mapOf(
        ColorSchemeMode.System to stringResource(R.string.color_mode_system),
        ColorSchemeMode.Light to stringResource(R.string.color_mode_light),
        ColorSchemeMode.Dark to stringResource(R.string.color_mode_dark),
        ColorSchemeMode.MonetSystem to stringResource(R.string.color_mode_monet_system),
        ColorSchemeMode.MonetLight to stringResource(R.string.color_mode_monet_light),
        ColorSchemeMode.MonetDark to stringResource(R.string.color_mode_monet_dark),
    )

    // 回调（非 Composable）里不能调用 isSystemInDarkTheme()，提前在组合期取好
    val systemInDark = isSystemInDarkTheme()

    MiuixPage(
        title = stringResource(R.string.tab_settings),
        subtitle = stringResource(R.string.settings_subtitle),
        bottomPadding = bottomPadding,
    ) {
        item { SmallTitle(text = stringResource(R.string.settings_section_theme)) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.settings_monet),
                    summary = stringResource(R.string.settings_monet_summary),
                    checked = isMonet,
                    onCheckedChange = { enabled ->
                        settings.update { current ->
                            current.copy(
                                colorSchemeMode = if (enabled) {
                                    ColorSchemeMode.MonetSystem
                                } else if (systemInDark) {
                                    ColorSchemeMode.Dark
                                } else {
                                    ColorSchemeMode.Light
                                },
                            )
                        }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_color_mode),
                    items = ColorModeOptions.map { colorModeLabel(it, colorModeLabels) },
                    selectedIndex = ColorModeOptions.indexOf(state.colorSchemeMode).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        settings.update { current ->
                            current.copy(colorSchemeMode = ColorModeOptions[index])
                        }
                    },
                )
                if (isMonet) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_key_color),
                        items = KeyColorChoices.map { keyColorLabel(it) },
                        selectedIndex = KeyColorChoices.indexOf(state.keyColor?.toArgb()).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            settings.update { current ->
                                current.copy(
                                    keyColor = KeyColorChoices[index]?.let { Color(it) },
                                )
                            }
                        },
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.settings_blur),
                    summary = if (blurSupported) {
                        stringResource(R.string.settings_blur_summary)
                    } else {
                        stringResource(R.string.settings_blur_summary_unsupported)
                    },
                    checked = state.blurEnabled,
                    onCheckedChange = { enabled ->
                        settings.update { current -> current.copy(blurEnabled = enabled) }
                    },
                )
            }
        }
    }
}
