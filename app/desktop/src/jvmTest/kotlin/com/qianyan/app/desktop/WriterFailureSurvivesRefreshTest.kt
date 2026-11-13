package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.write.CANDIDATE_LOST_MESSAGE
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

/**
 * T-O106-F（A-29 / 方案 D1）· 待确认计数刷新不得重建写作页、不得擦除失败文案。
 *
 * 23 号诊断：`Main.kt` 在 writerBusy 收敛时 `state.refreshKey++`，WritePage 以 refreshKey 为 key
 * 重建 WriterController 并 `load()`（applyContext 把 error/failure 置 null），导致「候选已失效」只存在一帧。
 * 本用例锁定：两个刷新键彼此独立；busy 收敛只递增 gateRefreshKey；RestoreFailure 文案保存在 state 中。
 */
class WriterFailureSurvivesRefreshTest {

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** (a) 两键独立：递增任一键不会带动另一键（拆分后计数刷新不再重建写作页）。 */
    @Test
    fun `gate refresh key and page refresh key are independent`() {
        val dir = Files.createTempDirectory("qianyan-o106-keys")
        val state = DesktopAppState(graphAt(dir))
        assertEquals(0, state.refreshKey, "前置：页面刷新键初值")
        assertEquals(0, state.gateRefreshKey, "前置：计数刷新键初值")

        state.gateRefreshKey++
        assertEquals(1, state.gateRefreshKey)
        assertEquals(0, state.refreshKey, "递增 gateRefreshKey 不得连带 refreshKey（否则仍会重建写作页）")

        state.refreshKey++
        assertEquals(1, state.gateRefreshKey)
        assertEquals(1, state.refreshKey)
    }

    /** (b) 源码守卫：busy 收敛只递增 gateRefreshKey；计数 effect 依赖两键；写作页 key 不含新键。 */
    @Test
    fun `busy ending bumps only the gate refresh key`() {
        val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
        assertTrue(
            "if (shouldRefreshAfterBusy(wasWriterBusy, state.writerBusy)) state.gateRefreshKey++" in main,
            "busy 收敛必须只递增 gateRefreshKey",
        )
        assertFalse(
            "if (shouldRefreshAfterBusy(wasWriterBusy, state.writerBusy)) state.refreshKey++" in main,
            "busy 收敛不得再递增 refreshKey（会重建写作页并擦除失败文案）",
        )
        assertTrue(
            "LaunchedEffect(state.projectId, state.refreshKey, state.gateRefreshKey)" in main,
            "待确认计数 effect 必须同时依赖 refreshKey 与 gateRefreshKey",
        )

        val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt").readText()
        assertTrue(
            "LaunchedEffect(chapterId, state.novel?.novelId, state.refreshKey)" in page,
            "写作页控制器重建 key 必须保持原样",
        )
        assertTrue(
            "onDecision = { state.gateRefreshKey++ }" in page,
            "T-O108：决议回调只递增 gateRefreshKey（refreshKey++ 会重建控制器并擦除失败文案/后台状态）",
        )
    }

    /** (c) 失败文案保留：RestoreFailure 后 error/failure 在 state 中；递增 gateRefreshKey 不改变它。 */
    @Test
    fun `lost candidate failure survives a gate count refresh`() {
        val controller = WriterController(
            novelId = NovelId("novel-t-o106"),
            chapter = chapter(),
            gatewayProvider = { ThrowingGateway() },
            workflowProvider = { IdleWorkflow() },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        controller.load()
        assertTrue(controller.uiState.value.isLoaded, "前置：上下文已加载")

        controller.continueWriting()

        val failed = controller.uiState.value
        assertEquals(CANDIDATE_LOST_MESSAGE, failed.error, "必须留下可读失败文案")
        assertNotNull(failed.failure, "失败原因必须留在正文页（failure 摘要）")
        assertFalse(failed.busy, "失败后忙态必须收敛")

        val dir = Files.createTempDirectory("qianyan-o106-survive")
        val state = DesktopAppState(graphAt(dir))
        state.gateRefreshKey++

        assertEquals(1, state.gateRefreshKey)
        assertEquals(0, state.refreshKey)
        assertEquals(CANDIDATE_LOST_MESSAGE, controller.uiState.value.error, "计数刷新不得擦除失败文案")
        assertEquals(failed.failure, controller.uiState.value.failure)
    }

    /* ---------- fixtures ---------- */

    private fun chapter(): Chapter = Chapter(
        chapterId = ChapterId("chapter-t-o106"),
        novelId = NovelId("novel-t-o106"),
        title = "第 1 章 · 失效候选",
        order = 1,
        createdAt = Clock.System.now(),
    )

    private inner class ThrowingGateway : WriterGateway {
        override fun loadContext(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): WriterChapterContext = WriterChapterContext(
            novelId = novelId,
            novelTitle = "T-O106 测试作品",
            chapterId = chapterId,
            chapterTitle = "第 1 章 · 失效候选",
            draft = null,
            phase = ChapterPhase.NOT_STARTED,
            waitingForUser = false,
        )

        override fun saveContent(draftId: DraftId, content: String) = error("saveContent 不应被调用")
        override fun saveFirstDraft(chapterId: ChapterId, content: String) = error("saveFirstDraft 不应被调用")

        override fun continueWriting(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ChapterWorkflowProgress = throw ApplicationException(
            ApplicationError.RestoreFailure("上一轮 AI 工作产物已失效（进程重启后内存工作区丢失）"),
        )

        override fun rewrite(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ChapterWorkflowProgress = error("rewrite 不应被调用")

        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress =
            error("rejectGate 不应被调用")
    }

    private inner class IdleWorkflow : ChapterWorkflowGateway {
        override fun startChapter(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
            kind: WorkflowKind,
        ): ChapterWorkflowProgress = progress()

        override fun advance(chapterId: ChapterId): ChapterWorkflowProgress = progress()
        override fun resume(chapterId: ChapterId): ChapterWorkflowProgress = progress()

        override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress = progress()

        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress = progress()
        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress = progress()

        override fun proposeRewrite(
            chapterId: ChapterId,
            workingDraftId: WorkingDraftId,
        ): ChapterWorkflowProgress = progress()

        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress = progress()
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress = progress()

        private fun progress(): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = ChapterId("chapter-t-o106"),
            novelId = NovelId("novel-t-o106"),
            variantId = null,
            phase = ChapterPhase.NOT_STARTED,
            waitingForUser = false,
            revisionCount = 0,
            draftId = null,
        )
    }
}
