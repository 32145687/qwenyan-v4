package com.qianyan.application.usecase.writing.confirmation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.1.4 — 最小 HITL Confirmation Gate（T1–T13 + 最小 E2E）。
 * 全程内存库 + Mock LLM；confirm() 不调用 Agent/LLM（用 LLM 调用计数验证）。
 */
class ConfirmationGateTest {

    private var llmCalls = 0

    private val defaultKu =
        """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""

    /** Mock LLM：为各 Agent 返回对应合法输出；计数验证 confirm() 不调用 LLM。 */
    private fun gateway(knowledgeJson: String = defaultKu): MockLLMGateway = MockLLMGateway { req ->
        llmCalls++
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "KnowledgeUpdateAgent" in system -> knowledgeJson
            "StoryRevisionAgent" in system -> """{"content":"修订稿。"}"""
            "StoryWriterAgent" in system -> """{"content":"正文。"}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun newDraft(
        id: String, novelId: NovelId, variantId: VariantId?, scope: VariantScope,
        status: DraftStatus, chapterId: ChapterId,
    ): Draft {
        val now = Clock.System.now()
        return Draft(
            draftId = DraftId(id), novelId = novelId, variantId = variantId, scope = scope,
            chapterId = chapterId, content = "正文", status = status,
            createdAt = now, updatedAt = now,
        )
    }

    private fun chapter(app: ApplicationContainer, novelId: NovelId, variantId: VariantId?, scope: VariantScope, id: String): Chapter {
        val now = Clock.System.now()
        return app.chapterRepository.createNextChapter(
            Chapter(ChapterId(id), novelId, variantId, scope, title = "章", order = 0,
                status = ChapterStatus.FINAL, createdAt = now, updatedAt = now),
        )
    }

