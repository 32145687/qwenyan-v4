package com.qianyan.app.desktop.ui.settings

import com.qianyan.app.desktop.di.UsageBucket
import java.time.LocalDate

/**
 * U-01 · 用量看板的纯函数层：单价表 + 按日聚合 + 金额估算。
 *
 * 边界（沿用 A-14 精神并按用户新需求修订）：
 *  - 账本 schema 与记账路径不动；本层只读 [UsageBucket] 投影；
 *  - 金额一律是**本地单价估算**，不是厂商账单——UI 必须标注「估算」；
 *  - 未列入单价表的模型不计金额（costKnown=false），绝不编造价格。
 */
/** 三段单价（CNY / 百万 token）：命中缓存 / 未命中 / 输出。 */
internal data class TierUnitPrice(
    val cacheHit: Double,
    val inputMiss: Double,
    val output: Double,
)

/** 一档模型的峰/谷双价（无峰谷的厂商两档同价）。 */
internal data class TieredModelPrice(val peak: TierUnitPrice, val offPeak: TierUnitPrice)

internal object ModelPricing {
    /**
     * 单价表（CNY / 百万 token）——2026-11-16 用户提供厂商价格页真值：
     *
     * DeepSeek（峰谷双价；空闲=高峰一半。高峰=北京时间周一至五 9:00–12:00、14:00–18:00）：
     *   deepseek-flash：命中 0.02/0.04 · 未命中 1/2 · 输出 4/8（空闲/高峰）
     *   deepseek-v4-pro：命中 0.15/0.30 · 未命中 4.5/9.0 · 输出 13.5/27.0
     *   官方脚注 (1)：旧模型名 deepseek-v4-flash（含 vision-exp）仍可调用，按 Flash 价格计。
     * MiMo（无峰谷，双档同价；实时推理价，本应用不用批量推理）：
     *   mimo-v2.6-flash 0.02/1.00/2.00 · mimo-v2.6-pro 0.025/3.00/6.00 ·
     *   mimo-v2.6-pro-ultraspeed 0.25/30/60
     *
     * 只收模型 ID 精确匹配；未列入的模型（含 MOCK / unknown）不计金额。
     * 法定节假日按空闲计的日历未实现（局限如实标注）。
     */
    private val flash = TieredModelPrice(
        peak = TierUnitPrice(cacheHit = 0.04, inputMiss = 2.0, output = 8.0),
        offPeak = TierUnitPrice(cacheHit = 0.02, inputMiss = 1.0, output = 4.0),
    )
    private val pro = TieredModelPrice(
        peak = TierUnitPrice(cacheHit = 0.30, inputMiss = 9.0, output = 27.0),
        offPeak = TierUnitPrice(cacheHit = 0.15, inputMiss = 4.5, output = 13.5),
    )
    private val table = mapOf(
        "deepseek-flash" to flash,
        "deepseek-v4-flash" to flash, // 旧名按 Flash 价格计（官方脚注 (1)）
        "deepseek-v4-pro" to pro,
        "mimo-v2.6-flash" to TieredModelPrice(
            peak = TierUnitPrice(0.02, 1.0, 2.0),
            offPeak = TierUnitPrice(0.02, 1.0, 2.0),
        ),
        "mimo-v2.6-pro" to TieredModelPrice(
            peak = TierUnitPrice(0.025, 3.0, 6.0),
            offPeak = TierUnitPrice(0.025, 3.0, 6.0),
        ),
        "mimo-v2.6-pro-ultraspeed" to TieredModelPrice(
            peak = TierUnitPrice(0.25, 30.0, 60.0),
            offPeak = TierUnitPrice(0.25, 30.0, 60.0),
        ),
    )

    internal fun priceOf(modelId: String): TieredModelPrice? = table[modelId]

    /**
     * 一桶用量的计价（CNY）：峰/谷拆分分别按三段（命中/未命中/输出）计价后求和。
     * 未命中 = prompt − cacheHit。未知模型返回 null（不计金额）。
     */
    internal fun costCny(
        modelId: String,
        prompt: Long,
        completion: Long,
        cacheHit: Long,
        peakPrompt: Long,
        peakHit: Long,
        peakCompletion: Long,
    ): Double? {
        val price = table[modelId] ?: return null
        val miss = (prompt - cacheHit).coerceAtLeast(0)
        val peakMiss = (peakPrompt - peakHit).coerceAtLeast(0)
        val offMiss = (miss - peakMiss).coerceAtLeast(0)
        val offCompletion = (completion - peakCompletion).coerceAtLeast(0)

        fun tierCost(t: TierUnitPrice, hit: Long, missTokens: Long, out: Long): Double =
            hit / 1_000_000.0 * t.cacheHit +
                missTokens / 1_000_000.0 * t.inputMiss +
                out / 1_000_000.0 * t.output

        return tierCost(price.peak, peakHit, peakMiss, peakCompletion) +
            tierCost(price.offPeak, cacheHit - peakHit, offMiss, offCompletion)
    }
}

