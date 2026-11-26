package cn.qxfly.aod.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.base.BackButton
import cn.qxfly.aod.ui.base.MiuixPage
import cn.qxfly.aod.ui.core.LocalNavigator
import cn.qxfly.aod.ui.core.LocalUiHub
import cn.qxfly.aod.ui.core.Route
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 外观二级页的公共骨架：顶栏带返回、一张内容卡、底部保存卡。
 *
 * 草稿来自 [StyleDraftStore]（与主页共用），二级页只改草稿，
 * 「保存并应用」才写 Provider。
 */
@Composable
private fun StyleSubPage(
    title: String,
    subtitle: String,
    bottomPadding: Dp,
    content: @Composable () -> Unit,
) {
    val navigator = LocalNavigator.current
    val data = LocalUiHub.current
    StyleDraftStore.ensureLoaded(data)

    MiuixPage(
        title = title,
        subtitle = subtitle,
        navigationIcon = { BackButton { navigator.pop() } },
        bottomPadding = bottomPadding,
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                content()
            }
        }
        item { StyleSaveCard(data) }
    }
}

// ── 文字、背景与附加信息 ─────────────────────────────────────────────

/**
 * 二级页：文字外观 + 背景板 + 附加信息。
 *
 * 原来是「文字样式」和「背景与附加信息」两页，用户反馈分得太碎，
 * 现在合成一页，用「文字 / 背景 / 附加信息」三个小节标题分段。
 */
