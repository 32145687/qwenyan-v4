package com.qianyan.application.usecase.agent

import com.qianyan.model.ChapterId
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowHumanGateId
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-7-1-A · NovelAgent 决策点行为测试（A4/A5/A6/A7）。 */
class DecisionPointBehaviorTest {

    @Test
    fun `A4 bridgeCandidate returns completed for already committed candidate without duplicate commit`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(waiting.sessionId)
        val artifactId = assertNotNull(waiting.artifactId)
        assertNotNull(pendingGateId(f, w.chapterId))

        f.app.workflowFacade.approve(w.chapterId)
        val historyBefore = f.app.commits.history(w.projectId)
        val draftsBefore = f.app.draftRepository.listByChapter(w.chapterId)
        assertEquals(1, historyBefore.size)

        val result = f.app.novelAgent.bridgeCandidate(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
        )

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertNull(result.failure)
        assertNotNull(result.commitId)
        assertEquals(historyBefore.size, f.app.commits.history(w.projectId).size, "不得重复 Commit")
        assertEquals(draftsBefore, f.app.draftRepository.listByChapter(w.chapterId), "Canonical 不得变化")
        f.close()
    }

    @Test
    fun `A5a refreshCandidate pending gate returns waiting human`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))

        val result = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = assertNotNull(waiting.sessionId)),
            assertNotNull(waiting.artifactId),
        )

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
        assertEquals(NovelAgentApprovalState.BRIDGED, result.approvalState)
        assertNull(result.failure)
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `A5b refreshCandidate approved without commit returns diagnostic unbridged`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val gateId = pendingGateId(f, w.chapterId)
        val gate = assertNotNull(f.app.workflowRepository.getGate(gateId))
        f.app.workflowRepository.updateGate(
            gate.copy(
                status = HumanGateStatus.RESOLVED,
                decision = HumanDecision.APPROVED,
                resolvedAt = FIXED_INSTANT,
                resolvedBy = "user",
            ),
        )

        val result = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = assertNotNull(waiting.sessionId)),
            assertNotNull(waiting.artifactId),
        )

        assertEquals(NovelAgentOutcome.CANDIDATE_UNBRIDGED, result.outcome)
        assertEquals(NovelAgentErrorCodes.CANDIDATE_APPROVED_NOT_COMMITTED, result.failure?.code)
        assertEquals(NovelAgentApprovalState.UNBRIDGED, result.approvalState)
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `A5c refreshCandidate without matching gate returns unbridged`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val workflow = assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId))
        f.app.workflowRepository.updateWorkflow(workflow.copy(pendingGateId = null))

        val result = f.app.novelAgent.refreshCandidate(
            request(w, "继续写第一章", sessionId = assertNotNull(waiting.sessionId)),
            assertNotNull(waiting.artifactId),
        )

        assertEquals(NovelAgentOutcome.CANDIDATE_UNBRIDGED, result.outcome)
        assertEquals(NovelAgentErrorCodes.BRIDGE_FAILED, result.failure?.code)
        assertEquals(NovelAgentApprovalState.UNBRIDGED, result.approvalState)
        f.close()
    }

    @Test
    fun `A6 reject committed candidate fails without new commit or cancel`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val waiting = f.app.novelAgent.run(request(w, "继续写第一章"))
        val sessionId = assertNotNull(waiting.sessionId)
        val artifactId = assertNotNull(waiting.artifactId)
        f.app.workflowFacade.approve(w.chapterId)
        val historyBefore = f.app.commits.history(w.projectId)
        val sessionBefore = f.app.agentSessions.sessionOf(sessionId).status

        val result = f.app.novelAgent.reject(
            request(w, "继续写第一章", sessionId = sessionId),
            artifactId,
            reason = "不应允许拒绝已提交候选",
        )

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.CANDIDATE_ALREADY_COMMITTED, result.failure?.code)
        assertEquals(historyBefore.size, f.app.commits.history(w.projectId).size, "不得新增 Commit")
        assertEquals(sessionBefore, f.app.agentSessions.sessionOf(sessionId).status, "不得 Cancel 已提交候选的会话")
        assertNotEquals(AgentSessionStatus.CANCELLED, f.app.agentSessions.sessionOf(sessionId).status)
        f.close()
    }

    @Test
    fun `A7 failed analysis stays completed and exposes passed false counts`() {
        val gateway = MockLLMGateway { request ->
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = if ("StoryCriticAgent" in system) {
                """{"passed":false,"issues":[{"field":"正文","severity":"ERROR","message":"需要修改"}]}"""
            } else {
                """{"content":"占位"}"""
            }
            ProviderResponse(
                message = ChatMessage(
                    ChatRole.ASSISTANT,
                    buildJsonObject { put("answer", body) }.toString(),
                ),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val f = novelAgentFixture(gateway)
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "分析第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertNull(result.workingDraftId)
        assertNull(result.artifactId)
        assertTrue(result.summary.contains("passed=false"), result.summary)
        assertTrue(result.summary.contains("问题 1 项"), result.summary)
        assertTrue(result.summary.contains("ERROR 1"), result.summary)
        assertEquals(1, f.app.draftRepository.listByChapter(w.chapterId).size, "只读分析不得写 Canonical")
        f.close()
    }

    private fun pendingGateId(f: NovelAgentFixture, chapterId: ChapterId): WorkflowHumanGateId =
        assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId)
}
