package com.qianyan.app.desktop

import com.qianyan.app.desktop.di.UsageLedger
import com.qianyan.provider.ProviderType
import com.qianyan.provider.Usage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A-14 本地用量账本（临时目录，无真实网络）。 */
class UsageLedgerTest {

    private fun ledgerAt(
        dir: Path = Files.createTempDirectory("qianyan-usage"),
        date: () -> LocalDate = { LocalDate.of(2026, 11, 7) },
    ): Pair<UsageLedger, Path> = UsageLedger(dir.resolve("usage.json"), date) to dir

    private fun usage(prompt: Int, completion: Int) =
        Usage(promptTokens = prompt, completionTokens = completion, totalTokens = prompt + completion)

    @Test
    fun `records aggregate by provider and model`() {
        val (ledger, _) = ledgerAt()
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-pro", usage(10, 5))
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-pro", usage(20, 7))
        ledger.record(ProviderType.MIMO, "mimo-v2.6-pro", usage(1, 1))

        val buckets = ledger.summary()
        assertEquals(2, buckets.size)
        val ds = buckets.first { it.provider == ProviderType.DEEPSEEK }
        assertEquals("deepseek-v4-pro", ds.modelId)
        assertEquals(2, ds.calls)
        assertEquals(30L, ds.prompt)
        assertEquals(12L, ds.completion)
        assertEquals(42L, ds.total)
    }

    @Test
    fun `summary filters 7 and 30 day ranges`() {
        var today = LocalDate.of(2026, 11, 7)
        val (ledger, _) = ledgerAt(date = { today })

        today = LocalDate.of(2026, 11, 1) // 7 天窗口首日（含今天共 7 天）
        ledger.record(ProviderType.DEEPSEEK, "m", usage(1, 1))
        today = LocalDate.of(2026, 10, 25) // 30 天窗口内（13 天前）
        ledger.record(ProviderType.DEEPSEEK, "m", usage(2, 2))
        today = LocalDate.of(2026, 10, 1) // 30 天窗口外（37 天前）
        ledger.record(ProviderType.DEEPSEEK, "m", usage(4, 4))
        today = LocalDate.of(2026, 11, 7)

        assertEquals(1, ledger.summary(days = 7).size, "7 天只含 11-01 与今天")
        assertEquals(2, ledger.summary(days = 30).size, "30 天不含 10-01")
        assertEquals(3, ledger.summary(days = null).size, "全部不过滤")
    }

    @Test
    fun `prunes buckets older than 400 days`() {
        var today = LocalDate.of(2025, 1, 1)
        val (ledger, _) = ledgerAt(date = { today })
        ledger.record(ProviderType.DEEPSEEK, "m", usage(1, 1))

        today = LocalDate.of(2026, 3, 1) // 距离 2025-01-01 超过 400 天
        ledger.record(ProviderType.MIMO, "m", usage(2, 2))

        val dates = ledger.summary().map { it.date }
        assertEquals(listOf(LocalDate.of(2026, 3, 1)), dates, "旧桶必须被裁剪")
    }

    @Test
    fun `corrupt file is backed up and reset`() {
        val (ledger, dir) = ledgerAt()
        val file = dir.resolve("usage.json").toFile()
        file.writeText("{ not json")

        ledger.record(ProviderType.DEEPSEEK, "m", usage(3, 4))

        assertNotNull(ledger.corruptNotice(), "损坏重置必须留下说明")
        val backups = dir.toFile().listFiles { f -> f.name.startsWith("usage.json.corrupt-") }
        assertTrue(backups != null && backups.isNotEmpty(), "损坏文件必须先备份")
        assertEquals(1, ledger.recordCount(), "重置后可以继续记账")
    }

    @Test
    fun `clear empties the ledger and it stays writable`() {
        val (ledger, dir) = ledgerAt()
        ledger.record(ProviderType.DEEPSEEK, "m", usage(1, 1))
        assertEquals(1, ledger.recordCount())

        ledger.clear()
        assertEquals(0, ledger.recordCount())
        assertTrue(ledger.summary().isEmpty())

        ledger.record(ProviderType.MIMO, "m2", usage(5, 5))
        assertEquals(1, ledger.recordCount())
        assertTrue(dir.resolve("usage.json").toFile().isFile)
    }

    @Test
    fun `written file follows schema v1`() {
        val (ledger, dir) = ledgerAt()
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-pro", usage(7, 8))

        val root = Json.parseToJsonElement(dir.resolve("usage.json").toFile().readText()).jsonObject
        assertEquals(1, root["version"]!!.jsonPrimitive.content.toInt())
        val buckets = root["buckets"]!!.jsonObject
        val key = buckets.keys.single()
        assertEquals("2026-11-07|DEEPSEEK|deepseek-v4-pro", key)
        val counts = buckets[key] as JsonObject
        assertEquals(1, counts["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, counts["prompt"]!!.jsonPrimitive.content.toInt())
        assertEquals(8, counts["completion"]!!.jsonPrimitive.content.toInt())
        assertEquals(15, counts["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `record count is bucket count and missing file is an empty ledger`() {
        val (ledger, _) = ledgerAt()
        assertEquals(0, ledger.recordCount(), "文件缺失 = 空账本")
        assertTrue(ledger.summary().isEmpty())

        var today = LocalDate.of(2026, 11, 7)
        val dir = Files.createTempDirectory("qianyan-usage-count")
        val counting = UsageLedger(dir.resolve("usage.json")) { today }
        counting.record(ProviderType.DEEPSEEK, "a", usage(1, 1))
        counting.record(ProviderType.DEEPSEEK, "a", usage(1, 1))
        counting.record(ProviderType.DEEPSEEK, "b", usage(1, 1))
        today = LocalDate.of(2026, 11, 8)
        counting.record(ProviderType.DEEPSEEK, "a", usage(1, 1))

        assertEquals(3, counting.recordCount(), "同一厂商不同模型/不同日期各算一行")
    }
}
