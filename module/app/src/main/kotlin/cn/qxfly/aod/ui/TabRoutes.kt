package cn.qxfly.aod.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.core.Route
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune

/** 底部标签对应的路由；文案用字符串资源，便于多语言。 */
sealed interface TabRoute {
    val route: Route

    @get:StringRes
    val labelRes: Int
    val icon: ImageVector
}

private data object HomeTab : TabRoute {
    override val route = Route.Home
    override val labelRes = R.string.tab_home
    override val icon = MiuixIcons.Home
}

private data object StyleTab : TabRoute {
    override val route = Route.Style
    override val labelRes = R.string.tab_style
    override val icon = MiuixIcons.Tune
}

private data object SettingsTab : TabRoute {
    override val route = Route.Settings
    override val labelRes = R.string.tab_settings
    override val icon = MiuixIcons.Settings
}

private data object AboutTab : TabRoute {
    override val route = Route.About
    override val labelRes = R.string.tab_about
    override val icon = MiuixIcons.Info
}

internal val TabRoutes: List<TabRoute> = listOf(HomeTab, StyleTab, SettingsTab, AboutTab)
