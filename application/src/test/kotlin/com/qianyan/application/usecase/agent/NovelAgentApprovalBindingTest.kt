package com.qianyan.application.usecase.agent

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workingdraft.WorkingDraftStatus
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P0-3B · Step 1/2：候选身份绑定 + 正式 Workflow 桥接（D2/D3/D4/D5/D7）。
 *
 * 全部使用真实 ApplicationContainer + Mock LLM；桥接 / Gate / Workflow 均为既有实现。
 */
class NovelAgentApprovalBindingTest {

    /* ---------------- Step 1：会话身份 ---------------- */

    @Test
    fun `resume requires the original session id`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val artifactId = assertNotNull(waiting.artifactId)
        val gateId = pendingGateId(f, w.chapterId)

        val result = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = null),
            artifactId,
            CommitApproval(gateId),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.SESSION_REQUIRED, result.failure?.code)
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `resume rejects a different session in the same project`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val artifactId = assertNotNull(waiting.artifactId)
        val gateId = pendingGateId(f, w.chapterId)
        val otherSession = f.app.agentSessions.startSession(w.novelId).sessionId

        val result = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = otherSession),
            artifactId,
            CommitApproval(gateId),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.CANDIDATE_SESSION_MISMATCH, result.failure?.code)
        assertEquals(WorkingDraftStatus.VALIDATED, f.app.workingDrafts.get(assertNotNull(waiting.workingDraftId)).status)
        assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(gateId)?.status)
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `reject rejects a different session in the same project`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val otherSession = f.app.agentSessions.startSession(w.novelId).sessionId

        val result = f.app.novelAgent.reject(
            request(w, "继续写第一章", sessionId = otherSession),
            assertNotNull(waiting.artifactId),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.CANDIDATE_SESSION_MISMATCH, result.failure?.code)
        assertEquals(
            WorkingDraftStatus.VALIDATED,
            f.app.workingDrafts.get(assertNotNull(waiting.workingDraftId)).status,
            "身份不匹配不得丢弃候选",
        )
        f.close()
    }

    @Test
    fun `valid session cannot operate an artifact of another project`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val otherProject = f.app.projects.createProject(title = "书B")
        val otherRequest = request(w, "继续写第一章", sessionId = waiting.sessionId)
            .copy(projectId = otherProject.projectId)

        val result = f.app.novelAgent.resume(
            otherRequest,
            assertNotNull(waiting.artifactId),
            CommitApproval(pendingGateId(f, w.chapterId)),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, result.failure?.code)
        f.close()
    }

    @Test
    fun `completed session cannot resume an uncommitted candidate`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(waiting.sessionId)
        f.app.agentSessions.activate(sessionId)
        f.app.agentSessions.complete(sessionId)

        val result = f.app.novelAgent.resume(
            request(w, "继续写第一章", sessionId = sessionId),
            assertNotNull(waiting.artifactId),
            CommitApproval(pendingGateId(f, w.chapterId)),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.SESSION_STATE_INVALID, result.failure?.code)
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    /* ---------------- Step 2：正式 Workflow 桥接 ---------------- */

    @Test
    fun `bridge creates exactly one workflow and gate bound to the candidate`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val beforeWorkflows = countRows(f.handle, "Workflow")

        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, waiting.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, waiting.approvalState)
        assertEquals(beforeWorkflows + 1, countRows(f.handle, "Workflow"))
        val wf = assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId))
        assertEquals(WorkflowStatus.WAITING_HUMAN, wf.status)
        val gate = assertNotNull(f.app.workflowRepository.getGate(assertNotNull(wf.pendingGateId)))
        assertEquals(HumanGateStatus.PENDING, gate.status)
        assertEquals(
            "WORKING:${assertNotNull(waiting.workingDraftId).value}",
            f.app.workflowFacade.getChapterProgress(w.chapterId).pendingWorkingRef,
        )
        f.close()
    }

    @Test
    fun `bridging the same candidate twice is idempotent`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(waiting.sessionId)
        val artifactId = assertNotNull(waiting.artifactId)
        val wfBefore = assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId))
        val gateBefore = assertNotNull(wfBefore.pendingGateId)
        val workflowsBefore = countRows(f.handle, "Workflow")

        val retry = f.app.novelAgent.bridgeCandidate(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
        )

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, retry.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, retry.approvalState)
        assertEquals(workflowsBefore, countRows(f.handle, "Workflow"), "重复桥接不得创建竞争 Workflow")
        val wfAfter = assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId))
        assertEquals(wfBefore.workflowId, wfAfter.workflowId)
        assertEquals(gateBefore, wfAfter.pendingGateId, "同一候选应定位既有 Gate")
        f.close()
    }

    @Test
    fun `a second candidate is unbridged while another gate is pending`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val first = f.app.novelAgent.run(request(w, "继续写第一章"))
        assertEquals(NovelAgentApprovalState.BRIDGED, first.approvalState)

        val second = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.CANDIDATE_UNBRIDGED, second.outcome)
        assertEquals(NovelAgentApprovalState.UNBRIDGED, second.approvalState)
        assertEquals(NovelAgentErrorCodes.BRIDGE_FAILED, second.failure?.code)
        assertEquals(
            AgentSessionStatus.PAUSED,
            f.app.agentSessions.sessionOf(assertNotNull(second.sessionId)).status,
            "未桥接候选保留在 PAUSED 会话中，供显式重试/丢弃",
        )
        assertEquals(
            WorkingDraftStatus.VALIDATED,
            f.app.workingDrafts.get(assertNotNull(second.workingDraftId)).status,
            "未桥接不得丢弃候选",
        )
        assertEquals(
            "WORKING:${assertNotNull(first.workingDraftId).value}",
            f.app.workflowFacade.getChapterProgress(w.chapterId).pendingWorkingRef,
            "原候选的 Gate 不得被覆盖",
        )
        f.close()
    }

    @Test
    fun `retry succeeds after the blocking gate is formally rejected`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val first = f.app.novelAgent.run(request(w, "继续写第一章"))
        val second = f.app.novelAgent.run(request(w, "继续写第一章"))
        assertEquals(NovelAgentOutcome.CANDIDATE_UNBRIDGED, second.outcome)
        val oldGateId = pendingGateId(f, w.chapterId)

        // 正式拒绝原候选的 Gate（唯一正式拒绝入口）
        f.app.workflowFacade.reject(w.chapterId)
        assertEquals(HumanGateStatus.RESOLVED, f.app.workflowRepository.getGate(oldGateId)?.status)
        assertEquals(HumanDecision.REJECTED, f.app.workflowRepository.getGate(oldGateId)?.decision)

        val retry = f.app.novelAgent.bridgeCandidate(
            request(w, "继续写第一章", sessionId = assertNotNull(second.sessionId)),
            assertNotNull(second.artifactId),
        )

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, retry.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, retry.approvalState)
        val newGateId = pendingGateId(f, w.chapterId)
        assertNotEquals(oldGateId, newGateId, "必须创建新 Gate，旧 Gate 决议保持 REJECTED")
        assertEquals(HumanDecision.REJECTED, f.app.workflowRepository.getGate(oldGateId)?.decision)
        assertEquals(
            "WORKING:${assertNotNull(second.workingDraftId).value}",
            f.app.workflowFacade.getChapterProgress(w.chapterId).pendingWorkingRef,
        )
        f.close()
    }

    @Test
    fun `after formal reject a new run produces a new candidate and a new gate`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val first = f.app.novelAgent.run(request(w, "继续写第一章"))
        val firstSession = assertNotNull(first.sessionId)
        val firstDraft = assertNotNull(first.workingDraftId)
        val firstArtifact = assertNotNull(first.artifactId)
        val firstGate = pendingGateId(f, w.chapterId)

        // 正式拒绝 + 协调收敛旧候选
        f.app.workflowFacade.reject(w.chapterId)
        val converged = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = firstSession),
            firstArtifact,
        )
        assertEquals(NovelAgentOutcome.CANCELLED, converged.outcome)
        assertEquals(WorkingDraftStatus.DISCARDED, f.app.workingDrafts.get(firstDraft).status)

        // 重新发起：新会话 → 新 WorkingDraft / Artifact → 新 Gate（旧 Gate 决议保持 REJECTED）
        val second = f.app.novelAgent.run(request(w, "重写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, second.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, second.approvalState)
        assertNotEquals(firstDraft, second.workingDraftId)
        assertNotEquals(firstArtifact, second.artifactId)
        val secondGate = pendingGateId(f, w.chapterId)
        assertNotEquals(firstGate, secondGate)
        assertEquals(HumanDecision.REJECTED, f.app.workflowRepository.getGate(firstGate)?.decision)
        assertEquals(
            "WORKING:${assertNotNull(second.workingDraftId).value}",
            f.app.workflowFacade.getChapterProgress(w.chapterId).pendingWorkingRef,
        )
        f.close()
    }

    @Test
    fun `in-flight workflow makes the candidate explicitly unbridged`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val wfId = WorkflowId("wf-inflight-p0-3b")
        f.app.workflowRepository.createWorkflow(
            Workflow(
                workflowId = wfId,
                novelId = w.novelId,
                kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.RUNNING,
                activeChapterId = w.chapterId,
                createdAt = FIXED_INSTANT,
                updatedAt = FIXED_INSTANT,
            ),
        )
        f.app.workflowRepository.createStep(
            WorkflowStep(
                stepId = WorkflowStepId("step-inflight-writing"),
                workflowId = wfId,
                chapterId = w.chapterId,
                phase = WorkflowStepPhase.WRITING,
                logicalStepKey = "$wfId:${w.chapterId.value}:WRITING",
                status = WorkflowStepStatus.RUNNING,
                createdAt = FIXED_INSTANT,
            ),
        )
        val gatesBefore = countRows(f.handle, "WorkflowHumanGate")

        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.CANDIDATE_UNBRIDGED, result.outcome)
        assertEquals(NovelAgentApprovalState.UNBRIDGED, result.approvalState)
        assertEquals(NovelAgentErrorCodes.BRIDGE_FAILED, result.failure?.code)
        assertEquals(gatesBefore, countRows(f.handle, "WorkflowHumanGate"), "in-flight 时不得创建 Gate")
        assertEquals(
            WorkingDraftStatus.VALIDATED,
            f.app.workingDrafts.get(assertNotNull(result.workingDraftId)).status,
            "失败必须保留候选，不静默丢弃",
        )
        f.close()
    }

    @Test
    fun `candidate is explicitly lost after container reopen`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(waiting.sessionId)
        val artifactId = assertNotNull(waiting.artifactId)
        val gateId = pendingGateId(f, w.chapterId)

        // 同一 SQLite driver，新 ApplicationContainer：持久状态仍在，内存 WorkingDraft / Artifact 丢失
        val reopened = ApplicationContainer.fromDriver(f.handle.driver, routingGateway())
        val result = reopened.novelAgent.resume(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            CommitApproval(gateId),
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.ARTIFACT_NOT_FOUND, result.failure?.code)
        assertTrue(
            result.failure?.detail.orEmpty().contains("失效") || result.failure?.detail.orEmpty().contains("不存在"),
            result.failure?.detail.orEmpty(),
        )
        assertEquals(0, reopened.commits.history(w.projectId).size)
        f.close()
    }

    /* ---------------- helpers ---------------- */

    private fun pendingGateId(f: NovelAgentFixture, chapterId: com.qianyan.model.ChapterId) =
        assertNotNull(
            assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(chapterId)).pendingGateId,
        )
}
