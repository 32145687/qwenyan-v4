package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.providerLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.provider.ProviderBalance
import com.qianyan.provider.ProviderType
import java.time.LocalDate
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
    UsageDashboardSection(state)
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
            if (index > 0) Spacer(Modifier.height(10.dp))
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
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(palette.bg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
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

/* ---------------- 用量看板（U-01） ---------------- */

/** 看板指标下标：0 金额（估算）/ 1 请求次数 / 2 Tokens。 */
private const val METRIC_COST = 0
private const val METRIC_CALLS = 1
private const val METRIC_TOKENS = 2

/** 图表模型配色（固定序列；按模型名排序取色，超出后循环）。 */
private val chartModelColors = listOf(
    Color(0xFFFF9500),
    Color(0xFF0A84FF),
    Color(0xFF30D158),
    Color(0xFFBF5AF2),
    Color(0xFF64D2FF),
    Color(0xFFFFD60A),
    Color(0xFFFF375F),
    Color(0xFFAC8E68),
)

private const val CHART_LEFT = 46f
private const val CHART_RIGHT = 8f
private const val CHART_BOTTOM = 22f

private fun metricValueOfDay(row: DailyUsage, metric: Int): Double = when (metric) {
    METRIC_COST -> row.costCny
    METRIC_CALLS -> row.calls.toDouble()
    else -> row.total.toDouble()
}

private fun metricValueOfModel(row: DailyModelUsage, metric: Int): Double = when (metric) {
    METRIC_COST -> row.costCny ?: 0.0
    METRIC_CALLS -> row.calls.toDouble()
    else -> row.total.toDouble()
}

/**
 * 用量看板（U-01）：本月/30 天/全部 三档范围 + 概览三卡 + 按日柱状图（按模型分色堆叠）
 * + 点选单日明细。金额一律是本地单价估算，看板内反复标注「估算，非厂商账单」。
 */
@Composable
private fun UsageDashboardSection(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var rangeIndex by remember { mutableStateOf(0) }
    var metricIndex by remember { mutableStateOf(METRIC_COST) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val refreshKey = state.refreshKey

    val today = remember(refreshKey) { LocalDate.now() }
    val summaryDays = when (rangeIndex) {
        0 -> today.dayOfMonth
        1 -> 30
        else -> null
    }
    val from = when (rangeIndex) {
        0 -> monthStart(today)
        1 -> today.minusDays(29)
        else -> null
    }
    val buckets = remember(rangeIndex, refreshKey) {
        state.graph.usageLedger.summary(summaryDays)
    }
    val byModel = remember(buckets, from, today) { aggregateDailyByModel(buckets, from, today) }
    val daily = remember(byModel) { aggregateDaily(byModel) }
    val totals = remember(daily) { summarize(daily) }
    val models = remember(byModel) { byModel.map { it.modelId }.distinct().sorted() }

    SettingsSection("用量看板", "按日统计本地调用；金额为单价估算，非厂商账单。") {
        SettingsSegmented(listOf("本月", "30 天", "全部"), rangeIndex) {
            rangeIndex = it
            selectedDate = null
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val costText = if (totals.costKnown) "¥%.2f".format(totals.costCny) else "¥%.2f+".format(totals.costCny)
            UsageStatCard("消费金额（估算）", costText, palette.accent, Modifier.weight(1f))
            UsageStatCard("API 请求次数", formatTokens(totals.calls.toLong()) + " 次", palette.add, Modifier.weight(1f))
            UsageStatCard("Tokens", formatTokens(totals.total), chartModelColors[3], Modifier.weight(1f))
        }
        if (!totals.costKnown) {
            SettingsNote("部分模型未列入单价表，其用量未计入金额（实际消费高于估算）。", divider = true)
        }
        HLine(Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "按日视图",
                style = MaterialTheme.typography.labelMedium,
                color = palette.text2,
                modifier = Modifier.weight(1f),
            )
            SettingsSegmented(listOf("金额", "请求", "Tokens"), metricIndex) {
                metricIndex = it
                selectedDate = null
            }
        }
        if (daily.isEmpty()) {
            SettingsNote("还没有记录：真实模型调用完成后才会显示图表")
        } else {
            UsageBarChart(
                daily = daily,
                byModel = byModel.groupBy { it.date },
                models = models,
                metric = metricIndex,
                selected = selectedDate,
                onSelect = { selectedDate = if (selectedDate == it) null else it },
            )
            if (models.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    models.forEachIndexed { index, model ->
                        Row(
                            Modifier
                                .background(palette.bg, RoundedCornerShape(999.dp))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(8.dp).background(
                                    chartModelColors[index % chartModelColors.size],
                                    RoundedCornerShape(999.dp),
                                ),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                model,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.text2,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            selectedDate?.let { date ->
                val rows = byModel.filter { it.date == date }
                HLine(Modifier.padding(start = 16.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${date.monthValue}/${date.dayOfMonth} 明细（点选柱条切换）",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.text2,
                    )
                    Spacer(Modifier.height(4.dp))
                    rows.forEach { row ->
                        val costText = row.costCny?.let { "¥%.2f".format(it) } ?: "—"
                        Text(
                            "${row.modelId} · ${row.calls} 次 · " +
                                "${formatTokens(row.prompt)} / ${formatTokens(row.completion)} / " +
                                "${formatTokens(row.total)} · $costText",
                            style = QianyanCodeStyle,
                            color = palette.text2,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageStatCard(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val palette = QianyanTheme.palette
    Column(
        modifier
            .background(palette.bg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(tint, RoundedCornerShape(999.dp)))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = palette.text3, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            style = QianyanCodeStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = palette.text,
            maxLines = 1,
        )
    }
}

/** 轴标签紧凑格式：金额保留两位；大数 k/M 缩写（避免长数字顶出画布）。 */
private fun formatAxisValue(v: Double, metric: Int): String = when {
    metric == METRIC_COST -> "%.2f".format(v)
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
    v >= 1_000 -> "%.1fk".format(v / 1_000)
    else -> v.toInt().toString()
}

/** 按日柱状图：X 轴按日、同日按模型分色堆叠；水平网格线 + 圆角柱 + 选中列描边；点选柱条回调日期（再点同日取消）。 */
@Composable
private fun UsageBarChart(
    daily: List<DailyUsage>,
    byModel: Map<LocalDate, List<DailyModelUsage>>,
    models: List<String>,
    metric: Int,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val palette = QianyanTheme.palette
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = palette.text3)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
            .padding(horizontal = 16.dp)
            .pointerInput(daily, metric) {
                detectTapGestures { offset ->
                    val n = daily.size
                    val slot = (size.width - CHART_LEFT - CHART_RIGHT) / n
                    val idx = ((offset.x - CHART_LEFT) / slot).toInt()
                    if (idx in 0 until n) onSelect(daily[idx].date) else onSelect(null)
                }
            },
    ) {
        val n = daily.size
        val slot = (size.width - CHART_LEFT - CHART_RIGHT) / n
        val chartH = size.height - CHART_BOTTOM
        val plotW = size.width - CHART_LEFT - CHART_RIGHT
        val maxV = daily.maxOf { metricValueOfDay(it, metric) }.coerceAtLeast(1.0)

        // 水平网格线（4 分格）+ 轴标签
        for (g in 0..4) {
            val frac = g / 4f
            val y = chartH - frac * (chartH - 8f)
            drawLine(
                palette.line.copy(alpha = 0.45f),
                Offset(CHART_LEFT, y),
                Offset(CHART_LEFT + plotW, y),
                1f,
            )
            if (g == 0 || g == 2 || g == 4) {
                val label = formatAxisValue(maxV * frac, metric)
                drawText(textMeasurer, label, Offset(2f, (y - 6f).coerceAtLeast(0f)), labelStyle)
            }
        }
        // 底轴线（比网格线略重）
        drawLine(palette.lineStrong, Offset(CHART_LEFT, chartH), Offset(CHART_LEFT + plotW, chartH), 1.5f)

        // 选中日的整列：淡填充 + 描边
        selected?.let { sel ->
            val idx = daily.indexOfFirst { it.date == sel }
            if (idx >= 0) {
                drawRect(
                    color = palette.accent.copy(alpha = 0.08f),
                    topLeft = Offset(CHART_LEFT + idx * slot, 0f),
                    size = Size(slot, chartH),
                )
                drawRect(
                    color = palette.accent.copy(alpha = 0.55f),
                    topLeft = Offset(CHART_LEFT + idx * slot, 0f),
                    size = Size(slot, chartH),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        // 堆叠柱（同日按模型固定顺序，颜色与图例一致；圆角柱）
        val radius = CornerRadius(3.dp.toPx())
        daily.forEachIndexed { i, day ->
            val barW = (slot * 0.6f).coerceIn(2f, 22.dp.toPx())
            val x0 = CHART_LEFT + i * slot + (slot - barW) / 2f
            var y = chartH
            val rows = (byModel[day.date] ?: emptyList())
                .sortedBy { models.indexOf(it.modelId) }
            rows.forEach { row ->
                val v = metricValueOfModel(row, metric)
                if (v > 0.0) {
                    val h = (v / maxV * (chartH - 8f)).toFloat().coerceAtLeast(2f)
                    y -= h
                    val colorIdx = models.indexOf(row.modelId).coerceAtLeast(0)
                    drawRoundRect(
                        color = chartModelColors[colorIdx % chartModelColors.size],
                        topLeft = Offset(x0, y),
                        size = Size(barW, h),
                        cornerRadius = radius,
                    )
                }
            }
        }

        // X 轴日期标签：首 / 中 / 末
        val xLabel: (LocalDate) -> String = { "${it.monthValue}/${it.dayOfMonth}" }
        drawText(textMeasurer, xLabel(daily.first().date), Offset(CHART_LEFT, chartH + 6f), labelStyle)
        if (n >= 3) {
            val midText = xLabel(daily[n / 2].date)
            val mw = textMeasurer.measure(midText, labelStyle).size.width
            drawText(
                textMeasurer,
                midText,
                Offset(CHART_LEFT + plotW / 2f - mw / 2f, chartH + 6f),
                labelStyle,
            )
        }
        if (n > 1) {
            val lastText = xLabel(daily.last().date)
            val w = textMeasurer.measure(lastText, labelStyle).size.width
            drawText(
                textMeasurer,
                lastText,
                Offset((CHART_LEFT + plotW - w).coerceAtLeast(CHART_LEFT), chartH + 6f),
                labelStyle,
            )
        }
    }
}
