package cn.qxfly.aod.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import cn.qxfly.aod.ui.core.AppNavigator
import cn.qxfly.aod.ui.core.Route
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

/**
 * Compose 界面挂载入口（由 MainActivity 调用）。
 *
 * 返回 false 表示 Compose 初始化失败 —— 调用方回退到 Java 界面，不能崩：
 * 这个进程还承担着歌词主源（SuperLyric 注册）与 Provider 服务两个职责。
 */
object ModuleUi {

    /** @return true 当 Compose 界面已挂上 */
    fun mount(activity: ComponentActivity): Boolean {
        return try {
            activity.setContent {
                // 每个底部标签一个独立回退栈；@Serializable 路由保证进程被杀后各栈都能恢复。
                // activeTab 不持久化：恢复后回到首页标签，各栈内容仍在。
                val homeStack = rememberNavBackStack<Route>(Route.Home)
                val styleStack = rememberNavBackStack<Route>(Route.Style)
                val settingsStack = rememberNavBackStack<Route>(Route.Settings)
                val aboutStack = rememberNavBackStack<Route>(Route.About)
                val navigator = remember(homeStack, styleStack, settingsStack, aboutStack) {
                    AppNavigator(
                        mapOf(
                            Route.Home to homeStack,
                            Route.Style to styleStack,
                            Route.Settings to settingsStack,
                            Route.About to aboutStack,
                        ),
                    )
                }

                MiuixAppRoot(
                    data = requireNotNull(cn.qxfly.aod.ui.core.UiHub.data),
                    navigator = navigator,
                )
            }
            true
        } catch (t: Throwable) {
            false
        }
    }
}
