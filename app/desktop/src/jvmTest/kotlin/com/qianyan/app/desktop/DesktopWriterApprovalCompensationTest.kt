package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.workflow.PostCommitStage
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.application.usecase.writing.WriterPendingProposal
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.writing.Draft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

/**
 * T-O52-FIX · 桌面打开章节时的批准收尾补偿（O-52）。
 *
 * 场景：Gate 已批准但 Workflow 未收尾（批准后进程中断 / 强制重启）。打开正文页时 `WriterController.load`
 * 必须只走**快路径**（`approveDecision`：决议 + 提交 + 幂等投递），**绝不**再走整链 `approve`
 * （T-O108/A-32：整链会同步跑 WMU + KU，数十秒）。已提交（RUNNING）由后台收尾器负责，补偿不得重复投递；
 * 待决 Gate（waitingForUser）/ 已拒绝 / 无 Gate 一律不得触发。
 */
class DesktopWriterApprovalCompensationTest {

    @Test
    fun `load compensates an approved but unfinished workflow`() {
        val workflow = CompensationWorkflow(
            initialDecision = HumanDecision.APPROVED,
            initialPhase = ChapterPhase.WAITING_CONFIRMATION,
        )
        val controller = controller(workflow)

        controller.load()

        assertEquals(1, workflow.decisionCalls, "已批准但未提交（仍停在人工门）⇒ 打开章节必须快路径补投一次")
        assertEquals(0, workflow.approveCalls, "补偿不得调用整链 approve（会同步跑 WMU/KU，数十秒）")
        assertTrue(controller.uiState.value.isLoaded)
    }

    @Test
    fun `load leaves an already submitted approval to the background finalizer`() {
        val workflow = CompensationWorkflow(
            initialDecision = HumanDecision.APPROVED,
            initialPhase = ChapterPhase.UPDATING_STORY,
        )

        controller(workflow).load()

        assertEquals(0, workflow.decisionCalls, "已提交（RUNNING）⇒ 收尾归后台任务，补偿不得重复投递")
        assertEquals(0, workflow.approveCalls, "补偿不得调用整链 approve")
    }

    @Test
    fun `load does not re-enqueue when a background job already exists`() {
        val failed = CompensationWorkflow(
            initialDecision = HumanDecision.APPROVED,
            initialPhase = ChapterPhase.WAITING_CONFIRMATION,
            stage = PostCommitStage.FAILED,
        )
        controller(failed).load()
        assertEquals(0, failed.decisionCalls, "已有失败的后台任务 ⇒ 只能人工重试；补偿再投递会每次开页都重跑 LLM")

        val pending = CompensationWorkflow(
            initialDecision = HumanDecision.APPROVED,
            initialPhase = ChapterPhase.WAITING_CONFIRMATION,
            stage = PostCommitStage.PENDING,
        )
        controller(pending).load()
        assertEquals(0, pending.decisionCalls, "已有进行中的后台任务 ⇒ 补偿不得重复投递")
    }

    @Test
    fun `load does not compensate a pending or rejected gate`() {
        val pending = CompensationWorkflow(initialDecision = HumanDecision.PENDING)
        controller(pending).load()
        assertEquals(0, pending.decisionCalls, "待决 Gate 不得因打开章节自动批准")
        assertEquals(0, pending.approveCalls, "待决 Gate 不得走整链 approve")

        val rejected = CompensationWorkflow(initialDecision = HumanDecision.REJECTED)
        controller(rejected).load()
        assertEquals(0, rejected.decisionCalls, "已拒绝 Gate 不得因打开章节自动重开/批准")
        assertEquals(0, rejected.approveCalls, "已拒绝 Gate 不得走整链 approve")
    }

    // ---------- fixtures ----------

    /** 只读进度投影 + 可观测的决议调用；决议后视为已收敛（decision → null、phase → COMPLETED）。 */
    private class CompensationWorkflow(
        private val initialDecision: HumanDecision?,
        private val initialPhase: ChapterPhase = ChapterPhase.WAITING_CONFIRMATION,
        private val stage: PostCommitStage = PostCommitStage.NONE,
    ) : ChapterWorkflowGateway {
        var decisionCalls: Int = 0
        var approveCalls: Int = 0
        private var resolved: Boolean = false

        override fun postCommitState(chapterId: ChapterId): PostCommitStage = stage

        override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = NovelId("novel-t-o52"),
            variantId = null,
            phase = if (resolved) ChapterPhase.COMPLETED else initialPhase,
            waitingForUser = initialDecision == HumanDecision.PENDING && !resolved,
            revisionCount = 0,
            draftId = null,
            pendingGateDecision = if (resolved) null else initialDecision,
        )

        override fun approveDecision(chapterId: ChapterId): ChapterWorkflowProgress {
            decisionCalls++
            resolved = true
            return getChapterProgress(chapterId)
        }

        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress {
            approveCalls++
            resolved = true
            return getChapterProgress(chapterId)
        }

        override fun startChapter(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId, kind: WorkflowKind): ChapterWorkflowProgress =
            error("startChapter 不应被调用")
        override fun advance(chapterId: ChapterId): ChapterWorkflowProgress = error("advance 不应被调用")
        override fun resume(chapterId: ChapterId): ChapterWorkflowProgress = error("resume 不应被调用")
        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress = error("reject 不应被调用")
        override fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress =
            error("proposeRewrite 不应被调用")
        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress = error("abandonRound 不应被调用")
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueToNextChapter 不应被调用")
    }

    private class ReadOnlyWriterGateway : WriterGateway {
        override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext =
            WriterChapterContext(
                novelId = novelId,
                novelTitle = "T-O52-FIX 测试作品",
                chapterId = chapterId,
                chapterTitle = "第 1 章",
                draft = null,
                phase = ChapterPhase.COMPLETED,
                waitingForUser = false,
                pendingProposal = null as WriterPendingProposal?,
            )

        override fun saveContent(draftId: DraftId, content: String): Draft = error("saveContent 不应被调用")

        override fun saveFirstDraft(chapterId: ChapterId, content: String): Draft = error("saveFirstDraft 不应被调用")
        override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueWriting 不应被调用")
        override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress =
            error("rewrite 不应被调用")
        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = error("rejectGate 不应被调用")
    }

    private fun controller(workflow: ChapterWorkflowGateway): WriterController = WriterController(
        novelId = NovelId("novel-t-o52"),
        chapter = Chapter(
            chapterId = ChapterId("chapter-t-o52"),
            novelId = NovelId("novel-t-o52"),
            title = "第 1 章",
            order = 1,
            createdAt = Clock.System.now(),
        ),
        gateway = ReadOnlyWriterGateway(),
        workflow = workflow,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )
}
