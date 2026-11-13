package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.settings.saveProviderFromSettings
import com.qianyan.app.desktop.ui.write.WriterController
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
import com.qianyan.model.writing.Draft
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
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
 * T-O88-F（清单 4i-A）· Provider 热切换：不重启即生效 + 写作页忙时拒绝切换。
 *
 * 只验证桌面端接线：控制器在**动作发生时**解析当前容器服务；
 * 切换失败不得半切换、不得丢本地正文。业务语义仍由既有 UseCase 决定。
 */
class WriterHotProviderSwitchTest {

    private val novelId = NovelId("novel-t-o88")
    private val chapterId = ChapterId("chapter-t-o88")

    /* ---------- (a) 热切换后走新服务 ---------- */

    @Test
    fun `switching provider container takes effect on next action without recreating controller`() {
        val first = MarkerGateway("first")
        val second = MarkerGateway("second")
        var current: WriterGateway = first
        val controller = WriterController(
            novelId = novelId,
            chapter = chapter(),
            gatewayProvider = { current },
            workflowProvider = { NotStartedWorkflow() },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        controller.load()
        controller.onContentChange("第一段正文")
        controller.save()
        assertEquals("first#author-first", controller.uiState.value.draftId?.value, "首次保存必须命中旧服务")

        // 设置页切 Provider ⇒ 容器重建；控制器不重建，只改解析目标。
        current = second
        controller.onContentChange("第二段正文")
        controller.save()
        assertEquals("second#content", controller.uiState.value.draftId?.value, "切换后下一次保存必须命中新服务")
    }

    /* ---------- (b) 写作页忙时禁止切换 ---------- */

    @Test
    fun `busy writer rejects provider switch and leaves provider unchanged`() {
        val dir = Files.createTempDirectory("qianyan-o88-busy")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.DEEPSEEK, "test-key")
        val state = DesktopAppState(graph)
        assertEquals(ProviderType.MOCK, graph.provider.value, "前置：当前生效 MOCK")

        state.writerBusy = true
        assertFalse(
            saveProviderFromSettings(state, ProviderType.DEEPSEEK, "", ""),
            "写作任务进行中必须拒绝切换",
        )
        assertEquals(ProviderType.MOCK, graph.provider.value, "被拒后 Provider 不得改变")
        val message = graph.providerMessage.value
        assertNotNull(message, "拒绝必须给出可见提示")
        assertTrue("当前写作任务进行中" in message, "提示必须可读：$message")

        state.writerBusy = false
        assertTrue(
            saveProviderFromSettings(state, ProviderType.DEEPSEEK, "", ""),
            "写作空闲后同一入口必须成功",
        )
        assertEquals(ProviderType.DEEPSEEK, graph.provider.value)
    }

    /* ---------- (c) 切换不丢本地正文 ---------- */

    @Test
    fun `unsaved draft content survives provider switch`() {
        var current: WriterGateway = MarkerGateway("first")
        val controller = WriterController(
            novelId = novelId,
            chapter = chapter(),
            gatewayProvider = { current },
            workflowProvider = { NotStartedWorkflow() },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
        controller.load()
        controller.onContentChange("还没保存的正文")
        assertTrue(controller.uiState.value.dirty, "前置：正文未保存")

        current = MarkerGateway("second")

        assertEquals("还没保存的正文", controller.uiState.value.draftContent, "切换不得清空本地正文")
        assertTrue(controller.uiState.value.dirty, "切换不得把未保存正文标记为已保存")
    }

    /* ---------- fixtures ---------- */

    private fun chapter(): Chapter = Chapter(
        chapterId = chapterId,
        novelId = novelId,
        title = "第 1 章 · 热切换",
        order = 1,
        createdAt = Clock.System.now(),
    )

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** 用 DraftId 前缀区分「哪个服务被真正调用」。 */
    private inner class MarkerGateway(private val marker: String) : WriterGateway {
        override fun loadContext(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): WriterChapterContext = WriterChapterContext(
            novelId = novelId,
            novelTitle = "T-O88 测试作品",
            chapterId = chapterId,
            chapterTitle = "第 1 章 · 热切换",
            draft = null,
            phase = ChapterPhase.NOT_STARTED,
            waitingForUser = false,
        )

        override fun saveContent(draftId: DraftId, content: String): Draft =
            draft(DraftId("$marker#content"), content)

        override fun saveFirstDraft(chapterId: ChapterId, content: String): Draft =
            draft(DraftId("$marker#author-first"), content)

        override fun continueWriting(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ChapterWorkflowProgress = error("continueWriting 不应被调用")

        override fun rewrite(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ChapterWorkflowProgress = error("rewrite 不应被调用")

        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress =
            error("rejectGate 不应被调用")

        private fun draft(draftId: DraftId, content: String): Draft {
            val now = Clock.System.now()
            return Draft(
                draftId = draftId,
                novelId = novelId,
                chapterId = chapterId,
                content = content,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private class NotStartedWorkflow : ChapterWorkflowGateway {
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
            novelId = NovelId("novel-t-o88"),
            variantId = null,
            phase = ChapterPhase.NOT_STARTED,
            waitingForUser = false,
            revisionCount = 0,
            draftId = null,
        )

        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress = error("approve 不应被调用")
        override fun reject(chapterId: ChapterId): ChapterWorkflowProgress = error("reject 不应被调用")
        override fun proposeRewrite(
            chapterId: ChapterId,
            workingDraftId: WorkingDraftId,
        ): ChapterWorkflowProgress = error("proposeRewrite 不应被调用")

        override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress =
            error("abandonRound 不应被调用")

        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress =
            error("continueToNextChapter 不应被调用")
    }
}
