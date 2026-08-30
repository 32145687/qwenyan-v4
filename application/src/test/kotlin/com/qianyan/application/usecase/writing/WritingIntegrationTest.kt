package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
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
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * P11.3 Writing 持久化集成测试（核心链路，全 Mock）。
 *
 * 1. PLANNING Task → ChapterPlan Checkpoint
 * 2. 恢复 ChapterPlan
 * 3. WRITING Task → WriterAgent → Mock LLM → Draft
 * 4. DraftRepository.save
 * 5. Writing Checkpoint
 * 6. COMPLETED
 * 7. close database
 * 8. reopen database
 * 9. 读取 Draft
 * 10. restore Checkpoint
 * 11. 内容保持一致
 *
 * 全程 Mock LLM，无网络；SQLite close/reopen 验证 Draft 与 Checkpoint 持久化一致。
 */
class WritingIntegrationTest {

    private fun container(url: String = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY): ApplicationContainer {
        val gateway = MockLLMGateway { request ->
            // 同一 Mock 同时回答 Planning（PlanDto）与 Writing（DraftDto）两种协议：
            // 按 SYSTEM 提示中的 Agent 名区分 —— StoryPlannerAgent → 规划 JSON；StoryWriterAgent → 正文 JSON。
            val prompt = request.messages.joinToString("\n") { m -> m.content }
            val answer = if (prompt.contains("StoryPlannerAgent")) {
                "{\"chapterGoal\":\"跨过雷劫进入元婴\",\"expectedEvents\":[\"渡劫\"],\"endingHook\":\"裂缝显现\"}"
            } else {
                "{\"content\":\"雷劫之下，元婴凝成，裂缝随即显现。\"}"
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", answer) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        return ApplicationContainer.open(url, analysisGateway = gateway)
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-int"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    @Test
    fun `planning to writing full chain survives close and reopen`() {
        val tmp = Files.createTempFile("qianyan-p113-writing", ".db").toString()
        try {
            // 1) PLANNING Task → ChapterPlan Checkpoint → COMPLETED
            val app = container("jdbc:sqlite:$tmp")
            val novelId = app.novels.createOriginal(title = "持剑")
            val planTaskId = app.tasks.create(TaskType.PLANNING)
            val plan = app.taskRunner.executePlanning(planTaskId, request(novelId))
            assertEquals(TaskStatus.COMPLETED, app.tasks.findById(planTaskId).status)
            assertEquals("跨过雷劫进入元婴", plan.chapterGoal)

            // 2) 恢复 ChapterPlan（PLANNING Checkpoint）
            val restoredPlan = app.planning.chapterPlanFrom(app.tasks.restoreCheckpoint(planTaskId))
            assertNotNull(restoredPlan)
            assertEquals(plan.chapterGoal, restoredPlan.chapterGoal)

            // 3) WRITING Task → WriterAgent → Mock LLM → Draft
            val writeTaskId = app.tasks.create(TaskType.WRITING)
            val draft = app.taskRunner.executeWriting(writeTaskId, request(novelId), restoredPlan)
            assertNotNull(draft)
            assertEquals("雷劫之下，元婴凝成，裂缝随即显现。", draft.content)
            assertEquals(restoredPlan.chapterPlanId, draft.planId)

            // 4/5/6) Draft 已保存 + WRITING Checkpoint + COMPLETED
            assertEquals(TaskStatus.COMPLETED, app.tasks.findById(writeTaskId).status)
            assertEquals("WRITING", app.tasks.restoreCheckpoint(writeTaskId).stage)

            // 7) close（容器丢弃，SQLite 文件保留）→ 8) reopen
            val reopened = container("jdbc:sqlite:$tmp")

            // 9) 读取 Draft（close/reopen 后数据一致）
            val persisted = reopened.draftRepository.getById(draft.draftId)
            assertNotNull(persisted, "close/reopen 后 Draft 应可读")
            assertEquals(draft.content, persisted.content)
            assertEquals(draft.novelId, persisted.novelId)
            assertEquals(draft.planId, persisted.planId)
            assertEquals(draft.sourceModel, persisted.sourceModel)

            // 10) restore Checkpoint → 11) 解码 Draft，内容保持一致
            val cp = reopened.tasks.restoreCheckpoint(writeTaskId)
            assertEquals("WRITING", cp.stage)
            val restoredDraft = reopened.writingExecution.draftFrom(cp)
            assertNotNull(restoredDraft)
            assertEquals(persisted.content, restoredDraft.content)
            assertEquals(persisted.draftId, restoredDraft.draftId)
            assertEquals(persisted.planId, restoredDraft.planId)

            // 收尾：planning 链路不受 writing 影响（不回归）
            assertEquals(TaskStatus.COMPLETED, reopened.tasks.findById(planTaskId).status)
            assertEquals(1, reopened.draftRepository.listByNovel(novelId).size)
        } finally {
            Files.deleteIfExists(Path(tmp))
        }
    }

    @Test
    fun `writing checkpoint is distinct from planning checkpoint`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "双阶段")
        val planTaskId = app.tasks.create(TaskType.PLANNING)
        val plan = app.taskRunner.executePlanning(planTaskId, request(novelId))

        val writeTaskId = app.tasks.create(TaskType.WRITING)
        app.taskRunner.executeWriting(writeTaskId, request(novelId), plan)

        // 两 Task 各自保存类型化 checkpoint：Planning → PLANNING，Writing → WRITING
        assertEquals("PLANNING", app.tasks.restoreCheckpoint(planTaskId).stage)
        assertEquals("WRITING", app.tasks.restoreCheckpoint(writeTaskId).stage)
        assertEquals(1, app.tasks.findCheckpoints(planTaskId).size)
        assertEquals(1, app.tasks.findCheckpoints(writeTaskId).size)

        // Planning 在 Writing 之后仍可正常执行（全新 Task，验证无回归）
        val planTask2 = app.tasks.create(TaskType.PLANNING)
        val plan2 = app.taskRunner.executePlanning(planTask2, request(novelId))
        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(planTask2).status)
        assertEquals("跨过雷劫进入元婴", plan2.chapterGoal)
        // 已 COMPLETED 的 Task 不可重跑（状态机拒绝，不回归 P8.2）
        assertFailsWith<com.qianyan.application.error.ApplicationException> {
            app.taskRunner.executePlanning(planTaskId, request(novelId))
        }
    }
}