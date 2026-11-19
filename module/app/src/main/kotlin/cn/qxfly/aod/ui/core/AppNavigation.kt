package cn.qxfly.aod.ui.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * 应用路由表。
 *
 * miuix-nav 的 [NavKey] 是纯标记接口，配合 `@Serializable` 即可让回退栈
 * 在进程被杀后自动恢复。密封接口必须显式继承自 [Route]，否则序列化器只会捕获到
 * 单一子类型。
 *
 * [Home] / [Settings] / [About] 是底部标签的根页面；其余路由压入所在标签的栈中。
 */
@Serializable
sealed interface Route : NavKey {

    @Serializable
    data object Home : Route

    @Serializable
    data object Style : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object About : Route
}

/** 标签根路由（与底部导航一一对应）。 */
val TabRootRoutes: List<Route> = listOf(Route.Home, Route.Style, Route.Settings, Route.About)

/**
 * 多标签导航器：每个底部标签各自持有一个独立的 [NavBackStack]。
 *
 * - 切换标签只是切换 [activeTab]，各标签的页面栈互不干扰、切回时原样恢复；
 * - push / pop 都作用于当前激活标签的栈；
 * - 标签根页面（栈内只有一项）上按返回会穿透给系统，直接退出应用，
 *   符合 HyperOS「标签即顶层页面」的习惯。
 */
class AppNavigator(private val tabStacks: Map<Route, NavBackStack>) {

    /** 当前激活的标签根路由，切换时驱动 NavDisplay 与底栏。 */
    var activeTab: Route by mutableStateOf(Route.Home)

    /** 当前激活标签的页面栈，交给 NavDisplay 渲染。 */
    val activeStack: NavBackStack
        get() = tabStacks.getValue(activeTab)

    /** 在当前标签的栈顶压入一个新页面；重复目标会被忽略（避免 contentKey 冲突）。 */
    fun push(route: NavKey) {
        val stack = activeStack
        if (route !in stack) {
            stack.add(route)
        }
    }

    /** 在当前标签内出栈。标签根页面由系统返回手势直接退出应用，这里无需处理。 */
    fun pop() {
        val stack = activeStack
        if (stack.size > 1) {
            stack.removeLastOrNull()
        }
    }

    /** 切换底部标签；各标签的页面栈保持不变。 */
    fun selectTab(route: Route) {
        activeTab = route
    }
}

/** 全局导航器，可在任意组件中通过 `LocalNavigator.current` 访问。 */
val LocalNavigator = staticCompositionLocalOf<AppNavigator> {
    error("LocalNavigator 未提供，请在根界面中注入")
}
