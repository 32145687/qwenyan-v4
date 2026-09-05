package com.qianyan.application.usecase.chapter

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.task.TaskType
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.BaseNovelId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.VariantContext
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.story.ContinuationReference
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P12.1.7 — Android Planning / Writing / Review Chain（编排层），真实链验证。
 * 全程 Mock LLM（无网络）但数据均为真实：真实 Chapter / ChapterPlan / Draft / Critique / Revision /
 * Confirmation / Knowledge Update，全部经既有 UseCases 落库。
 */
class ChapterWritingChainTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in system -> """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}"""
            "StoryWriterAgent" in system -> """{"content":"正文被写就。"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            "StoryRevisionAgent" in system -> """{"content":"修订后正文。"}"""
            "KnowledgeUpdateAgent" in system ->
                """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = gateway())

    private fun newChapter(app: ApplicationContainer, novelId: NovelId, variantId: VariantId?, id: String): Chapter {
        val now = Clock.System.now()
        return app.chapterRepository.createNextChapter(
            Chapter(
                chapterId = ChapterId(id), novelId = novelId, variantId = variantId,
                scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
                title = "章", order = 0, status = ChapterStatus.PLANNED, createdAt = now, updatedAt = now,
            ),
        )
    }

    private fun session(app: ApplicationContainer, chapter: Chapter) =
        app.chapterWriting.open(chapter.chapterId, chapter.novelId, chapter.variantId)

    /* T1 — ChapterDetail → Planning 真实调用（绑定既有 Chapter，不新建孤儿章） */
    @Test
    fun `t1 planning binds to existing chapter`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t1-ch")

        val s = session(app, ch)
        val plan = s.plan()

        assertEquals(ch.chapterId, plan.chapterId)
        // 未产生孤儿章节（列表仍只有目标章）
        assertEquals(1, app.chapters.listByNovel(novelId).size)
    }

    /* T2 — Planning → Real Plan persistence（checkpoint 可恢复解码，绑定目标章） */
    @Test
    fun `t2 plan persists to checkpooint`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t2-ch")
        val s = session(app, ch)
        val plan = s.plan()

        val planTaskId = s.current().planTaskId!!
        val restored = app.planning.chapterPlanFrom(app.tasks.restoreCheckpoint(planTaskId))
        assertNotNull(restored)
        assertEquals(plan.chapterPlanId, restored.chapterPlanId)
        assertEquals(ch.chapterId, restored.chapterId)
    }

    /* T3 — Plan → Writing → Real Draft persistence */
    @Test
    fun `t3 writing persists real draft`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t3-ch")
        val s = session(app, ch)
        s.plan()
        val d = s.write()

        assertEquals(ch.chapterId, d.chapterId)
        assertEquals(DraftStatus.WRITTEN, d.status)
        // Draft 来自数据库
        assertNotNull(app.draftRepository.getById(d.draftId))
        assertTrue(d.content.contains("正文被写就"))
    }

    /* T4 — Writing → Critique */
    @Test
    fun `t4 writing to critique`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t4-ch")
        val s = session(app, ch)
        s.plan(); s.write()
        val c = s.critique()
        assertNotNull(c)
        assertTrue(c.passed)
    }

    /* T5 — Critique → Revision 产生新 Draft，lineage 不覆盖旧稿 */
    @Test
    fun `t5 critique to revision`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t5-ch")
        val s = session(app, ch)
        s.plan(); val a = s.write(); s.critique()
        val b = s.revise()

        assertEquals(DraftStatus.REVISED, b.status)
        assertNotEquals(a.draftId, b.draftId)
        assertEquals(a.draftId, b.previousDraftId)
        // 旧 Draft 未被覆盖（可从 DB 读回）
        assertNotNull(app.draftRepository.getById(a.draftId))
    }

    /* T6 — Revision lineage A→B→C（真实 RevisionExecutionUseCase 机制） */
    @Test
    fun `t6 revision lineage a to b to c`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t6-ch")
        val s = session(app, ch)
        s.plan(); val a = s.write()
        val ok = ValidationResult(passed = true)
        val wt = s.current().writeTaskId!!
        val b = app.taskRunner.executeRevision(wt, a, ok)
        val c = app.taskRunner.executeRevision(wt, b, ok)
        assertNull(a.previousDraftId)
        assertEquals(a.draftId, b.previousDraftId)
        assertEquals(b.draftId, c.previousDraftId)
    }

    /* T7 — RevisionGate 最大 3 次（真实 RevisionGate 机制） */
    @Test
    fun `t7 revision gate max three`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t7-ch")
        val s = session(app, ch)
        s.plan(); val a = s.write()
        val ok = ValidationResult(passed = true)
        val wt = s.current().writeTaskId!!
        app.taskRunner.executeRevision(wt, a, ok) // rev2
        val c = app.taskRunner.executeRevision(wt, a, ok) // rev3
        // 再修订 → RevisionNotAllowed（闸门在 Application 层拒绝，不调 LLM）
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeRevision(wt, c, ok)
        }
        assertIs<ApplicationError.RevisionNotAllowed>(ex.error)
    }

    /* T8 — Final Draft ≠ Confirmed */
    @Test
    fun `t8 final draft is not confirmed`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t8-ch")
        val s = session(app, ch)
        val finalDraft = s.let { it.plan(); it.write(); it.finalize() }
        assertEquals(DraftStatus.FINAL, finalDraft.status)
        assertNotEquals(DraftStatus.CONFIRMED, finalDraft.status)
    }

    /* T9 — 未 Confirmed 时 Knowledge Update 必须失败（UI 绕过 Application 也拒绝） */
    @Test
    fun `t9 knowledge update blocked without confirmation`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t9-ch")
        val s = session(app, ch)
        val finalDraft = s.let { it.plan(); it.write(); it.finalize() }

        // 直接绕到 Application：未 CONFIRMED → 类型化拒绝
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeKnowledgeUpdate(app.tasks.create(TaskType.KNOWLEDGE_UPDATE), finalDraft)
        }
        assertIs<ApplicationError.DraftConfirmationRequired>(ex.error)
    }

    /* T10 — 已 Confirmed 后 Knowledge Update 成功 */
    @Test
    fun `t10 knowledge update succeeds after confirmation`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t10-ch")
        val s = session(app, ch)
        s.plan(); s.write(); s.finalize(); s.confirm()
        val outcome = s.knowledgeUpdate()

        assertNotNull(outcome)
        assertTrue(outcome.applied.isNotEmpty())
        // 知识已沉淀可被 Story World 读到
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("突破金丹期") })
    }

    /* T11 — Confirmation 幂等 */
    @Test
    fun `t11 confirmation idempotent`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch = newChapter(app, novelId, null, "t11-ch")
        val s = session(app, ch)
        s.plan(); s.write()
        val c1 = s.confirm()
        val c2 = s.confirm()
        assertEquals(DraftStatus.CONFIRMED, c1.status)
        assertEquals(c1.draftId, c2.draftId)
        assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(c1.draftId)!!.status)
    }

    /* T12 — 页面/Activity 重建后链状态从数据库恢复 */
    @Test
    fun `t12 state restores from database after reopen`() {
        val tmp = Files.createTempFile("qianyan-chain", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            fun open(): ApplicationContainer {
                val h = QianyanDbFactory.open("jdbc:sqlite:$tmp")
                handles += h
                return ApplicationContainer.fromDriver(h.driver, analysisGateway = gateway())
            }
            var app = open()
            val novelId = app.novels.createOriginal(title = "仙侠")
            val ch = newChapter(app, novelId, null, "t12-ch")
            val s = session(app, ch)
            s.plan(); s.write(); s.finalize(); s.confirm()
            val confirmed = s.current().confirmedDraft!!
            s.knowledgeUpdate()

            // Activity 重建：重新打开容器 + 新 session，从 DB 恢复
            app = open()
            val restoredSession = session(app, ch)
            val r = restoredSession.refreshFromDatabase()
            assertNotNull(r.confirmedDraft)
            assertEquals(DraftStatus.CONFIRMED, r.confirmedDraft!!.status)
            assertEquals(confirmed.draftId, r.confirmedDraft!!.draftId)
            // Knowledge Update 结果（Memory）已被 Resolver 读到
            assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.isNotEmpty())
        } finally {
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* T13 — Variant isolation（Variant A 的 Draft 不落入 Variant B） */
    @Test
    fun `t13 variant isolation`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val vA = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("va")), "VAR-A")
        val vB = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("vb")), "VAR-B")
        val chA = newChapter(app, novelId, vA, "t13-cha")
        val s = session(app, chA)
        s.plan(); s.write()

        // Variant B 作用域查询不到 chA，也读不到其 Draft
        assertTrue(app.chapters.listByNovel(novelId, vB).none { it.chapterId == chA.chapterId })
        assertTrue(app.draftRepository.listByNovel(novelId).none { it.chapterId == chA.chapterId && it.variantId == vB })
    }

    /* T14 — Novel isolation（Novel A 的 Draft 不落入 Novel B） */
    @Test
    fun `t14 novel isolation`() {
        val app = app()
        val nA = app.novels.createOriginal(title = "A")
        val nB = app.novels.createOriginal(title = "B")
        val chA = newChapter(app, nA, null, "t14-cha")
        val s = session(app, chA)
        s.plan()
        val d = s.write()

        assertTrue(app.chapters.listByNovel(nB).isEmpty())
        assertTrue(app.draftRepository.listByNovel(nB).none { it.draftId == d.draftId })
    }

    /* T15 — Continuation：Chapter1 Final → Ref → Chapter2 Planning → Chapter2 Writing */
    @Test
    fun `t15 continuation to chapter two plan and write`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = newChapter(app, novelId, null, "t15-c1")
        val s1 = session(app, ch1)
        s1.plan(); s1.write(); s1.finalize()
        val c1Final = s1.current().finalDraft!!

        // Chapter2 为目标章，显式 continuation source = Chapter1 Final
        val ch2 = newChapter(app, novelId, null, "t15-c2")
        val s2 = session(app, ch2)
        val ref = ContinuationReference(ch1.chapterId, c1Final.draftId)
        val plan2 = s2.plan(ref)
        assertEquals(ch2.chapterId, plan2.chapterId)
        // 保持上一章 Final Draft 作为 continuation source（Resolver 已验 FINAL 通过）
        val d2 = s2.write()
        assertEquals(ch2.chapterId, d2.chapterId)
    }
}