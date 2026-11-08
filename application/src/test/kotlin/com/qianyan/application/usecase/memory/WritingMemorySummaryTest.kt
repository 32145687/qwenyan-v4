package com.qianyan.application.usecase.memory

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.VariantScope
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** O-08：知识更新结果视图的数据源（按 Novel + layer=WRITING + effective 统计）。 */
class WritingMemorySummaryTest {

    private fun entry(
        id: String,
        novelId: com.qianyan.model.NovelId,
        layer: MemoryLayer,
        content: String,
        updatedAt: Instant,
        effective: Boolean = true,
    ) = MemoryEntry(
        id = MemoryEntryId(id),
        novelId = novelId,
        variantId = null,
        scope = VariantScope.ORIGINAL,
        layer = layer,
        content = content,
        target = "t-$id",
        effective = effective,
        source = "KNOWLEDGE_UPDATE",
        createdBy = null,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `summary counts effective writing entries and orders recent by updated time`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "O-08")
        val t0 = Instant.parse("2026-11-08T00:00:00Z")

        app.memoryRepository.saveEntry(entry("w1", novelId, MemoryLayer.WRITING, "归", t0))
        app.memoryRepository.saveEntry(entry("w2", novelId, MemoryLayer.WRITING, "雨夜", t0.plus(kotlin.time.Duration.parse("1s"))))
        app.memoryRepository.saveEntry(entry("w3", novelId, MemoryLayer.WRITING, "旧信", t0.plus(kotlin.time.Duration.parse("2s"))))
        // 不相关 layer / 已失效（被 UPDATE/REMOVE 替换）都不计入
        app.memoryRepository.saveEntry(entry("l1", novelId, MemoryLayer.LONG_TERM, "长期记忆", t0.plus(kotlin.time.Duration.parse("3s"))))
        app.memoryRepository.saveEntry(
            entry("w-old", novelId, MemoryLayer.WRITING, "已失效", t0.plus(kotlin.time.Duration.parse("9s")), effective = false),
        )

        val summary = app.memories.writingLayerSummary(novelId)

        assertEquals(3, summary.count)
        assertEquals(t0.plus(kotlin.time.Duration.parse("2s")), summary.lastUpdatedAt)
        assertEquals(listOf("旧信", "雨夜"), summary.recentContents)
    }

    @Test
    fun `summary is empty for a novel without writing memory`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "O-08-empty")

        val summary = app.memories.writingLayerSummary(novelId)

        assertEquals(0, summary.count)
        assertEquals(null, summary.lastUpdatedAt)
        assertEquals(emptyList(), summary.recentContents)
    }
}
