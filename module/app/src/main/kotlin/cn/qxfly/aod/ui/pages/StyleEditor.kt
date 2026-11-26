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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qxfly.aod.LyricStyle
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.core.AnchorComponent
import cn.qxfly.aod.ui.core.LocalNavigator
import cn.qxfly.aod.ui.core.ModuleData
import cn.qxfly.aod.ui.core.Route
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 歌词外观的本地编辑草稿；「保存并应用」才写入 Provider。 */
data class StyleDraft(
    val textSizeSp: Float = 20f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val typefaceName: String? = null,
    val strokeWidth: Float = 0f,
    val strokeColor: Int = 0xFF000000.toInt(),
    val weight: Int = 0,
    val offsetRatio: Float = 0.42f,
    /** 0 居中（与组件一致），1 靠左，2 靠右。 */
    val align: Int = 0,
    /** 锚点组件资源名；null/空 = 用百分比偏移（旧模式）。 */
    val anchorId: String? = null,
    /** 0 = 锚点上方，1 = 锚点下方。 */
    val anchorPos: Int = 1,
    /** 与锚点边缘的间距（px）。 */
    val anchorGap: Float = 12f,
    /** true = 把歌词插进组件树的锚点槽位，由原生布局引擎排位。 */
    val treeInsert: Boolean = false,
    /** true = 发行方提供翻译时，在原文下方显示翻译。 */
    val showTranslation: Boolean = false,
    /** true = 在每行歌词后面画圆角色块。 */
    val bgEnabled: Boolean = false,
    /** 背景色块颜色（ARGB）。 */
    val bgColor: Int = 0xFF000000.toInt(),
    /** 背景色块不透明度 0..1。 */
    val bgAlpha: Float = 0.45f,
    /** 背景色块圆角（dp），0 = 直角。 */
    val bgRadiusDp: Float = 24f,
    /** true = 在歌词上方显示歌名与歌手。 */
    val showTrack: Boolean = false,
    /** true = 在歌词下方淡色显示下一句。 */
    val showNext: Boolean = false,
    /** true = 在歌词下方显示播放进度。 */
    val showProgress: Boolean = false,
    /** true = 暂停时把当前歌词变暗保留。 */
    val pauseKeep: Boolean = true,
    /** 沉浸模式：0 关闭 / 1 纯黑 / 2 保留壁纸。 */
    val immersive: Int = 0,
    /** true = 设备横放时把歌词转 90°。 */
    val landscape: Boolean = false,
    /** 沉浸模式九宫格位置：0..8 行优先，4 = 居中。 */
    val immersiveGrid: Int = 4,
)

internal fun LyricStyle.toDraft() = StyleDraft(
    textSizeSp = textSizeSp,
    textColor = textColor,
    typefaceName = typefaceName,
    strokeWidth = strokeWidth,
    strokeColor = strokeColor,
    weight = weight,
    offsetRatio = offsetRatio,
    align = align,
    anchorId = anchorId,
    anchorPos = anchorPos,
    anchorGap = anchorGap,
    treeInsert = treeInsert,
    showTranslation = showTranslation,
    bgEnabled = bgEnabled,
    bgColor = bgColor,
    bgAlpha = bgAlpha,
    bgRadiusDp = bgRadiusDp,
    showTrack = showTrack,
    showNext = showNext,
    showProgress = showProgress,
    pauseKeep = pauseKeep,
    immersive = immersive,
    landscape = landscape,
    immersiveGrid = immersiveGrid,
)