    /* T1 — Final Draft 可以进入确认（→ CONFIRMED）；T3 — 确认后状态为 CONFIRMED */
    @Test
    fun `t1 t3 final draft confirmation reaches confirmed status`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t-ch")
        val d = newDraft("d1", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(d)

        val confirmed = app.confirmations.confirmFinalDraft(d.draftId, novelId, null)

        assertEquals(DraftStatus.CONFIRMED, confirmed.status)
        assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(d.draftId)!!.status)
    }

    /* T2 — 非 Final Draft 不能确认 */
    @Test
    fun `t2 non final draft cannot be confirmed`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t-ch")
        val d = newDraft("d-nf", novelId, null, VariantScope.ORIGINAL, DraftStatus.WRITTEN, ch.chapterId)
        app.draftRepository.save(d)

        val ex = assertFailsWith<ApplicationException> { app.confirmations.confirmFinalDraft(d.draftId, novelId, null) }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    /* T4 — 未确认 Final Draft 执行 Knowledge Update 必须失败 */
    @Test
    fun `t4 knowledge update on unconfirmed final draft fails`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t-ch")
        val d = newDraft("d-pending", novelId, null, VariantScope.ORIGINAL, DraftStatus.PENDING_CONFIRMATION, ch.chapterId)
        app.draftRepository.save(d)

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeKnowledgeUpdate(app.tasks.create(TaskType.WRITING), d)
        }
        assertIs<ApplicationError.DraftConfirmationRequired>(ex.error)
    }

    /* T5 — 已确认 Final Draft 可以执行 Knowledge Update */
    @Test
    fun `t5 knowledge update on confirmed final draft succeeds`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t-ch")
        val d = newDraft("d-c", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(d)
        app.confirmations.confirmFinalDraft(d.draftId, novelId, null)

        llmCalls = 0
        val outcome = app.taskRunner.executeKnowledgeUpdate(app.tasks.create(TaskType.WRITING), d)
        assertEquals(1, outcome.applied.size)
        assertTrue(llmCalls >= 1) // KU 确实调用了 Agent
    }

    /* T6 — Draft A 已确认，Revision 生成 Draft B，B ≠ CONFIRMED；T7 — lineage 正确 */
    @Test
    fun `t6 t7 revision new draft does not inherit confirmation and keeps lineage`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t6-ch")
        val a = newDraft("dA", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(a)
        app.confirmations.confirmFinalDraft(a.draftId, novelId, null)

        val b = app.taskRunner.executeRevision(app.tasks.create(TaskType.WRITING), a, ValidationResult(passed = true))

        assertEquals(DraftStatus.REVISED, b.status)
        assertNotEquals(DraftStatus.CONFIRMED, b.status)
        assertEquals(a.draftId, b.previousDraftId)
        assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(a.draftId)!!.status) // A 仍 CONFIRMED
    }

    /* T8 — Variant A 的 Draft 不能被 Variant B 确认 */
    @Test
    fun `t8 variant isolation rejects cross variant confirmation`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val chA = chapter(app, novelId, VariantId("va"), VariantScope.VARIANT, "t8-cha")
        val da = newDraft("dA-v", novelId, VariantId("va"), VariantScope.VARIANT, DraftStatus.FINAL, chA.chapterId)
        app.draftRepository.save(da)

        val ex = assertFailsWith<ApplicationException> { app.confirmations.confirmFinalDraft(da.draftId, novelId, VariantId("vb")) }
        assertIs<ApplicationError.VariantMismatch>(ex.error)
    }

    /* T9 — Novel A 的 Draft 不能被 Novel B 确认 */
    @Test
    fun `t9 novel isolation rejects cross novel confirmation`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val nA = app.novels.createOriginal(title = "A")
        val nB = app.novels.createOriginal(title = "B")
        val chA = chapter(app, nA, null, VariantScope.ORIGINAL, "t9-cha")
        val da = newDraft("dA-n", nA, null, VariantScope.ORIGINAL, DraftStatus.FINAL, chA.chapterId)
        app.draftRepository.save(da)

        val ex = assertFailsWith<ApplicationException> { app.confirmations.confirmFinalDraft(da.draftId, nB, null) }
        assertIs<ApplicationError.VariantMismatch>(ex.error)
    }

    /* T10 — Original Draft 不能通过 confirmation 被写入（confirm 后 Ku 对 Original 仍 immutable 拒绝） */
    @Test
    fun `t10 original confirmation does not enable writing`() {
        val updateKu = """{"changes":[{"changeId":"r","operation":"UPDATE","target":"主角","content":"改境界"}]}"""
        val app = ApplicationContainer.open(analysisGateway = gateway(updateKu))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t10-ch")
        val d = newDraft("d-orig", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(d)
        app.confirmations.confirmFinalDraft(d.draftId, novelId, null) // confirm 本身不写入

        // Original immutable：KU（UPDATE）被确定性拒绝，不落地、Memory 不变
        val outcome = app.taskRunner.executeKnowledgeUpdate(app.tasks.create(TaskType.WRITING), d)
        assertEquals(0, outcome.applied.size)
        assertTrue(outcome.validated.rejected.isNotEmpty())
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.none { it.contains("改境界") })
    }

    /* T11 — 重复确认是幂等的，且不调用 LLM */
    @Test
    fun `t11 repeated confirmation is idempotent`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t11-ch")
        val d = newDraft("d-idem", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(d)

        llmCalls = 0
        val c1 = app.confirmations.confirmFinalDraft(d.draftId, novelId, null)
        val c2 = app.confirmations.confirmFinalDraft(d.draftId, novelId, null)

        assertEquals(DraftStatus.CONFIRMED, c1.status)
        assertEquals(DraftStatus.CONFIRMED, c2.status)
        assertEquals(0, llmCalls)
        assertEquals(d.content, app.draftRepository.getById(d.draftId)!!.content)
    }

    /* T12 — confirm 不会自动执行 Knowledge Update（不调用 Agent/LLM，也不沉淀 Memory） */
    @Test
    fun `t12 confirm does not auto run knowledge update`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t12-ch")
        val d = newDraft("d-confirm-only", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(d)

        llmCalls = 0
        app.confirmations.confirmFinalDraft(d.draftId, novelId, null)

        assertEquals(0, llmCalls)
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.none { it.contains("金丹期") })
    }

    /* T13 — 确认状态经现有 Task/Checkpoint 语义恢复（checkpoint 只存引用，Draft 状态在 DB 为真） */
    @Test
    fun `t13 confirmation status recovers via checkpoint reference`() {
        val tmp = Files.createTempFile("qianyan-confirm", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            fun open(): ApplicationContainer {
                val handle = QianyanDbFactory.open("jdbc:sqlite:$tmp")
                handles += handle
                return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())
            }

            var app = open()
            val novelId = app.novels.createOriginal(title = "仙侠")
            val ch = chapter(app, novelId, null, VariantScope.ORIGINAL, "t13-ch")
            val d = newDraft("d-reopen", novelId, null, VariantScope.ORIGINAL, DraftStatus.FINAL, ch.chapterId)
            app.draftRepository.save(d)
            val confirmed = app.confirmations.confirmFinalDraft(d.draftId, novelId, null)

            val taskId = app.tasks.create(TaskType.WRITING)
            app.tasks.saveCheckpoint(taskId, WritingSnapshot.STAGE, WritingSnapshot.encode(confirmed))

            app = open()
            val restored = app.writingExecution.draftFrom(app.tasks.restoreCheckpoint(taskId))
            assertNotNull(restored)
            assertEquals(DraftStatus.CONFIRMED, restored.status)
        } finally {
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* E2E 未确认 → KU FAIL（预期）；确认 → KU PASS */
    @Test
    fun `e2e confirmation gates knowledge update`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = chapter(app, novelId, VariantId("va"), VariantScope.VARIANT, "e-ch")
        val d = newDraft("d-e2e", novelId, VariantId("va"), VariantScope.VARIANT, DraftStatus.FINAL, ch.chapterId)
        app.draftRepository.save(d)

        val failEx = assertFailsWith<ApplicationException> {
            app.taskRunner.executeKnowledgeUpdate(app.tasks.create(TaskType.WRITING), d)
        }
        assertIs<ApplicationError.DraftConfirmationRequired>(failEx.error)

        app.confirmations.confirmFinalDraft(d.draftId, novelId, VariantId("va"))
        val ok = app.taskRunner.executeKnowledgeUpdate(app.tasks.create(TaskType.WRITING), d)
        assertEquals(1, ok.applied.size)
    }
}