package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.TaskId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P11.2 Planning Task 生命周期测试（经 TaskRunner.executePlanning）。
 *
 * 验证：
 *  - PENDING → RUNNING → Planner(AgentRuntime → Mock LLM) → ChapterPlan
 *      → Checkpoint(stage=PLANNING) → COMPLETED；
 *  - 规划失败（空输出）→ FAILED，类型化 [ApplicationError.InvalidPlanningOutput]；
 *  - 非 PLANNING Task → InvalidOperation；Task 不存在 → TaskNotFound。
 * 全程 Mock LLM，无网络。
 */
class PlanningExecutionTest {

    private fun validPlanJson() = "{\"chapterGoal\":\"突破到筑基期并结识道友\",\"mainConflict\":\"conf-c\"," +
        "\"characterGoals\":{\"c1\":\"变强\"},\"expectedEvents\":[\"闭关\",\"遇险\"]," +
        "\"endingHook\":\"门被敲响\",\"constraints\":[\"无\"],\"forbiddenEvents\":[\"死亡\"]}"

    /** LLM 返回 `{"answer":"<PlanDto 正文>"}`，即 AgentRuntime Final 协议承载的 Planner 原文。 */
    private fun container(planJson: String): ApplicationContainer {
        val content = buildJsonObject { put("answer", planJson) }.toString()
        val gateway = MockLLMGateway { ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, content),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        ) }
        return ApplicationContainer.open(analysisGateway = gateway)
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-exec"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    /* 成功：PENDING → RUNNING → PLANNING Checkpoint → COMPLETED，ChapterPlan 可恢复 */
    @Test
    fun `planning task completes and checkpoint restores chapter plan`() {
        val app = container(validPlanJson())
        val novelId = app.novels.createOriginal(title = "测试仙侠")
        val id = app.tasks.create(TaskType.PLANNING)

        val plan = app.taskRunner.executePlanning(id, request(novelId))

        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)
        assertEquals(1f, app.tasks.findById(id).progress)
        assertEquals(1, app.tasks.findById(id).revisionCount)
        // Checkpoint(PLANNING) 可 restore，且可解码回 ChapterPlan
        val cp = app.tasks.restoreCheckpoint(id)
        assertEquals("PLANNING", cp.stage)
        val restored = app.planning.chapterPlanFrom(cp)
        assertNotNull(restored)
        assertEquals(plan.chapterGoal, restored.chapterGoal)
        assertEquals("突破到筑基期并结识道友", restored.chapterGoal)
    }

    /* 失败：空输出 → InvalidPlanningOutput → FAILED，失败原因记录、无可用 ChapterPlan */
    @Test
    fun `planning failure moves task to failed with typed error`() {
        val app = container("""{"chapterGoal":""}""")
        val novelId = app.novels.createOriginal(title = "测试")
        val id = app.tasks.create(TaskType.PLANNING)

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(id, request(novelId))
        }
        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error)

        val failed = app.tasks.findById(id)
        assertEquals(TaskStatus.FAILED, failed.status)
        assertNotNull(failed.error)
        assertTrue(failed.error!!.isNotBlank())
        // 失败路径不产生可解码的 ChapterPlan 快照
        val cp = app.tasks.findCheckpoints(id).lastOrNull()
        if (cp != null) {
            assertNull(app.planning.chapterPlanFrom(cp))
        }
    }

    /* 非 PLANNING Task 经 executePlanning → InvalidOperation（类型化拒绝，不误执行） */
    @Test
    fun `non planning task rejected by executePlanning`() {
        val app = container(validPlanJson())
        val id = app.tasks.create(TaskType.WRITING)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(id, request(NovelId("x")))
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
        assertEquals(TaskStatus.PENDING, app.tasks.findById(id).status)
    }

    /* 缺失 Task → TaskNotFound */
    @Test
    fun `missing task throws TaskNotFound`() {
        val app = container(validPlanJson())
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(TaskId("ghost"), request(NovelId("x")))
        }
        assertIs<ApplicationError.TaskNotFound>(ex.error)
    }
}