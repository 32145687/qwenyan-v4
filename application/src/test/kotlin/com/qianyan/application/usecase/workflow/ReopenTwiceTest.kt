package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-O53 · 「继续写」重开的幂等性：连续点击只开 1 个新轮次（工作产物仍可用时的正常路径）。
 */
class ReopenTwiceTest {

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

    private fun open(): ApplicationContainer = ApplicationContainer.open(analysisGateway = okGateway())

    private fun seed(app: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = app.novels.createOriginal(title = "T-O53 幂等书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun workflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应存在 Workflow")

    private fun confirmations(app: ApplicationContainer, chapterId: ChapterId) =
        app.workflowRepository.listSteps(workflow(app, chapterId).workflowId)
            .count { it.phase == WorkflowStepPhase.CONFIRMATION }

    @Test
    fun `continuing twice opens exactly one new round`() {
        val app = open()
        val (novelId, chapterId) = seed(app)
        app.writerGateway.continueWriting(novelId, null, chapterId)
        val oldGateId = assertNotNull(workflow(app, chapterId).pendingGateId)
        assertEquals(1, confirmations(app, chapterId))

        // 用户拒绝当前提案
        app.workflowService.rejectGate(oldGateId, HumanDecision.REJECTED)
        assertEquals(HumanGateStatus.RESOLVED, app.workflowRepository.getGate(oldGateId)!!.status)

        // 第一次「继续写」：开新一轮 + 新 PENDING Gate
        app.workflowFacade.advance(chapterId)
        val afterFirst = workflow(app, chapterId)
        val newGateId = assertNotNull(afterFirst.pendingGateId, "重开后必须有新 Gate")
        assertNotEquals(oldGateId, newGateId, "不得复用已拒 Gate")
        assertEquals(WorkflowStatus.WAITING_HUMAN, afterFirst.status)
        assertEquals(2, confirmations(app, chapterId), "恰好多 1 个 CONFIRMATION 轮次")

        // 第二次「继续写」：Gate 仍 PENDING ⇒ 幂等 no-op，不得再开轮次
        app.workflowFacade.advance(chapterId)
        val afterSecond = workflow(app, chapterId)
        assertEquals(newGateId, afterSecond.pendingGateId, "不得换 Gate")
        assertEquals(WorkflowStatus.WAITING_HUMAN, afterSecond.status)
        assertEquals(2, confirmations(app, chapterId), "第二次不得新增轮次")
        assertTrue(app.commits.history(app.projects.projectOf(novelId).projectId).isEmpty(), "不得产生 Commit")
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
    }
}
