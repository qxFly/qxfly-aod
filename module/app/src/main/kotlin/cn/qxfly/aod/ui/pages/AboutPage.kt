package cn.qxfly.aod.ui.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.core.LocalNavigator
import cn.qxfly.aod.ui.core.Route
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Community
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DEVELOPER_URL = "https://github.com/qxFly"
private const val GITHUB_URL = "https://github.com/qxFly/qxfly-aod"
private const val ISSUES_URL = "https://github.com/qxFly/qxfly-aod/issues"
private const val RELEASES_URL = "https://github.com/qxFly/qxfly-aod/releases"
private const val DOCS_URL = "https://github.com/qxFly/qxfly-aod/blob/main/docs/README.md"

private val LightPalette = listOf(
    Color(1f, 0.90f, 0.94f),
    Color(1f, 0.84f, 0.89f),
    Color(0.97f, 0.73f, 0.82f),
    Color(0.64f, 0.65f, 0.98f),
)

private val DarkPalette = listOf(
    Color(0.20f, 0.06f, 0.88f, 0.55f),
    Color(0.30f, 0.14f, 0.55f, 0.65f),
    Color(0f, 0.64f, 0.96f, 0.65f),
    Color(0.11f, 0.16f, 0.83f, 0.55f),
)

/**
 * 关于页：对照参考图 —— 顶部动态渐变、居中大图标 / 渐变应用名 / 版本，
 * 下方「开发者 / 讨论 / 模块 / 项目」分组行卡。滚动时渐变与图标淡出。
 */
@Composable
fun AboutPage(bottomPadding: Dp) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val version = rememberVersionDisplay(context)
    val dark = isSystemInDarkTheme()
    val listState = rememberLazyListState()

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val heroHeight = screenHeight * 0.60f
    val heroHeightPx = with(density) { heroHeight.toPx() }
    val fadeDistancePx = with(density) { 389.dp.toPx() }

    val scrollOffset by remember(listState, heroHeightPx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                heroHeightPx
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }
    }
    val backgroundAlpha = (1f - scrollOffset / fadeDistancePx).coerceIn(0f, 1f)
    val heroProgress = (scrollOffset / (heroHeightPx * 0.55f)).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        AboutGradientBackground(
            dark = dark,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + 180.dp)
                .alpha(backgroundAlpha),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 0.dp,
                bottom = bottomPadding + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(heroHeight + 16.dp)) }

            item {
                Column(Modifier.fillMaxWidth()) {
                    SectionTitle(stringResource(R.string.about_developer))
                    DeveloperCard { context.openUrl(DEVELOPER_URL) }
                }
            }

            item {
                SectionTitle(stringResource(R.string.about_discussion))
                Card(modifier = Modifier.fillMaxWidth()) {
                    ActionRow(
                        title = stringResource(R.string.about_feedback),
                        icon = MiuixIcons.Community,
                        summary = stringResource(R.string.about_feedback_summary),
                        endArrow = false,
                    ) { context.openUrl(ISSUES_URL) }
                }
            }

            item {
                SectionTitle(stringResource(R.string.about_module))
                Card(modifier = Modifier.fillMaxWidth()) {
                    ActionRow(
                        title = stringResource(R.string.about_style),
                        icon = MiuixIcons.Tune,
                        summary = stringResource(R.string.about_style_summary),
                        endArrow = true,
                    ) { navigator.selectTab(Route.Style) }
                    ActionRow(
                        title = stringResource(R.string.about_check_update),
                        icon = MiuixIcons.Update,
                        endArrow = false,
                    ) { context.openUrl(RELEASES_URL) }
                }
            }

            item {
                SectionTitle(stringResource(R.string.about_project))
                Card(modifier = Modifier.fillMaxWidth()) {
                    ActionRow(
                        title = stringResource(R.string.about_github),
                        icon = MiuixIcons.Info,
                        summary = stringResource(R.string.about_github_summary),
                        endArrow = false,
                    ) { context.openUrl(GITHUB_URL) }
                    ActionRow(
                        title = stringResource(R.string.about_changelog),
                        icon = MiuixIcons.Update,
                        endArrow = false,
                    ) { context.openUrl(RELEASES_URL) }
                    ActionRow(
                        title = stringResource(R.string.about_docs),
                        icon = MiuixIcons.Info,
                        endArrow = false,
                    ) { context.openUrl(DOCS_URL) }
                    ActionRow(
                        title = stringResource(R.string.about_thanks),
                        icon = MiuixIcons.Info,
                        endArrow = false,
                    ) { context.openUrl(DOCS_URL) }
                }
            }
        }

        AboutHero(
            version = version,
            dark = dark,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(heroHeight),
            alpha = 1f - heroProgress,
            scale = 1f - heroProgress * 0.1f,
        )
    }
}

