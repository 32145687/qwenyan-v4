package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // MOCK（离线示意）不渲染余额卡（2026-11-16 用户裁决）；不触网约束仍在 graph 接缝层
            ProviderType.entries.filter { it != ProviderType.MOCK }.forEach { type ->
                val configured = state.graph.isConfigured(type)
                LaunchedEffect(type, configured) {
                    if (type != ProviderType.MOCK && configured) refresh(type, force = false)
                }
                BalanceCard(
                    type = type,
                    configured = configured,
                    isCurrent = type == current,
                    snapshot = balanceCache[type],
                    onRefresh = { refresh(type, force = true) },
                    onConfigure = { state.openSettingsSection(SETTINGS_PROVIDER_SECTION, type) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** 余额卡：厂商名 + 刷新（右上）→ 大字余额 → 现金/赠送分解 → 刷新时间；等高并排。 */
@Composable
private fun BalanceCard(
    type: ProviderType,
    configured: Boolean,
    isCurrent: Boolean,
    snapshot: BalanceSnapshot?,
    onRefresh: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = QianyanTheme.palette
    Column(
        modifier
            .background(palette.soft, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                providerLabel(type),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isCurrent) {
                Spacer(Modifier.width(6.dp))
                Text("当前", style = MaterialTheme.typography.labelSmall, color = palette.accent)
            }
            Spacer(Modifier.weight(1f))
            when {
                !configured -> ActionButton("配置 ›", ActionLevel.TEXT, true) { onConfigure() }
                else -> ActionButton("刷新", ActionLevel.TEXT, snapshot?.loading != true) { onRefresh() }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
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
                    Spacer(Modifier.height(4.dp))
                }
                if (balance.lines.isEmpty()) {
                    Text("厂商未返回币种余额", style = MaterialTheme.typography.bodySmall, color = palette.text3)
                } else {
                    val first = balance.lines.first()
                    Text(
                        "${first.currency} ${first.total}",
                        style = QianyanCodeStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.text,
                        maxLines = 1,
                    )
                    val parts = buildList {
                        first.cash?.let { add("现金 $it") }
                        first.granted?.let { add("赠送 $it") }
                    }
                    if (parts.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                            maxLines = 1,
                        )
                    }
                    balance.lines.drop(1).forEach { extra ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            formatBalanceLine(extra),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text2,
                            maxLines = 1,
                        )
                    }
                }
                snapshot.at?.let {
                    Spacer(Modifier.height(6.dp))
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
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(palette.soft, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("文件", style = MaterialTheme.typography.labelMedium, color = palette.text3)
                Spacer(Modifier.width(12.dp))
                Text(
                    ledger.path.toString(),
                    style = QianyanCodeStyle.copy(fontSize = 11.sp),
                    color = palette.text2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            HLine(Modifier.padding(vertical = 2.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("记录数", style = MaterialTheme.typography.labelMedium, color = palette.text3)
                Spacer(Modifier.weight(1f))
                Text(
                    count.toString(),
                    style = QianyanCodeStyle,
                    color = palette.text,
                )
            }
        }
        ledger.corruptNotice()?.let { SettingsNote(it, divider = true) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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

/* ---------------- 用量看板（U-01 / U-02 按模型视图） ---------------- */

/** 图表模型配色（概览卡着色用）。 */
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

private const val CHART_LEFT = 40f
private const val CHART_RIGHT = 8f
private const val CHART_BOTTOM = 22f

/** Tokens 三段配色：命中缓存（最浅）/ 未命中（中）/ 输出（最深），对齐厂商用量页。 */
private val tokenHitColor = Color(0xFFA9D3FF)
private val tokenMissColor = Color(0xFF3D95F7)
private val tokenOutColor = Color(0xFF1668DC)

/** 轴标签紧凑格式（k/M 缩写，避免长数字顶出画布）。 */
private fun formatAxisNumber(v: Double): String = when {
    v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
    v >= 1_000 -> "%.1fk".format(v / 1_000)
    else -> v.toInt().toString()
}

private fun xLabel(date: LocalDate): String = "${date.monthValue}/${date.dayOfMonth}"

/**
 * 用量看板（U-01/U-02）：本月/30 天/全部 三档范围 + 概览三卡 + 按模型分组两卡
 * （左：API 请求次数平滑面积图；右：Tokens 命中缓存/未命中/输出 三段堆叠柱 + 点击浮层明细）。
 * 金额一律是本地单价估算，看板内反复标注「估算，非厂商账单」。
 */
@Composable
private fun UsageDashboardSection(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var rangeIndex by remember { mutableStateOf(0) }
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
    // 模型按合计 token 倒序（用量大的在前，对齐厂商用量页习惯）
    val models = remember(byModel) {
        byModel.groupBy { it.modelId }
            .map { (id, rows) -> id to rows.sumOf { it.total } }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    SettingsSection("用量看板", "按 模型 × 日 统计；金额按厂商公开单价计（峰谷时段，节假日按空闲）。") {
        SettingsSegmented(listOf("本月", "30 天", "全部"), rangeIndex) { rangeIndex = it }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val costText = if (totals.costKnown) "¥%.2f".format(totals.costCny) else "¥%.2f+".format(totals.costCny)
            UsageStatCard("消费金额（计价）", costText, palette.accent, Modifier.weight(1f))
            UsageStatCard("API 请求次数", formatTokens(totals.calls.toLong()) + " 次", palette.add, Modifier.weight(1f))
            UsageStatCard("Tokens", formatTokens(totals.total), chartModelColors[3], Modifier.weight(1f))
        }
        if (!totals.costKnown) {
            SettingsNote("部分模型未列入单价表，其用量未计入金额（实际消费高于估算）。", divider = true)
        }
        if (models.isEmpty()) {
            SettingsNote("还没有记录：真实模型调用完成后才会显示图表")
        } else {
            models.forEach { model ->
                Spacer(Modifier.height(14.dp))
                ModelDashboardBlock(
                    modelId = model,
                    rows = byModel.filter { it.modelId == model },
                    from = from,
                    to = today,
                )
            }
        }
    }
}

/** 单模型一节：模型名标题 + 两卡并排（请求面积图 / Tokens 三段堆叠柱）。 */
@Composable
private fun ModelDashboardBlock(modelId: String, rows: List<DailyModelUsage>, from: LocalDate?, to: LocalDate) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            modelId,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CallsAreaCard(modelDailySeries(rows, from, to), Modifier.weight(1f))
            TokensStackCard(modelDailySeries(rows, from, to), Modifier.weight(1f))
        }
    }
}

@Composable
private fun UsageStatCard(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val palette = QianyanTheme.palette
    Column(
        modifier
            .background(palette.soft, RoundedCornerShape(14.dp))
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

/** 左卡：API 请求次数平滑面积图（cubic 平滑 + 渐变填充 + 网格线 + 点击柱日出虚线引导/白点/浮层）。 */
@Composable
private fun CallsAreaCard(series: List<DailyPoint>, modifier: Modifier = Modifier) {
    val palette = QianyanTheme.palette
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = palette.text3)
    val tipStyle = TextStyle(fontSize = 10.sp, color = palette.text)
    var selectedIdx by remember(series) { mutableStateOf<Int?>(null) }
    val total = series.sumOf { it.calls }

    Column(
        modifier
            .background(palette.soft, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "API 请求次数",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatTokens(total.toLong()),
                style = QianyanCodeStyle,
                color = palette.text2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(series) {
                    detectTapGestures { offset ->
                        val n = series.size
                        if (n == 0) return@detectTapGestures
                        val step = (size.width - CHART_LEFT - CHART_RIGHT) / (n - 1).coerceAtLeast(1)
                        val idx = ((offset.x - CHART_LEFT) / step + 0.5f).toInt()
                        selectedIdx = if (idx in 0 until n) idx else null
                    }
                },
        ) {
            val chartH = size.height - CHART_BOTTOM
            val plotW = size.width - CHART_LEFT - CHART_RIGHT
            if (series.isEmpty()) return@Canvas
            val step = plotW / (series.size - 1).coerceAtLeast(1)
            val maxV = series.maxOf { it.calls }.coerceAtLeast(1).toDouble()

            for (g in 0..2) {
                val y = chartH - (g / 2f) * (chartH - 6f)
                drawLine(palette.line.copy(alpha = 0.5f), Offset(CHART_LEFT, y), Offset(CHART_LEFT + plotW, y), 1f)
                drawText(textMeasurer, formatAxisNumber(maxV * (g / 2f)), Offset(2f, (y - 6f).coerceAtLeast(0f)), labelStyle)
            }
            drawLine(palette.lineStrong, Offset(CHART_LEFT, chartH), Offset(CHART_LEFT + plotW, chartH), 1f)

            fun px(i: Int) = CHART_LEFT + i * step
            fun py(calls: Int) = chartH - (calls / maxV * (chartH - 6f)).toFloat()

            val line = Path().apply {
                moveTo(px(0), py(series[0].calls))
                for (i in 1 until series.size) {
                    val midX = (px(i - 1) + px(i)) / 2f
                    cubicTo(midX, py(series[i - 1].calls), midX, py(series[i].calls), px(i), py(series[i].calls))
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(px(series.size - 1), chartH)
                lineTo(px(0), chartH)
                close()
            }
            drawPath(
                area,
                brush = Brush.verticalGradient(
                    colors = listOf(palette.accent.copy(alpha = 0.30f), palette.accent.copy(alpha = 0.02f)),
                    startY = 0f,
                    endY = chartH,
                ),
            )
            drawPath(line, color = palette.accent, style = Stroke(width = 2.dp.toPx()))

            drawText(textMeasurer, xLabel(series.first().date), Offset(CHART_LEFT, chartH + 6f), labelStyle)
            if (series.size > 1) {
                val lastText = xLabel(series.last().date)
                val w = textMeasurer.measure(lastText, labelStyle).size.width
                drawText(
                    textMeasurer,
                    lastText,
                    Offset((CHART_LEFT + plotW - w).coerceAtLeast(CHART_LEFT), chartH + 6f),
                    labelStyle,
                )
            }

            // 选中日：虚线引导（曲线点 → 底轴）+ 曲线上的白点 + 浮层（对齐厂商用量页）
            selectedIdx?.let { idx ->
                val p = series[idx]
                val dotX = px(idx)
                val dotY = py(p.calls)
                drawLine(
                    brush = SolidColor(palette.text3),
                    start = Offset(dotX, dotY),
                    end = Offset(dotX, chartH),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
                drawCircle(Color.White, radius = 4.dp.toPx(), center = Offset(dotX, dotY))
                drawCircle(
                    palette.accent,
                    radius = 4.dp.toPx(),
                    center = Offset(dotX, dotY),
                    style = Stroke(width = 2.dp.toPx()),
                )
                val tipW = 150.dp.toPx()
                val tipH = 60.dp.toPx()
                val tipX = (dotX + 14.dp.toPx()).coerceAtMost(size.width - CHART_RIGHT - tipW)
                    .coerceAtLeast(CHART_LEFT)
                val tipY = (dotY - tipH - 10.dp.toPx()).coerceAtLeast(2f)
                drawRoundRect(
                    color = palette.raised,
                    topLeft = Offset(tipX, tipY),
                    size = Size(tipW, tipH),
                    cornerRadius = CornerRadius(9.dp.toPx()),
                )
                var ly = tipY + 9.dp.toPx()
                drawText(textMeasurer, xLabel(p.date), Offset(tipX + 12.dp.toPx(), ly), tipStyle)
                val vText = p.calls.toString()
                val vw = textMeasurer.measure(vText, tipStyle).size.width
                drawText(textMeasurer, vText, Offset(tipX + tipW - 12.dp.toPx() - vw, ly), tipStyle)
                ly += 19.dp.toPx()
                drawText(
                    textMeasurer,
                    "请求次数",
                    Offset(tipX + 12.dp.toPx(), ly),
                    tipStyle.copy(color = palette.text2),
                )
                val vw2 = textMeasurer.measure(vText, tipStyle).size.width
                drawText(textMeasurer, vText, Offset(tipX + tipW - 12.dp.toPx() - vw2, ly), tipStyle)
            }
        }
    }
}

/** 右卡：Tokens 三段堆叠柱（命中缓存/未命中/输出）+ 点击柱条浮层明细（对齐厂商用量页）。 */
@Composable
private fun TokensStackCard(series: List<DailyPoint>, modifier: Modifier = Modifier) {
    val palette = QianyanTheme.palette
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 9.sp, color = palette.text3)
    val tipStyle = TextStyle(fontSize = 10.sp, color = palette.text)
    var selectedIdx by remember(series) { mutableStateOf<Int?>(null) }
    val total = series.sumOf { it.total }

    Column(
        modifier
            .background(palette.soft, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Tokens",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatTokens(total.toLong()),
                style = QianyanCodeStyle,
                color = palette.text2,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(series) {
                    detectTapGestures { offset ->
                        val n = series.size
                        val slot = (size.width - CHART_LEFT - CHART_RIGHT) / n
                        val idx = ((offset.x - CHART_LEFT) / slot).toInt()
                        selectedIdx = if (idx in 0 until n) idx else null
                    }
                },
        ) {
            val n = series.size
            if (n == 0) return@Canvas
            val slot = (size.width - CHART_LEFT - CHART_RIGHT) / n
            val chartH = size.height - CHART_BOTTOM
            val plotW = size.width - CHART_LEFT - CHART_RIGHT
            val maxV = series.maxOf { it.total }.coerceAtLeast(1).toDouble()

            for (g in 0..2) {
                val y = chartH - (g / 2f) * (chartH - 6f)
                drawLine(palette.line.copy(alpha = 0.5f), Offset(CHART_LEFT, y), Offset(CHART_LEFT + plotW, y), 1f)
                drawText(textMeasurer, formatAxisNumber(maxV * (g / 2f)), Offset(2f, (y - 6f).coerceAtLeast(0f)), labelStyle)
            }
            drawLine(palette.lineStrong, Offset(CHART_LEFT, chartH), Offset(CHART_LEFT + plotW, chartH), 1f)

            series.forEachIndexed { i, p ->
                val barW = (slot * 0.55f).coerceIn(2f, 16.dp.toPx())
                val x0 = CHART_LEFT + i * slot + (slot - barW) / 2f
                // 段与段方角直连（内部不许有圆角缺口），只给整根柱顶/底留圆角
                val segs = buildList {
                    if (p.cacheHit > 0) add(tokenHitColor to p.cacheHit)
                    val miss = p.prompt - p.cacheHit
                    if (miss > 0) add(tokenMissColor to miss)
                    if (p.completion > 0) add(tokenOutColor to p.completion)
                }
                var y = chartH
                segs.forEachIndexed { si, (color, v) ->
                    val h = (v / maxV * (chartH - 6f)).toFloat().coerceAtLeast(1.5f)
                    y -= h
                    val r = minOf(2.5.dp.toPx(), h / 2f, barW / 2f)
                    val roundRect = RoundRect(
                        rect = Rect(Offset(x0, y), Size(barW, h)),
                        topLeft = CornerRadius(if (si == segs.lastIndex) r else 0f),
                        topRight = CornerRadius(if (si == segs.lastIndex) r else 0f),
                        bottomRight = CornerRadius(if (si == 0) r else 0f),
                        bottomLeft = CornerRadius(if (si == 0) r else 0f),
                    )
                    drawPath(Path().apply { addRoundRect(roundRect) }, color)
                }
            }

            drawText(textMeasurer, xLabel(series.first().date), Offset(CHART_LEFT, chartH + 6f), labelStyle)
            if (n > 1) {
                val lastText = xLabel(series.last().date)
                val w = textMeasurer.measure(lastText, labelStyle).size.width
                drawText(
                    textMeasurer,
                    lastText,
                    Offset((CHART_LEFT + plotW - w).coerceAtLeast(CHART_LEFT), chartH + 6f),
                    labelStyle,
                )
            }

            // 浮层明细：虚线引导 + 日期 + 合计 + 命中缓存/未命中/输出 三行（点击同柱再点取消）
            selectedIdx?.let { idx ->
                val p = series[idx]
                val barCenterX = CHART_LEFT + idx * slot + slot / 2f
                drawLine(
                    brush = SolidColor(palette.text3),
                    start = Offset(barCenterX, 2f),
                    end = Offset(barCenterX, chartH),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                )
                val tipW = 196.dp.toPx()
                val tipH = 88.dp.toPx()
                val tipX = (CHART_LEFT + idx * slot + slot / 2f - tipW / 2f)
                    .coerceIn(CHART_LEFT, (size.width - CHART_RIGHT - tipW).coerceAtLeast(CHART_LEFT))
                val tipY = 2f
                drawRoundRect(
                    color = palette.raised,
                    topLeft = Offset(tipX, tipY),
                    size = Size(tipW, tipH),
                    cornerRadius = CornerRadius(9.dp.toPx()),
                )
                var ly = tipY + 8.dp.toPx()
                drawText(textMeasurer, xLabel(p.date), Offset(tipX + 10.dp.toPx(), ly), tipStyle)
                val totalText = formatTokens(p.total)
                val tw = textMeasurer.measure(totalText, tipStyle).size.width
                drawText(textMeasurer, totalText, Offset(tipX + tipW - 10.dp.toPx() - tw, ly), tipStyle)
                ly += 18.dp.toPx()
                listOf(
                    Triple(tokenHitColor, "输入（命中缓存）", p.cacheHit),
                    Triple(tokenMissColor, "输入（未命中缓存）", p.prompt - p.cacheHit),
                    Triple(tokenOutColor, "输出", p.completion),
                ).forEach { (color, label, v) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(tipX + 10.dp.toPx(), ly + 3.dp.toPx()),
                        size = Size(8.dp.toPx(), 8.dp.toPx()),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    drawText(
                        textMeasurer,
                        label,
                        Offset(tipX + 24.dp.toPx(), ly),
                        tipStyle.copy(color = palette.text2),
                    )
                    val vm = textMeasurer.measure(formatTokens(v), tipStyle).size.width
                    drawText(textMeasurer, formatTokens(v), Offset(tipX + tipW - 10.dp.toPx() - vm, ly), tipStyle)
                    ly += 17.dp.toPx()
                }
            }
        }
    }
}
