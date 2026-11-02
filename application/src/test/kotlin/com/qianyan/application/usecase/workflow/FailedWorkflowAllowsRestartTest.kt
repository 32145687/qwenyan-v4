package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O53-B · FAILED 终态**不得占位章节**（可重新发起）。
 *
 * 缺陷链（复核结论）：
 *  1) `executeConfirmation` 缺工作产物时把 Step / Workflow 置 FAILED，但未清空 `activeChapterId`；
 *  2) `ChapterWorkflowFacade.startChapter` 经 `getWorkflowByActiveChapter`（`singleWorkflowByActiveChapter`
 *     无状态过滤，LIMIT 1）恒命中该 FAILED 行 ⇒ 不建新 Workflow；
 *  3) `runForward` 对 FAILED 直接 done ⇒ 错误文案"请重新发起任务"实际无效。
 *
 * 修复断言（文件库 + 跨"进程重启"）：
 *  ① 脏数据 → `advance` 终止为 FAILED，且 `active_chapter_id` 被释放（为空）；
 *  ② 随后 `startChapter` → 新建**不同** `workflow_id`，状态 `CREATED`，章节占位指向新行；
 *  ③ 对新 Workflow `advance` → 新 PENDING Gate；批准后 COMPLETED，`CommitHistory` 恰 1（不重复）。
 */
class FailedWorkflowAllowsRestartTest {

