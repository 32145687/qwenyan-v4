package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.1.3 — Explicit ContinuationReference。
 *
 * 覆盖要求中的所有场景（Test 1–10）+ 最小 Chapter1 Final Draft → ContinuationReference → Chapter2 Planning E2E。
 * 全部内存库 + Mock LLM，无网络。
 */
class ContinuationReferenceTest {

    private var idSeq = 0
    private fun nextId(prefix: String): String = "$prefix-${idSeq++}"

    /** Mock LLM：Planner 返回合法 ChapterPlan。captured 用于捕获 Planner 收到的 USER 输入（renderInput）。 */
    private fun gateway(captured: MutableList<String>? = null): MockLLMGateway = MockLLMGateway { req ->
        val user = req.messages.first { it.role == ChatRole.USER }.content
        captured?.add(user)
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = if ("StoryPlannerAgent" in system) """{"chapterGoal":"救下女主"}""" else """{"content":"正文。"}"""
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    /* ====================================================================
     * Test 1 — 第一章 Planning：无 ContinuationReference → 正常，不要求 source，不报 continuation 错误
     * ==================================================================== */
    @Test
    fun `test1 first chapter planning works without continuation reference`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")

        val id = app.tasks.create(TaskType.PLANNING)
        val plan = app.taskRunner.executePlanning(id, request(novelId))

        assertNotNull(plan.chapterId)
        assertEquals(TaskType.PLANNING, app.tasks.findById(id).type)
    }

