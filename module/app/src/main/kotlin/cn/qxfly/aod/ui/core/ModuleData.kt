package cn.qxfly.aod.ui.core

import cn.qxfly.aod.LyricStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「运行状态」卡片内容，字段与 MainActivity.refreshStateInner 生成的展示文案对应。
 *
 * [moduleOk] 用于给模块状态上色。
 */
data class CardsData @JvmOverloads constructor(
    /** 模块状态正文；为空时 UI 显示「读取中…」。 */
    val module: String = "",
    val moduleOk: Boolean? = null,
    /** 投喂记录正文；为空时 UI 显示「暂无投喂记录」。 */
    val hook: String = "",
    /** Hook 状态原始值：null=尚未上报，false=未注入，true=已注入。 */
    val hooksInstalled: Boolean? = null,
    /** Hook 进程上报的框架 API 版本。 */
    val moduleApi: String = "",
    /** Hook 进程上报的框架实现名。 */
    val framework: String = "",
    /** 最近投喂的曲目；空 = 暂无投喂。 */
    val track: String = "",
    /** 最近投喂的歌词行。 */
    val lyricLine: String = "",
    /** 最近一次投喂的时间（已格式化）；空 = 暂无投喂。 */
    val feedTime: String = "",
    /** 息屏歌词总开关；false 时 Hook 侧停止一切歌词显示与注入。 */
    val moduleEnabled: Boolean = true,
    /** 本应用是否成功接入 SuperLyric；null = 尚未尝试 / 状态未知。 */
    val superLyricOk: Boolean? = null,
    /** SuperLyric 接入详情（服务端 API 版本或失败原因）；空 = 无。 */
    val superLyricDetail: String = "",
)

/** 可作为歌词锚点的息屏组件（Hook 进程枚举经 Provider 转发）。 */
data class AnchorComponent(
    val id: String,
    val label: String,
)

/** 保存歌词外观（Java lambda 可直接实现 fun interface）。 */
fun interface StyleSaver {
    fun save(style: LyricStyle)
}

/** 读取当前外观。 */
fun interface StyleReader {
    fun read(): LyricStyle
}

/** 写入息屏歌词总开关（Java lambda 可直接实现 fun interface）。 */
fun interface ModuleEnabledSetter {
    fun set(enabled: Boolean)
}

/**
 * Java（Provider）与 Compose 界面之间的数据桥。
 *
 * MainActivity 在 onCreate 时创建实例、注册回调并发布数据；
 * Compose 页面通过 [UiHub.LocalUiHub] 读取状态、调用动作。
 *
 * 回调使用普通 Java 接口而不是 Kotlin 函数类型：javac 生成 lambda 时不必
 * 适配 Function0/Function1 的 Unit 返回值，避免类型不匹配。
 */
class ModuleData(val settings: AppSettings) {

    // ---- 状态流 ----
    private val _cards = MutableStateFlow(CardsData())
    val cards: StateFlow<CardsData> = _cards.asStateFlow()

    private val _components = MutableStateFlow<List<AnchorComponent>>(emptyList())
    val components: StateFlow<List<AnchorComponent>> = _components.asStateFlow()

    // ---- 由 Java 侧注入的动作（Provider 调用只存在于 Java 侧）----
    var saveStyle: StyleSaver? = null
    var readStyle: StyleReader? = null
    var moduleEnabledSetter: ModuleEnabledSetter? = null

    /** Java 侧注入：重新读取 Provider 状态（对应主页右上角刷新按钮）。 */
    var refresher: Runnable? = null

    // ---- Java 侧调用的发布方法 ----
    fun publishCards(c: CardsData) {
        _cards.value = c
    }

    fun publishComponents(c: List<AnchorComponent>) {
        _components.value = c
    }

    // ---- Compose 侧调用的动作 ----
    fun save(style: LyricStyle) {
        saveStyle?.save(style)
    }

    fun currentStyle(): LyricStyle = readStyle?.read() ?: LyricStyle()

    /** Compose 侧调用：写总开关到 Provider（缺实现时静默忽略）。 */
    fun setModuleEnabled(enabled: Boolean) {
        moduleEnabledSetter?.set(enabled)
    }

    /** Compose 侧调用：重新读取 Provider 状态。 */
    fun refresh() {
        refresher?.run()
    }
}

/** 进程级单例入口，MainActivity 设置；Compose 根界面读取。 */
object UiHub {

    @Volatile
    var data: ModuleData? = null
}

/** Compose 内访问 [ModuleData] 的入口，根界面 provide。 */
val LocalUiHub = androidx.compose.runtime.staticCompositionLocalOf<ModuleData> {
    error("UiHub 未提供，请在根界面中注入")
}
