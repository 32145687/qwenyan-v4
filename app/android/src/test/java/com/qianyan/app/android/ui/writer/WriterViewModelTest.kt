package com.qianyan.app.android.ui.writer

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-P3 · WriterViewModel 单元测试。
 *
 * 依赖只有用户层 [WriterGateway]（+ 用户层 DTO），**不接触** Repository / Provider / Agent / Workflow 内部，
 * 也**不拥有 DecisionModel**（Gateway 契约中不存在任何 Decision API）。
 * 用 kotlinx-coroutines-test 注入 Main 调度器（与既有 Android VM 测试一致）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriterViewModelTest {

    /** 替身 Gateway：只用户层语义，记录调用序列（用于验证「调用正确 UseCase」与「Resume 不重复生成」）。 */
    private class FakeWriterGateway : WriterGateway {
        var context: WriterChapterContext = context(phase = ChapterPhase.NOT_STARTED, draft = null)
        var continuePhase: ChapterPhase = ChapterPhase.WAITING_CONFIRMATION
        var rewritable: Draft? = null
        var failContinue: Boolean = false
        var failSave: Boolean = false

        val calls = mutableListOf<String>()
        var lastSavedContent: String? = null

        override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext {
            calls += "loadContext"
            return context
        }

        override fun saveContent(draftId: DraftId, content: String): Draft {
            calls += "saveContent"
            if (failSave) throw ApplicationException(ApplicationError.UnknownStorage(RuntimeException("db down")))
            lastSavedContent = content
            val saved = (context.draft ?: draft("d1", "初稿")).copy(content = content)
            context = context.copy(draft = saved)
            return saved
        }

        override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
            calls += "continueWriting"
            if (failContinue) throw ApplicationException(ApplicationError.WritingFailed("writer exploded"))
            return progress(continuePhase)
        }

        override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): Draft {
            calls += "rewrite"
            return rewritable ?: draft("d2", "修订后的正文")
        }
    }

    private fun TestScope.viewModel(gateway: FakeWriterGateway): WriterViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return WriterViewModel(
            novelId = NovelId("n1"),
            variantId = null,
            chapterId = ChapterId("c1"),
            gateway = gateway,
            ioDispatcher = dispatcher,
        )
    }

    /** VM-1：打开章节 → Draft 正确加载（内容 / format / 章节信息 / 阶段映射）。 */
    @Test
    fun `VM1 open loads chapter and draft`() = runTest {
        val gw = FakeWriterGateway().apply {
            context = context(
                phase = ChapterPhase.WRITING,
                draft = draft("d1", "已有正文", DraftFormat.CONTROLLED_MARKDOWN),
                chapterTitle = "第一章",
            )
        }
        val vm = viewModel(gw)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("第一章", state.chapterTitle)
        assertEquals("d1", state.draftId?.value)
        assertEquals("已有正文", state.draftContent)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, state.draftFormat)
        assertEquals(WriterTaskStatus.WRITING, state.taskStatus)
        assertNull(state.error)
        Dispatchers.resetMain()
    }

    /** VM-2：修改文本 → State 更新（纯 UI 状态，不落库）。 */
    @Test
    fun `VM2 editing updates state without saving`() = runTest {
        val gw = FakeWriterGateway().apply { context = context(draft = draft("d1", "旧")) }
        val vm = viewModel(gw)
        advanceUntilIdle()
        val callsAfterOpen = gw.calls.size

        vm.onContentChange("用户新输入的第一段")

        assertEquals("用户新输入的第一段", vm.uiState.value.draftContent)
        assertEquals(callsAfterOpen, gw.calls.size, "编辑不得触发任何 Gateway 调用/落库")
        Dispatchers.resetMain()
    }

    /** VM-3：保存 → 保存请求携带编辑后内容（并回写服务端结果）。 */
    @Test
    fun `VM3 save sends edited content`() = runTest {
        val gw = FakeWriterGateway().apply { context = context(draft = draft("d1", "旧正文")) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        vm.onContentChange("编辑后正文")
        vm.save()
        advanceUntilIdle()

        assertTrue(gw.calls.contains("saveContent"))
        assertEquals("编辑后正文", gw.lastSavedContent)
        assertFalse(vm.uiState.value.isSaving)
        assertEquals("编辑后正文", vm.uiState.value.draftContent)
        Dispatchers.resetMain()
    }

    /** VM-3b：尚无 Draft → 明确提示且不发送保存请求（不伪造草稿）。 */
    @Test
    fun `VM3b save without draft is rejected`() = runTest {
        val gw = FakeWriterGateway().apply { context = context(draft = null) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        vm.onContentChange("无草稿时的输入")
        vm.save()
        advanceUntilIdle()

        assertFalse(gw.calls.contains("saveContent"))
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.canSave)
        Dispatchers.resetMain()
    }

    /** VM-4：AI 写作 → 调用正确用例（continueWriting）+ 重新加载 Draft 与阶段。 */
    @Test
    fun `VM4 continue writing calls gateway and reloads draft`() = runTest {
        val gw = FakeWriterGateway().apply { context = context(draft = null) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        // AI 完成后章节出现新 Draft（真实持久化结果由 Gateway 投影）
        gw.context = context(
            phase = ChapterPhase.WAITING_CONFIRMATION,
            draft = draft("d-ai", "AI 生成的正文", DraftFormat.CONTROLLED_MARKDOWN),
        )
        vm.continueWriting()
        advanceUntilIdle()

        assertTrue(gw.calls.contains("continueWriting"), "AI 继续写必须经用户层 Writing seam")
        assertTrue(gw.calls.count { it == "loadContext" } >= 2, "完成后重新加载 Draft/阶段")
        assertEquals("AI 生成的正文", vm.uiState.value.draftContent)
        assertEquals("d-ai", vm.uiState.value.draftId?.value)
        assertEquals(WriterTaskStatus.COMPLETED, vm.uiState.value.taskStatus)
        assertFalse(vm.uiState.value.isGenerating)
        Dispatchers.resetMain()
    }

    /** VM-4b：AI 改写 → 调用 rewrite 并重新加载（新 Draft 成为当前草稿）。 */
    @Test
    fun `VM4b rewrite calls gateway and reloads draft`() = runTest {
        val gw = FakeWriterGateway().apply { context = context(draft = draft("d1", "原正文")) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        gw.context = context(draft = draft("d2", "修订后的正文"), phase = ChapterPhase.WAITING_CONFIRMATION)
        vm.rewrite()
        advanceUntilIdle()

        assertTrue(gw.calls.contains("rewrite"))
        assertEquals("修订后的正文", vm.uiState.value.draftContent)
        assertEquals("d2", vm.uiState.value.draftId?.value)
        Dispatchers.resetMain()
    }

    /** VM-5：Writing 失败 → UI 显示错误（不崩溃，isGenerating 复位）。 */
    @Test
    fun `VM5 writing failure shows error`() = runTest {
        val gw = FakeWriterGateway().apply { failContinue = true }
        val vm = viewModel(gw)
        advanceUntilIdle()

        vm.continueWriting()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("写作失败"), "类型化错误映射为用户可读文案：${state.error}")
        assertFalse(state.isGenerating)
        Dispatchers.resetMain()
    }

    /** VM-5b：保存失败 → UI 显示错误（isSaving 复位）。 */
    @Test
    fun `VM5b save failure shows error`() = runTest {
        val gw = FakeWriterGateway().apply {
            context = context(draft = draft("d1", "旧正文"))
            failSave = true
        }
        val vm = viewModel(gw)
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.isSaving)
        Dispatchers.resetMain()
    }

    /**
     * VM-6：Resume（重建 ViewModel）→ 只读恢复，**不重新 Decision**：
     * 重建只触发 loadContext（读上下文），不触发任何 AI 生成（continueWriting / rewrite）。
     * 结构上 ViewModel 也不持有 DecisionModel —— WriterGateway 契约中不存在任何 Decision API。
     */
    @Test
    fun `VM6 rebuild resumes by read only and never decides`() = runTest {
        val gw = FakeWriterGateway().apply {
            context = context(phase = ChapterPhase.WAITING_CONFIRMATION, draft = draft("d1", "已生成正文"))
        }
        val vm1 = viewModel(gw)
        advanceUntilIdle()
        assertEquals(WriterTaskStatus.COMPLETED, vm1.uiState.value.taskStatus)
        Dispatchers.resetMain()

        // 重建（模拟 Resume）：全新实例，共享持久化上下文
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        gw.calls.clear()
        val vm2 = WriterViewModel(NovelId("n1"), null, ChapterId("c1"), gw, ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals(WriterTaskStatus.COMPLETED, vm2.uiState.value.taskStatus)
        assertEquals("已生成正文", vm2.uiState.value.draftContent)
        assertEquals(listOf("loadContext"), gw.calls, "Resume 只读恢复：不重新 Decision、不重新生成")
        Dispatchers.resetMain()
    }

    /** VM-7：生成期间忽略重复点击（不产生重复 AI 请求）。 */
    @Test
    fun `VM7 concurrent generate is deduplicated`() = runTest {
        val gw = FakeWriterGateway().apply { context = context(draft = null) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        vm.continueWriting()
        vm.continueWriting()
        advanceUntilIdle()

        assertEquals(1, gw.calls.count { it == "continueWriting" })
        Dispatchers.resetMain()
    }
}

private fun draft(id: String, content: String, format: String? = null): Draft = Draft(
    draftId = DraftId(id),
    novelId = NovelId("n1"),
    chapterId = ChapterId("c1"),
    content = content,
    format = format,
    status = DraftStatus.WRITTEN,
    createdAt = Clock.System.now(),
    updatedAt = Clock.System.now(),
)

private fun context(
    phase: ChapterPhase = ChapterPhase.WRITING,
    draft: Draft?,
    chapterTitle: String = "第一章",
): WriterChapterContext = WriterChapterContext(
    novelId = NovelId("n1"),
    novelTitle = "测试小说",
    chapterId = ChapterId("c1"),
    chapterTitle = chapterTitle,
    draft = draft,
    phase = phase,
)

private fun progress(phase: ChapterPhase): ChapterWorkflowProgress = ChapterWorkflowProgress(
    chapterId = ChapterId("c1"),
    novelId = NovelId("n1"),
    variantId = null,
    phase = phase,
    waitingForUser = false,
    revisionCount = 0,
    draftId = null,
)