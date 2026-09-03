package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
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
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ChapterStatus
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
 * P12.0.1 P2/P3：Planning Chapter ownership 校验 + Writing Request↔Plan 一致性。
 */
class PlanningChapterOwnershipTest {

    private fun request(novelId: NovelId, variantId: VariantId? = null) = UserWritingRequest(
        requestId = RequestId("req-own"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        variantId = variantId,
    )

    private fun plan(chapterId: ChapterId?, novelId: NovelId, variantId: VariantId?, scope: VariantScope) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-x"),
        chapterId = chapterId,
        arcId = ArcId("arc"),
        actId = ActId("act"),
        novelId = novelId,
        variantId = variantId,
        scope = scope,
        chapterGoal = "目标",
    )

    private fun gateway(): com.qianyan.provider.impl.MockLLMGateway = MockLLMGateway { req ->
        val agent = req.messages.first { it.role == com.qianyan.provider.ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in agent -> """{"chapterGoal":"救下女主"}"""
            else -> """{"content":"正文。"}"""
        }
        com.qianyan.provider.ProviderResponse(
            message = com.qianyan.provider.ChatMessage(com.qianyan.provider.ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = com.qianyan.provider.Usage(10, 10, 20),
            finishReason = com.qianyan.provider.FinishReason.STOP,
        )
    }

    /* P2：规划创建的章节始终归属自身 novel/variant/scope（创建路径不产生跨实体章节） */
    @Test
    fun `planning created chapter belongs to its own scope`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "N")
        val plan = app.taskRunner.executePlanning(app.tasks.create(com.qianyan.model.task.TaskType.PLANNING), request(novelId))
        assertNotNull(plan.chapterId)
        val chapter = app.chapterRepository.findById(plan.chapterId!!)
        assertNotNull(chapter)
        assertEquals(novelId, chapter.novelId)
        assertEquals(plan.variantId, chapter.variantId)
        assertEquals(plan.scope, chapter.scope)
        // 归属正确：Original 章节只出现在 Original 查询
        assertEquals(listOf(chapter.chapterId), app.chapterRepository.listByNovel(novelId, null).map { it.chapterId })
    }

    /* P3：Writing request 与 plan 作用域不一致 → 在调 LLM 前类型化拒绝 */
    @Test
    fun `writing rejects request plan mismatch`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val n1 = app.novels.createOriginal(title = "N1")
        val n2 = app.novels.createOriginal(title = "N2")
        val planN2 = plan(null, n2, null, VariantScope.ORIGINAL)

        // novel 不一致
        val ex1 = assertFailsWith<ApplicationException> {
            app.taskRunner.executeWriting(app.tasks.create(com.qianyan.model.task.TaskType.WRITING), request(n1), planN2)
        }
        assertIs<ApplicationError.VariantMismatch>(ex1.error)

        // variant 不一致（plan 无 variant vs request 有 variant）
        val planOrig = plan(null, n1, null, VariantScope.ORIGINAL)
        val ex2 = assertFailsWith<ApplicationException> {
            app.taskRunner.executeWriting(app.tasks.create(com.qianyan.model.task.TaskType.WRITING), request(n1, VariantId("va")), planOrig)
        }
        assertIs<ApplicationError.VariantMismatch>(ex2.error)

        // scope 不一致（plan ORIGINAL vs request scope VARIANT）
        val ex3 = assertFailsWith<ApplicationException> {
            app.taskRunner.executeWriting(
                app.tasks.create(com.qianyan.model.task.TaskType.WRITING),
                request(n1).copy(scope = VariantScope.VARIANT),
                planOrig,
            )
        }
        assertIs<ApplicationError.VariantMismatch>(ex3.error)
    }
}