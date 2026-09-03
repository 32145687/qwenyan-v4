package com.qianyan.application.usecase.writing.context

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * P12.0 P0-1：Variant Canon 可见性。
 * 语义：Variant Context = Original 只读基座(variant_id IS NULL) + 当前 Variant 记忆；
 * 不跨 Variant 读取其它 Variant 记忆、不跨 Novel、不修改 Original Canon。
 */
class StoryWorldContextVariantCanonTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun mem(id: String, novel: NovelId, variant: VariantId?, layer: MemoryLayer, content: String) = MemoryEntry(
        id = MemoryEntryId(id), novelId = novel, variantId = variant,
        scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        layer = layer, content = content,
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    /** 新建 DB：novelA(base canon + v1 + v2)、novelB(base canon)。返回容器与 novel id。 */
    private data class Seed(val app: ApplicationContainer, val novelA: NovelId, val novelB: NovelId)

    private fun seed(): Seed {
        val c = app()
        val novelA = c.novels.createOriginal(title = "A")
        val novelB = c.novels.createOriginal(title = "B")
        c.memoryRepository.saveEntry(mem("baseA", novelA, null, MemoryLayer.ORIGINAL, "A的canon"))
        c.memoryRepository.saveEntry(mem("v1", novelA, VariantId("va1"), MemoryLayer.WRITING, "v1的记忆"))
        c.memoryRepository.saveEntry(mem("v2", novelA, VariantId("va2"), MemoryLayer.WRITING, "v2的记忆"))
        c.memoryRepository.saveEntry(mem("baseB", novelB, null, MemoryLayer.ORIGINAL, "B的canon"))
        return Seed(c, novelA, novelB)
    }

    @Test
    fun `original context sees only original base canon`() {
        val s = seed()
        val ctx = s.app.storyWorldContextResolver.resolve(s.novelA)
        assertTrue(ctx.canon.contains("A的canon"))
        // 无 variant 的 ORIGINAL 上下文不包含其它 Variant 记忆
        assertTrue(ctx.canon + ctx.worldState + ctx.facts + ctx.memories noneContains listOf("v1的记忆", "v2的记忆"))
        assertTrue(ctx.canon.none { it == "B的canon" })
    }

    @Test
    fun `variant context sees original canon plus its own memory`() {
        val s = seed()
        val ctx = s.app.storyWorldContextResolver.resolve(s.novelA, variantId = VariantId("va1"))
        assertTrue(ctx.canon.contains("A的canon"))
        assertTrue(ctx.memories.contains("v1的记忆"))
        assertTrue(ctx.memories.none { it == "v2的记忆" })
    }

    @Test
    fun `different variant does not see other variant memory`() {
        val s = seed()
        val ctx = s.app.storyWorldContextResolver.resolve(s.novelA, variantId = VariantId("va2"))
        assertTrue(ctx.memories.contains("v2的记忆"))
        assertTrue(ctx.memories.none { it == "v1的记忆" })
    }

    @Test
    fun `cross novel memory excluded`() {
        val s = seed()
        val ctx = s.app.storyWorldContextResolver.resolve(s.novelA, variantId = VariantId("va1"))
        assertTrue((ctx.canon + ctx.worldState + ctx.facts + ctx.memories).none { it == "B的canon" })
    }

    @Test
    fun `resolver does not mutate original canon`() {
        val s = seed()
        s.app.storyWorldContextResolver.resolve(s.novelA, variantId = VariantId("va1"))
        // 解析只读：重新 resolve 后 canon 不变
        assertTrue(s.app.storyWorldContextResolver.resolve(s.novelA).canon.contains("A的canon"))
    }

    private infix fun List<String>.noneContains(others: List<String>) = others.none { o -> this.any { it == o } }
}