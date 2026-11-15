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
internal data class ModelUnitPrice(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
)

internal object ModelPricing {
    /**
     * 单价表（CNY / 百万 token，输入 / 输出分计）。
     *
     * ⚠ 占位数值：上线前须按各厂商价格页核对修改；只收模型 ID 精确匹配，
     * 未列入的模型（含 MOCK / unknown）不计金额。
     * 模型 ID 以本地账本实际记录为准（真机 2026-11-16 校对：deepseek-v4-flash /
     * mimo-v2.6-flash / mimo-v2.6-pro / mock-v1）。
     */
    private val table = mapOf(
        "deepseek-flash" to ModelUnitPrice(inputPerMillion = 0.5, outputPerMillion = 2.0),
        "deepseek-v4-flash" to ModelUnitPrice(inputPerMillion = 0.5, outputPerMillion = 2.0),
        "deepseek-v4-pro" to ModelUnitPrice(inputPerMillion = 4.0, outputPerMillion = 16.0),
        "mimo-v2.6-flash" to ModelUnitPrice(inputPerMillion = 1.0, outputPerMillion = 4.0),
        "mimo-v2.6-pro" to ModelUnitPrice(inputPerMillion = 2.0, outputPerMillion = 8.0),
    )

    internal fun priceOf(modelId: String): ModelUnitPrice? = table[modelId]

    /** 一次调用的估算金额（CNY）；未知模型返回 null（不计入）。 */
    internal fun costCny(modelId: String, promptTokens: Long, completionTokens: Long): Double? =
        priceOf(modelId)?.let { price ->
            promptTokens / 1_000_000.0 * price.inputPerMillion +
                completionTokens / 1_000_000.0 * price.outputPerMillion
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
            DailyModelUsage(
                date = key.first,
                modelId = key.second,
                calls = rows.sumOf { it.calls },
                prompt = prompt,
                completion = completion,
                total = rows.sumOf { it.total },
                costCny = ModelPricing.costCny(key.second, prompt, completion),
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
