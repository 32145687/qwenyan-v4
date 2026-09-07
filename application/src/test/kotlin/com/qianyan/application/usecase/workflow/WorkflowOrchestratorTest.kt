package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.workflow.AttemptErrorCategory
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderException
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.2 · Test F：Retry attempt history（真实 file-backed DB）。
 * WRITING Attempt 1 FAILED（Provider 可重试）→ Attempt 2 SUCCESS；重启后历史保留、Step 保持 COMPLETED、不增加第二个 Draft。
 */
class WorkflowOrchestratorTest {

    private var writerCalls = 0

    /** 全 Agent 有效响应、不失败的 gateway（供 Test G 完整单章链）。 */
    private fun okGateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in system -> """{"content":"正文已写就。"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in system -> """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
            else -> """{"chapterGoal":"g"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2), finishReason = FinishReason.STOP,
        )
    }

    /** Test G — 完整单章 Workflow：Planning→Writing→Critique→Finalize→HumanGate→Approve→KU→COMPLETED。 */
    @Test
    fun `testG full single chapter workflow completes`() {
        val app = ApplicationContainer.open(analysisGateway = okGateway())
        val novelId = app.novels.createOriginal(title = "T")
        val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
        val wfId = WorkflowId("W-G")
        app.workflowRepository.createWorkflow(
            Workflow(wfId, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, activeChapterId = ch.chapterId, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
        )

        // 前向：直到 WAITING_HUMAN
        val r1 = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, r1.status)
        val wf = app.workflowRepository.getWorkflow(wfId)!!
        assertNotNull(wf.pendingGateId)
        val gate = app.workflowRepository.getGate(wf.pendingGateId!!)!!

        // approve → RUNNING → KU → COMPLETED
        app.workflowOrchestrator.approveGate(gate.gateId)
        val r2 = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.COMPLETED, r2.status)
        assertEquals(WorkflowStatus.COMPLETED, app.workflowRepository.getWorkflow(wfId)!!.status)

        // 每步 COMPLETED + resultReference 非空
        val steps = app.workflowRepository.listSteps(wfId)
        assertTrue(steps.isNotEmpty())
        steps.forEach { assertEquals(WorkflowStepStatus.COMPLETED, it.status, "step ${it.phase} 未 COMPLETED") }
        steps.forEach { assertNotNull(it.resultReference, "step ${it.phase} 缺少 resultReference") }

        // Draft 定稿并确认；KU 沉淀进 Memory（真实持久化证据）
        val draft = app.draftRepository.latestByChapter(ch.chapterId)!!
        assertEquals(DraftStatus.CONFIRMED, draft.status)
        assertTrue(app.storyWorldContextResolver.resolve(novelId).memories.any { it.contains("突破金丹期") })

        // 幂等：COMPLETED 后再 runForward 不执行任何业务。
        val r3 = app.workflowOrchestrator.runForward(wfId)
        assertEquals(WorkflowStatus.COMPLETED, r3.status)
        assertEquals(1, app.draftRepository.listByChapter(ch.chapterId).size)
    }

    /** First Writer 调用抛可重试 Provider 错误，其后成功。 */
    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        when {
            "StoryWriterAgent" in system -> {
                writerCalls++
                if (writerCalls == 1) throw ProviderException.Timeout("writer transient")
                ProviderResponse(
                    message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", """{"content":"正文已写就。"}""") }.toString()),
                    usage = Usage(1, 1, 2), finishReason = FinishReason.STOP,
                )
            }
            "StoryCriticAgent" in system -> ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", """{"passed":true}""") }.toString()),
                usage = Usage(1, 1, 2), finishReason = FinishReason.STOP,
            )
            else -> ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", """{"chapterGoal":"g"}""") }.toString()),
                usage = Usage(1, 1, 2), finishReason = FinishReason.STOP,
            )
        }
    }

    private fun open(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val h = QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = gateway())
    }

    private fun closeAll(h: MutableList<QianyanDbHandle>) {
        h.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        h.clear()
    }

    private fun request(novelId: NovelId) = com.qianyan.model.context.UserWritingRequest(
        requestId = com.qianyan.model.RequestId("req-f"),
        intentType = com.qianyan.model.IntentType.PLAN,
        target = com.qianyan.model.context.TargetRef(com.qianyan.model.context.TargetKind.CHAPTER, null),
        planningScope = com.qianyan.model.PlanningScope.CHAPTER,
        baseNovelId = com.qianyan.model.BaseNovelId(novelId.value),
        scope = VariantScope.ORIGINAL,
    )

    @Test
    fun `testF retry attempt history persists and no duplicate draft`() {
        writerCalls = 0
        val tmp = Files.createTempFile("qianyan-wf-f", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "T")
            val ch = app.chapters.createNextChapter(title = "章", novelId = novelId)
            val wfId = WorkflowId("W-F")
            app.workflowRepository.createWorkflow(
                Workflow(wfId, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, activeChapterId = ch.chapterId, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
            )

            // 一次 runForward 完成 规划→写作(Attempt1 失败→Attempt2 成功)→评审→定稿→人闸(WAITING_HUMAN)
            val r1 = app.workflowOrchestrator.runForward(wfId)
            assertEquals(WorkflowStatus.WAITING_HUMAN, r1.status)
            // Attempt 1 → FAILED（RETRYABLE）；Attempt 2 → SUCCESS
            val step = app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.WRITING }
            val attempts = app.workflowRepository.listAttempts(step.stepId)
            assertEquals(2, attempts.size)
            assertEquals(WorkflowAttemptStatus.FAILED, attempts[0].status)
            assertEquals(AttemptErrorCategory.RETRYABLE, attempts[0].errorCategory)
            assertEquals(WorkflowAttemptStatus.COMPLETED, attempts[1].status)
            assertEquals(WorkflowStepStatus.COMPLETED, app.workflowRepository.getStep(step.stepId)!!.status)
            assertNotNull(step.resultReference)
            assertEquals(1, app.draftRepository.latestByChapter(ch.chapterId)?.let { listOf(it) }?.size ?: 0)
            // 不重复：只有一个 Draft
            assertEquals(1, app.draftRepository.listByChapter(ch.chapterId).size)

            // 重启恢复：Attempt 历史保留；Step 保持 COMPLETED；Writer 不再被调用。
            closeAll(handles)
            writerCalls = 0
            app = open("jdbc:sqlite:$tmp", handles)
            app.workflowOrchestrator.runForward(wfId)
            val stepAfter = app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.WRITING }
            assertEquals(2, app.workflowRepository.listAttempts(stepAfter.stepId).size)
            assertEquals(WorkflowStepStatus.COMPLETED, app.workflowRepository.getStep(stepAfter.stepId)!!.status)
            assertEquals(1, app.draftRepository.listByChapter(ch.chapterId).size)
            assertEquals(0, writerCalls) // 恢复不重调 Writer
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }
}