package com.qianyan.application.usecase.chapter

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.1.8 — Chapter1 Final → ContinuationReference → Chapter2 Planning → Chapter2 Writing 真实 E2E。
 *
 * 全部走真实 Application / Domain / Repository / Agent / Provider（Mock LLM 但非假数据）；验证
 * 章节间**上下文连续性**（Chapter1 Knowledge Update 沉淀的 Memory 事实能被 Chapter2 Planning /
 * Writing 的真实上下文读取），以及 Chapter / Novel / Variant / Draft 隔离。
 */
class Chapter12E2ETest {

    private val kuFact = "已突破金丹期"

    private fun gateway(plannerInputs: MutableList<String>, writerInputs: MutableList<String>): MockLLMGateway =
        MockLLMGateway { req ->
            val user = req.messages.first { it.role == ChatRole.USER }.content
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> {
                    plannerInputs.add(user)
                    """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}"""
                }
                "StoryWriterAgent" in system -> {
                    writerInputs.add(user)
                    """{"content":"正文被写就。"}"""
                }
                "StoryCriticAgent" in system -> """{"passed":true}"""
                "StoryRevisionAgent" in system -> """{"content":"修订后正文。"}"""
                "KnowledgeUpdateAgent" in system ->
                    """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"$kuFact"}]}"""
                else -> """{"content":"占位"}"""
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }

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

    private fun request(novelId: NovelId, variantId: VariantId? = null) = UserWritingRequest(
        requestId = RequestId("req-e2e"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        variantId = variantId,
        scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    )

    /** Chapter1 全链：Plan → Write → Critique → Revision → Finalize(FINAL) → Confirm(CONFIRMED) → Knowledge Update。 */
    private fun runChapter1(app: ApplicationContainer, ch: Chapter): DraftChain {
        val s = session(app, ch)
        val plan = s.plan()
        assertEquals(ch.chapterId, plan.chapterId)
        val a = s.write()
        assertEquals(ch.chapterId, a.chapterId)
        s.critique()
        val b = s.revise()
        assertEquals(a.draftId, b.previousDraftId) // lineage 保留
        val f = s.finalize()
        assertEquals(DraftStatus.FINAL, f.status)
        val c = s.confirm()
        assertEquals(DraftStatus.CONFIRMED, c.status)
        val ku = s.knowledgeUpdate()
        assertTrue(ku.applied.isNotEmpty())
        return DraftChain(initial = a, finalConfirmed = c, session = s)
    }

    /* 核心 E2E：Chapter1 Final → ContinuationReference → Chapter2 Planning → Chapter2 Writing（上下文连续） */
    @Test
    fun `chapter1 to chapter2 e2e keeps real continuity`() {
        val plannerInputs = mutableListOf<String>()
        val writerInputs = mutableListOf<String>()
        val app = ApplicationContainer.open(analysisGateway = gateway(plannerInputs, writerInputs))
        val novelId = app.novels.createOriginal(title = "仙侠")

        // ---- Chapter 1：全链 ----
        val ch1 = newChapter(app, novelId, null, "e2e-c1")
        assertEquals(novelId, ch1.novelId)
        assertEquals(null, ch1.variantId)
        val c1 = runChapter1(app, ch1)

        // Chapter1 Knowledge Update 已生效 → Story World 可读（Memory 事实）
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains(kuFact) })

        // ---- Chapter 2：createNextChapter（order = Ch1+1，同 Novel/Variant） ----
        val ch2 = newChapter(app, novelId, null, "e2e-c2")
        assertEquals(1, ch1.order)
        assertEquals(2, ch2.order)
        assertNotEquals(ch1.chapterId, ch2.chapterId)

        // ---- ContinuationReference：真实 Ch1 Final（已 CONFIRMED） ----
        val ref = ContinuationReference(ch1.chapterId, c1.finalConfirmed.draftId)
        val s2 = session(app, ch2)

        // Chapter2 Planning 经真实 ContinuationResolver（缺省不绕过）
        val plan2 = s2.plan(ref)
        assertEquals(ch2.chapterId, plan2.chapterId)

        // 连续性 ①：PlannerAgent 真实输入携带续篇来源 + Chapter1 已生效事实
        val p2 = plannerInputs.last()
        assertTrue(p2.contains("【续篇来源】"), "Ch2 planner 输入应携带续篇来源")
        assertTrue(p2.contains("sourceChapterId: ${ch1.chapterId.value}"))
        assertTrue(p2.contains(kuFact), "Ch2 planner 输入应读到 Ch1 KU 事实（上下文连续）")

