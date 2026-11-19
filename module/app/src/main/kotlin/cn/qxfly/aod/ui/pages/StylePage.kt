package cn.qxfly.aod.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.qxfly.aod.R
import cn.qxfly.aod.ui.base.MiuixPage
import cn.qxfly.aod.ui.core.LocalUiHub
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 外观页：总开关 + 息屏歌词外观编辑（从原主页拆出来，主页只保留概览）。
 */
@Composable
fun StylePage(bottomPadding: Dp) {
    val data = LocalUiHub.current
    val cards by data.cards.collectAsStateWithLifecycle()

    MiuixPage(
        title = stringResource(R.string.tab_style),
        subtitle = stringResource(R.string.style_page_subtitle),
        bottomPadding = bottomPadding,
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.home_master_switch),
                    summary = stringResource(R.string.home_master_switch_summary),
                    checked = cards.moduleEnabled,
                    onCheckedChange = { enabled -> data.setModuleEnabled(enabled) },
                )
            }
        }
        item { StyleEditorCard(data) }
    }
}
