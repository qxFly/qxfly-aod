package cn.qxfly.aod.ui.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.qxfly.aod.AodLog
import cn.qxfly.aod.AodLyricModule
import cn.qxfly.aod.LyricPublisher
import cn.qxfly.aod.R
import cn.qxfly.aod.StateKeys
import cn.qxfly.aod.ui.base.BackButton
import cn.qxfly.aod.ui.base.MiuixPage
import cn.qxfly.aod.ui.core.LocalNavigator
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 诊断页：把跨进程状态、Hook 日志和应用日志集中到一处，并支持一键复制。
 *
 * <p>Hook 进程（SystemUI / com.miui.aod）的日志 UI 进程读不到，所以采用
 * 「请求 + 回传」：点「请求 Hook 日志」写 DIAG_REQUEST，Hook 在下一次轮询里
 * 把环形缓冲的末尾 30 行塞回 provider。
 */
@Composable
fun DiagnosticsPage(bottomPadding: Dp) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var snapshot by remember { mutableStateOf<Bundle?>(null) }
    var statusLine by remember { mutableStateOf("") }
    var debugLog by remember { mutableStateOf(AodLog.isDebug()) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = readSnapshot(context)
            // The hook answers on its own poll cadence; 1.2s keeps the page
            // visibly live without hammering the provider.
            delay(1200)
        }
    }

    LaunchedEffect(statusLine) {
        if (statusLine.isNotEmpty()) {
            delay(2200)
            statusLine = ""
        }
    }

    val snap = snapshot
    val copyText = buildString {
        appendLine("息屏歌词诊断信息")
        appendLine("app=${versionName(context)} build=${AodLyricModule.BUILD}")
        snap?.let { s ->
            for (key in s.keySet().sorted()) {
                if (key == StateKeys.LOG_TAIL) continue
                appendLine("$key=${s.get(key)}")
            }
            appendLine("---- hook log ----")
            appendLine(s.getString(StateKeys.LOG_TAIL).orEmpty())
        }
        appendLine("---- app log ----")
        appendLine(AodLog.tail(40))
    }

    MiuixPage(
        title = stringResource(R.string.diag_title),
        subtitle = stringResource(R.string.diag_subtitle),
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
                Text(
                    text = stringResource(R.string.diag_section_status),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                KeyValue("App", "${versionName(context)} · ${AodLyricModule.BUILD}")
                KeyValue("Schema", snap?.getString(StateKeys.SCHEMA) ?: "—")
                KeyValue(
                    "Hook",
                    when (snap?.getBoolean(StateKeys.HOOKS_INSTALLED, false)) {
                        true -> "已安装"
                        false -> "未安装/失败"
                        null -> "—"
                    },
                )
                KeyValue("Detail", snap?.getString(StateKeys.HOOK_DETAIL) ?: "—")
                KeyValue(
                    "Framework",
                    listOfNotNull(
                        snap?.getString(StateKeys.MODULE_API),
                        snap?.getString(StateKeys.MODULE_FRAMEWORK),
                    ).joinToString(" · ").ifEmpty { "—" },
                )
                KeyValue(
                    "SuperLyric",
                    LyricPublisher.get().ok()?.let { ok ->
                        if (ok) LyricPublisher.get().detail() else "未接入"
                    } ?: "尚未注册",
                )
                KeyValue("设备", android.os.Build.MODEL + " · API " + android.os.Build.VERSION.SDK_INT)
                val track = snap?.getString(StateKeys.LYRIC_TRACK).orEmpty()
                val line = snap?.getString(StateKeys.LYRIC_LINE).orEmpty()
                if (track.isNotEmpty()) {
                    KeyValue("曲目", track)
                    KeyValue("当前行", line.ifEmpty { "—" })
                    KeyValue("状态", snap?.getString(StateKeys.LYRIC_PLAY_STATE) ?: "—")
                    val pos = snap?.getString(StateKeys.LYRIC_POSITION)
                    val dur = snap?.getString(StateKeys.LYRIC_DURATION)
                    if (pos != null) {
                        KeyValue("进度", "$pos / ${dur ?: "?"} ms")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = stringResource(R.string.diag_refresh),
                        onClick = {
                            runCatching {
                                context.contentResolver.call(
                                    StateKeys.stateUri(),
                                    StateKeys.METHOD_DIAG_REQUEST,
                                    System.currentTimeMillis().toString(),
                                    null,
                                )
                            }
                            statusLine = context.getString(R.string.diag_refresh_sent)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.diag_copy),
                        onClick = {
                            copyToClipboard(context, copyText)
                            statusLine = context.getString(R.string.diag_copied)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.diag_section_hook),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = listOfNotNull(
                        snap?.getString(StateKeys.LOG_PROCESS),
                        snap?.getString(StateKeys.LOG_BUILD),
                    ).joinToString(" · "),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                LogBlock(snap?.getString(StateKeys.LOG_TAIL))
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.diag_section_app),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                LogBlock(AodLog.tail(30))
            }
        }

        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.diag_debug_log),
                    summary = stringResource(R.string.diag_debug_log_summary),
                    checked = debugLog,
                    onCheckedChange = { enabled ->
                        debugLog = enabled
                        AodLog.setDebug(enabled)
                        runCatching {
                            val b = Bundle()
                            b.putString(StateKeys.TYPE, StateKeys.TYPE_MODULE)
                            b.putBoolean(StateKeys.LOG_DEBUG, enabled)
                            context.contentResolver.call(
                                StateKeys.stateUri(), StateKeys.METHOD_UPDATE, null, b,
                            )
                        }
                    },
                )
            }
        }

        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 24.dp),
            ) {
                if (statusLine.isNotEmpty()) {
                    Text(
                        text = statusLine,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(0.32f),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.68f),
        )
    }
}

@Composable
private fun LogBlock(text: String?) {
    val body = text?.trim().orEmpty()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
    ) {
        Text(
            text = body.ifEmpty { stringResource(R.string.diag_empty) },
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun readSnapshot(context: Context): Bundle? = runCatching {
    context.contentResolver.call(StateKeys.stateUri(), StateKeys.METHOD_GET, null, null)
}.getOrNull()

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "?"

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aodlyric-diagnostics", text))
    }
}