internal fun StyleDraft.toLyricStyle() = LyricStyle().apply {
    textSizeSp = this@toLyricStyle.textSizeSp
    textColor = this@toLyricStyle.textColor
    typefaceName = this@toLyricStyle.typefaceName
    strokeWidth = this@toLyricStyle.strokeWidth
    strokeColor = this@toLyricStyle.strokeColor
    weight = this@toLyricStyle.weight
    offsetRatio = this@toLyricStyle.offsetRatio
    align = this@toLyricStyle.align
    anchorId = canonicalAnchorValue(this@toLyricStyle.anchorId)
    anchorPos = this@toLyricStyle.anchorPos
    anchorGap = this@toLyricStyle.anchorGap
    treeInsert = this@toLyricStyle.treeInsert
    showTranslation = this@toLyricStyle.showTranslation
    bgEnabled = this@toLyricStyle.bgEnabled
    bgColor = this@toLyricStyle.bgColor
    bgAlpha = this@toLyricStyle.bgAlpha
    bgRadiusDp = this@toLyricStyle.bgRadiusDp
    showTrack = this@toLyricStyle.showTrack
    showNext = this@toLyricStyle.showNext
    showProgress = this@toLyricStyle.showProgress
    pauseKeep = this@toLyricStyle.pauseKeep
    immersive = this@toLyricStyle.immersive
    landscape = this@toLyricStyle.landscape
    immersiveGrid = this@toLyricStyle.immersiveGrid
}

/** 粗粒度锚点（与 Hook 侧 ANCHOR_* 一致）：大时钟 / 图片 / 时间日期组件 / 通知。 */
private val COARSE_ANCHORS = setOf("clock", "image", "timedate", "notify")

/** 旧细粒度锚点 -> 最近粗粒度锚点（与 Hook 侧 legacyToCanonical 规则一致）。 */
internal fun coarseAnchorId(id: String?): String? {
    val l = id?.lowercase() ?: return null
    return when {
        listOf("shower", "image", "gallery", "photo", "wallpaper", "cover", "album")
            .any(l::contains) -> "image"
        listOf("gradient", "clock_container", "clock_vertical", "big")
            .any(l::contains) -> "clock"
        listOf("icon", "notif").any(l::contains) -> "notify"
        listOf("clock", "date", "time", "lunar", "battery", "step")
            .any(l::contains) -> "timedate"
        else -> null
    }
}

/** 保存前归一化：已是粗粒度 id 原样保留，旧 id 映射，未识别的保持原值。 */
private fun canonicalAnchorValue(id: String?): String? {
    if (id.isNullOrEmpty() || COARSE_ANCHORS.contains(id)) {
        return id
    }
    return coarseAnchorId(id) ?: id
}

/**
 * 外观草稿的进程级存放点。
 *
 * 主页和 4 个二级页共用同一份草稿，只有「保存并应用」才写入 Provider；
 * 二级页来回切换不会丢改动。进程重建后从 Provider 重新读取一次。
 */
internal object StyleDraftStore {
    var draft by mutableStateOf(StyleDraft())

    /** 最近一次操作提示；空串表示不显示。 */
    var status by mutableStateOf("")
        private set

    /**
     * 提示序号：每次 [show] 都递增。
     *
     * 计时不能用 [status] 本身做 key——连点同一个按钮时文案没变，key 不变，
     * 协程不会重启，提示会在上一次的 2 秒到点时提前消失。
     */
    var statusSeq by mutableStateOf(0)
        private set

    private var loaded = false

    /** 显示一条提示并重新开始计时。 */
    fun show(text: String) {
        status = text
        statusSeq++
    }

    fun clearStatus() {
        status = ""
    }

    fun ensureLoaded(data: ModuleData) {
        if (!loaded) {
            draft = data.currentStyle().toDraft()
            loaded = true
        }
    }
}

/**
 * 外观主页第一张卡片：所见即所得预览。
 *
 * 只放「看效果」——所有细项都收进下面的分类二级页，避免一个页面堆三十多个控件。
 * （预设一键换风格已按用户要求移除：观感完全由二级页里的具体参数决定。）
 */
@Composable
internal fun StylePreviewCard(data: ModuleData) {
    StyleDraftStore.ensureLoaded(data)
    val draft = StyleDraftStore.draft

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        StylePreview(draft)
    }
}

