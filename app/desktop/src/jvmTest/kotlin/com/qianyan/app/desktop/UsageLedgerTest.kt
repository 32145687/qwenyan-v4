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
        date: () -> LocalDate = { LocalDate.of(2026, 11, 7) }, // 周六：DeepSeek 全天空闲
        time: () -> java.time.LocalTime = { java.time.LocalTime.NOON },
    ): Pair<UsageLedger, Path> = UsageLedger(dir.resolve("usage.json"), date, time) to dir

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
    fun `written file follows schema v3 with peak split`() {
        val (ledger, dir) = ledgerAt()
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-pro", usage(7, 8))

        val root = Json.parseToJsonElement(dir.resolve("usage.json").toFile().readText()).jsonObject
        assertEquals(3, root["version"]!!.jsonPrimitive.content.toInt())
        val buckets = root["buckets"]!!.jsonObject
        val key = buckets.keys.single()
        assertEquals("2026-11-07|DEEPSEEK|deepseek-v4-pro", key)
        val counts = buckets[key] as JsonObject
        assertEquals(1, counts["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, counts["prompt"]!!.jsonPrimitive.content.toInt())
        assertEquals(8, counts["completion"]!!.jsonPrimitive.content.toInt())
        assertEquals(15, counts["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, counts["cacheHit"]!!.jsonPrimitive.content.toInt())
        // 周六 = DeepSeek 全天空闲 → 峰侧全 0
        assertEquals(0, counts["peakCalls"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, counts["peakPrompt"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `peak and offpeak usage split into the same bucket by time of day`() {
        var day = LocalDate.of(2026, 11, 11) // 周三
        var time = java.time.LocalTime.of(10, 30) // 高峰（9:00–12:00）
        val (ledger, _) = ledgerAt(date = { day }, time = { time })
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-flash", usage(100, 10))
        time = java.time.LocalTime.of(20, 30) // 空闲
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-flash", usage(50, 5))
        day = LocalDate.of(2026, 11, 14) // 周六：全天空闲
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-flash", usage(20, 2))

        val bucket = ledger.summary().first { it.date == LocalDate.of(2026, 11, 11) }
        assertEquals(2, bucket.calls)
        assertEquals(150L, bucket.prompt)
        assertEquals(1, bucket.peakCalls, "周三 10:30 计入高峰")
        assertEquals(100L, bucket.peakPrompt)
        assertEquals(0L, bucket.peakHit)
        assertEquals(10L, bucket.peakCompletion)
        // 谷侧 = 总量 − 峰量
        assertEquals(50L, bucket.prompt - bucket.peakPrompt)
    }

    @Test
    fun `cache hit tokens accumulate per bucket`() {
        val (ledger, _) = ledgerAt()
        val hit = Usage(promptTokens = 100, completionTokens = 10, totalTokens = 110, promptCacheHitTokens = 60)
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-flash", hit)
        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-flash", hit)

        val bucket = ledger.summary().single()
        assertEquals(2, bucket.calls)
        assertEquals(120L, bucket.cacheHit, "同桶 cacheHit 累加")
        assertEquals(200L, bucket.prompt)
    }

    @Test
    fun `legacy v1 file without cacheHit reads back as zero and upgrades on next write`() {
        val (ledger, dir) = ledgerAt()
        // 手工写入一份 v1 账本（无 cacheHit / 峰拆分字段）
        dir.resolve("usage.json").toFile().writeText(
            """{"version":1,"buckets":{"2026-11-07|DEEPSEEK|deepseek-v4-pro":{"calls":2,"prompt":20,"completion":10,"total":30}}}""",
        )

        val bucket = ledger.summary().single()
        assertEquals(2, bucket.calls)
        assertEquals(0L, bucket.cacheHit, "v1 旧账本读回 cacheHit = 0，不伪造")
        assertEquals(2, bucket.peakCalls, "v1 峰拆分未知 → 全量按高峰保守计")
        assertEquals(20L, bucket.peakPrompt)

        ledger.record(ProviderType.DEEPSEEK, "deepseek-v4-pro", usage(1, 1))
        val root = Json.parseToJsonElement(dir.resolve("usage.json").toFile().readText()).jsonObject
        assertEquals(3, root["version"]!!.jsonPrimitive.content.toInt(), "继续记账即升级 v3")
        val counts = root["buckets"]!!.jsonObject.values.single().jsonObject
        assertEquals(3, counts["calls"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, counts["cacheHit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `legacy v2 file without peak split reads back conservatively`() {
        val (ledger, dir) = ledgerAt()
        dir.resolve("usage.json").toFile().writeText(
            """{"version":2,"buckets":{"2026-11-07|DEEPSEEK|deepseek-v4-pro":{"calls":2,"prompt":20,"completion":10,"total":30,"cacheHit":8}}}""",
        )

        val bucket = ledger.summary().single()
        assertEquals(8L, bucket.cacheHit)
        assertEquals(2, bucket.peakCalls, "v2 峰拆分未知 → 全量按高峰保守计")
        assertEquals(20L, bucket.peakPrompt)
        assertEquals(8L, bucket.peakHit)
        assertEquals(10L, bucket.peakCompletion)
    }

    @Test
    fun `record count is bucket count and missing file is an empty ledger`() {
        val (ledger, _) = ledgerAt()
        assertEquals(0, ledger.recordCount(), "文件缺失 = 空账本")
        assertTrue(ledger.summary().isEmpty())

        var today = LocalDate.of(2026, 11, 7)
        val dir = Files.createTempDirectory("qianyan-usage-count")
        val counting = UsageLedger(dir.resolve("usage.json"), { today }, { java.time.LocalTime.NOON })
        counting.record(ProviderType.DEEPSEEK, "a", usage(1, 1))
        counting.record(ProviderType.DEEPSEEK, "a", usage(1, 1))
        counting.record(ProviderType.DEEPSEEK, "b", usage(1, 1))
        today = LocalDate.of(2026, 11, 8)
        counting.record(ProviderType.DEEPSEEK, "a", usage(1, 1))

        assertEquals(3, counting.recordCount(), "同一厂商不同模型/不同日期各算一行")
    }
}
