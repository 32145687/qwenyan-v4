package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterUiState
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.application.usecase.writing.WriterPendingProposal
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.story.Chapter
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

/**
 * T-O48 · Gate 决议防抖。
 *
 * 背景：拒绝成功后动作栏立即切换为「保存 / 让 Qianyan 修改 / 继续写」；双击「拒绝」的第二击
 * 在同一坐标命中「继续写」，`advance` 会开启新一轮并新建 PENDING Gate（G8 实测）。
 *
 * 约束：只锁生成类动作 500ms，不改按钮文案 / 审批链 / Workflow 语义；不影响保存与只读。
 */
class DesktopWriterDecisionCooldownTest {

    @Test
    fun `decision lock disables generation but not save`() {
        val locked = WriterUiState(
            isLoaded = true,
            draftId = DraftId("draft-cooldown-ui"),
            draftStatus = DraftStatus.DRAFTING.name,
            decisionLocked = true,
        )
        assertFalse(locked.canGenerate, "防抖窗口内不得继续写")
        assertFalse(locked.canRewrite, "防抖窗口内不得改写")
        assertTrue(locked.canSave, "保存/只读不受防抖影响")

        val open = locked.copy(decisionLocked = false)
        assertTrue(open.canGenerate, "到期后恢复继续写")
        assertTrue(open.canRewrite, "到期后恢复改写")
    }

    @Test
    fun `reject success blocks immediate continue and recovers after cooldown`() {
        var now = 1_000L
        val gate = GateState(waitingForUser = true, proposal = proposal())
        val gateway = CooldownGateway(gate)
        val workflow = CooldownWorkflow(gate)
        val controller = controller(gateway, workflow) { now }

        controller.load()
        assertTrue(controller.uiState.value.canReject)

        controller.rejectGate()
        val locked = controller.uiState.value
        assertFalse(locked.waitingForUser, "拒绝已生效")
        assertTrue(locked.decisionLocked, "决议成功必须进入 500ms 防抖窗口")
        assertFalse(locked.canGenerate)
        assertFalse(locked.canRewrite)
        assertTrue(locked.canSave, "保存不受防抖影响")

        controller.continueWriting()
        assertEquals(0, gateway.continueCalls, "防抖窗口内不得调用 gateway / advance")
        assertFalse(controller.uiState.value.isGenerating)

        controller.rewrite()
        assertEquals(0, gateway.rewriteCalls, "防抖窗口内不得改写")

        now += 501L
        controller.continueWriting()
        assertEquals(1, gateway.continueCalls, "500ms 后继续写必须恢复（不是永久禁用）")
        assertFalse(controller.uiState.value.decisionLocked)
    }

    @Test
    fun `approve success also starts the cooldown`() {
        val gate = GateState(waitingForUser = true, proposal = proposal())
        val gateway = CooldownGateway(gate)
        val workflow = CooldownWorkflow(gate)
        val controller = controller(gateway, workflow) { 5_000L }

        controller.load()
        controller.approveGate()

        val state = controller.uiState.value
        assertFalse(state.waitingForUser)
        assertTrue(state.decisionLocked, "批准成功同样需要防抖")
        assertFalse(state.canGenerate)

        controller.continueWriting()
        assertEquals(0, gateway.continueCalls)
    }

    // ---------- fixtures ----------

    private class GateState(
        var waitingForUser: Boolean,
        var proposal: WriterPendingProposal?,
    )

    private fun proposal() = WriterPendingProposal(
        baseContent = "原文",
        candidateContent = "候选",
        diff = TextDiffer.diff("原文", "候选"),
    )

    private fun controller(
        gateway: WriterGateway,
        workflow: ChapterWorkflowGateway,
        nowMillis: () -> Long,
    ): WriterController = WriterController(
        novelId = NovelId("novel-t-o48"),
        chapter = Chapter(
            chapterId = ChapterId("chapter-t-o48"),
            novelId = NovelId("novel-t-o48"),
            title = "第 1 章 · 防抖",
            order = 1,
            createdAt = Clock.System.now(),
        ),
        gateway = gateway,
        workflow = workflow,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
        nowMillis = nowMillis,
    )

    private class CooldownGateway(private val gate: GateState) : WriterGateway {
        var continueCalls: Int = 0
        var rewriteCalls: Int = 0

        override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext =
            WriterChapterContext(
                novelId = novelId,
                novelTitle = "T-O48 测试作品",
                chapterId = chapterId,
                chapterTitle = "第 1 章 · 防抖",
                draft = cooldownDraft(novelId, chapterId),
                phase = if (gate.waitingForUser) ChapterPhase.WAITING_CONFIRMATION else ChapterPhase.NOT_STARTED,
                waitingForUser = gate.waitingForUser,
                pendingProposal = gate.proposal?.takeIf { gate.waitingForUser },
            )

        override fun saveContent(draftId: DraftId, content: String): Draft = error("saveContent 不应被调用")

        override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
            continueCalls++
            return progress(chapterId)
        }

        override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
            rewriteCalls++
            return progress(chapterId)
        }

        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = error("rejectGate 不应被调用")

        private fun progress(chapterId: ChapterId) = ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = NovelId("novel-t-o48"),
            variantId = null,
            phase = if (gate.waitingForUser) ChapterPhase.WAITING_CONFIRMATION else ChapterPhase.NOT_STARTED,
            waitingForUser = gate.waitingForUser,
            revisionCount = 0,
            draftId = null,
        )
    }

    private class CooldownWorkflow(private val gate: GateState) : ChapterWorkflowGateway {
        override fun startChapter(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            kind: WorkflowKind,
        ): ChapterWorkflowProgress = error("startChapter 不应被调用")

        override fun advance(chapterId: ChapterId): ChapterWorkflowProgress = error("advance 不应被调用")
        override fun resume(chapterId: ChapterId): ChapterWorkflowProgress = error("resume 不应被调用")

        override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = NovelId("novel-t-o48"),
            variantId = null,
            phase = if (gate.waitingForUser) ChapterPhase.WAITING_CONFIRMATION else ChapterPhase.NOT_STARTED,
            waitingForUser = gate.waitingForUser,
            revisionCount = 0,
            draftId = null,
        )

        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress {
            gate.waitingForUser = false
            return getChapterProgress(chapterId)
        }

        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress {
            gate.waitingForUser = false
            return getChapterProgress(chapterId)
        }

        override fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress =
            error("proposeRewrite 不应被调用")
        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress = error("abandonRound 不应被调用")
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueToNextChapter 不应被调用")
    }
}

private fun cooldownDraft(novelId: NovelId, chapterId: ChapterId): Draft = Draft(
    draftId = DraftId("draft-cooldown"),
    novelId = novelId,
    variantId = null,
    scope = VariantScope.ORIGINAL,
    chapterId = chapterId,
    content = "防抖测试正文",
    format = null,
    status = DraftStatus.DRAFTING,
    createdAt = Clock.System.now(),
    updatedAt = Clock.System.now(),
)
