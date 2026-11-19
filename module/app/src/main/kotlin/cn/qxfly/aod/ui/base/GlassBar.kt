package cn.qxfly.aod.ui.base

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 毛玻璃总开关：blurEnabled 且 Android 13+ 时为 true，由根界面统一计算后下发。 */
val LocalBlurActive = staticCompositionLocalOf { false }

/**
 * 毛玻璃栏容器。
 *
 * - 毛玻璃激活时，对 [backdrop] 中「栏身后面的内容」做实时模糊，
 *   并叠加一层半透明 surface 底色保证可读性；栏本体需要用透明背景；
 * - 未激活 / 不支持时原样渲染内容（栏本体保持默认 surface 色）。
 *
 * miuix 的 Scaffold 是叠加布局（body 与 topBar 都画在 (0,0)），
 * 列表滚动时内容会从栏身后面穿过，因此模糊能透出真实滚动内容。
 *
 * 关键约束（曾导致真机启动即 SIGSEGV）：**使用本栏的节点绝不能处于
 * [backdrop] 的 `layerBackdrop` 记录层内部**。blur 节点绘制时会先把
 * backdrop 层录进自己的 GraphicsLayer，如果它本身又被 backdrop 层录制，
 * 两个 RenderNode 互相引用成环，RenderThread 的 `prepareTreeImpl` 会
 * 无限递归直到栈溢出。正确结构是「顶栏 blur 节点与被记录的内容 Box 互为
 * 兄弟」（见 MiuixPage / MiuixAppRoot）。
 */
@Composable
fun GlassBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean,
    content: @Composable () -> Unit,
) {
    if (blurActive && backdrop != null) {
        val surface = MiuixTheme.colorScheme.surface
        Box(
            modifier = Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = 0.8f))),
                ),
            ),
        ) {
            content()
        }
    } else {
        content()
    }
}
