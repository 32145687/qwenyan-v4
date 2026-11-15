package com.qianyan.app.desktop

import com.qianyan.app.desktop.di.UsageBucket
import com.qianyan.app.desktop.ui.settings.ModelPricing
import com.qianyan.app.desktop.ui.settings.aggregateDaily
import com.qianyan.app.desktop.ui.settings.aggregateDailyByModel
import com.qianyan.app.desktop.ui.settings.modelDailySeries
import com.qianyan.app.desktop.ui.settings.monthStart
import com.qianyan.app.desktop.ui.settings.summarize
import com.qianyan.provider.ProviderType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U-01 · 用量看板纯函数测试：按日聚合 / 月过滤 / 金额估算 / 未知模型不计金额 / 汇总求和。
 *
 * 金额一律是本地估算（单价表占位值），测试只验证机制（公式与边界），不锁定占位单价数值。
 */
class UsageDashboardMathTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 16)

    private fun bucket(
        date: LocalDate,
        model: String,
        calls: Int = 1,
        prompt: Long = 1_000L,
        completion: Long = 500L,
    ) = UsageBucket(
        date = date,
        provider = ProviderType.DEEPSEEK,
        modelId = model,
        calls = calls,
        prompt = prompt,
        completion = completion,
        total = prompt + completion,
    )

    /* ---------- 单价表 ---------- */

    @Test
    fun `cost uses input and output unit prices`() {
        val price = ModelPricing.priceOf("deepseek-flash")!!
        val cost = ModelPricing.costCny("deepseek-flash", promptTokens = 1_000_000, completionTokens = 1_000_000)!!
        assertEquals(price.inputPerMillion + price.outputPerMillion, cost, 1e-9)
    }

    @Test
    fun `unknown model has no price and no fabricated cost`() {
        assertNull(ModelPricing.priceOf("no-such-model"), "未列入单价表的模型不得编造价格")
        assertNull(ModelPricing.costCny("no-such-model", 1_000, 1_000))
        assertNull(ModelPricing.costCny("unknown", 1_000, 1_000), "MOCK/unknown 兜底名同样不计金额")
    }

    /* ---------- 按日 × 模型聚合 ---------- */

    @Test
    fun `daily by model merges same-day buckets and sorts ascending`() {
        val buckets = listOf(
            bucket(today, "deepseek-flash", calls = 2),
            bucket(today, "deepseek-flash", calls = 3),
            bucket(today.minusDays(1), "deepseek-v4-pro"),
            bucket(today, "deepseek-v4-pro"),
        )
        val byModel = aggregateDailyByModel(buckets, from = null, to = today)
        assertEquals(listOf(today.minusDays(1), today, today), byModel.map { it.date }, "日期升序")
        assertEquals(5, byModel.first { it.date == today && it.modelId == "deepseek-flash" }.calls, "同日同模型合并")
        assertEquals(2, byModel.count { it.date == today }, "同日两模型两行")
    }

    @Test
    fun `range filter keeps inclusive bounds`() {
        val first = monthStart(today)
        val buckets = listOf(
            bucket(first, "deepseek-flash"),
            bucket(first.minusDays(1), "deepseek-flash"), // 上月，应被过滤
            bucket(today, "deepseek-flash"),
        )
        val byModel = aggregateDailyByModel(buckets, from = first, to = today)
        assertEquals(setOf(first, today), byModel.map { it.date }.toSet(), "上月 1 号前必须被排除；两端含")
    }

    /* ---------- 金额 ---------- */

    @Test
    fun `daily cost sums known models and flags unknown ones`() {
        val day = today
        val byModel = aggregateDailyByModel(
            listOf(
                bucket(day, "deepseek-flash", prompt = 1_000_000, completion = 0),
                bucket(day, "no-such-model", prompt = 1_000_000, completion = 0),
            ),
            from = null,
            to = today,
        )
        val flash = byModel.first { it.modelId == "deepseek-flash" }
        val unknown = byModel.first { it.modelId == "no-such-model" }
        assertEquals(
            ModelPricing.priceOf("deepseek-flash")!!.inputPerMillion,
            flash.costCny!!,
            1e-9,
        )
        assertNull(unknown.costCny, "未知模型不计金额")

        val daily = aggregateDaily(byModel)
        assertEquals(1, daily.size)
        assertEquals(
            ModelPricing.priceOf("deepseek-flash")!!.inputPerMillion,
            daily[0].costCny,
            1e-9,
            "日金额 = 已知模型之和，未知模型不计入",
        )
        assertFalse(daily[0].costKnown, "存在未知模型 ⇒ costKnown=false（金额为低估）")
    }

    @Test
    fun `all known models keep costKnown true`() {
        val byModel = aggregateDailyByModel(
            listOf(bucket(today, "deepseek-flash"), bucket(today, "deepseek-v4-pro")),
            from = null,
            to = today,
        )
        assertTrue(aggregateDaily(byModel).single().costKnown)
    }

    /* ---------- 汇总 ---------- */

    @Test
    fun `summarize totals across days`() {
        val daily = aggregateDaily(
            aggregateDailyByModel(
                listOf(
                    bucket(today, "deepseek-flash", calls = 2, prompt = 1_000_000, completion = 500_000),
                    bucket(today.minusDays(1), "deepseek-flash", calls = 1, prompt = 0, completion = 1_000_000),
                ),
                from = null,
                to = today,
            ),
        )
        val totals = summarize(daily)
        assertEquals(3, totals.calls)
        assertEquals(1_000_000, totals.prompt)
        assertEquals(1_500_000, totals.completion)
        assertEquals(2_500_000, totals.total)
        val perMillion = ModelPricing.priceOf("deepseek-flash")!!
        val expected = 1.0 * perMillion.inputPerMillion + 0.5 * perMillion.outputPerMillion +
            0.0 * perMillion.inputPerMillion + 1.0 * perMillion.outputPerMillion
        assertEquals(expected, totals.costCny, 1e-9, "月汇总金额 = 各日估算之和")
        assertTrue(totals.costKnown)
    }

    @Test
    fun `empty ledger produces empty series and zero totals`() {
        assertTrue(aggregateDailyByModel(emptyList(), from = null, to = today).isEmpty())
        val totals = summarize(aggregateDaily(emptyList()))
        assertEquals(0, totals.calls)
        assertEquals(0L, totals.total)
        assertEquals(0.0, totals.costCny, 1e-9)
        assertTrue(totals.costKnown, "空账本视为已知（不显示低估提示）")
    }

    /* ---------- U-02 按模型序列 ---------- */

    @Test
    fun `model daily series zero fills missing days inclusively`() {
        val byModel = aggregateDailyByModel(
            listOf(
                bucket(LocalDate.of(2026, 11, 1), "deepseek-v4-flash", calls = 3),
                bucket(LocalDate.of(2026, 11, 3), "deepseek-v4-flash", calls = 1),
            ),
            from = null,
            to = LocalDate.of(2026, 11, 3),
        )
        val series = modelDailySeries(byModel, from = null, to = LocalDate.of(2026, 11, 3))
        assertEquals(3, series.size, "11/1..11/3 逐日连续（缺日零填充）")
        assertEquals(3, series[0].calls)
        assertEquals(0, series[1].calls, "11/2 缺日 = 0")
        assertEquals(1, series[2].calls)
    }

    @Test
    fun `model daily series carries cache hit and empty model gives empty series`() {
        val cacheBucket = UsageBucket(
            date = today,
            provider = ProviderType.DEEPSEEK,
            modelId = "deepseek-v4-flash",
            calls = 2,
            prompt = 100,
            completion = 50,
            total = 150,
            cacheHit = 40,
        )
        val byModel = aggregateDailyByModel(listOf(cacheBucket), from = null, to = today)
        assertEquals(40L, byModel.single().cacheHit, "cacheHit 逐桶汇总")

        val series = modelDailySeries(byModel, from = null, to = today)
        assertEquals(1, series.size)
        assertEquals(40L, series[0].cacheHit)
        assertEquals(60L, series[0].prompt - series[0].cacheHit, "未命中 = prompt - hit")

        assertTrue(modelDailySeries(emptyList(), from = null, to = today).isEmpty(), "空模型 → 空序列")
    }
}