/**
 * 3 个功能分类的入口卡：主页只显示「当前值摘要」，点进去才是二级页。
 *
 * 分组原则：按用户调节时的目的分——文字/背景/附加显示、放在哪里、息屏沉浸。
 */
@Composable
internal fun StyleGroupsCard(data: ModuleData) {
    val navigator = LocalNavigator.current
    val draft = StyleDraftStore.draft
    val components by data.components.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        GroupRow(
            title = stringResource(R.string.style_group_text),
            summary = textStyleSummary(draft),
        ) { navigator.push(Route.StyleText) }
        GroupDivider()
        GroupRow(
            title = stringResource(R.string.style_group_layout),
            summary = layoutSummary(draft, components),
        ) { navigator.push(Route.StyleLayout) }
        GroupDivider()
        GroupRow(
            title = stringResource(R.string.style_group_immersive),
            summary = immersiveSummary(draft),
        ) { navigator.push(Route.StyleImmersive) }
    }
}

/**
 * 保存卡：主页和每个二级页都有同一份，改完就地保存，不用退回主页。
 *
 * 提示条用固定高度占位：文字出现/消失都不会改变按钮位置（按钮不再乱跳，
 * 连续点击也不会因为行高变化而点空）。
 */
@Composable
internal fun StyleSaveCard(data: ModuleData) {
    val savedMsg = stringResource(R.string.style_saved)
    val resetMsg = stringResource(R.string.style_reset_done)

    LaunchedEffect(StyleDraftStore.statusSeq) {
        if (StyleDraftStore.status.isNotEmpty()) {
            delay(2000)
            StyleDraftStore.clearStatus()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.style_save),
                onClick = {
                    data.save(StyleDraftStore.draft.toLyricStyle())
                    StyleDraftStore.show(savedMsg)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(
                text = stringResource(R.string.style_reset),
                onClick = {
                    StyleDraftStore.draft = StyleDraft()
                    StyleDraftStore.show(resetMsg)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(top = 4.dp),
        ) {
            if (StyleDraftStore.status.isNotEmpty()) {
                Text(
                    text = StyleDraftStore.status,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 配置导入 / 导出卡（SAF，不需要存储权限）。 */
@Composable
internal fun StyleBackupCard() {
    val context = LocalContext.current
    val exportFailedMsg = stringResource(R.string.style_import_fail)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(StyleDraftStore.draft.toJson().toByteArray(Charsets.UTF_8))
                } != null
            }.getOrDefault(false)
            StyleDraftStore.show(
                if (ok) {
                    context.getString(R.string.style_export_ok, uri.lastPathSegment ?: "JSON")
                } else {
                    exportFailedMsg
                },
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() }
            }.getOrNull()
            val imported = text?.let { draftFromJson(it) }
            if (imported != null) {
                StyleDraftStore.draft = imported
                StyleDraftStore.show(context.getString(R.string.style_import_ok))
            } else {
                StyleDraftStore.show(context.getString(R.string.style_import_fail))
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = stringResource(R.string.style_backup),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.style_backup_summary),
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.style_export),
                onClick = { exportLauncher.launch("aodlyric-style.json") },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.style_import),
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 二级页入口行：标题 + 当前值摘要 + 右箭头。 */
@Composable
private fun GroupRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(
                text = summary,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 卡片内的分组分隔线：左侧留 16dp，与标题对齐。 */
@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)),
    )
}

