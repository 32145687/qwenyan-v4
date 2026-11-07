package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.providerLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.provider.ProviderBalance
import com.qianyan.provider.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次余额查询的页面内快照（loading/结果/错误/完成时刻）。 */
private data class BalanceSnapshot(
    val loading: Boolean = false,
    val balance: ProviderBalance? = null,
    val error: String? = null,
    val at: Long? = null,
)

/**
 * 页内余额缓存（进程内）：同一 Provider 60 秒内复用，切页 / 重进不重复请求；
 * 手动「刷新余额」绕过缓存。
 */
private val balanceCache = mutableStateMapOf<ProviderType, BalanceSnapshot>()

private const val BALANCE_CACHE_MILLIS = 60_000L

/**
 * 设置 › 用量与余额（A-14）。
 *
 * 约束：
 *  - 余额只读厂商接口，只展示币种与金额，不做价格估算；MOCK 不查询；
 *  - 本节使用页内 loading，不占用全局 busy（不阻塞创作区任务）；
 *  - 用量来自本地账本 `usage.json`，不写数据库。
 */
@Composable
fun UsageSettings(state: DesktopAppState) {
    BalanceSection(state)
    UsageTableSection(state)
    LedgerSection(state)
}

/* ---------------- 余额 ---------------- */

@Composable
private fun BalanceSection(state: DesktopAppState) {
    val current by state.graph.provider.collectAsState()

    fun refresh(type: ProviderType, force: Boolean) {
        val snapshot = balanceCache[type]
        val now = System.currentTimeMillis()
        if (snapshot?.loading == true) return
        val fresh = snapshot?.at != null && now - snapshot.at!! < BALANCE_CACHE_MILLIS
        if (!force && fresh) return
        // 未配置 / MOCK 不构造查询器（不触网）
        val query = state.graph.balanceQuery(type) ?: return
        balanceCache[type] = (snapshot ?: BalanceSnapshot()).copy(loading = true, error = null)
        state.scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { query.queryBalance() } }
            val at = System.currentTimeMillis()
            balanceCache[type] = outcome.fold(
                onSuccess = { BalanceSnapshot(loading = false, balance = it, at = at) },
                onFailure = { BalanceSnapshot(loading = false, error = balanceErrorText(it), at = at) },
            )
        }
    }

    SettingsSection("余额", "余额直接读自厂商接口；只展示币种与金额，不做价格估算。") {
        ProviderType.entries.forEachIndexed { index, type ->
            if (index > 0) HLine(Modifier.padding(start = 16.dp))
            val configured = state.graph.isConfigured(type)
            LaunchedEffect(type, configured) {
                if (type != ProviderType.MOCK && configured) refresh(type, force = false)
            }
            BalanceRow(
                type = type,
                configured = configured,
                isCurrent = type == current,
                snapshot = balanceCache[type],
                onRefresh = { refresh(type, force = true) },
                onConfigure = { state.openSettingsSection(SETTINGS_PROVIDER_SECTION, type) },
            )
        }
    }
}

@Composable
private fun BalanceRow(
    type: ProviderType,
    configured: Boolean,
    isCurrent: Boolean,
    snapshot: BalanceSnapshot?,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    providerLabel(type),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = palette.text,
                )
                if (isCurrent) {
                    Spacer(Modifier.width(6.dp))
                    Text("当前", style = MaterialTheme.typography.labelSmall, color = palette.accent)
                }
            }
            Spacer(Modifier.height(3.dp))
            when {
                type == ProviderType.MOCK ->
                    Text("离线示意不查询余额", style = MaterialTheme.typography.bodySmall, color = palette.text3)

                !configured ->
                    Text("未配置 API Key", style = MaterialTheme.typography.bodySmall, color = palette.text3)

                snapshot?.loading == true ->
                    Text("查询中…", style = MaterialTheme.typography.bodySmall, color = palette.text3)

                snapshot?.error != null ->
                    Text(snapshot.error!!, style = MaterialTheme.typography.bodySmall, color = palette.del)

                snapshot?.balance != null -> {
                    val balance = snapshot.balance!!
                    if (!balance.available) {
                        Text(
                            "厂商返回：账户当前不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.del,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    if (balance.lines.isEmpty()) {
                        Text("厂商未返回币种余额", style = MaterialTheme.typography.bodySmall, color = palette.text3)
                    } else {
                        balance.lines.forEach { line ->
                            Text(formatBalanceLine(line), style = MaterialTheme.typography.bodyMedium, color = palette.text)
                        }
                    }
                    snapshot.at?.let {
                        Text(
                            "上次刷新 ${formatRefreshTime(it)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                        )
                    }
                }

                else ->
                    Text("尚未查询", style = MaterialTheme.typography.bodySmall, color = palette.text3)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            type == ProviderType.MOCK -> Unit
            !configured -> ActionButton("配置 ›", ActionLevel.TEXT, true) { onConfigure() }
            else -> ActionButton("刷新余额", ActionLevel.TEXT, snapshot?.loading != true) { onRefresh() }
        }
    }
}

