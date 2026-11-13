package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepAttempt
import com.qianyan.model.workflow.WorkflowStepAttemptId
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-O82b-F · 失败原因投影：FAILED/CANCELLED 时 [ChapterWorkflowProgress.failureMessage]
 * 取该 Workflow **最近一个 failed attempt** 的 `error_message`（单行化 + trim + ≤200）。
 *
 * 只验证**只读投影**：不改状态机 / 重试策略 / 仓储接口。
 */
class ChapterWorkflowFailureMessageTest {

    private fun response(body: String): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
        usage = Usage(1, 1, 2),
        finishReason = FinishReason.STOP,
    )

    /** Writer 抛 NonRetryable 错误：一次 attempt 即终止为 Workflow.FAILED。 */
    private fun failingWriter(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        when {
            "StoryWriterAgent" in system -> throw ProviderException.MalformedOutput("writer output malformed")
            "StoryCriticAgent" in system -> response("""{"passed":true,"issues":[]}""")
            else -> response("""{"chapterGoal":"g"}""")
        }
    }

    private fun request(): Triple<ApplicationContainer, NovelId, ChapterId> {
        val app = ApplicationContainer.open(analysisGateway = failingWriter())
        val novelId = app.novels.createOriginal(title = "T-O82b")
        val chapter = app.chapters.createNextChapter(title = "第 1 章", novelId = novelId)
        return Triple(app, novelId, chapter.chapterId)
    }

    /* ---------- 1. 真实失败链路：推进后的投影带真实 attempt 摘要 ---------- */

    @Test
    fun `failed workflow exposes last attempt message`() {
        val (app, novelId, chapterId) = request()
        app.workflowFacade.startChapter(novelId, null, chapterId)
        val wfId = app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.workflowId
            ?: error("startChapter 后应能按章节找到 Workflow")

        val progress = app.workflowFacade.advance(chapterId)

        assertEquals(ChapterPhase.FAILED, progress.phase, "NonRetryable writer 失败 ⇒ FAILED")
        val message = assertNotNull(progress.failureMessage, "FAILED 必须携带最近 failed attempt 的真实错误摘要")
        assertTrue("attempt 1 失败" in message, "摘要应保留 attempt 原文：$message")
        assertTrue(message.length <= 200, "摘要必须 ≤200 字：${message.length}")

        // 与库内 attempt 原文对照（同为单行化 + 截断后的值）。
        val writingStep = app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.WRITING }
        val attempt = app.workflowRepository.listAttempts(writingStep.stepId)
            .last { it.status == WorkflowAttemptStatus.FAILED }
        val raw = assertNotNull(attempt.errorMessage)
        assertEquals(raw.replace('\r', ' ').replace('\n', ' ').trim().take(200), message)
    }

    /* ---------- 2. 长文本：单行化 + 截断 ≤200 ---------- */

    @Test
    fun `failure message is normalized and capped`() {
        val app = ApplicationContainer.open(analysisGateway = failingWriter())
        val chapterId = ChapterId("c-t-o82b-cap")
        val stepId = seedFailed(app, WorkflowStatus.FAILED, chapterId, "第一行\n第二行\r\n" + "长".repeat(300))

        val message = assertNotNull(app.workflowFacade.getChapterProgress(chapterId).failureMessage)
        assertEquals(200, message.length, "≤200 字硬上限")
        assertTrue('\n' !in message && '\r' !in message, "摘要必须单行化")
        assertNotNull(app.workflowRepository.getStep(stepId))
    }

    /* ---------- 3. 无 attempt / 无 message：null ---------- */

    @Test
    fun `failed workflow without attempt message yields null`() {
        val app = ApplicationContainer.open(analysisGateway = failingWriter())
        val noMessage = ChapterId("c-t-o82b-null")
        seedFailed(app, WorkflowStatus.FAILED, noMessage, errorMessage = null)
        assertNull(
            app.workflowFacade.getChapterProgress(noMessage).failureMessage,
            "无 attempt message ⇒ null（由 UI 决定兜底文案）",
        )
    }

    /* ---------- 4. 成功 / 进行中：null ---------- */

    @Test
    fun `completed workflow has no failure message`() {
        val app = ApplicationContainer.open(analysisGateway = failingWriter())
        val chapterId = ChapterId("c-t-o82b-done")
        seedFailed(app, WorkflowStatus.COMPLETED, chapterId, "不应出现的失败文本")

        val progress = app.workflowFacade.getChapterProgress(chapterId)
        assertEquals(ChapterPhase.COMPLETED, progress.phase)
        assertNull(progress.failureMessage, "非 FAILED 一律不投影失败原因")
    }

    /* ---------- fixtures ---------- */

    /** 直接构造 durable 状态：Workflow(activeChapterId 保留) + 单个 FAILED/COMPLETED Step + 一条 failed attempt。 */
    private fun seedFailed(
        app: ApplicationContainer,
        status: WorkflowStatus,
        chapterId: ChapterId,
        errorMessage: String?,
    ): WorkflowStepId {
        val novelId = NovelId("n-${chapterId.value}")
        val wfId = WorkflowId("W-${chapterId.value}")
        val now = Clock.System.now()
        app.workflowRepository.createWorkflow(
            Workflow(
                workflowId = wfId, novelId = novelId, variantId = null, kind = WorkflowKind.WRITE_NOVEL,
                status = status, activeChapterId = chapterId, createdAt = now, updatedAt = now,
            ),
        )
        val stepId = WorkflowStepId("S-${chapterId.value}")
        app.workflowRepository.createStep(
            WorkflowStep(
                stepId = stepId, workflowId = wfId, chapterId = chapterId, phase = WorkflowStepPhase.WRITING,
                logicalStepKey = "${wfId.value}:${chapterId.value}:WRITING",
                status = if (status == WorkflowStatus.COMPLETED) WorkflowStepStatus.COMPLETED else WorkflowStepStatus.FAILED,
                createdAt = now, completedAt = now,
            ),
        )
        app.workflowRepository.createAttempt(
            WorkflowStepAttempt(
                attemptId = WorkflowStepAttemptId("A-${chapterId.value}"),
                stepId = stepId, attemptNo = 1,
                status = WorkflowAttemptStatus.FAILED,
                errorMessage = errorMessage,
                startedAt = now, completedAt = now,
            ),
        )
        return stepId
    }
}