        // 连续性 ②：PlanningContext 直接组装视图可读到 Ch1 事实 + 源章节
        val resolved = app.continuationResolver.resolve(request(novelId), ref)
        val ctx = app.planningContextAssembly.assemble(request(novelId), ref, resolved)
        assertEquals(ch1.chapterId, ctx.sourceChapter?.chapterId)
        assertEquals(c1.finalConfirmed.draftId, ctx.sourceFinalDraft?.draftId)
        assertTrue(ctx.memories.any { it.contains(kuFact) })

        // ---- Chapter2 Writing：真实 Draft 绑定 Ch2 ----
        val d2 = s2.write()
        assertEquals(ch2.chapterId, d2.chapterId)
        assertNotEquals(ch1.chapterId, d2.chapterId)
        // 连续性 ③：WriterAgent 真实输入读到 Ch1 事实（continuation 上下文未被丢弃）
        val w2 = writerInputs.last()
        assertTrue(w2.contains(kuFact), "Ch2 writer 输入应读到 Ch1 KU 事实")

        // 隔离：Ch1 Final/Confirmed Draft 未被覆盖（仍可读回、内容与确认时一致）
        assertEquals(c1.finalConfirmed.content, app.draftRepository.getById(c1.finalConfirmed.draftId)!!.content)
        assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(c1.finalConfirmed.draftId)!!.status)
    }

    /* Novel isolation：Novel A 的 Ch1 continuation 不能用于 Novel B 的 Ch2 */
    @Test
    fun `novel isolation blocks cross novel continuation`() {
        val app = ApplicationContainer.open(analysisGateway = gateway(mutableListOf(), mutableListOf()))
        val nA = app.novels.createOriginal(title = "A")
        val nB = app.novels.createOriginal(title = "B")
        val chA = newChapter(app, nA, null, "iso-a")
        runChapter1(app, chA)
        val chB = newChapter(app, nB, null, "iso-b")
        val sB = session(app, chB)
        val ref = ContinuationReference(chA.chapterId, chapter1FinalConfirmed(app, chA))
        val ex = assertFailsWith<ApplicationException> { sB.plan(ref) }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* Variant isolation：Original 的 Ch1 continuation 不能用于 Variant 的章节 */
    @Test
    fun `variant isolation blocks original to variant continuation`() {
        val app = ApplicationContainer.open(analysisGateway = gateway(mutableListOf(), mutableListOf()))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = newChapter(app, novelId, null, "iso-v-c1")
        runChapter1(app, ch1)
        val vB = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("vb")), "VB")
        val chV = newChapter(app, novelId, vB, "iso-v-vb")
        val sV = session(app, chV)
        val ref = ContinuationReference(ch1.chapterId, chapter1FinalConfirmed(app, ch1))
        val ex = assertFailsWith<ApplicationException> { sV.plan(ref) }
        assertIs<ApplicationError.InvalidContinuationSource>(ex.error)
    }

    /* Chapter/Draft 隔离：Ch1 Draft 不会成为 Ch2 Draft；Ch1 持久态不被 Ch2 覆盖 */
    @Test
    fun `chapter and draft isolation`() {
        val planner = mutableListOf<String>()
        val writer = mutableListOf<String>()
        val app = ApplicationContainer.open(analysisGateway = gateway(planner, writer))
        val novelId = app.novels.createOriginal(title = "仙侠")
        val ch1 = newChapter(app, novelId, null, "iso-d-c1")
        val s1 = session(app, ch1)
        val d1 = s1.let { it.plan(); it.write() }
        s1.finalize(); s1.confirm()

        val ch2 = newChapter(app, novelId, null, "iso-d-c2")
        val s2 = session(app, ch2)
        s2.plan(ContinuationReference(ch1.chapterId, s1.current().confirmedDraft!!.draftId))
        val d2 = s2.write()

        assertEquals(ch1.chapterId, d1.chapterId)
        assertEquals(ch2.chapterId, d2.chapterId)
        assertNotEquals(d1.draftId, d2.draftId)
        // Ch1 Draft 仍为自身内容（未被 Ch2 覆盖）
        assertTrue(app.draftRepository.getById(d1.draftId)!!.content.contains("正文被写就"))
    }

    private fun chapter1FinalConfirmed(app: ApplicationContainer, ch: Chapter): com.qianyan.model.DraftId {
        val latest = app.draftRepository.listByNovel(ch.novelId)
            .last { it.chapterId == ch.chapterId }
        return latest.draftId
    }

    private data class DraftChain(
        val initial: com.qianyan.model.writing.Draft,
        val finalConfirmed: com.qianyan.model.writing.Draft,
        val session: com.qianyan.application.usecase.chapter.ChapterWritingSession,
    )
}