package cn.qxfly.aod.ui

import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qxfly.aod.ui.base.GlassBar
import cn.qxfly.aod.ui.base.LocalBlurActive
import cn.qxfly.aod.ui.core.AppNavigator
import cn.qxfly.aod.ui.core.LocalAppSettings
import cn.qxfly.aod.ui.core.LocalUiHub
import cn.qxfly.aod.ui.core.LocalNavigator
import cn.qxfly.aod.ui.core.ModuleData
import cn.qxfly.aod.ui.core.MiuixAppTheme
import cn.qxfly.aod.ui.core.Route
import cn.qxfly.aod.ui.pages.AboutPage
import cn.qxfly.aod.ui.pages.DiagnosticsPage
import cn.qxfly.aod.ui.pages.HomePage
import cn.qxfly.aod.ui.pages.SettingsPage
import cn.qxfly.aod.ui.pages.StylePage
import cn.qxfly.aod.ui.pages.StyleExtraPage
import cn.qxfly.aod.ui.pages.StyleImmersivePage
import cn.qxfly.aod.ui.pages.StyleLayoutPage
import cn.qxfly.aod.ui.pages.StyleTextPage
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用根界面：主题 → 底部导航 → 各标签独立页面栈。
 *
 * 结构说明：
 * - 外层 [Scaffold] 只负责底部导航栏，并把导航栏高度通过 `bottomPadding` 传给页面；
 * - 全局 backdrop 标在内容 Box（包住 NavDisplay）上，**仅供底栏毛玻璃采样**；
 *   底栏 blur 节点在内容 Box 之外，不构成 RenderNode 引用环；
 * - 页面顶栏的毛玻璃由各页面自建 backdrop（见 MiuixPage），不采样本层——
 *   若页面 blur 节点采样了包含它自己的本层，RenderThread 会无限递归崩溃；
 * - 页面切换由 miuix-nav 的 [NavDisplay] 负责，带 MIUI 风格的转场动画与预测式返回手势；
 * - 每个底部标签持有独立页面栈（见 [AppNavigator]），标签根页面上按返回直接退出应用。
 */
@Composable
fun MiuixAppRoot(
    data: ModuleData,
    navigator: AppNavigator,
) {
    val settingsState by data.settings.state.collectAsStateWithLifecycle()

    CompositionLocalProvider(
        LocalAppSettings provides data.settings,
        LocalNavigator provides navigator,
        LocalUiHub provides data,
    ) {
        MiuixAppTheme(settingsState) {
            SystemBarStyleEffect()

            // 毛玻璃：内容层把画面快照交给 backdrop，底栏再对它做实时模糊
            val surfaceColor = MiuixTheme.colorScheme.surface
            val backdrop = rememberLayerBackdrop {
                drawRect(surfaceColor)
                drawContent()
            }
            // RuntimeShader 需要 Android 13（API 33），低版本始终走纯色栏
            val blurActive = settingsState.blurEnabled && Build.VERSION.SDK_INT >= 33

            CompositionLocalProvider(
                LocalBlurActive provides blurActive,
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    // 页面自行处理状态栏安全区，这里不再重复添加
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = { MiuixBottomBar(navigator, backdrop) },
                ) { innerPadding ->
                    val bottomPadding = innerPadding.calculateBottomPadding()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop),
                    ) {
                        NavDisplay(
                            backStack = navigator.activeStack,
                            modifier = Modifier.fillMaxSize(),
                            onBack = { navigator.pop() },
                        ) {
                            entry<Route.Home> { HomePage(bottomPadding) }
                            entry<Route.Style> { StylePage(bottomPadding) }
                            entry<Route.Settings> { SettingsPage(bottomPadding) }
                            entry<Route.About> { AboutPage(bottomPadding) }
                            entry<Route.Diagnostics> { DiagnosticsPage(bottomPadding) }
                            entry<Route.StyleText> { StyleTextPage(bottomPadding) }
                            entry<Route.StyleLayout> { StyleLayoutPage(bottomPadding) }
                            entry<Route.StyleExtra> { StyleExtraPage(bottomPadding) }
                            entry<Route.StyleImmersive> { StyleImmersivePage(bottomPadding) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 根据当前主题的明暗，动态切换系统栏图标颜色。
 */
@Composable
private fun SystemBarStyleEffect() {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val context = LocalContext.current

    DisposableEffect(isDark) {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { isDark },
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { isDark },
        )
        onDispose { }
    }
}

/** 底部标签栏：毛玻璃由 [GlassBar] 统一处理，采样 [backdrop]（底栏在记录层之外，安全）。 */
@Composable
private fun MiuixBottomBar(
    navigator: AppNavigator,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
) {
    val blurActive = LocalBlurActive.current

    GlassBar(backdrop = backdrop, blurActive = blurActive) {
        NavigationBar(color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface) {
            TabRoutes.forEach { tab ->
                NavigationBarItem(
                    selected = navigator.activeTab == tab.route,
                    onClick = { navigator.selectTab(tab.route) },
                    icon = tab.icon,
                    label = androidx.compose.ui.res.stringResource(tab.labelRes),
                )
            }
        }
    }
}