/** 文字摘要：字号 · 字体 · 颜色（· 背景开）（· 歌名/下一句/进度/翻译）。 */
@Composable
private fun textStyleSummary(d: StyleDraft): String {
    val font = when (d.typefaceName) {
        "sans-serif" -> stringResource(R.string.font_sans)
        "serif" -> stringResource(R.string.font_serif)
        "monospace" -> stringResource(R.string.font_mono)
        "custom" -> stringResource(R.string.font_custom)
        else -> stringResource(R.string.font_system)
    }
    val color = "#" + String.format(java.util.Locale.US, "%06X", d.textColor and 0xFFFFFF)
    val parts = mutableListOf(
        stringResource(R.string.style_group_text_value, d.textSizeSp.toInt(), font, color),
    )
    if (d.bgEnabled) {
        parts += stringResource(R.string.style_summary_bg_on)
    }
    val extras = mutableListOf<String>()
    if (d.showTrack) extras += stringResource(R.string.style_summary_track)
    if (d.showNext) extras += stringResource(R.string.style_summary_next)
    if (d.showProgress) extras += stringResource(R.string.style_summary_progress)
    if (d.showTranslation) extras += stringResource(R.string.style_summary_translation)
    if (extras.isNotEmpty()) {
        parts += extras.joinToString("/")
    }
    if (!d.pauseKeep) {
        parts += stringResource(R.string.style_summary_pause_off)
    }
    return parts.joinToString(" · ")
}

/** 位置摘要：沉浸模式下由九宫格决定，否则是「对齐 + 垂直百分比」或锚点组件。 */
@Composable
private fun layoutSummary(d: StyleDraft, components: List<AnchorComponent>): String {
    if (d.immersive != 0) {
        return stringResource(R.string.style_group_layout_immersive, gridLabel(d.immersiveGrid))
    }
    val anchor = d.anchorId
    if (!anchor.isNullOrEmpty()) {
        val label = components
            .firstOrNull { it.id == anchor || coarseAnchorId(anchor) == it.id }
            ?.label ?: coarseAnchorName(anchor)
        return stringResource(R.string.style_group_layout_anchor, label)
    }
    return stringResource(
        R.string.style_group_layout_value,
        alignLabel(d.align),
        (d.offsetRatio * 100).toInt(),
    )
}

/** 粗粒度锚点的可读名：组件清单里没有对应项时兜底，避免摘要显示裸 id。 */
@Composable
private fun coarseAnchorName(id: String): String = when (coarseAnchorId(id) ?: id) {
    "clock" -> stringResource(R.string.anchor_name_clock)
    "image" -> stringResource(R.string.anchor_name_image)
    "timedate" -> stringResource(R.string.anchor_name_timedate)
    "notify" -> stringResource(R.string.anchor_name_notify)
    else -> stringResource(R.string.anchor_name_other)
}

/** 沉浸摘要：关闭 / 模式 · 九宫格位置（· 横屏）。 */
@Composable
private fun immersiveSummary(d: StyleDraft): String {
    val mode = when (d.immersive) {
        1 -> stringResource(R.string.style_immersive_black)
        2 -> stringResource(R.string.style_immersive_wallpaper)
        else -> return stringResource(R.string.style_immersive_off)
    }
    return if (d.landscape) {
        stringResource(R.string.style_group_immersive_value_landscape, mode, gridLabel(d.immersiveGrid))
    } else {
        stringResource(R.string.style_group_immersive_value, mode, gridLabel(d.immersiveGrid))
    }
}

/** 对齐方式的短名（摘要用，避免「居中（与组件一致）」撑长一行）。 */
@Composable
internal fun alignLabel(align: Int): String = stringResource(
    when (align) {
        1 -> R.string.style_align_left
        2 -> R.string.style_align_right
        else -> R.string.style_align_short_center
    },
)

/** 九宫格位置名：0 左上 … 4 居中 … 8 右下。 */
@Composable
internal fun gridLabel(index: Int): String = stringResource(
    when (index.coerceIn(0, 8)) {
        0 -> R.string.style_grid_pos_0
        1 -> R.string.style_grid_pos_1
        2 -> R.string.style_grid_pos_2
        3 -> R.string.style_grid_pos_3
        4 -> R.string.style_grid_pos_4
        5 -> R.string.style_grid_pos_5
        6 -> R.string.style_grid_pos_6
        7 -> R.string.style_grid_pos_7
        else -> R.string.style_grid_pos_8
    },
)