@Composable
internal fun StyleTextPage(bottomPadding: Dp) {
    val context = LocalContext.current
    var customFontLabel by remember { mutableStateOf(readCustomFontLabel(context)) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showStrokeColorPicker by remember { mutableStateOf(false) }
    var showBgColorPicker by remember { mutableStateOf(false) }
    val fontImportFailedMsg = stringResource(R.string.font_custom_failed)

    // 自定义字体：文件由系统文件选择器（SAF）导入到应用私有目录，
    // Hook 进程经 Provider 读取；这里只记录展示用的原始文件名。
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = importCustomFont(context, uri)
            if (name != null) {
                customFontLabel = name
                StyleDraftStore.draft = StyleDraftStore.draft.copy(typefaceName = "custom")
                StyleDraftStore.show(name)
            } else {
                StyleDraftStore.show(fontImportFailedMsg)
            }
        }
    }

    StyleSubPage(
        title = stringResource(R.string.style_group_text),
        subtitle = stringResource(R.string.style_group_text_summary),
        bottomPadding = bottomPadding,
    ) {
        val draft = StyleDraftStore.draft

        // ── 文字 ──
        SectionHeader(stringResource(R.string.style_section_text), first = true)
        SliderRow(
            label = stringResource(R.string.style_text_size),
            value = draft.textSizeSp,
            range = 12f..40f,
            defaultValue = 20f,
            format = { "${it.toInt()}" },
            onChange = { StyleDraftStore.draft = StyleDraftStore.draft.copy(textSizeSp = it) },
        )
        SliderRow(
            label = stringResource(R.string.style_stroke_width),
            value = draft.strokeWidth,
            range = 0f..6f,
            defaultValue = 0f,
            onChange = { StyleDraftStore.draft = StyleDraftStore.draft.copy(strokeWidth = it) },
        )
        if (draft.strokeWidth > 0.5f) {
            Text(
                text = stringResource(R.string.style_stroke_color),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            ColorSwatches(
                selected = draft.strokeColor,
                onPick = { StyleDraftStore.draft = StyleDraftStore.draft.copy(strokeColor = it) },
                onCustom = { showStrokeColorPicker = true },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.style_color), fontSize = 13.sp)
        ColorSwatches(
            selected = draft.textColor,
            onPick = { StyleDraftStore.draft = StyleDraftStore.draft.copy(textColor = it) },
            onCustom = { showColorPicker = true },
        )

        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.style_typeface), fontSize = 13.sp)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StyleFonts.forEach { (labelRes, value) ->
                SelectableChip(
                    label = stringResource(labelRes),
                    selected = draft.typefaceName == value,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        StyleDraftStore.draft = StyleDraftStore.draft.copy(typefaceName = value)
                    },
                )
            }
            SelectableChip(
                label = stringResource(R.string.font_custom),
                selected = draft.typefaceName == "custom",
                modifier = Modifier.weight(1f),
                onClick = {
                    StyleDraftStore.draft = StyleDraftStore.draft.copy(typefaceName = "custom")
                },
            )
        }
        if (draft.typefaceName == "custom") {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = customFontLabel.ifEmpty {
                            stringResource(R.string.font_custom_none)
                        },
                        fontSize = 12.sp,
                    )
                    Text(
                        text = stringResource(R.string.font_custom_hint),
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                TextButton(
                    text = stringResource(R.string.font_custom_import),
                    onClick = { fontPicker.launch(FONT_MIME_TYPES) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.style_bold), Modifier.weight(1f), fontSize = 13.sp)
            Switch(
                checked = draft.weight == 0,
                onCheckedChange = { bold ->
                    StyleDraftStore.draft = StyleDraftStore.draft.copy(weight = if (bold) 0 else 1)
                },
            )
        }
        LabeledSwitchRow(
            title = stringResource(R.string.style_show_translation),
            summary = stringResource(R.string.style_show_translation_summary),
            checked = draft.showTranslation,
            onChange = { v ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(showTranslation = v)
            },
        )

        // ── 背景 ──
        SectionHeader(stringResource(R.string.style_section_bg))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.style_bg_summary),
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Switch(
                checked = draft.bgEnabled,
                onCheckedChange = { v ->
                    StyleDraftStore.draft = StyleDraftStore.draft.copy(bgEnabled = v)
                },
            )
        }
        if (draft.bgEnabled) {
            Text(
                text = stringResource(R.string.style_bg_color),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
            ColorSwatches(
                selected = draft.bgColor,
                onPick = { StyleDraftStore.draft = StyleDraftStore.draft.copy(bgColor = it) },
                onCustom = { showBgColorPicker = true },
            )
            SliderRow(
                label = stringResource(R.string.style_bg_alpha),
                value = draft.bgAlpha,
                range = 0f..1f,
                defaultValue = 0.45f,
                format = { "${(it * 100).toInt()}%" },
                toInput = { Math.round(it * 100).toString() },
                fromInput = { it.trim().removeSuffix("%").trim().toFloatOrNull()?.div(100f) },
                onChange = { StyleDraftStore.draft = StyleDraftStore.draft.copy(bgAlpha = it) },
            )
            SliderRow(
                label = stringResource(R.string.style_bg_radius),
                value = draft.bgRadiusDp,
                range = 0f..40f,
                defaultValue = 24f,
                format = { "${it.toInt()} dp" },
                toInput = { it.toInt().toString() },
                fromInput = { it.trim().removeSuffix("dp").trim().toFloatOrNull() },
                onChange = { StyleDraftStore.draft = StyleDraftStore.draft.copy(bgRadiusDp = it) },
            )
        }

        // ── 附加信息 ──
        SectionHeader(stringResource(R.string.style_extra_section))
        LabeledSwitchRow(
            title = stringResource(R.string.style_show_track),
            summary = stringResource(R.string.style_show_track_summary),
            checked = draft.showTrack,
            onChange = { v -> StyleDraftStore.draft = StyleDraftStore.draft.copy(showTrack = v) },
        )
        LabeledSwitchRow(
            title = stringResource(R.string.style_show_next),
            summary = stringResource(R.string.style_show_next_summary),
            checked = draft.showNext,
            onChange = { v -> StyleDraftStore.draft = StyleDraftStore.draft.copy(showNext = v) },
        )
        LabeledSwitchRow(
            title = stringResource(R.string.style_show_progress),
            summary = stringResource(R.string.style_show_progress_summary),
            checked = draft.showProgress,
            onChange = { v ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(showProgress = v)
            },
        )
        LabeledSwitchRow(
            title = stringResource(R.string.style_pause_keep),
            summary = stringResource(R.string.style_pause_keep_summary),
            checked = draft.pauseKeep,
            onChange = { v -> StyleDraftStore.draft = StyleDraftStore.draft.copy(pauseKeep = v) },
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initial = StyleDraftStore.draft.textColor,
            onDismiss = { showColorPicker = false },
            onConfirm = { argb ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(textColor = argb)
                showColorPicker = false
            },
        )
    }
    if (showStrokeColorPicker) {
        ColorPickerDialog(
            initial = StyleDraftStore.draft.strokeColor,
            onDismiss = { showStrokeColorPicker = false },
            onConfirm = { argb ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(strokeColor = argb)
                showStrokeColorPicker = false
            },
        )
    }
    if (showBgColorPicker) {
        ColorPickerDialog(
            initial = StyleDraftStore.draft.bgColor,
            onDismiss = { showBgColorPicker = false },
            onConfirm = { argb ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(bgColor = argb)
                showBgColorPicker = false
            },
        )
    }
}