/** 顶部渐变：柔和的粉紫蓝底 + 四团径向高光，底部渐隐到页面背景。 */
@Composable
private fun AboutGradientBackground(dark: Boolean, modifier: Modifier = Modifier) {
    val palette = if (dark) DarkPalette else LightPalette
    Canvas(
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        },
    ) {
        drawRect(
            brush = Brush.linearGradient(
                colors = palette.map { it.copy(alpha = it.alpha * 0.62f) },
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
        val radius = size.maxDimension * 0.62f
        val centers = listOf(
            Offset(size.width * 0.18f, size.height * 0.20f),
            Offset(size.width * 0.82f, size.height * 0.78f),
            Offset(size.width * 0.22f, size.height * 0.80f),
            Offset(size.width * 0.80f, size.height * 0.20f),
        )
        centers.forEachIndexed { index, center ->
            val color = palette[index % palette.size]
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = 0.86f),
                        color.copy(alpha = 0f),
                    ),
                    center = center,
                    radius = radius,
                ),
                center = center,
                radius = radius,
            )
        }
        // DstIn：只保留顶部渐变，底部平滑过渡到页面背景色。
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    0.62f to Color.White,
                    1f to Color.Transparent,
                ),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

@Composable
private fun AboutHero(
    version: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    scale: Float = 1f,
) {
    Column(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppMark(Modifier.size(88.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            style = TextStyle(
                brush = Brush.linearGradient(
                    if (dark) {
                        listOf(Color(0xFFC79CFF), Color(0xFF9B6BFF), Color(0xFFE0A6FF))
                    } else {
                        listOf(Color(0xFF7B2CBF), Color(0xFF4A148C), Color(0xFF9D4EDD))
                    },
                ),
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = version,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 应用图标：圆角渐变块 + 白色方框与圆点（参考图 logo 的抽象复刻）。 */
@Composable
internal fun AppMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF7B2CBF), Color(0xFF4A148C)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension
            val stroke = side * 0.055f
            val inset = side * 0.20f
            val ringSide = side - inset * 2
            drawRoundRect(
                color = Color.White.copy(alpha = 0.94f),
                topLeft = Offset(inset, inset),
                size = Size(ringSide, ringSide),
                cornerRadius = CornerRadius(ringSide * 0.26f),
                style = Stroke(width = stroke),
            )
            drawCircle(
                color = Color.White,
                radius = side * 0.075f,
                center = Offset(side * 0.775f, side * 0.225f),
            )
        }
    }
}

@Composable
private fun DeveloperCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF7B2CBF), Color(0xFFE91E63))))
                    .border(2.dp, Color.White.copy(alpha = 0.68f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Q",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(R.string.about_dev_name),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.about_dev_handle),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

/** 行卡：左侧图标 + 标题/副标题 + 右侧箭头或链接图标。 */
@Composable
private fun ActionRow(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    endArrow: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Icon(
            imageVector = if (endArrow) MiuixIcons.Basic.ArrowRight else MiuixIcons.Link,
            contentDescription = null,
            modifier = if (endArrow) {
                Modifier.size(width = 10.dp, height = 16.dp)
            } else {
                Modifier.size(26.dp)
            },
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    SmallTitle(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
    )
}

private fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** 没有 Gradle 的 BuildConfig，运行时从 PackageManager 读取版本名。 */
@Composable
internal fun rememberVersionName(context: Context): String =
    remember(context) {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "?"
        }.getOrDefault("?")
    }

/** 参考图格式：1.0.261119.001(261119001)。 */
@Composable
internal fun rememberVersionDisplay(context: Context): String =
    remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName ?: "?"}($code)"
        }.getOrDefault("?")
    }