/** 标题 + 说明 + 右侧开关，用于附加信息里的 4 个选项。 */
@Composable
internal fun LabeledSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(
                text = summary,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 配置导出格式：带版本号，方便以后兼容旧文件。 */
private const val STYLE_JSON_VERSION = 1

private fun StyleDraft.toJson(): String = org.json.JSONObject().apply {
    put("format", "aodlyric-style")
    put("version", STYLE_JSON_VERSION)
    put("textSizeSp", textSizeSp.toDouble())
    put("textColor", textColor)
    put("typefaceName", typefaceName ?: "")
    put("strokeWidth", strokeWidth.toDouble())
    put("strokeColor", strokeColor)
    put("weight", weight)
    put("offsetRatio", offsetRatio.toDouble())
    put("align", align)
    put("anchorId", anchorId ?: "")
    put("anchorPos", anchorPos)
    put("anchorGap", anchorGap.toDouble())
    put("treeInsert", treeInsert)
    put("showTranslation", showTranslation)
    put("bgEnabled", bgEnabled)
    put("bgColor", bgColor)
    put("bgAlpha", bgAlpha.toDouble())
    put("bgRadiusDp", bgRadiusDp.toDouble())
    put("showTrack", showTrack)
    put("showNext", showNext)
    put("showProgress", showProgress)
    put("pauseKeep", pauseKeep)
    put("immersive", immersive)
    put("landscape", landscape)
    put("immersiveGrid", immersiveGrid)
}.toString(2)

private fun draftFromJson(raw: String): StyleDraft? = runCatching {
    val o = org.json.JSONObject(raw)
    if (o.optString("format") != "aodlyric-style") {
        return null
    }
    StyleDraft(
        textSizeSp = o.optDouble("textSizeSp", 20.0).toFloat(),
        textColor = o.optInt("textColor", 0xFFFFFFFF.toInt()),
        typefaceName = o.optString("typefaceName").takeIf { it.isNotEmpty() },
        strokeWidth = o.optDouble("strokeWidth", 0.0).toFloat(),
        strokeColor = o.optInt("strokeColor", 0xFF000000.toInt()),
        weight = o.optInt("weight", 0),
        offsetRatio = o.optDouble("offsetRatio", 0.42).toFloat(),
        align = o.optInt("align", 0),
        anchorId = o.optString("anchorId").takeIf { it.isNotEmpty() },
        anchorPos = o.optInt("anchorPos", 1),
        anchorGap = o.optDouble("anchorGap", 12.0).toFloat(),
        treeInsert = o.optBoolean("treeInsert", false),
        showTranslation = o.optBoolean("showTranslation", false),
        bgEnabled = o.optBoolean("bgEnabled", false),
        bgColor = o.optInt("bgColor", 0xFF000000.toInt()),
        bgAlpha = o.optDouble("bgAlpha", 0.45).toFloat(),
        bgRadiusDp = o.optDouble("bgRadiusDp", 24.0).toFloat(),
        showTrack = o.optBoolean("showTrack", false),
        showNext = o.optBoolean("showNext", false),
        showProgress = o.optBoolean("showProgress", false),
        pauseKeep = o.optBoolean("pauseKeep", true),
        immersive = o.optInt("immersive", 0),
        landscape = o.optBoolean("landscape", false),
        immersiveGrid = o.optInt("immersiveGrid", 4).coerceIn(0, 8),
    )
}.getOrNull()

/** 选中态明确的文字按钮：字体等选项点击后必须有可见反馈。 */
@Composable
internal fun SelectableChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary
                else MiuixTheme.colorScheme.surfaceContainer,
            )
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (selected) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 歌词效果预览：按草稿的字号 / 颜色 / 字体 / 粗细 / 描边 / 对齐
 * 在深色底上渲染一句样例，即所见即所得。
 */