/** 单日 × 单模型的聚合（图表堆叠与单日明细的最小单元）。 */
internal data class DailyModelUsage(
    val date: LocalDate,
    val modelId: String,
    val calls: Int,
    val prompt: Long,
    val completion: Long,
    val total: Long,
    /** 输入中命中上下文缓存的 token 累计（U-02；厂商不返回为 0）。 */
    val cacheHit: Long,
    /** 估算金额；null = 该模型未列入单价表（不计金额）。 */
    val costCny: Double?,
)

/** 单日聚合（全模型求和）。 */
internal data class DailyUsage(
    val date: LocalDate,
    val calls: Int,
    val prompt: Long,
    val completion: Long,
    val total: Long,
    /** 已知模型金额之和。 */
    val costCny: Double,
    /** 当日是否所有模型都在单价表内（false → 金额为低估）。 */
    val costKnown: Boolean,
)

/** 看板顶部汇总（范围 = 全部选中日之和）。 */
internal data class UsageTotals(
    val calls: Int,
    val prompt: Long,
    val completion: Long,
    val total: Long,
    val costCny: Double,
    val costKnown: Boolean,
)

/**
 * 按日 × 模型聚合，日期升序（图表 X 轴从左到右）。
 *
 * [from] = null 表示不设下界；[to] 为包含上界。只做求和过滤，不补空日样本。
 */
internal fun aggregateDailyByModel(
    buckets: List<UsageBucket>,
    from: LocalDate?,
    to: LocalDate,
): List<DailyModelUsage> =
    buckets.asSequence()
        .filter { (from == null || !it.date.isBefore(from)) && !it.date.isAfter(to) }
        .groupBy { it.date to it.modelId }
        .map { (key, rows) ->
            val prompt = rows.sumOf { it.prompt }
            val completion = rows.sumOf { it.completion }
            val cacheHit = rows.sumOf { it.cacheHit }
            val peakPrompt = rows.sumOf { it.peakPrompt }
            val peakHit = rows.sumOf { it.peakHit }
            val peakCompletion = rows.sumOf { it.peakCompletion }
            DailyModelUsage(
                date = key.first,
                modelId = key.second,
                calls = rows.sumOf { it.calls },
                prompt = prompt,
                completion = completion,
                total = rows.sumOf { it.total },
                cacheHit = cacheHit,
                costCny = ModelPricing.costCny(
                    modelId = key.second,
                    prompt = prompt,
                    completion = completion,
                    cacheHit = cacheHit,
                    peakPrompt = peakPrompt,
                    peakHit = peakHit,
                    peakCompletion = peakCompletion,
                ),
            )
        }
        .sortedWith(compareBy({ it.date }, { it.modelId }))

/** 单日聚合（对 [aggregateDailyByModel] 结果按日求和），日期升序。 */
internal fun aggregateDaily(byModel: List<DailyModelUsage>): List<DailyUsage> =
    byModel.groupBy { it.date }
        .map { (date, rows) ->
            DailyUsage(
                date = date,
                calls = rows.sumOf { it.calls },
                prompt = rows.sumOf { it.prompt },
                completion = rows.sumOf { it.completion },
                total = rows.sumOf { it.total },
                costCny = rows.sumOf { it.costCny ?: 0.0 },
                costKnown = rows.all { it.costCny != null },
            )
        }
        .sortedBy { it.date }

/** 看板汇总 = 各日求和（金额浮点求和，展示层格式化到分）。 */
internal fun summarize(daily: List<DailyUsage>): UsageTotals =
    UsageTotals(
        calls = daily.sumOf { it.calls },
        prompt = daily.sumOf { it.prompt },
        completion = daily.sumOf { it.completion },
        total = daily.sumOf { it.total },
        costCny = daily.sumOf { it.costCny },
        costKnown = daily.all { it.costKnown },
    )

/** 「本月」下界：当月 1 号（含）。 */
internal fun monthStart(today: LocalDate): LocalDate = today.withDayOfMonth(1)

/** 单模型 × 单日数据点（U-02；缺日零填充——面积图需要连续横轴）。 */
internal data class DailyPoint(
    val date: LocalDate,
    val calls: Int,
    val prompt: Long,
    val completion: Long,
    val total: Long,
    val cacheHit: Long,
)

/**
 * 某模型在 [from, to] 内的按日序列；[from] = null 时从该模型最早有数据日开始，
 * 到 [to] 止逐日零填充（缺数据的日期 calls/tokens = 0）。
 */
internal fun modelDailySeries(
    rows: List<DailyModelUsage>,
    from: LocalDate?,
    to: LocalDate,
): List<DailyPoint> {
    if (rows.isEmpty()) return emptyList()
    val start = from ?: rows.minOf { it.date }
    val byDate = rows.groupBy { it.date }
    val out = mutableListOf<DailyPoint>()
    var day = start
    while (!day.isAfter(to)) {
        val rs = byDate[day].orEmpty()
        out += DailyPoint(
            date = day,
            calls = rs.sumOf { it.calls },
            prompt = rs.sumOf { it.prompt },
            completion = rs.sumOf { it.completion },
            total = rs.sumOf { it.total },
            cacheHit = rs.sumOf { it.cacheHit },
        )
        day = day.plusDays(1)
    }
    return out
}
