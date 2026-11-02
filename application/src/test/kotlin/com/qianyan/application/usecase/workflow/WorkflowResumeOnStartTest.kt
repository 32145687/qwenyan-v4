package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitResult
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.writing.DraftStatus
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O52-FIX · 中断补偿（跨"进程重启"）。
 *
 * 构造"批准已落定但收尾未执行"的持久脏数据：Gate `RESOLVED + APPROVED`、Commit 已写入、载体 `CONFIRMED`、
 * Workflow 已 `RUNNING` 且 `pendingGateId` 仍指向该 Gate、`CONFIRMATION` 仍 `PENDING`。
 * 关闭容器（= 进程退出，内存工作产物丢失）后重开，再走桌面打开章节时使用的补偿入口
 * （`ChapterWorkflowFacade.approve`，幂等收敛），必须收敛为 `COMPLETED` 且 Commit 仍为 1。
 */
class WorkflowResumeOnStartTest {

    private fun okGateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"推进主线"}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys ->
                """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"$KU_FACT"}]}"""
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
        val novelId = app.novels.createOriginal(title = "T-O52-FIX 重启补偿")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun projectId(app: ApplicationContainer, novelId: NovelId) =
        app.projects.projectOf(novelId).projectId

    @Test
    fun `running workflow with an approved gate is completed by compensation`() {
        val tmp = Files.createTempFile("qianyan-o52-resume", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = openFile("jdbc:sqlite:$tmp", handles)
            val (novelId, chapterId) = seed(app)
            app.writerGateway.continueWriting(novelId, null, chapterId)
            val wf0 = workflow(app, chapterId)
            val gateId = assertNotNull(wf0.pendingGateId, "前置：应停在人工门")
            val confirmation = app.workflowRepository.listSteps(wf0.workflowId)
                .single { it.phase == WorkflowStepPhase.CONFIRMATION }

            // 构造：Gate 已批准 + Commit 已落库 + 载体 CONFIRMED（= commitApproval 已完成步骤 1/2）
            val gate = app.workflowRepository.getGate(gateId)!!
            app.workflowRepository.updateGate(
                gate.copy(
                    status = HumanGateStatus.RESOLVED,
                    decision = HumanDecision.APPROVED,
                    resolvedAt = Clock.System.now(),
                    resolvedBy = "user",
                ),
            )
            val working = app.workingDrafts.list(projectId(app, novelId))
                .single { it.target.chapterId == chapterId && !it.status.isTerminal }
            val commitResult: CommitResult = commitDirectly(app, working.workingDraftId, gateId)
            app.draftRepository.save(
                app.draftRepository.getById(commitResult.resultingDraftId)!!
                    .copy(status = DraftStatus.CONFIRMED, updatedAt = Clock.System.now()),
            )
            // 构造：状态已置 RUNNING，但收尾前中断（pendingGateId / currentStepId / CONFIRMATION PENDING 均未清）
            app.workflowRepository.updateWorkflow(
                wf0.copy(
                    status = WorkflowStatus.RUNNING,
                    pendingGateId = gateId,
                    currentStepId = confirmation.stepId,
                    updatedAt = Clock.System.now(),
                ),
            )
            assertEquals(1, app.commits.history(projectId(app, novelId)).size, "前置：Commit 恰 1 条")
            closeAll(handles) // 进程退出：内存工作产物全部丢失

            app = openFile("jdbc:sqlite:$tmp", handles)
            assertEquals(WorkflowStatus.RUNNING, workflow(app, chapterId).status, "前置：脏数据仍 RUNNING")
            assertEquals(
                WorkflowStepStatus.PENDING,
                app.workflowRepository.listSteps(workflow(app, chapterId).workflowId)
                    .single { it.phase == WorkflowStepPhase.CONFIRMATION }.status,
                "前置：CONFIRMATION 仍 PENDING",
            )

            // 桌面打开章节时的补偿入口（幂等批准收敛）
            app.workflowFacade.approve(chapterId)

            val wf = workflow(app, chapterId)
            assertEquals(WorkflowStatus.COMPLETED, wf.status, "补偿后必须 COMPLETED")
            assertNull(wf.pendingGateId, "补偿后必须清空 pendingGateId")
            val steps = app.workflowRepository.listSteps(wf.workflowId)
            assertEquals(
                WorkflowStepStatus.COMPLETED,
                steps.single { it.phase == WorkflowStepPhase.CONFIRMATION }.status,
                "CONFIRMATION 必须 COMPLETED",
            )
            assertEquals(
                WorkflowStepStatus.COMPLETED,
                steps.single { it.phase == WorkflowStepPhase.KNOWLEDGE_UPDATE }.status,
                "KNOWLEDGE_UPDATE 必须被补建并 COMPLETED",
            )
            assertEquals(1, app.commits.history(projectId(app, novelId)).size, "补偿不得重复 Commit")
            assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(commitResult.resultingDraftId)!!.status)
            assertTrue(
                app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains(KU_FACT) },
                "补偿后 KU 正常执行",
            )

            // 再次补偿：保持幂等
            app.workflowFacade.approve(chapterId)
            assertEquals(WorkflowStatus.COMPLETED, workflow(app, chapterId).status)
            assertEquals(1, app.commits.history(projectId(app, novelId)).size, "重复补偿不得新增 Commit")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /** 构造"正式 Commit 已发生"的持久化事实：WorkingDraft → validate → Change Artifact → CommitUseCases.commit。 */
    private fun commitDirectly(
        app: ApplicationContainer,
        workingDraftId: com.qianyan.model.workingdraft.WorkingDraftId,
        gateId: com.qianyan.model.workflow.WorkflowHumanGateId,
    ): CommitResult {
        app.workingDrafts.validate(workingDraftId)
        val artifact = app.changes.prepare(workingDraftId)
        return app.commits.commit(artifact.projectId, artifact.artifactId, CommitApproval(gateId))
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
        const val KU_FACT = "已突破金丹期"
    }
}