    private fun okGateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"推进主线"}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys -> """{"changes":[]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun openFile(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val h = QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = okGateway())
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { h ->
            (h.driver as? JdbcSqliteDriver)?.getConnection()?.close()
        }
        handles.clear()
    }

    private fun seed(app: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = app.novels.createOriginal(title = "T-O53-B 重新发起")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) =
        app.projects.projectOf(novelId).projectId

    @Test
    fun `failed workflow releases the chapter and a new task starts a fresh workflow`() {
        val tmp = Files.createTempFile("qianyan-o53b-restart", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            /* ---------- 前置：制造"RUNNING + CONFIRMATION PENDING + 无 Gate"的历史脏数据 ---------- */
            var app = openFile("jdbc:sqlite:$tmp", handles)
            val (novelId, chapterId) = seed(app)
            app.writerGateway.continueWriting(novelId, null, chapterId)
            val failedWf0 = workflow(app, chapterId)
            val gateId = assertNotNull(failedWf0.pendingGateId, "前置：应停在人工门")
            val confirmation = app.workflowRepository.listSteps(failedWf0.workflowId)
                .single { it.phase == WorkflowStepPhase.CONFIRMATION }
            app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
            app.workflowRepository.updateWorkflow(
                failedWf0.copy(
                    status = WorkflowStatus.RUNNING,
                    pendingGateId = null,
                    currentStepId = confirmation.stepId,
                    updatedAt = Clock.System.now(),
                ),
            )
            closeAll(handles) // 进程退出：内存工作产物丢失

            app = openFile("jdbc:sqlite:$tmp", handles)
            assertEquals(WorkflowStatus.RUNNING, workflow(app, chapterId).status, "前置：脏数据仍 RUNNING")

            /* ---------- ① advance 终止为 FAILED，且释放 activeChapterId ---------- */
            val error = assertFailsWith<ApplicationException> { app.workflowFacade.advance(chapterId) }
            assertTrue("工作产物" in error.message.orEmpty(), "必须给出明确文案，实际：${error.message}")

            val failedWf = app.workflowRepository.getWorkflow(failedWf0.workflowId)!!
            assertEquals(WorkflowStatus.FAILED, failedWf.status, "必须显式终止")
            assertNull(failedWf.activeChapterId, "FAILED 终态必须释放章节占位（active_chapter_id 为空）")
            assertNull(failedWf.pendingGateId, "终止后不得残留 pendingGateId")
            assertEquals(
                WorkflowStepStatus.FAILED,
                app.workflowRepository.listSteps(failedWf.workflowId)
                    .single { it.phase == WorkflowStepPhase.CONFIRMATION }.status,
                "在途 CONFIRMATION 必须显式 FAILED",
            )
            assertNull(
                app.workflowRepository.getWorkflowByActiveChapter(chapterId),
                "占位释放后，按章节查询不得再命中 FAILED 行",
            )

            /* ---------- ② startChapter 新建不同 Workflow（CREATED） ---------- */
            app.workflowFacade.startChapter(novelId, null, chapterId, WorkflowKind.WRITE_NOVEL)
            val restarted = workflow(app, chapterId)
            assertTrue(
                restarted.workflowId != failedWf0.workflowId,
                "必须新建不同的 workflow_id（旧=${failedWf0.workflowId.value}，新=${restarted.workflowId.value}）",
            )
            assertEquals(WorkflowStatus.CREATED, restarted.status, "新 Workflow 应为 CREATED")
            assertEquals(chapterId, restarted.activeChapterId, "active_chapter_id 应指向新行")
            assertEquals(failedWf0.workflowId, failedWf.workflowId)

            /* ---------- ③ 新 Workflow 可正常推进 → 新 PENDING Gate → 批准 → COMPLETED ---------- */
            app.workflowFacade.advance(chapterId)
            val running = workflow(app, chapterId)
            assertEquals(restarted.workflowId, running.workflowId, "推进后仍应是新 Workflow")
            assertEquals(WorkflowStatus.WAITING_HUMAN, running.status, "应进入新的人工门")
            val newGateId = assertNotNull(running.pendingGateId, "必须建立新的 PENDING Gate")
            assertTrue(newGateId.value != gateId.value, "不得复用旧的已拒绝 Gate")
            val newGate = assertNotNull(app.workflowRepository.getGate(newGateId))
            assertEquals(0, app.commits.history(projectId(app, novelId)).size, "批准前不得产生 Commit")

            app.workflowFacade.approve(chapterId)
            val done = workflow(app, chapterId)
            assertEquals(WorkflowStatus.COMPLETED, done.status, "批准后必须 COMPLETED")
            assertNull(done.pendingGateId, "完成后不得残留 pendingGateId")
            assertEquals(
                1,
                app.commits.history(projectId(app, novelId)).size,
                "新一轮恰产生 1 个 Commit（旧 FAILED 轮 0 个，不重复）",
            )
            assertNotNull(newGate.stepId)

            // 旧 FAILED Workflow 保持原样（未被复用 / 未复活）
            val oldStill = app.workflowRepository.getWorkflow(failedWf0.workflowId)!!
            assertEquals(WorkflowStatus.FAILED, oldStill.status)
            assertNull(oldStill.activeChapterId)
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /**
     * T-O53-C · `termFailed`（重试耗尽 / NonRetryable）同样必须释放章节占位。
     *
     * 触发路径（确定性、不调 LLM）：REVISION Step 无前置 CRITIQUE ⇒ 执行抛 `InvalidOperation`
     * （attempt 错误类别为空，按 NON_RETRYABLE 处理）⇒ `shouldRetryStep` 终止 ⇒ `termFailed`。
     */
    @Test
    fun `retry exhaustion failure releases the chapter so a new workflow can start`() {
        val tmp = Files.createTempFile("qianyan-o53c-termfailed", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val (novelId, chapterId) = seed(app)
            val now = Clock.System.now()
            val stuckId = WorkflowId("wf-o53c-stuck")
            app.workflowRepository.createWorkflow(
                Workflow(
                    workflowId = stuckId, novelId = novelId, kind = WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.RUNNING, activeChapterId = chapterId, createdAt = now, updatedAt = now,
                ),
            )
            val stepId = WorkflowStepId("step-o53c-revision")
            app.workflowRepository.createStep(
                WorkflowStep(
                    stepId = stepId, workflowId = stuckId, chapterId = chapterId,
                    phase = WorkflowStepPhase.REVISION,
                    logicalStepKey = "${stuckId.value}:${chapterId.value}:REVISION:R0",
                    status = WorkflowStepStatus.PENDING, roundNo = 0, createdAt = now,
                ),
            )

            app.workflowFacade.advance(chapterId)

            val failed = app.workflowRepository.getWorkflow(stuckId)!!
            assertEquals(WorkflowStatus.FAILED, failed.status, "重试耗尽/NonRetryable 必须终止为 FAILED")
            assertNull(failed.activeChapterId, "termFailed 必须释放章节占位（active_chapter_id 为空）")
            assertEquals(
                WorkflowStepStatus.FAILED,
                app.workflowRepository.getStep(stepId)!!.status,
                "终止时该 Step 必须 FAILED",
            )

            app.workflowFacade.startChapter(novelId, null, chapterId, WorkflowKind.WRITE_NOVEL)
            val restarted = workflow(app, chapterId)
            assertTrue(
                restarted.workflowId != stuckId,
                "必须新建不同的 workflow_id（旧=${stuckId.value}，新=${restarted.workflowId.value}）",
            )
            assertEquals(WorkflowStatus.CREATED, restarted.status, "新 Workflow 应为 CREATED")

            app.workflowFacade.advance(chapterId)
            val running = workflow(app, chapterId)
            assertEquals(WorkflowStatus.WAITING_HUMAN, running.status, "新 Workflow 应能推进到人工门")
            assertNotNull(running.pendingGateId, "必须建立 PENDING Gate")
            assertEquals(0, app.commits.history(projectId(app, novelId)).size, "推进不得产生 Commit")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
    }
}
