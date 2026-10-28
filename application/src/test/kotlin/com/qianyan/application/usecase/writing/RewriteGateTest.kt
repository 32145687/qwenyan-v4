package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0-2.1 · AI 改写受控链路：Critique → Revision → **WorkingDraft** → Human Gate → Change → Commit。
 *
 * 硬断言：批准前零 Draft 写入、零 Canonical；拒绝只结束当前 Gate；旧 Gate 永不复用。
 */
class RewriteGateTest {

    /* 1. 改写 = 工作产物 + 待决 Gate；不写 Draft / Canonical */
    @Test
    fun `rewrite produces a working draft and a pending gate without writing drafts or canonical`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId, base) = seed(app)
        val projectId = app.projects.projectOf(novelId).projectId

        val progress = app.writerGateway.rewrite(novelId, null, chapterId)

        assertEquals(ChapterPhase.WAITING_CONFIRMATION, progress.phase)
        assertTrue(progress.waitingForUser)
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "批准前不得新增 Draft")
        assertEquals("原正文", app.draftRepository.latestByChapter(chapterId)?.content)
        assertNull(app.canonicalRead.committedContent(chapterId))
        assertTrue(app.commits.history(projectId).isEmpty())

        val working = app.workingDrafts.list(projectId)
            .single { it.target.chapterId == chapterId && !it.status.isTerminal }
        assertEquals("AI 修订正文 v1", working.content)
        assertEquals(base.draftId, working.base.baseDraftId, "候选以当前正文为 Diff 基线")
        assertEquals(WorkingDraftStatus.VALIDATED, working.status)

        val context = app.writerGateway.loadContext(novelId, null, chapterId)
        assertEquals("原正文", context.draft?.content, "编辑区仍显示已提交正文")
        assertEquals("原正文", context.pendingProposal?.baseContent)
        assertEquals("AI 修订正文 v1", context.pendingProposal?.candidateContent)
        assertTrue(context.pendingProposal?.diff?.hasChanges == true)

        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId))
        assertEquals(WorkflowStatus.WAITING_HUMAN, wf.status)
        val gate = assertNotNull(app.workflowRepository.getGate(assertNotNull(wf.pendingGateId)))
        assertEquals(HumanGateStatus.PENDING, gate.status)
        assertNull(gate.draftId, "工作产物 Gate 不把 WorkingDraftId 冒充 DraftId")
        val confirmStep = assertNotNull(app.workflowRepository.getStep(assertNotNull(gate.stepId)))
        assertEquals(WorkflowStepPhase.CONFIRMATION, confirmStep.phase)
        val revisionStep = app.workflowRepository.listSteps(wf.workflowId)
            .last { it.phase == WorkflowStepPhase.REVISION }
        assertEquals(WorkflowStepStatus.COMPLETED, revisionStep.status)
        assertEquals("WORKING:${working.workingDraftId.value}", revisionStep.resultReference)
    }

    /* 2. 批准 = 既有 Change/Commit；重复批准幂等 */
    @Test
    fun `approving the rewrite gate commits the candidate exactly once`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId, base) = seed(app)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.rewrite(novelId, null, chapterId)
        val working = app.workingDrafts.list(projectId).single { it.target.chapterId == chapterId }
        val gateId = assertNotNull(
            app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId,
        )

        app.workflowFacade.approve(chapterId)
        app.workflowFacade.approve(chapterId)

        val history = app.commits.history(projectId)
        assertEquals(1, history.size, "重复批准不得重复提交")
        assertEquals(gateId, history.single().gateId)
        assertEquals("AI 修订正文 v1", history.single().resultingContent)
        assertEquals("AI 修订正文 v1", app.canonicalRead.committedContent(chapterId))
        assertEquals(WorkingDraftStatus.DISCARDED, app.workingDrafts.get(working.workingDraftId).status)
        val carrier = assertNotNull(app.draftRepository.latestByChapter(chapterId))
        assertEquals(base.draftId, carrier.previousDraftId, "新 Canonical 载体 lineage 指向原稿")
        assertEquals(DraftStatus.CONFIRMED, carrier.status)
        assertNull(app.writerGateway.loadContext(novelId, null, chapterId).pendingProposal)
    }

    /* 3. 拒绝只结束当前 Gate；Canonical 不变；旧 Gate 不可复用 */
    @Test
    fun `rejecting the rewrite gate leaves canonical unchanged and never reuses the gate`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId, _) = seed(app)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.rewrite(novelId, null, chapterId)
        val oldGateId = assertNotNull(
            app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId,
        )
        val working = app.workingDrafts.list(projectId).single { it.target.chapterId == chapterId }

        app.workflowFacade.reject(chapterId)
        app.workflowFacade.reject(chapterId) // 幂等

        assertFalse(app.workflowFacade.getChapterProgress(chapterId).waitingForUser)
        assertNull(app.canonicalRead.committedContent(chapterId), "拒绝后 Canonical 不变")
        assertTrue(app.commits.history(projectId).isEmpty(), "拒绝不产生 CommitHistory")
        assertEquals(1, app.draftRepository.listByChapter(chapterId).size, "拒绝不得写 Draft")
        val oldGate = assertNotNull(app.workflowRepository.getGate(oldGateId))
        assertEquals(HumanGateStatus.RESOLVED, oldGate.status)
        assertEquals(HumanDecision.REJECTED, oldGate.decision)
        assertEquals(WorkingDraftStatus.VALIDATED, app.workingDrafts.get(working.workingDraftId).status, "候选保留可审计")

        // 已拒绝 Gate 不能被批准
        assertFailsWith<ApplicationException> { app.workflowFacade.approve(chapterId) }

        // 显式推进才开启新的决议轮次：新 Gate 身份，旧 Gate 决议不改写
        val reentered = app.workflowFacade.advance(chapterId)
        assertTrue(reentered.waitingForUser, "显式推进后应停在新的待决 Gate")
        val newGateId = assertNotNull(
            app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId,
        )
        assertNotEquals(oldGateId, newGateId, "必须创建新 Gate，禁止复用旧 Gate")
        assertEquals(HumanDecision.REJECTED, app.workflowRepository.getGate(oldGateId)?.decision)
        assertNull(app.canonicalRead.committedContent(chapterId), "重新进入也不得自动写入 Canonical")
    }

    /* 4. 已有待决候选时拒绝再次改写（不产生第二个候选 / 无谓 LLM 调用） */
    @Test
    fun `rewrite while a gate is pending is rejected without generating another candidate`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId, _) = seed(app)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.rewrite(novelId, null, chapterId)
        val workingCount = app.workingDrafts.list(projectId).size

        val error = assertFailsWith<ApplicationException> {
            app.writerGateway.rewrite(novelId, null, chapterId)
        }

        assertTrue(error.message.orEmpty().contains("已有待确认"), error.message.orEmpty())
        assertEquals(workingCount, app.workingDrafts.list(projectId).size, "不得产生第二个候选")
    }

    /* 5. 完成后的章节再次改写：复用同一 Workflow，开启新 Gate，不产生第二个 Workflow */
    @Test
    fun `rewrite after completion reuses the chapter workflow and opens a new gate`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val (novelId, chapterId, _) = seed(app)
        val projectId = app.projects.projectOf(novelId).projectId

        app.writerGateway.rewrite(novelId, null, chapterId)
        app.workflowFacade.approve(chapterId)
        var guard = 0
        while (guard++ < 8 && app.workflowFacade.getChapterProgress(chapterId).phase != ChapterPhase.COMPLETED) {
            app.workflowFacade.advance(chapterId)
        }
        assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.getChapterProgress(chapterId).phase)
        val workflowId = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId)).workflowId
        val firstGateId = app.commits.history(projectId).single().gateId

        app.writerGateway.rewrite(novelId, null, chapterId)
        val secondGateId = assertNotNull(
            app.workflowRepository.getWorkflowByActiveChapter(chapterId)?.pendingGateId,
        )

        assertEquals(
            workflowId,
            assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId)).workflowId,
            "同一章节必须复用既有 Workflow",
        )
        assertNotEquals(firstGateId, secondGateId, "新一轮必须使用新 Gate")
        assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser)
        assertEquals(1, app.commits.history(projectId).size, "第二次改写批准前不得新增提交")
    }

    /* ---------- fixtures ---------- */

    private data class Seeded(val novelId: NovelId, val chapterId: ChapterId, val base: Draft)

    private fun seed(app: ApplicationContainer): Seeded {
        val novelId = app.novels.createOriginal(title = "改写测试书")
        // 改写路径不经过 Planning；显式建立 Project 运行态（WorkingDraft 的 Project 归属校验所需）。
        app.projects.openProject(novelId)
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val now = Clock.System.now()
        val base = Draft(
            draftId = DraftId("rewrite-base"),
            novelId = novelId,
            variantId = null,
            scope = VariantScope.ORIGINAL,
            chapterId = chapter.chapterId,
            content = "原正文",
            format = DraftFormat.CONTROLLED_MARKDOWN,
            status = DraftStatus.DRAFTING,
            createdAt = now,
            updatedAt = now,
        )
        app.draftRepository.save(base)
        return Seeded(novelId, chapter.chapterId, base)
    }

    private fun gateway(): MockLLMGateway {
        var revisions = 0
        return MockLLMGateway { req ->
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryWriterAgent" in system -> """{"content":"AI 生成正文"}"""
                // 每次修订产出不同正文，保证"改写 → 批准 → 再次改写"确有变化。
                "StoryRevisionAgent" in system -> """{"content":"AI 修订正文 v${++revisions}"}"""
                "StoryCriticAgent" in system -> """{"passed":true}"""
                "KnowledgeUpdateAgent" in system -> """{"changes":[]}"""
                else -> """{"chapterGoal":"推进主线"}"""
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(1, 1, 2),
                finishReason = FinishReason.STOP,
            )
        }
    }
}