/* ---------------- 用量表 ---------------- */

@Composable
private fun UsageTableSection(state: DesktopAppState) {
    var rangeIndex by remember { mutableStateOf(0) }
    val days = when (rangeIndex) {
        0 -> 7
        1 -> 30
        else -> null
    }
    // 聊天结束后 refreshKey 递增：这里跟着重算，不用轮询
    val refreshKey = state.refreshKey
    val rows = remember(days, refreshKey) { aggregateUsage(state.graph.usageLedger.summary(days)) }

    SettingsSection("用量", "按 厂商 × 模型 统计成功调用与 token（本地账本，不含金额）。") {
        SettingsRow("统计范围", "调用 · 输入 / 输出 / 合计") {
            SettingsSegmented(listOf("7 天", "30 天", "全部"), rangeIndex) { rangeIndex = it }
        }
        if (rows.isEmpty()) {
            SettingsNote("还没有记录：真实模型调用完成后才会计数", divider = true)
        } else {
            rows.forEach { row -> UsageTableRow(row) }
        }
    }
}

@Composable
private fun UsageTableRow(row: UsageRow) {
    val palette = QianyanTheme.palette
    HLine(Modifier.padding(start = 16.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.modelId, style = MaterialTheme.typography.bodyMedium, color = palette.text, maxLines = 1)
            Text(
                buildString {
                    append(providerLabel(row.provider))
                    if (row.provider == ProviderType.MOCK) append(" · 离线示意·不计费")
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("${row.calls} 次", style = MaterialTheme.typography.labelMedium, color = palette.text2)
        Spacer(Modifier.width(12.dp))
        Text(
            "${formatTokens(row.prompt)} / ${formatTokens(row.completion)} / ${formatTokens(row.total)}",
            style = QianyanCodeStyle,
            color = palette.text2,
            maxLines = 1,
        )
    }
}

/* ---------------- 本地账本维护 ---------------- */

@Composable
private fun LedgerSection(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ledger = state.graph.usageLedger
    var confirming by remember { mutableStateOf(false) }
    val refreshKey = state.refreshKey
    val count = remember(refreshKey) { ledger.recordCount() }

    SettingsSection("本地账本", "用量只追加在本机文件里，不进数据库；保留最近 400 天。") {
        SettingsInfoRow("文件", ledger.path.toString())
        SettingsInfoRow("记录数", count.toString(), divider = true)
        ledger.corruptNotice()?.let { SettingsNote(it, divider = true) }
        HLine(Modifier.padding(start = 16.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (confirming) {
                Text(
                    "确认清空本地统计？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
                ActionButton("取消", ActionLevel.TEXT, true) { confirming = false }
                Spacer(Modifier.width(6.dp))
                ActionButton("确认清空", ActionLevel.PRIMARY, true) {
                    ledger.clear()
                    confirming = false
                    state.refreshKey++
                }
            } else {
                Spacer(Modifier.weight(1f))
                ActionButton("清空本地统计", ActionLevel.TEXT, count > 0) { confirming = true }
            }
        }
    }
}