    /* ====================================================================
     * E2E：Chapter1 Final Draft → ContinuationReference → Chapter2 Planning
     * ==================================================================== */
    @Test
    fun `e2e chapter1 final draft to chapter2 planning`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")

        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "e2e-ch1")
        val draft1 = createFinal(app, ch1.chapterId, novelId, null, "e2e-d1")

        val ref = ContinuationReference(ch1.chapterId, draft1.draftId)
        val plan2 = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId), ref)

        assertNotNull(plan2.chapterId)
        val target = app.chapterRepository.findById(plan2.chapterId!!)
        assertNotNull(target)
        assertEquals(novelId, target.novelId)
        // 目标章节真实为同一 Novel 的 Chapter 2（order 递增），而非来源章
        assertEquals(listOf(1, 2), app.chapterRepository.listByNovel(novelId, null).map { it.order })
    }

    /* ====================================================================
     * Test 2 — 第二章正常 continuation：PlanningContext 解析 source=Chapter1/Final Draft1，target=Chapter2
     * ==================================================================== */
    @Test
    fun `test2 second chapter continuation resolves context`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t2-ch1")
        val draft1 = createFinal(app, ch1.chapterId, novelId, null, "t2-d1")

        val ref = ContinuationReference(ch1.chapterId, draft1.draftId)
        val resolved = app.continuationResolver.resolve(request(novelId), ref)
        val context = app.planningContextAssembly.assemble(request(novelId), ref, resolved)

        assertEquals(ch1.chapterId, context.sourceChapter!!.chapterId)
        assertEquals(draft1.draftId, context.sourceFinalDraft!!.draftId)
        assertEquals(ref, context.continuationReference)

        val plan2 = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId), ref)
        assertNotNull(plan2.chapterId)
        val target = app.chapterRepository.findById(plan2.chapterId!!)!!
        assertEquals(2, target.order)
        assertEquals(1, ch1.order)
    }

    /* ====================================================================
     * Test 3 — source Draft 非 FINAL → 必须失败，PlannerAgent 不得执行
     * ==================================================================== */
    @Test
    fun `test3 non final source draft fails`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t3-ch1")
        val draft = createFinal(app, ch1.chapterId, novelId, null, "t3-d1", status = DraftStatus.WRITTEN)

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(
                app.tasks.create(TaskType.PLANNING), request(novelId),
                ContinuationReference(ch1.chapterId, draft.draftId),
            )
        }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* ====================================================================
     * Test 4 — Draft 与 Chapter 不匹配 → 必须失败
     * ==================================================================== */
    @Test
    fun `test4 draft chapter mismatch fails`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t4-ch1")
        val ch2 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t4-ch2")
        val draft2 = createFinal(app, ch2.chapterId, novelId, null, "t4-d2")

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(
                app.tasks.create(TaskType.PLANNING), request(novelId),
                ContinuationReference(ch1.chapterId, draft2.draftId),
            )
        }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* ====================================================================
     * Test 5 — Variant Isolation：Variant A Chapter1 Final 不能是 Variant B 的 continuation source
     * ==================================================================== */
    @Test
    fun `test5 variant isolation fails`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val vA = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("va")), "Variant A")
        val vB = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("vb")), "Variant B")
        val chA = createChapter(app, novelId, vA, VariantScope.VARIANT, "t5-cha")
        val draftA = createFinal(app, chA.chapterId, novelId, vA, "t5-dA")

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(
                app.tasks.create(TaskType.PLANNING), request(novelId, vB),
                ContinuationReference(chA.chapterId, draftA.draftId),
            )
        }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* ====================================================================
     * Test 6 — Novel Isolation：Novel A Chapter1 Final 不能是 Novel B 的 continuation source
     * ==================================================================== */
    @Test
    fun `test6 novel isolation fails`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val nA = app.novels.createOriginal(title = "A")
        val nB = app.novels.createOriginal(title = "B")
        val chA = createChapter(app, nA, null, VariantScope.ORIGINAL, "t6-cha")
        val draftA = createFinal(app, chA.chapterId, nA, null, "t6-dA")

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(
                app.tasks.create(TaskType.PLANNING), request(nB),
                ContinuationReference(chA.chapterId, draftA.draftId),
            )
        }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* ====================================================================
     * Test 7 — Original / Variant Isolation：Original source 不能是 Variant continuation
     * ==================================================================== */
    @Test
    fun `test7 original source rejected as variant continuation`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("va")), "Variant A")
        val chOrig = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t7-chOrig")
        val draftOrig = createFinal(app, chOrig.chapterId, novelId, null, "t7-dOrig")

        // 用 Original Chapter1 Final 作为 Variant A 的 continuation source → scope 混淆 → 失败
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(
                app.tasks.create(TaskType.PLANNING), request(novelId, VariantId("va")),
                ContinuationReference(chOrig.chapterId, draftOrig.draftId),
            )
        }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* ====================================================================
     * Test 8 — Explicit Source：同一 Novel 多个 Chapter，显式指定 ch1+draft1 时必须用，而非"最新"
     * ==================================================================== */
    @Test
    fun `test8 explicit source not latest`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t8-ch1")
        val draft1 = createFinal(app, ch1.chapterId, novelId, null, "t8-d1")
        val ch2 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t8-ch2")
        createFinal(app, ch2.chapterId, novelId, null, "t8-d2") // 更新的 Draft 存在

        val ref = ContinuationReference(ch1.chapterId, draft1.draftId)
        val resolved = app.continuationResolver.resolve(request(novelId), ref)
        val context = app.planningContextAssembly.assemble(request(novelId), ref, resolved)

        // 显式 ch1+draft1 → 必须用 ch1+draft1，绝不用"最新" ch2/d2
        assertEquals(ch1.chapterId, context.sourceChapter!!.chapterId)
        assertEquals(draft1.draftId, context.sourceFinalDraft!!.draftId)
    }

    /* ====================================================================
     * Test 9 — Wrong Draft Lineage：source Draft 不属于指定 Chapter 的 lineage → 必须失败
     * ==================================================================== */
    @Test
    fun `test9 wrong draft lineage fails`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t9-ch1")
        createFinal(app, ch1.chapterId, novelId, null, "t9-d1")

        // source Draft 悬挂于其它章节 lineage：chOther 的 Final Draft 被误当 ch1 的来源
        val chOther = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t9-chOther")
        val draftOther = createFinal(app, chOther.chapterId, novelId, null, "t9-dOther")

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(
                app.tasks.create(TaskType.PLANNING), request(novelId),
                ContinuationReference(ch1.chapterId, draftOther.draftId),
            )
        }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* ====================================================================
     * Test 10 — Context Propagation：reference → context → Planner 输入，source 不丢
     * ==================================================================== */
    @Test
    fun `test10 context propagates to planner input`() {
        val captured = mutableListOf<String>()
        val app = ApplicationContainer.open(analysisGateway = gateway(captured))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = createChapter(app, novelId, null, VariantScope.ORIGINAL, "t10-ch1")
        val draft1 = createFinal(app, ch1.chapterId, novelId, null, "t10-d1")

        val ref = ContinuationReference(ch1.chapterId, draft1.draftId)
        app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId), ref)

        assertTrue(captured.isNotEmpty())
        val rendered = captured.first()
        assertTrue(rendered.contains("【续篇来源】"), "Planner 输入应包含续篇来源标记")
        assertTrue(rendered.contains(ch1.chapterId.value), "Planner 输入应包含 sourceChapterId")
        assertTrue(rendered.contains(draft1.draftId.value), "Planner 输入应包含 sourceDraftId")
    }

    /* ---- helper ---- */

    private fun request(novelId: NovelId, variantId: VariantId? = null): UserWritingRequest = UserWritingRequest(
        requestId = RequestId(nextId("req")),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        variantId = variantId,
        scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    )

    private fun createChapter(
        app: ApplicationContainer, novelId: NovelId, variantId: VariantId?,
        scope: VariantScope, id: String,
    ): Chapter {
        val now = Clock.System.now()
        return app.chapterRepository.createNextChapter(
            Chapter(
                chapterId = ChapterId(id), novelId = novelId, variantId = variantId, scope = scope,
                title = "章", order = 0, status = ChapterStatus.PLANNED,
                createdAt = now, updatedAt = now,
            ),
        )
    }

    private fun createFinal(
        app: ApplicationContainer, chapterId: ChapterId, novelId: NovelId,
        variantId: VariantId?, id: String, status: DraftStatus = DraftStatus.FINAL,
    ): Draft {
        val now = Clock.System.now()
        val draft = Draft(
            draftId = DraftId(id), novelId = novelId, variantId = variantId,
            chapterId = chapterId, content = "正文", status = status,
            createdAt = now, updatedAt = now,
        )
        app.draftRepository.save(draft)
        return draft
    }
}