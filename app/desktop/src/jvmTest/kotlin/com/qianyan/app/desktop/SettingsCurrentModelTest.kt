package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.CustomModel
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.settings.currentModelSection
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock

/**
 * T-O89-F（清单 4i-B）· 模型页「当前模型」投影 + O-96 双路径守卫。
 *
 * 只验证桌面端 UI 投影与守卫：切换一律经 [DesktopAppState.chooseProviderModel]，
 * 忙标志由控制器真实忙态驱动，「任务收敛才复位」。
 */
class SettingsCurrentModelTest {

    private val novelId = NovelId("novel-t-o89")
    private val chapterId = ChapterId("chapter-t-o89")

    /* ---------- (a) 当前模型可见且标记已选 ---------- */

    @Test
    fun `current model section marks the active model and stays read only for mock`() {
        val mock = currentModelSection(
            provider = ProviderType.MOCK,
            currentModelId = "mock-v1",
            candidates = emptyList(),
            defaultModelId = "mock-v1",
        )
        assertEquals("离线示意", mock.providerLabel)
        assertEquals("mock-v1", mock.currentModelId, "当前模型必须被标记为已选值")
        assertEquals(listOf("mock-v1"), mock.options, "MOCK 无可切换候选")
        assertFalse(mock.switchable, "MOCK 小节只读展示")

        val deepseek = currentModelSection(
            provider = ProviderType.DEEPSEEK,
            currentModelId = "b",
            candidates = listOf(
                CustomModel(provider = ProviderType.DEEPSEEK, modelId = "a", label = "模型 A"),
                CustomModel(provider = ProviderType.DEEPSEEK, modelId = "b", label = "模型 B"),
            ),
            defaultModelId = "deepseek-v4-flash",
        )
        assertEquals("模型 B", deepseek.currentLabel, "有显示名称时用显示名称")
        assertEquals(listOf("a", "b", "deepseek-v4-flash"), deepseek.options, "候选去重且包含当前值")
        assertTrue(deepseek.switchable, "多于一个候选时可切换")
    }

    /* ---------- (b) 切换走唯一入口 ---------- */

    @Test
    fun `switching current model goes through chooseProviderModel only`() {
        val dir = Files.createTempDirectory("qianyan-o89-switch")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.MIMO, "test-key")
        val state = DesktopAppState(graph)

        assertTrue(state.chooseProviderModel(ProviderType.MIMO, modelId = "mimo-v2.6-pro"))
        assertEquals(ProviderType.MIMO, graph.provider.value)
        assertEquals("mimo-v2.6-pro", graph.modelId())

        val source = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/ModelSettings.kt").readText()
        assertTrue("state.chooseProviderModel(" in source, "模型页必须经唯一入口生效")
        assertFalse("saveProviderConfig(" in source, "模型页不得直连 saveProviderConfig 绕过守卫")
    }

    /* ---------- (c) writerBusy 时被拒 ---------- */

    @Test
    fun `writerBusy rejects chooseProviderModel without touching provider or model`() {
        val dir = Files.createTempDirectory("qianyan-o89-busy")
        val graph = graphAt(dir)
        graph.credentialStore.setApiKey(ProviderType.MIMO, "test-key")
        val state = DesktopAppState(graph)
        val providerBefore = graph.provider.value
        val modelBefore = graph.modelId()

        state.writerBusy = true
        assertFalse(
            state.chooseProviderModel(ProviderType.MIMO, modelId = "mimo-v2.6-pro"),
            "写作任务进行中必须拒绝",
        )
        assertEquals(providerBefore, graph.provider.value, "Provider 不得改变")
        assertEquals(modelBefore, graph.modelId(), "模型不得改变")
        assertTrue("当前写作任务进行中" in (state.toast ?: ""), "提示必须可读：${state.toast}")

        state.writerBusy = false
        assertTrue(state.chooseProviderModel(ProviderType.MIMO, modelId = "mimo-v2.6-pro"), "空闲后必须成功")
    }

    /* ---------- (d) 忙标志跟随任务，不因离页复位 ---------- */

    @Test
    fun `writer busy stays until the task really finishes`() {
        val gate = CountDownLatch(1)
        val seen: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        val controller = WriterController(
            novelId = novelId,
            chapter = chapter(),
            gatewayProvider = { BlockingGateway(gate) },
            workflowProvider = { NotStartedWorkflow() },
            scope = CoroutineScope(Dispatchers.Default),
            ioDispatcher = Dispatchers.IO,
            onBusyChange = { seen += it },
        )
        controller.load()
        val loadDeadline = System.currentTimeMillis() + 5_000
        while (!controller.uiState.value.isLoaded && System.currentTimeMillis() < loadDeadline) Thread.sleep(20)
        assertTrue(controller.uiState.value.isLoaded, "前置：控制器必须已加载")

        controller.continueWriting()
        assertTrue(seen.contains(true), "生成开始后忙标志必须为 true: $seen")

        gate.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (!seen.contains(false) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(seen.contains(false), "任务收敛后忙标志必须复位为 false: $seen")
        assertEquals(true, seen.first(), "首次通知必须是进入忙态")

        // 离页不得无条件复位：标记只能由任务收敛回调写入。
        val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt").readText()
        assertTrue("onBusyChange = { state.writerBusy = it }" in page, "写页必须接控制器忙态回调")
        assertFalse("onDispose { state.writerBusy = false }" in page, "离页不得无条件清零忙标志")
        assertFalse("state.writerBusy = ui.busy" in page, "忙标志不再由组合期同步（会在离页后失真）")
    }

    /* ---------- fixtures ---------- */

    private fun chapter(): Chapter = Chapter(
        chapterId = chapterId,
        novelId = novelId,
        title = "第 1 章 · 当前模型",
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

    private inner class BlockingGateway(private val gate: CountDownLatch) : WriterGateway {
        override fun loadContext(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): WriterChapterContext = WriterChapterContext(
            novelId = novelId,
            novelTitle = "T-O89 测试作品",
            chapterId = chapterId,
            chapterTitle = "第 1 章 · 当前模型",
            draft = null,
            phase = ChapterPhase.NOT_STARTED,
            waitingForUser = false,
        )

        override fun saveContent(draftId: DraftId, content: String): Draft = error("saveContent 不应被调用")
        override fun saveFirstDraft(chapterId: ChapterId, content: String): Draft = error("saveFirstDraft 不应被调用")

        override fun continueWriting(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ChapterWorkflowProgress {
            gate.await()
            return ChapterWorkflowProgress(
                chapterId = chapterId,
                novelId = novelId,
                variantId = null,
                phase = ChapterPhase.WAITING_CONFIRMATION,
                waitingForUser = true,
                revisionCount = 0,
                draftId = null,
            )
        }

        override fun rewrite(
            novelId: NovelId,
            variantId: VariantId?,
            chapterId: ChapterId,
        ): ChapterWorkflowProgress = error("rewrite 不应被调用")

        override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = error("rejectGate 不应被调用")
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
            novelId = NovelId("novel-t-o89"),
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
