package cn.qxfly.aod.ui.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.core.CardsData
import cn.qxfly.aod.ui.core.LocalUiHub
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val GITHUB_URL = "https://github.com/qxFly/qxfly-aod"
private const val DOCS_URL = "https://github.com/qxFly/qxfly-aod/blob/main/docs/README.md"
private const val SUPERLYRIC_URL = "https://github.com/HChenX/SuperLyric"

private val ActiveColor = Color(0xFF36D167)
private val ActiveBackground = Color(0xFFDFFAE4)
private val InactiveColor = Color(0xFFFF5A52)
private val InactiveBackground = Color(0xFFFFE5E3)
private val WarningBackgroundLight = Color(0xFFFFF3D6)
private val WarningContentLight = Color(0xFF704D00)
private val WarningBackgroundDark = Color(0xFF3A2D12)
private val WarningContentDark = Color(0xFFFFD978)

/**
 * 主页：对照参考图重做 —— 大标题 + 右上刷新、左大右两窄的状态 / 数字卡、
 * 警示横幅、信息卡、链接卡。歌词外观已拆到「外观」标签页。
 */
@Composable
fun HomePage(bottomPadding: Dp) {
    val data = LocalUiHub.current
    val cards by data.cards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = rememberVersionName(context)
    val versionDisplay = rememberVersionDisplay(context)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 0.dp,
            bottom = bottomPadding + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HomeHeader(
                title = stringResource(R.string.app_name),
                onRefresh = { data.refresh() },
            )
        }

        item { StatusGrid(cards, versionName) }

        if (cards.hooksInstalled == false) {
            item {
                WarningCard(
                    title = stringResource(R.string.home_banner_title),
                    message = stringResource(R.string.home_banner_text),
                )
            }
        }

        item { InfoCard(cards, versionDisplay) }

        item { LinkCard(context) }

        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** 顶部：右侧刷新按钮 + 下方大标题（参考图里图标在标题上方）。 */
@Composable
private fun HomeHeader(title: String, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = MiuixIcons.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh),
                )
            }
        }
        Text(
            text = title,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
    }
}

/** 左大卡（状态 + 版本）+ 右侧两窄卡（SuperLyric 接入 / 总开关）。 */
@Composable
private fun StatusGrid(cards: CardsData, versionName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(cards, versionName, Modifier.weight(1f).aspectRatio(1f))
        Column(
            modifier = Modifier.weight(1f).aspectRatio(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard(
                title = stringResource(R.string.home_stat_superlyric),
                value = when (cards.superLyricOk) {
                    true -> stringResource(R.string.home_stat_ok)
                    false -> stringResource(R.string.home_stat_fail)
                    null -> stringResource(R.string.home_stat_unknown)
                },
                modifier = Modifier.weight(1f),
            )
            StatCard(
                title = stringResource(R.string.home_stat_switch),
                value = stringResource(
                    if (cards.moduleEnabled) R.string.home_stat_on else R.string.home_stat_off,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusCard(cards: CardsData, versionName: String, modifier: Modifier) {
    val active = cards.hooksInstalled == true
    val statusColor = if (active) ActiveColor else InactiveColor
    val statusBackground = if (active) ActiveBackground else InactiveBackground
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(statusBackground),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                StatusBadge(
                    color = statusColor.copy(alpha = 0.78f),
                    modifier = Modifier.size(110.dp),
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = stringResource(
                        if (active) R.string.home_status_active else R.string.home_status_inactive,
                    ),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010),
                )
                Text(
                    text = stringResource(R.string.home_version_fmt, versionName),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (active) Color(0xFF101010) else statusColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** 参考图右下角的「圈 + 勾」大标记；用 Canvas 画，避免额外图片资源。 */
@Composable
private fun StatusBadge(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val strokeWidth = size.minDimension * 0.10f
        val radius = (size.minDimension - strokeWidth) / 2f
        drawCircle(
            color = color,
            radius = radius,
            style = Stroke(width = strokeWidth),
        )
        val check = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.68f)
            lineTo(size.width * 0.74f, size.height * 0.34f)
        }
        drawPath(
            path = check,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 窄卡：小标题 + 大号数值。 */
@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 黄色警示横幅（Hook 未注入时）。 */
@Composable
private fun WarningCard(title: String, message: String) {
    val light = MiuixTheme.colorScheme.background.luminance() > 0.5f
    val background = if (light) WarningBackgroundLight else WarningBackgroundDark
    val content = if (light) WarningContentLight else WarningContentDark
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(background, content),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = content,
            )
            Text(
                text = message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = content.copy(alpha = 0.82f),
            )
        }
    }
}

/** 信息卡：粗体标签 + 灰色值。 */
@Composable
private fun InfoCard(cards: CardsData, versionDisplay: String) {
    val unknown = stringResource(R.string.state_unknown)
    val framework = listOf(cards.moduleApi, cards.framework)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifEmpty { "LSPosed" }
    val feed = when {
        cards.track.isBlank() -> stringResource(R.string.state_no_feed)
        cards.lyricLine.isBlank() -> cards.track
        else -> "${cards.track} · ${cards.lyricLine}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoText(
                label = stringResource(R.string.info_system),
                value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            InfoText(
                label = stringResource(R.string.info_app_version),
                value = versionDisplay.ifBlank { unknown },
            )
            InfoText(
                label = stringResource(R.string.info_framework),
                value = framework.ifBlank { unknown },
            )
            InfoText(
                label = stringResource(R.string.info_device),
                value = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { unknown },
            )
            InfoText(
                label = stringResource(R.string.info_feed),
                value = feed,
                last = true,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun InfoText(
    label: String,
    value: String,
    last: Boolean = false,
    maxLines: Int = 1,
) {
    Text(
        text = label,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = value,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(top = 2.dp, bottom = if (last) 0.dp else 24.dp),
    )
}

/** 链接卡：标题 + 副标题 + 右侧链接图标。 */
@Composable
private fun LinkCard(context: Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LinkRow(
            title = stringResource(R.string.home_link_github_title),
            summary = stringResource(R.string.home_link_github_summary),
        ) { context.openUrl(GITHUB_URL) }
        LinkRow(
            title = stringResource(R.string.home_link_docs_title),
            summary = stringResource(R.string.home_link_docs_summary),
        ) { context.openUrl(DOCS_URL) }
        LinkRow(
            title = stringResource(R.string.home_link_res_title),
            summary = stringResource(R.string.home_link_res_summary),
        ) { context.openUrl(SUPERLYRIC_URL) }
    }
}

@Composable
private fun LinkRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = MiuixIcons.Link,
            contentDescription = null,
            modifier = Modifier.padding(start = 12.dp).size(26.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
