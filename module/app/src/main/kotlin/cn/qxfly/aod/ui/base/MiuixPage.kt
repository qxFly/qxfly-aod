package cn.qxfly.aod.ui.base

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一的二级页面骨架：Scaffold + TopAppBar（大标题，滚动收缩）+ LazyColumn。
 *
 * 所有页面共用这一层样板，避免每个页面重复写 scrollBehavior / nestedScroll /
 * contentPadding 的组合；只需要提供标题和 LazyList 内容。
 *
 * 顶栏自动接入毛玻璃（结构照搬官方 MainPage，避免 RenderNode 引用环）：
 * - 本页自建 [backdrop]，`layerBackdrop` 只标记 Scaffold 的 **body 内容 Box**；
 * - 顶栏 [GlassBar] 是该 Box 的兄弟节点，blur 节点不在被记录层内部——
 *   若把 blur 节点放进记录层，两个 RenderNode 互相引用，RenderThread
 *   `prepareTreeImpl` 无限递归直至 SIGSEGV。
 *
 * @param title 顶栏标题（同时也是收缩前的大标题）
 * @param subtitle 顶栏副标题，可为空
 * @param navigationIcon 顶栏左侧插槽（返回按钮等）
 * @param actions 顶栏右侧动作区
 * @param bottomPadding 根界面底栏的高度（由调用方传入），保证列表底部不被遮挡
 * @param content LazyColumn 内容
 */
@Composable
fun MiuixPage(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    bottomPadding: Dp,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val blurEnabled = LocalBlurActive.current
    val surfaceColor = MiuixTheme.colorScheme.surface

    // 只在毛玻璃可用时创建 backdrop；记录层 = 本页 body 内容
    val backdrop = if (blurEnabled) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
    val blurActive = blurEnabled && backdrop != null

    Scaffold(
        topBar = {
            GlassBar(backdrop = backdrop, blurActive = blurActive) {
                TopAppBar(
                    title = title,
                    subtitle = subtitle.orEmpty(),
                    scrollBehavior = scrollBehavior,
                    // 毛玻璃材质来自 GlassBar，栏本体必须透明
                    color = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface,
                    navigationIcon = { navigationIcon?.invoke() },
                    actions = { actions?.invoke(this) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + bottomPadding + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
    }
}
