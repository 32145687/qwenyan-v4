package com.qianyan.application.usecase.writing.context

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P11.6 StoryWorldContextResolver 测试。
 * 验证：确定性（两次相同输入→相等）、Layer 分层、canon 优先、Novel 隔离、WRITING 不抬到 canon 前。
 */
class StoryWorldContextResolverTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun mem(id: String, novel: NovelId, layer: MemoryLayer, content: String) = MemoryEntry(
        id = MemoryEntryId(id), novelId = novel, scope = VariantScope.ORIGINAL, layer = layer, content = content,
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    @Test
    fun `resolver is deterministic same input same context`() {
        val container = app()
        val novelId = container.novels.createOriginal(title = "T")
        container.memoryRepository.saveEntry(mem("a1", novelId, MemoryLayer.LONG_TERM, "陈夜突破筑基期"))

        val r1 = container.storyWorldContextResolver.resolve(novelId, worldSummary = "w")
        val r2 = container.storyWorldContextResolver.resolve(novelId, worldSummary = "w")
        assertEquals(r1, r2)
    }

    @Test
    fun `layer resolution groups memory into canon state facts and writing`() {
        val container = app()
        val novelId = container.novels.createOriginal(title = "T")
        container.memoryRepository.saveEntry(mem("c1", novelId, MemoryLayer.ORIGINAL, "世界法则:灵气存在"))
        container.memoryRepository.saveEntry(mem("s1", novelId, MemoryLayer.CURRENT_STATE, "陈夜位于星落峡谷"))
        container.memoryRepository.saveEntry(mem("f1", novelId, MemoryLayer.LONG_TERM, "陈夜已突破筑基期"))
        container.memoryRepository.saveEntry(mem("w1", novelId, MemoryLayer.WRITING, "新章闪过古碑"))

        val ctx = container.storyWorldContextResolver.resolve(novelId)

        assertEquals(listOf("世界法则:灵气存在"), ctx.canon)
        assertEquals(listOf("陈夜位于星落峡谷"), ctx.worldState)
        assertEquals(listOf("陈夜已突破筑基期"), ctx.facts)
        assertEquals(listOf("新章闪过古碑"), ctx.memories)
        // canon 最优先（canon → worldState → facts → memories）
        assertEquals(
            listOf("世界法则:灵气存在", "陈夜位于星落峡谷", "陈夜已突破筑基期", "新章闪过古碑"),
            ctx.orderedVisible,
        )
    }

    @Test
    fun `novel isolation keeps unrelated novel memory out`() {
        val container = app()
        val novelA = container.novels.createOriginal(title = "A")
        val novelB = container.novels.createOriginal(title = "B")
        container.memoryRepository.saveEntry(mem("a1", novelA, MemoryLayer.LONG_TERM, "A的记忆"))
        container.memoryRepository.saveEntry(mem("b1", novelB, MemoryLayer.LONG_TERM, "B的记忆"))

        val ctxA = container.storyWorldContextResolver.resolve(novelA)
        assertTrue(ctxA.facts.contains("A的记忆"))
        assertTrue(ctxA.facts.none { it == "B的记忆" })
    }

    @Test
    fun `writing memory never precedes canon`() {
        val container = app()
        val novelId = container.novels.createOriginal(title = "T")
        container.memoryRepository.saveEntry(mem("w1", novelId, MemoryLayer.WRITING, "草稿里的猜测"))
        container.memoryRepository.saveEntry(mem("c1", novelId, MemoryLayer.ORIGINAL, "已确认 canon"))

        val ctx = container.storyWorldContextResolver.resolve(novelId)
        // 即便 WRITING 先插入，canon 依旧位于 orderedVisible 首位（canon 不被普通记忆覆盖）
        assertEquals(listOf("已确认 canon"), ctx.canon)
        assertEquals(listOf("草稿里的猜测"), ctx.memories)
        assertEquals("已确认 canon", ctx.orderedVisible.first())
    }
}