/** 卡片内的小节标题：把一页拆成「文字 / 背景 / 附加信息」三段。 */
@Composable
private fun SectionHeader(text: String, first: Boolean = false) {
    Spacer(Modifier.height(if (first) 0.dp else 18.dp))
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

// ── 位置与对齐 ────────────────────────────────────────────────────────

/** 二级页：对齐方式 / 垂直位置 / 组件锚点。沉浸模式下整页换成九宫格入口。 */
@Composable
internal fun StyleLayoutPage(bottomPadding: Dp) {
    val data = LocalUiHub.current
    val navigator = LocalNavigator.current
    val components by data.components.collectAsStateWithLifecycle()

    StyleSubPage(
        title = stringResource(R.string.style_group_layout),
        subtitle = stringResource(R.string.style_group_layout_summary),
        bottomPadding = bottomPadding,
    ) {
        val draft = StyleDraftStore.draft

        if (draft.immersive != 0) {
            // 沉浸模式的定位由九宫格独占：这里的对齐/锚点都不生效，
            // 与其展示一堆无效控件，不如直接给出入口。
            Text(
                text = stringResource(R.string.style_layout_immersive_hint),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.style_layout_go_grid),
                onClick = { navigator.push(Route.StyleImmersive) },
            )
            return@StyleSubPage
        }

        OverlayDropdownPreference(
            title = stringResource(R.string.style_align),
            items = listOf(
                stringResource(R.string.style_align_center),
                stringResource(R.string.style_align_left),
                stringResource(R.string.style_align_right),
            ),
            selectedIndex = draft.align.coerceIn(0, 2),
            onSelectedIndexChange = { index ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(align = index)
            },
        )

        val anchored = !draft.anchorId.isNullOrEmpty()
        if (!anchored) {
            SliderRow(
                label = stringResource(R.string.style_offset),
                value = draft.offsetRatio,
                range = 0f..0.9f,
                defaultValue = 0.42f,
                onChange = { StyleDraftStore.draft = draft.copy(offsetRatio = it) },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.style_anchor_section),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        SwitchPreference(
            title = stringResource(R.string.style_anchor_mode),
            summary = stringResource(R.string.style_anchor_mode_summary),
            checked = anchored,
            enabled = components.isNotEmpty() || anchored,
            onCheckedChange = { enabled ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(
                    anchorId = if (enabled) {
                        components.firstOrNull()?.id ?: draft.anchorId
                    } else {
                        null
                    },
                )
            },
        )
        if (!anchored) {
            return@StyleSubPage
        }
        if (components.isEmpty()) {
            Text(
                text = stringResource(R.string.style_anchor_empty),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@StyleSubPage
        }
        OverlayDropdownPreference(
            title = stringResource(R.string.style_anchor_component),
            items = components.map { it.label },
            selectedIndex = components.indexOfFirst {
                it.id == draft.anchorId || coarseAnchorId(draft.anchorId) == it.id
            }.coerceAtLeast(0),
            onSelectedIndexChange = { index ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(anchorId = components[index].id)
            },
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.style_anchor_position),
            items = listOf(
                stringResource(R.string.style_anchor_above),
                stringResource(R.string.style_anchor_below),
            ),
            selectedIndex = if (draft.anchorPos == 0) 0 else 1,
            onSelectedIndexChange = { index ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(anchorPos = index)
            },
        )
        SliderRow(
            label = stringResource(R.string.style_anchor_gap),
            value = draft.anchorGap,
            range = 0f..80f,
            defaultValue = 12f,
            format = { "${it.toInt()}" },
            onChange = { StyleDraftStore.draft = draft.copy(anchorGap = it) },
        )
        SwitchPreference(
            title = stringResource(R.string.style_tree_insert),
            summary = stringResource(R.string.style_tree_insert_summary),
            checked = draft.treeInsert,
            onCheckedChange = { enabled ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(treeInsert = enabled)
            },
        )
    }
}



// ── 息屏沉浸 ──────────────────────────────────────────────────────────

/** 二级页：沉浸模式 / 九宫格定位 / 横屏。 */
@Composable
internal fun StyleImmersivePage(bottomPadding: Dp) {
    StyleSubPage(
        title = stringResource(R.string.style_group_immersive),
        subtitle = stringResource(R.string.style_group_immersive_summary),
        bottomPadding = bottomPadding,
    ) {
        val draft = StyleDraftStore.draft

        OverlayDropdownPreference(
            title = stringResource(R.string.style_immersive),
            items = listOf(
                stringResource(R.string.style_immersive_off),
                stringResource(R.string.style_immersive_black),
                stringResource(R.string.style_immersive_wallpaper),
            ),
            selectedIndex = draft.immersive.coerceIn(0, 2),
            onSelectedIndexChange = { index ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(immersive = index)
            },
        )
        Text(
            text = stringResource(R.string.style_immersive_summary),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = stringResource(R.string.style_immersive_hint),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.style_grid_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.style_grid_hint),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (draft.immersive == 0) {
            Text(
                text = stringResource(R.string.style_immersive_grid_off),
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        ImmersiveGridPicker(
            selected = draft.immersiveGrid,
            enabled = draft.immersive != 0,
            onSelect = { index ->
                StyleDraftStore.draft = StyleDraftStore.draft.copy(immersiveGrid = index)
            },
        )

        Spacer(Modifier.height(10.dp))
        LabeledSwitchRow(
            title = stringResource(R.string.style_landscape),
            summary = stringResource(R.string.style_landscape_summary),
            checked = draft.landscape,
            onChange = { v -> StyleDraftStore.draft = draft.copy(landscape = v) },
        )
    }
}

/**
 * 九宫格定位选择器：3×3 共 9 档，行 = 垂直（上/中/下），列 = 水平（左/中/右）。
 *
 * 只画格子不画文字：格子里一个小圆点表示位置，选中的格子用主题色填充，
 * 下方用一行文案回读「当前：居中」。
 */
@Composable
private fun ImmersiveGridPicker(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val index = selected.coerceIn(0, 8)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .alpha(if (enabled) 1f else 0.45f)
                .clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 3) {
                        val cell = row * 3 + col
                        val on = cell == index
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    when {
                                        on && enabled -> MiuixTheme.colorScheme.primary
                                        on -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            .copy(alpha = 0.25f)
                                        else -> MiuixTheme.colorScheme.surface
                                    },
                                )
                                .border(
                                    width = 1.dp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        .copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(9.dp),
                                )
                                .clickable(enabled = enabled) { onSelect(cell) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (on) 10.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (on) {
                                            MiuixTheme.colorScheme.onPrimary
                                        } else {
                                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                .copy(alpha = 0.45f)
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.style_grid_current, gridLabel(index)),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
