package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.2 · Recovery & HITL 幂等（Test B / C / D）。
 * 真实文件 DB 模拟 App 崩溃/重建；不依赖内存 Session。
 * 证明用「真实 SQLite 状态」：gate.status/decision 落库、KU 沉淀进 Memory、LLM 调用一次。
 * 语义：at-least-once + durable result idempotency（不声称 exactly-once）。
 */
class WorkflowRecoveryHitlTest {

    private var llmCalls = 0

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        llmCalls++
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = if ("KnowledgeUpdateAgent" in system) {
            """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
        } else {
            """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun open(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }

    private fun newDraft(novelId: NovelId, chapterId: ChapterId, id: String): Draft {
        val now = Clock.System.now()
        return Draft(
            draftId = DraftId(id), novelId = novelId, chapterId = chapterId,
            content = "正文", status = DraftStatus.FINAL, // 定稿（FINAL）后才可 Confirm（真实 semantics）
            createdAt = now, updatedAt = now,
        )
    }

    /* Test B — resultReference 指向已持久化 Draft，Step 未 COMPLETED → 重启恢复复用，不重调 LLM。 */
    @Test
    fun `testB writing result reuse after restart`() {
        llmCalls = 0
        val tmp = Files.createTempFile("qianyan-wf-b", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open("jdbc:sqlite:$tmp", handles)
            val novelId = NovelId("n-b"); val ch = ChapterId("c-b")
            val wfId = WorkflowId("W-B"); val stepId = WorkflowStepId("S-B")
            app.workflowRepository.createWorkflow(
                Workflow(wfId, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.CREATED, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
            )
            app.workflowRepository.createStep(
                WorkflowStep(stepId, wfId, ch, WorkflowStepPhase.WRITING, "W-B:c-b:WRITING", WorkflowStepStatus.RUNNING, createdAt = Clock.System.now()),
            )
            val draft = newDraft(novelId, ch, "d-b")
            app.draftRepository.save(draft)
            // Draft 已落库 + resultReference 已指向 Draft，但 Step 未 COMPLETED（模拟 Writer 成功后崩溃）。
            app.workflowRepository.updateStep(app.workflowRepository.getStep(stepId)!!.copy(resultReference = draft.draftId.value))
            closeAll(handles)

            app = open("jdbc:sqlite:$tmp", handles)
            val outcome = app.workflowService.resumeWorkflow(wfId)

            assertEquals(draft.draftId.value, outcome.reusedResult)
            assertTrue(outcome.stepCompleted)
            assertEquals(WorkflowStepStatus.COMPLETED, app.workflowRepository.getStep(stepId)!!.status)
            assertEquals(1, app.draftRepository.listByNovel(novelId).size)
            assertEquals(0, llmCalls) // 恢复绝不重调 LLM
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** 构造一个 WAITING_HUMAN + pendingGate 的 Workflow。 */
    private fun gateFixture(app: ApplicationContainer, id: String): Triple<WorkflowId, Draft, com.qianyan.model.workflow.WorkflowHumanGate> {
        val novelId = NovelId("n-$id"); val ch = ChapterId("c-$id")
        val wfId = WorkflowId("W-$id"); val stepId = WorkflowStepId("S-$id")
        app.workflowRepository.createWorkflow(
            Workflow(wfId, novelId, null, WorkflowKind.WRITE_NOVEL, status = WorkflowStatus.WAITING_HUMAN, createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
        )
        app.workflowRepository.createStep(
            WorkflowStep(stepId, wfId, ch, WorkflowStepPhase.CONFIRMATION, "W-$id:c-$id:CONFIRMATION", WorkflowStepStatus.RUNNING, createdAt = Clock.System.now()),
        )
        val draft = newDraft(novelId, ch, "d-$id")
        app.draftRepository.save(draft)
        val gate = app.workflowService.createPendingGate(wfId, stepId, draft.draftId)
        return Triple(wfId, draft, gate)
    }

    /* Test C — WAITING_HUMAN + pendingGate 跨重启保持；approve 后 KU 一次（Memory 沉淀）。 */
    @Test
    fun `testC human gate persists across restart and approve runs ku once`() {
        llmCalls = 0
        val tmp = Files.createTempFile("qianyan-wf-c", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = open("jdbc:sqlite:$tmp", handles)
            val (wfId, draft, gate) = gateFixture(app, "c")
            closeAll(handles)

            app = open("jdbc:sqlite:$tmp", handles)
            val resume = app.workflowService.resumeWorkflow(wfId)
            assertEquals(WorkflowStatus.WAITING_HUMAN, resume.status)
            assertNotNull(resume.pendingGate)
            assertEquals(HumanGateStatus.PENDING, resume.pendingGate!!.status)

            assertIs<GateOutcome.Approved>(app.workflowService.approveGate(gate.gateId))
            val g = app.workflowRepository.getGate(gate.gateId)!!
            assertEquals(HumanGateStatus.RESOLVED, g.status)
            assertEquals(HumanDecision.APPROVED, g.decision)
            assertEquals(1, llmCalls) // KU（LLM）一次
            // KU 真实沉淀进 Memory（DB 证据）
            assertTrue(app.storyWorldContextResolver.resolve(draft.novelId).memories.any { it.contains("突破金丹期") })
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* Test D — approve ×3 → 仅一次 resolve（DB gate 状态）+ 一次 KU + 单次推进。 */
    @Test
    fun `testD triple approve is idempotent`() {
        llmCalls = 0
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (wfId, draft, gate) = gateFixture(app, "d")

        app.workflowService.approveGate(gate.gateId)
        assertIs<GateOutcome.AlreadyApproved>(app.workflowService.approveGate(gate.gateId))
        assertIs<GateOutcome.AlreadyApproved>(app.workflowService.approveGate(gate.gateId))

        val g = app.workflowRepository.getGate(gate.gateId)!!
        assertEquals(HumanGateStatus.RESOLVED, g.status)
        assertEquals(HumanDecision.APPROVED, g.decision)
        assertEquals(1, llmCalls) // 仅首次批准触发 KU
        assertTrue(app.storyWorldContextResolver.resolve(draft.novelId).memories.any { it.contains("突破金丹期") })
        assertEquals(WorkflowStatus.WAITING_HUMAN, app.workflowRepository.getWorkflow(wfId)!!.status)
    }
}