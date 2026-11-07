package com.qianyan.app.desktop

import com.qianyan.app.desktop.di.UsageBucket
import com.qianyan.app.desktop.ui.settings.aggregateUsage
import com.qianyan.app.desktop.ui.settings.balanceErrorText
import com.qianyan.app.desktop.ui.settings.formatBalanceLine
import com.qianyan.app.desktop.ui.settings.formatTokens
import com.qianyan.provider.BalanceLine
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderType
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** A-14 用量聚合 / 余额格式化纯函数（无 UI、无网络）。 */
class UsageAggregationTest {

    private fun bucket(
        date: LocalDate,
        provider: ProviderType = ProviderType.DEEPSEEK,
        model: String = "deepseek-v4-pro",
        calls: Int = 1,
        prompt: Long = 10,
        completion: Long = 5,
    ) = UsageBucket(
        date = date,
        provider = provider,
        modelId = model,
        calls = calls,
        prompt = prompt,
        completion = completion,
        total = prompt + completion,
    )

    @Test
    fun `aggregate merges dates per provider and model and sorts by total`() {
        val rows = aggregateUsage(
            listOf(
                bucket(LocalDate.of(2026, 11, 6), prompt = 10),
                bucket(LocalDate.of(2026, 11, 7), prompt = 20),
                bucket(LocalDate.of(2026, 11, 7), provider = ProviderType.MIMO, model = "mimo-v2.6-pro", prompt = 100),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(ProviderType.MIMO, rows[0].provider, "合计大的排前")
        assertEquals(105L, rows[0].total)
        assertEquals(2, rows[1].calls)
        assertEquals(30L, rows[1].prompt)
        assertEquals(40L, rows[1].total)
    }

    @Test
    fun `token formatting adds thousands separators without locale`() {
        assertEquals("0", formatTokens(0))
        assertEquals("999", formatTokens(999))
        assertEquals("1,000", formatTokens(1_000))
        assertEquals("1,234,567", formatTokens(1_234_567))
    }

    @Test
    fun `balance line and typed error text never expose response bodies`() {
        assertEquals(
            "CNY 110.00 · 现金 100.00 · 赠送 10.00",
            formatBalanceLine(BalanceLine("CNY", "110.00", cash = "100.00", granted = "10.00")),
        )
        assertEquals("CNY 8.50", formatBalanceLine(BalanceLine("CNY", "8.50")))
        assertEquals("查询超时，请稍后重试", balanceErrorText(ProviderException.Timeout("t")))
        assertEquals("请求被限流，请稍后重试", balanceErrorText(ProviderException.RateLimit("r")))
        assertEquals(
            "鉴权失败，请检查 API Key",
            balanceErrorText(ProviderException.ProviderUnavailable("HTTP 401（鉴权失败）")),
        )
        assertEquals("响应格式不符合预期", balanceErrorText(ProviderException.InvalidResponse("bad")))
    }
}
