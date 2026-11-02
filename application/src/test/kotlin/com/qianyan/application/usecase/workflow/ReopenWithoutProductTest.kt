package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
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
import kotlin.test.assertTrue

/**
 * T-O53 · 「继续写」重开轮次时工作产物已失效（跨重启）。
 *
 * 缺陷复现（wf `542e4116`）：强杀 → 重启 →「拒绝失效提案」→「继续写」，
 * `reopenRejectedRound` 建立了新 CONFIRMATION 轮次，但工作产物（进程内存 `WorkingDraftUseCases.store`）已丢失
 * ⇒ workflow 停在 `RUNNING` + `CONFIRMATION:PENDING` 且无 Gate。
 *
 * 修复断言：
 *  1) 被拒轮次产物失效 ⇒ **不建新轮次**、不改状态，明确类型化失败（不留 RUNNING 悬挂）；
 *  2) 历史脏数据（已 RUNNING + CONFIRMATION PENDING + 无产物）⇒ 显式终止为 FAILED，不留 RUNNING。
 * 两种情况都不得新增 Gate / Commit。
 */
class ReopenWithoutProductTest {

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
        val novelId = app.novels.createOriginal(title = "T-O53 测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) =
        app.projects.projectOf(novelId).projectId

    /** 情形 1：被拒 Gate + 产物随重启丢失 ⇒ 「继续写」不得建轮次、不得停在 RUNNING。 */
    @Test
    fun `rejected gate with a lost work product opens no round and stays out of RUNNING`() {
        val tmp = Files.createTempFile("qianyan-o53-reopen", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = openFile("jdbc:sqlite:$tmp", handles)
            val (novelId, chapterId) = seed(app)
            app.writerGateway.continueWriting(novelId, null, chapterId)
            val wf0 = workflow(app, chapterId)
            val gateId = assertNotNull(wf0.pendingGateId, "前置：应停在人工门")
            val confirmationsBefore = app.workflowRepository.listSteps(wf0.workflowId)
                .count { it.phase == WorkflowStepPhase.CONFIRMATION }

            // 用户「拒绝失效提案」
            app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
            assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(gateId)!!.status)
            assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(gateId)!!.decision)
            closeAll(handles) // 进程退出：内存工作产物丢失

            app = openFile("jdbc:sqlite:$tmp", handles)
            assertEquals(WorkflowStatus.WAITING_HUMAN, workflow(app, chapterId).status, "前置：拒绝后仍 WAITING_HUMAN")

            // 真实生产入口：「继续写」= WriterFacade.continueWriting → ChapterWorkflowFacade.advance
            val error = assertFailsWith<ApplicationException> { app.workflowFacade.advance(chapterId) }
            assertTrue(
                "工作产物已失效" in error.message.orEmpty(),
                "必须给出明确文案，实际：${error.message}",
            )

            val wf = workflow(app, chapterId)
            assertEquals(WorkflowStatus.WAITING_HUMAN, wf.status, "不得停在 RUNNING")
            assertEquals(gateId, wf.pendingGateId, "不得新建轮次 / 不得换 Gate")
            assertEquals(
                confirmationsBefore,
                app.workflowRepository.listSteps(wf.workflowId).count { it.phase == WorkflowStepPhase.CONFIRMATION },
                "不得新增 CONFIRMATION 轮次",
            )
            assertEquals(0, app.commits.history(projectId(app, novelId)).size, "不得新增 Commit")
            assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "不得新增载体 Draft")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /** 情形 2（历史脏数据）：已 RUNNING + CONFIRMATION PENDING + 无产物 ⇒ 显式终止为 FAILED，不留悬挂。 */
    @Test
    fun `running confirmation without a work product is terminated as failed`() {
        val tmp = Files.createTempFile("qianyan-o53-dirty", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = openFile("jdbc:sqlite:$tmp", handles)
            val (novelId, chapterId) = seed(app)
            app.writerGateway.continueWriting(novelId, null, chapterId)
            val wf0 = workflow(app, chapterId)
            val gateId = assertNotNull(wf0.pendingGateId)
            val confirmation = app.workflowRepository.listSteps(wf0.workflowId)
                .single { it.phase == WorkflowStepPhase.CONFIRMATION }
            // 构造旧缺陷留下的脏数据：拒绝 → RUNNING + 无 Gate + CONFIRMATION 仍 PENDING
            app.workflowService.rejectGate(gateId, HumanDecision.REJECTED)
            app.workflowRepository.updateWorkflow(
                wf0.copy(
                    status = WorkflowStatus.RUNNING,
                    pendingGateId = null,
                    currentStepId = confirmation.stepId,
                    updatedAt = Clock.System.now(),
                ),
            )
            closeAll(handles) // 进程退出：内存工作产物丢失

            app = openFile("jdbc:sqlite:$tmp", handles)
            assertEquals(WorkflowStatus.RUNNING, workflow(app, chapterId).status, "前置：脏数据仍 RUNNING")

            val error = assertFailsWith<ApplicationException> { app.workflowFacade.advance(chapterId) }
            assertTrue(
                "工作产物" in error.message.orEmpty(),
                "必须给出明确文案，实际：${error.message}",
            )

            // O-53-B：FAILED 终态会释放章节占位（`activeChapterId = null`），故终止后按 workflowId 回读。
            val wf = app.workflowRepository.getWorkflow(wf0.workflowId)!!
            assertEquals(WorkflowStatus.FAILED, wf.status, "必须显式终止，不得停留 RUNNING")
            assertEquals(null, wf.activeChapterId, "FAILED 终态必须释放章节占位")
            assertEquals(null, wf.pendingGateId, "终止后不得残留 pendingGateId")
            val steps = app.workflowRepository.listSteps(wf.workflowId)
            assertEquals(
                WorkflowStepStatus.FAILED,
                steps.single { it.phase == WorkflowStepPhase.CONFIRMATION }.status,
                "在途 CONFIRMATION 必须显式 FAILED",
            )
            assertEquals(0, app.commits.history(projectId(app, novelId)).size, "不得新增 Commit")
            assertEquals(0, app.draftRepository.listByChapter(chapterId).size, "不得新增载体 Draft")
            assertEquals(
                1,
                steps.count { it.phase == WorkflowStepPhase.CONFIRMATION },
                "不得新增 CONFIRMATION 轮次",
            )
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
    }
}
