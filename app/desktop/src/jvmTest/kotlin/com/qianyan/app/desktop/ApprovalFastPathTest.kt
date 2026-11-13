package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterPostCommitState
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
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.story.Chapter
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.writing.Draft
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O108-F2（A-32）· 桌面端：批准走快路径、后台状态投影、刷新键守卫。
 */
class ApprovalFastPathTest {

    @Test
    fun `controller approves through the fast path and submits background work`() {
        val workflow = FastWorkflow()
        val controller = controllerFor(workflow)
        controller.load()

        controller.approveGate()

        assertEquals(listOf("approveDecision", "runPostCommit"), workflow.calls, "控制器必须先决议再投递后台（不得走整链 approve）")
        assertFalse(workflow.approveCalled, "UI 不得调用整链 approve")
        assertEquals(WriterPostCommitState.PENDING, controller.uiState.value.postCommitState, "决议后应显示「后台更新中」")
        assertFalse(controller.uiState.value.waitingForUser, "决议后按钮行必须恢复")
        assertEquals(HumanDecision.APPROVED, controller.uiState.value.pendingGateDecision)
    }

    @Test
    fun `post commit state projects updating and failed`() {
        val updating = FastWorkflow(phase = ChapterPhase.UPDATING_STORY)
        val c1 = controllerFor(updating)
        c1.load()
        assertEquals(WriterPostCommitState.PENDING, c1.uiState.value.postCommitState)

        val failed = FastWorkflow(phase = ChapterPhase.FAILED, failed = true)
        val c2 = controllerFor(failed)
        c2.load()
        assertEquals(WriterPostCommitState.FAILED, c2.uiState.value.postCommitState)
    }

    @Test
    fun `load kicks a queued background job without blocking the page`() {
        val workflow = FastWorkflow(phase = ChapterPhase.UPDATING_STORY)
        val controller = controllerFor(workflow)

        controller.load()

        assertTrue(
            "runPostCommit" in workflow.calls,
            "加载时若已有投递未执行的后台收尾（补偿/上次退出遗留），必须异步启动一次，否则会永远停在「后台更新中」",
        )
        assertTrue(controller.uiState.value.isLoaded, "启动后台任务不得阻塞页面加载")
        assertEquals(WriterPostCommitState.PENDING, controller.uiState.value.postCommitState)
    }

    @Test
    fun `refresh guards keep controller identity and use gate refresh key`() {
        val page = repoFile("app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt").readText()
        val onDecision = page.substringAfter("onDecision = {").substringBefore("},")
        assertTrue("gateRefreshKey++" in onDecision, "决议后必须只递增 gateRefreshKey")
        assertFalse("refreshKey++" in onDecision, "不得再递增 refreshKey（会重建控制器）")

        val controllerKey = page.substringAfter("LaunchedEffect(chapterId, state.novel?.novelId,").substringBefore(")")
        assertFalse("gateRefreshKey" in controllerKey, "控制器重建 key 不得包含 gateRefreshKey")

        val kuPanelKey = page.substringAfter("LaunchedEffect(state.novel?.novelId,").substringBefore(")")
        assertTrue("gateRefreshKey" in kuPanelKey, "KU / 故事状态面板必须随 gateRefreshKey 刷新")

        val controllerSrc = repoFile("app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/write/WriterController.kt").readText()
        assertTrue("retryPostCommit(" in controllerSrc, "控制器必须提供手动重试入口")
        assertFalse(
            "workflow.approve(chapterId)" in controllerSrc,
            "控制器不得再调用整链 approve（补偿只投递）",
        )
    }

    // ---------- fixtures ----------

    private class FastWorkflow(
        var phase: ChapterPhase = ChapterPhase.WAITING_CONFIRMATION,
        val waiting: Boolean = true,
        val failed: Boolean = false,
    ) : ChapterWorkflowGateway {
        val calls = mutableListOf<String>()
        var approveCalled = false

        override fun approveDecision(chapterId: ChapterId): ChapterWorkflowProgress {
            calls += "approveDecision"
            phase = ChapterPhase.UPDATING_STORY
            return progress(chapterId)
        }

        override fun runPostCommit(chapterId: ChapterId) {
            calls += "runPostCommit"
        }

        override fun ensurePostCommitJob(chapterId: ChapterId) {
            calls += "ensurePostCommitJob"
        }

        override fun postCommitState(chapterId: ChapterId): PostCommitStage = when {
            failed -> PostCommitStage.FAILED
            phase == ChapterPhase.UPDATING_STORY -> PostCommitStage.PENDING
            else -> PostCommitStage.NONE
        }

        override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress = progress(chapterId)

        private fun progress(chapterId: ChapterId): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = NOVEL_ID,
            variantId = null,
            phase = if (calls.isEmpty()) ChapterPhase.WAITING_CONFIRMATION else phase,
            waitingForUser = if (calls.isEmpty()) waiting else false,
            revisionCount = 0,
            draftId = null,
            pendingGateDecision = if (calls.isEmpty()) null else HumanDecision.APPROVED,
            failureMessage = if (failed) "知识更新失败（测试）" else null,
        )

        override fun startChapter(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            kind: WorkflowKind,
        ): ChapterWorkflowProgress = error("startChapter 不应被调用")

        override fun advance(chapterId: ChapterId): ChapterWorkflowProgress = error("advance 不应被调用")
        override fun resume(chapterId: ChapterId): ChapterWorkflowProgress = error("resume 不应被调用")
        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress {
            approveCalled = true
            error("整链 approve 不应被 UI 调用")
        }

        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress = error("reject 不应被调用")
        override fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress =
            error("proposeRewrite 不应被调用")
        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress = error("abandonRound 不应被调用")
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueToNextChapter 不应被调用")
    }

    private class StubWriter(private val workflow: FastWorkflow) : WriterGateway {
        override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext =
            WriterChapterContext(
                novelId = novelId,
                novelTitle = "T-O108-F2",
                chapterId = chapterId,
                chapterTitle = "第 1 章",
                draft = null,
                phase = ChapterPhase.UPDATING_STORY,
                waitingForUser = workflow.calls.isEmpty(),
                pendingProposal = if (workflow.calls.isEmpty()) {
                    WriterPendingProposal("原文", "候选", TextDiffer.diff("原文", "候选"))
                } else {
                    null
                },
            )

        override fun saveContent(draftId: DraftId, content: String): Draft = error("saveContent 不应被调用")
        override fun saveFirstDraft(chapterId: ChapterId, content: String): Draft = error("saveFirstDraft 不应被调用")
        override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueWriting 不应被调用")
        override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress =
            error("rewrite 不应被调用")
        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = error("rejectGate 不应被调用")
    }

    private fun controllerFor(workflow: FastWorkflow): WriterController =
        WriterController(
            novelId = NOVEL_ID,
            chapter = Chapter(
                chapterId = CHAPTER_ID,
                novelId = NOVEL_ID,
                title = "第 1 章",
                order = 1,
                createdAt = Clock.System.now(),
            ),
            gateway = StubWriter(workflow),
            workflow = workflow,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private fun repoFile(repoRelativePath: String): File {
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, repoRelativePath)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("未找到仓库文件：$repoRelativePath")
    }

    private companion object {
        val NOVEL_ID = NovelId("novel-t-o108-f2")
        val CHAPTER_ID = ChapterId("chapter-t-o108-f2")
    }
}