@Composable
private fun StylePreview(draft: StyleDraft) {
    val fontFamily = when (draft.typefaceName) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        "sans-serif" -> FontFamily.SansSerif
        else -> FontFamily.Default
    }
    val fontWeight = if (draft.weight == 0) FontWeight.Bold else FontWeight.Normal
    val alignment = when (draft.align) {
        1 -> TextAlign.Start
        2 -> TextAlign.End
        else -> TextAlign.Center
    }
    // 背景色块：开启时贴着文字画一个圆角矩形（Hook 侧为整体面板）。
    val bgModifier = if (draft.bgEnabled) {
        Modifier
            .clip(RoundedCornerShape(draft.bgRadiusDp.coerceIn(0f, 40f).dp))
            .background(Color(draft.bgColor).copy(alpha = draft.bgAlpha))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B0E14))
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = when (alignment) {
                TextAlign.Start -> Alignment.Start
                TextAlign.End -> Alignment.End
                else -> Alignment.CenterHorizontally
            },
        ) {
            if (draft.showTrack) {
                BasicText(
                    text = stringResource(R.string.style_preview_track),
                    style = TextStyle(
                        fontSize = (draft.textSizeSp * 0.58f).sp,
                        color = Color(draft.textColor).copy(alpha = 0.55f),
                        fontWeight = fontWeight,
                        fontFamily = fontFamily,
                        textAlign = alignment,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (draft.strokeWidth > 0.05f) {
                // Compose 没有文字描边 API：用 8 向偏移的同字副本模拟。
                // 偏移量就是「描边在字外多厚」，与 Hook 侧的 dp 语义一致。
                val sw = draft.strokeWidth.coerceIn(0.5f, LyricStyle.STROKE_MAX_DP)
                Box {
                    listOf(
                        -sw to 0f, sw to 0f, 0f to -sw, 0f to sw,
                        -sw to -sw, sw to -sw, -sw to sw, sw to sw,
                    ).forEach { (dx, dy) ->
                        BasicText(
                            text = stringResource(R.string.style_preview_sample),
                            style = TextStyle(
                                fontSize = draft.textSizeSp.sp,
                                color = Color(draft.strokeColor),
                                fontWeight = fontWeight,
                                fontFamily = fontFamily,
                                textAlign = alignment,
                            ),
                            modifier = bgModifier
                                .then(Modifier.offset(dx.dp, dy.dp)),
                        )
                    }
                    BasicText(
                        text = stringResource(R.string.style_preview_sample),
                        style = TextStyle(
                            fontSize = draft.textSizeSp.sp,
                            color = Color(draft.textColor),
                            fontWeight = fontWeight,
                            fontFamily = fontFamily,
                            textAlign = alignment,
                        ),
                        modifier = bgModifier,
                    )
                }
            } else {
                BasicText(
                    text = stringResource(R.string.style_preview_sample),
                    style = TextStyle(
                        fontSize = draft.textSizeSp.sp,
                        color = Color(draft.textColor),
                        fontWeight = fontWeight,
                        fontFamily = fontFamily,
                        textAlign = alignment,
                        lineHeight = (draft.textSizeSp * 1.1f).sp,
                    ),
                    modifier = bgModifier,
                )
            }
            if (draft.showNext) {
                BasicText(
                    text = stringResource(R.string.style_preview_next),
                    style = TextStyle(
                        fontSize = (draft.textSizeSp * 0.60f).sp,
                        color = Color(draft.textColor).copy(alpha = 0.45f),
                        fontWeight = fontWeight,
                        fontFamily = fontFamily,
                        textAlign = alignment,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (draft.showProgress) {
                BasicText(
                    text = stringResource(R.string.style_preview_progress),
                    style = TextStyle(
                        fontSize = (draft.textSizeSp * 0.52f).sp,
                        color = Color(draft.textColor).copy(alpha = 0.50f),
                        textAlign = alignment,
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 颜色盘：色相 / 饱和度 / 明度三个滑条 + 实时色块预览，
 * 确定后写回目标颜色（文字色或描边色）。
 */
@Composable
internal fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val start = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    }
    var hue by remember { mutableStateOf(start[0]) }
    var sat by remember { mutableStateOf(start[1] * 100f) }
    var brightness by remember { mutableStateOf(start[2] * 100f) }
    val argb = remember(hue, sat, brightness) {
        android.graphics.Color.HSVToColor(
            floatArrayOf(hue.coerceIn(0f, 360f), sat / 100f, brightness / 100f),
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
        ) {
            Text(
                text = stringResource(R.string.style_color_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(argb)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = stringResource(R.string.style_picker_sample),
                    style = TextStyle(
                        fontSize = 22.sp,
                        color = Color.White,
                        shadow = Shadow(color = Color(0x99000000), blurRadius = 6f),
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            SliderRow(stringResource(R.string.style_hue), hue, 0f..360f,
                defaultValue = start[0], format = { "${it.toInt()}" },
                onChange = { hue = it })
            SliderRow(stringResource(R.string.style_sat), sat, 0f..100f,
                defaultValue = start[1] * 100f, format = { "${it.toInt()}" },
                onChange = { sat = it })
            SliderRow(stringResource(R.string.style_val), brightness, 0f..100f,
                defaultValue = start[2] * 100f, format = { "${it.toInt()}" },
                onChange = { brightness = it })
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "#" + Integer.toHexString(argb or 0xFF000000.toInt())
                        .uppercase(java.util.Locale.US),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.style_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.style_ok),
                    onClick = { onConfirm(argb) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 调色板行：预置色 + 自定义入口（带选中描边）。 */
@Composable
internal fun ColorSwatches(selected: Int, onPick: (Int) -> Unit, onCustom: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StyleColors.forEach { argb ->
            Swatch(
                color = argb,
                selected = selected == argb,
                onClick = { onPick(argb) },
            )
        }
        Swatch(
            color = selected,
            selected = StyleColors.none { it == selected },
            onClick = onCustom,
            label = "+",
        )
    }
}

@Composable
private fun Swatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
    label: String? = null,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(label, fontSize = 18.sp, color = Color.White)
        }
    }
}

/** slider 行：左侧标签 + 可点击输入的当前值，下面滑条。 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    onChange: (Float) -> Unit,
    format: (Float) -> String = { String.format(java.util.Locale.US, "%.2f", it) },
    /** 值 → 输入框文本（百分比类滑条覆盖此映射）。 */
    toInput: (Float) -> String = { trimNumber(it) },
    /** 输入框文本 → 值（百分比类滑条覆盖为 /100）。 */
    fromInput: (String) -> Float? = { it.trim().toFloatOrNull() },
) {
    var editing by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), fontSize = 13.sp)
            Text(
                text = format(value),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { editing = true }
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
    if (editing) {
        NumberInputDialog(
            title = label,
            initialText = toInput(value),
            // 范围也用 toInput 映射，百分比滑条才显示 0 ~ 100 而不是 0 ~ 1。
            rangeText = stringResource(
                R.string.style_input_hint,
                toInput(range.start),
                toInput(range.endInclusive),
                format(defaultValue),
            ),
            onReset = {
                onChange(defaultValue)
                editing = false
            },
            onDismiss = { editing = false },
            onConfirm = { text ->
                val parsed = fromInput(text)
                if (parsed == null || parsed.isNaN()) {
                    false
                } else {
                    val clamped = parsed.coerceIn(range.start, range.endInclusive)
                    onChange(clamped)
                    editing = false
                    true
                }
            },
        )
    }
}

/** 数值输入弹窗：确定 / 取消 / 恢复默认。 */
@Composable
private fun NumberInputDialog(
    title: String,
    initialText: String,
    rangeText: String,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean,
) {
    var text by remember { mutableStateOf(initialText) }
    var invalid by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp),
        ) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = rangeText,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 17.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }
            if (invalid) {
                Text(
                    text = stringResource(R.string.style_input_invalid),
                    fontSize = 11.sp,
                    color = Color(0xFFDD5346),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // 「恢复默认」放独立一行：与「取消/确定」同排会把文字挤成两行。
            Text(
                text = stringResource(R.string.style_reset_default),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.style_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.style_ok),
                    onClick = { if (!onConfirm(text)) invalid = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 输入框里的数值：整数不带小数点，其余最多两位小数。 */
private fun trimNumber(v: Float): String =
    if (v == v.toInt().toFloat()) {
        v.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

/** 外观可选颜色（ARGB），与旧界面一致。 */
internal val StyleColors = listOf(
    0xFFFFFFFF.toInt(),
    0xFF7D9BFF.toInt(),
    0xFF6DD58C.toInt(),
    0xFFF2C55C.toInt(),
    0xFFF2857C.toInt(),
    0xFFE8C8FF.toInt(),
)

/** 字体族选项：显示名资源 → typeface 名（null = 系统默认）。 */
internal val StyleFonts = listOf(
    R.string.font_system to null,
    R.string.font_sans to "sans-serif",
    R.string.font_serif to "serif",
    R.string.font_mono to "monospace",
)

/** 自定义字体选择器接受的 MIME（部分文件管理器把 ttf 标成 octet-stream）。 */
internal val FONT_MIME_TYPES = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/octet-stream",
)

private const val FONT_PREFS = "style"
private const val FONT_LABEL_KEY = "custom_font_label"

/** 上一次导入的字体原始文件名（仅用于界面展示）。 */
internal fun readCustomFontLabel(context: android.content.Context): String =
    context.getSharedPreferences(FONT_PREFS, android.content.Context.MODE_PRIVATE)
        .getString(FONT_LABEL_KEY, "")
        .orEmpty()

/**
 * 把用户在 SAF 里选中的字体拷贝为固定槽位文件 fonts/custom.ttf。
 *
 * <p>简单魔数校验拦截误选的任意文件：TrueType（0x00010000 / "true" / "ttcf"）
 * 或 OpenType（"OTTO"）。成功时返回原始文件名用于展示；已存在的文件被覆盖，
 * 保存样式后 Hook 侧下次应用样式时重新加载。
 */
internal fun importCustomFont(
    context: android.content.Context,
    uri: android.net.Uri,
): String? {
    return try {
        val displayName = queryDisplayName(context, uri).orEmpty()
        val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
        val out = java.io.File(dir, "custom.ttf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            // 边拷贝边读魔数：前 4 字节不是字体就直接拒绝，不再写盘。
            val head = ByteArray(4)
            val read = input.read(head)
            if (read < 4 || !isFontMagic(head)) {
                return null
            }
            out.outputStream().use { output ->
                output.write(head)
                input.copyTo(output)
            }
        } ?: return null
        if (out.length() < 512L) {
            out.delete()
            return null
        }
        context.getSharedPreferences(FONT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(FONT_LABEL_KEY, displayName).apply()
        displayName
    } catch (t: Throwable) {
        null
    }
}

/** TrueType / OpenType 的文件头。 */
private fun isFontMagic(head: ByteArray): Boolean {
    val ttf = head[0] == 0.toByte() && head[1] == 0x01.toByte()
            && head[2] == 0x00.toByte() && head[3] == 0x00.toByte()
    val otto = head[0] == 'O'.code.toByte() && head[1] == 'T'.code.toByte()
            && head[2] == 'T'.code.toByte() && head[3] == 'O'.code.toByte()
    val truthy = head[0] == 't'.code.toByte() && head[1] == 'r'.code.toByte()
            && head[2] == 'u'.code.toByte() && head[3] == 'e'.code.toByte()
    val ttcf = head[0] == 't'.code.toByte() && head[1] == 't'.code.toByte()
            && head[2] == 'c'.code.toByte() && head[3] == 'f'.code.toByte()
    return ttf || otto || truthy || ttcf
}

@Suppress("DEPRECATION")
private fun queryDisplayName(
    context: android.content.Context,
    uri: android.net.Uri,
): String? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (t: Throwable) {
        null
    }
}
