package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.DraftId
import com.qianyan.model.MemoryEntryId
import com.qianyan.model.NovelId
import com.qianyan.model.task.TaskType
import com.qianyan.model.VariantScope
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P12.0 P0-2 / P0-3：Knowledge 有效状态语义与事务原子化。
 * - ADD 后事实可见；UPDATE 后旧事实不再作为当前有效事实、新事实可见；REMOVE 后不可见（历史 effective=0 保留）。
 * - inTransaction 内失败整体回滚（不残留部分状态）。
 * 注：知识条目经 Applicator 渲染（含「【知识更新·operation】」前缀），断言用**子串**匹配。
 */
class KnowledgeEffectiveStateTransactionTest {

    private fun mem(id: String, novel: NovelId, target: String, content: String) = MemoryEntry(
        id = MemoryEntryId(id), novelId = novel, scope = VariantScope.ORIGINAL, layer = MemoryLayer.WRITING,
        content = content, target = target, effective = true,
        createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    @Test
    fun `update supersedes old effective fact and remove hides it`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "T")
        app.memoryRepository.saveEntry(mem("a1", novelId, "主角", "角色受伤"))
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("角色受伤") })

        // UPDATE：旧事实（同 target）失效，新事实成为当前有效
        app.memoryRepository.deactivateByTarget(novelId, null, "主角")
        app.memoryRepository.saveEntry(mem("a2", novelId, "主角", "角色已痊愈"))
        val afterUpdate = app.storyWorldContextResolver.resolve(novelId).memories
        assertTrue(afterUpdate.any { it.contains("角色已痊愈") })
        assertTrue(afterUpdate.none { it.contains("角色受伤") })

        // REMOVE：目标不再作为当前有效事实
        app.memoryRepository.deactivateByTarget(novelId, null, "主角")
        val afterRemove = app.storyWorldContextResolver.resolve(novelId).memories
        assertTrue(afterRemove.none { it.contains("角色已痊愈") || it.contains("角色受伤") })
    }

    @Test
    fun `variant deactivate does not touch original canon`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "T")
        app.memoryRepository.saveEntry(mem("canon", novelId, "主角", "主角是少年"))
        app.memoryRepository.saveEntry(mem("v1", novelId, "世界规则", "规则x").copy(variantId = com.qianyan.model.VariantId("va"), scope = VariantScope.VARIANT))

        app.memoryRepository.deactivateByTarget(novelId, com.qianyan.model.VariantId("va"), "世界规则")

        val vaCtx = app.storyWorldContextResolver.resolve(novelId, variantId = com.qianyan.model.VariantId("va")).memories
        assertTrue(vaCtx.none { it.contains("规则x") })
        assertTrue(vaCtx.any { it.contains("主角是少年") })
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("主角是少年") })
    }

    /* use case 集成：ADD 后 UPDATE（同 target）→ resolver 只见最新 */
    @Test
    fun `knowledge update flow supersedes by target`() {
        val draft = Draft(
            draftId = DraftId("d-k"), novelId = NovelId("novel-k"), content = "正文",
            status = DraftStatus.WRITTEN, createdAt = Instant.fromEpochMilliseconds(0), updatedAt = Instant.fromEpochMilliseconds(0),
        )
        fun gateway(body: String) = MockLLMGateway { _ -> ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20), finishReason = FinishReason.STOP,
        ) }

        val add = ApplicationContainer.open(analysisGateway = gateway("""{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"事实1"}]}"""))
        val addNovel = add.novels.createOriginal(title = "T")
        // P12.1.4：KU 需已 CONFIRMED Final Draft。
        val addDraft = draft.copy(novelId = addNovel, status = DraftStatus.CONFIRMED).also { add.draftRepository.save(it) }
        add.taskRunner.executeKnowledgeUpdate(add.tasks.create(TaskType.WRITING), addDraft)
        assertTrue(add.storyWorldContextResolver.resolve(addNovel).memories.any { it.contains("事实1") })

        val upd = ApplicationContainer.open(analysisGateway = gateway("""{"changes":[{"changeId":"k2","operation":"UPDATE","target":"主角","content":"事实2"}]}"""))
        val updNovel = upd.novels.createOriginal(title = "T")
        val idUpd = upd.tasks.create(TaskType.WRITING)
        // UPDATE 走 Variant（ORIGINAL 的 UPDATE 会被 immutable Canon 拒绝）；同 target 旧事实失效，「事实2」成为当前有效
        val vDraft = draft.copy(
            novelId = updNovel, variantId = com.qianyan.model.VariantId("va"), scope = VariantScope.VARIANT,
            status = DraftStatus.CONFIRMED,
        ).also { upd.draftRepository.save(it) }
        upd.taskRunner.executeKnowledgeUpdate(idUpd, vDraft)
        val after = upd.storyWorldContextResolver.resolve(updNovel, variantId = com.qianyan.model.VariantId("va")).memories
        assertTrue(after.any { it.contains("事实2") })
        assertTrue(after.none { it.contains("事实1") })
    }

    /* P0-3：inTransaction 内失败 → 全部回滚，无半完成状态 */
    @Test
    fun `transaction rolls back on failure`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "T")
        assertFailsWith<RuntimeException> {
            app.memoryRepository.inTransaction {
                app.memoryRepository.saveEntry(mem("t1", novelId, "t", "A"))
                app.memoryRepository.saveEntry(mem("t2", novelId, "t", "B"))
                throw RuntimeException("boom")
            }
        }
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.isEmpty())
    }

    /* P0-3：全部成功 → 全部落库 */
    @Test
    fun `transaction commits all on success`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "T")
        app.memoryRepository.inTransaction {
            app.memoryRepository.saveEntry(mem("t1", novelId, "t1", "A"))
            app.memoryRepository.saveEntry(mem("t2", novelId, "t2", "B"))
        }
        val memories = app.storyWorldContextResolver.resolve(novelId).memories
        assertTrue(memories.any { it.contains("A") } && memories.any { it.contains("B") })
        assertEquals(2, memories.size)
    }
}