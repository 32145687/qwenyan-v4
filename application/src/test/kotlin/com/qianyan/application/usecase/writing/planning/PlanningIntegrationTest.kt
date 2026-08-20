package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * P11.2 Planning 持久化集成测试。
 *
 * 全链路 + SQLite close/reopen：PLANNING Task 完成后，
 * 状态 = COMPLETED、Checkpoint(stage=PLANNING) 保留，重开后可 restore 解码回 ChapterPlan。
 * 经 TaskRunner.executePlanning（真实 Planner + Mock LLM）。
 */
class PlanningIntegrationTest {

    private fun container(url: String = JdbcSqliteDriver.IN_MEMORY, planJson: String = validPlanJson()): ApplicationContainer {
        val content = buildJsonObject { put("answer", planJson) }.toString()
        val gateway = MockLLMGateway { ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, content),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        ) }
        return ApplicationContainer.open(url, analysisGateway = gateway)
    }

    private fun validPlanJson() = "{\"chapterGoal\":\"跨过雷劫进入元婴\",\"expectedEvents\":[\"渡劫\",\"遇故人\"],\"endingHook\":\"裂缝显现\"}"

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-int"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    /* save → close → reopen → restoreCheckpoint → ChapterPlan 仍在 */
    @Test
    fun `planning checkpoint survives close and reopen`() {
        val tmp = Files.createTempFile("qianyan-p112-plan", ".db").toString()
        try {
            // 阶段一：PLANNING 任务执行到 COMPLETED
            val app = container("jdbc:sqlite:$tmp")
            val novelId = app.novels.createOriginal(title = "持剑")
            val id = app.tasks.create(TaskType.PLANNING)
            val plan = app.taskRunner.executePlanning(id, request(novelId))
            assertEquals(TaskStatus.COMPLETED, app.tasks.findById(id).status)

            // 阶段二：close/reopen 后状态 + checkpoint + ChapterPlan 均保留
            val reopened = container("jdbc:sqlite:$tmp")
            val task = reopened.tasks.findById(id)
            assertEquals(TaskStatus.COMPLETED, task.status)
            assertEquals(1f, task.progress)
            assertEquals(1, task.revisionCount)

            val restoredCheckpoint = reopened.tasks.restoreCheckpoint(id)
            assertEquals("PLANNING", restoredCheckpoint.stage)
            val restored = reopened.planning.chapterPlanFrom(restoredCheckpoint)
            assertNotNull(restored)
            assertEquals("跨过雷劫进入元婴", restored.chapterGoal)
            assertEquals(plan.chapterGoal, restored.chapterGoal)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }
}