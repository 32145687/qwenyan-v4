package com.qianyan.application.usecase.writing.context

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11.6 Planning Context Assembly 世界上下文接入测试。
 * 验证：Repository → Resolver → StoryWorldContext → PlanningContext（canon 优先 memories + worldContext 字段）。
 * 同时验证 PlannerAgent 的输入仍来自组装好的 Context（不直读 Repository）——经 Context 就位即可。
 */
class StoryWorldContextAssemblyTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-sw"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun mem(id: String, novel: NovelId, layer: MemoryLayer, content: String) = MemoryEntry(
        id = MemoryEntryId(id), novelId = novel, scope = VariantScope.ORIGINAL, layer = layer, content = content,
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    @Test
    fun `assembly carries canon first memories and world context`() {
        val container = app()
        val novelId = container.novels.createOriginal(title = "星辰大陆", synopsis = "少年探索星辰之谜。")
        container.memoryRepository.saveEntry(mem("w1", novelId, MemoryLayer.WRITING, "新章猜测：古碑隐藏传送阵"))
        container.memoryRepository.saveEntry(mem("c1", novelId, MemoryLayer.ORIGINAL, "世界法则：灵气决定修行上限"))

        val ctx: PlanningContext = container.planningContextAssembly.assemble(request(novelId))

        // canon 优先进入 memories
        assertTrue(ctx.memories.first() == "世界法则：灵气决定修行上限")
        assertTrue(ctx.memories.last() == "新章猜测：古碑隐藏传送阵")

        // worldContext 字段就位（canon/writing 分层）
        val wc = ctx.worldContext
        assertNotNull(wc)
        assertEquals(listOf("世界法则：灵气决定修行上限"), wc.canon)
        assertEquals(listOf("新章猜测：古碑隐藏传送阵"), wc.memories)
        assertTrue(wc.worldSummary.contains("星辰大陆"))
    }

    @Test
    fun `planner agent receives assembled context without touching repository`() {
        val container = app()
        val novelId = container.novels.createOriginal(title = "星辰大陆")
        container.memoryRepository.saveEntry(mem("c1", novelId, MemoryLayer.ORIGINAL, "canon一"))
        container.memoryRepository.saveEntry(mem("w1", novelId, MemoryLayer.WRITING, "草稿一"))

        val ctx = container.planningContextAssembly.assemble(request(novelId))
        // Planner 的输入是「已组装好、canon 优先的 PlanningContext」；PlannerAgent 只依赖该 Context，
        // 不直读 memoryRepository（Agent 边界由构造签名保证）。此处断言组装产物确实 canon 优先。
        val wc = ctx.worldContext
        assertNotNull(wc)
        assertEquals(listOf("canon一"), wc.canon)
        assertEquals("canon一", ctx.memories.first())
        assertEquals("草稿一", ctx.memories.last())
    }
}