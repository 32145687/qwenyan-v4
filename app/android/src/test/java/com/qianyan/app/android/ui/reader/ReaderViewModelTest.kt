package com.qianyan.app.android.ui.reader

import com.qianyan.application.usecase.reading.ReadingUseCases
import com.qianyan.application.error.ErrorMapper
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.reading.ReadingProgress
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.ReadingProgressRepository
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-P4 · ReaderViewModel 单元测试。
 *
 * 依赖为**真实** [ReadingUseCases] + 内存仓储替身（Chapter / Draft / ReadingProgress），
 * 因此真实走 P2 [com.qianyan.engine.markdown.ControlledMarkdown] 解析与 FD-9 阅读位置读写；
 * ViewModel 不接触 Repository / Provider / Agent（只依赖 ReadingUseCases）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    // ---------------- 内存替身仓储（只实现本测试所需行为） ----------------

    private class FakeChapterRepository : ChapterRepository {
        val chapters = mutableListOf<Chapter>()

        override fun save(chapter: Chapter) {
            chapters.removeAll { it.chapterId == chapter.chapterId }
            chapters += chapter
        }

        override fun findById(chapterId: ChapterId): Chapter? = chapters.firstOrNull { it.chapterId == chapterId }

        override fun listByNovel(novelId: NovelId, variantId: VariantId?): List<Chapter> =
            chapters.filter { it.novelId == novelId && it.variantId == variantId }.sortedBy { it.order }

        override fun nextOrder(novelId: NovelId, variantId: VariantId?): Int =
            (listByNovel(novelId, variantId).maxOfOrNull { it.order } ?: 0) + 1

        override fun createNextChapter(chapter: Chapter): Chapter =
            chapter.copy(order = nextOrder(chapter.novelId, chapter.variantId)).also { save(it) }
    }

    private class FakeDraftRepository : DraftRepository {
        val drafts = mutableListOf<Draft>()

        override fun save(draft: Draft) {
            drafts.removeAll { it.draftId == draft.draftId }
            drafts += draft
        }

        override fun getById(draftId: DraftId): Draft? = drafts.firstOrNull { it.draftId == draftId }

        override fun listByNovel(novelId: NovelId): List<Draft> = drafts.filter { it.novelId == novelId }

        override fun listByChapter(chapterId: ChapterId): List<Draft> = drafts.filter { it.chapterId == chapterId }

        override fun latestByChapter(chapterId: ChapterId): Draft? =
            listByChapter(chapterId).maxByOrNull { it.createdAt }
    }

    private class FakeReadingProgressRepository : ReadingProgressRepository {
        val saved = mutableMapOf<ChapterId, ReadingProgress>()
        var saveCount = 0

        override fun save(progress: ReadingProgress) {
            saveCount++
            saved[progress.chapterId] = progress
        }

        override fun get(chapterId: ChapterId): ReadingProgress? = saved[chapterId]
    }

    // ---------------- fixtures ----------------

    private val novelId = NovelId("n1")

    private fun chapter(id: String, order: Int, title: String = "第${order}章"): Chapter = Chapter(
        chapterId = ChapterId(id),
        novelId = novelId,
        variantId = null,
        scope = VariantScope.ORIGINAL,
        title = title,
        order = order,
        status = ChapterStatus.WRITTEN,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun draft(chapterId: String, content: String, format: String?): Draft = Draft(
        draftId = DraftId("d-$chapterId"),
        novelId = novelId,
        chapterId = ChapterId(chapterId),
        content = content,
        format = format,
        status = DraftStatus.WRITTEN,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private class Fixture {
        val chapters = FakeChapterRepository()
        val drafts = FakeDraftRepository()
        val progress = FakeReadingProgressRepository()
        val reading = ReadingUseCases(chapters, drafts, progress, ErrorMapper)
    }

    private fun TestScope.viewModel(fixture: Fixture, chapterId: String = "c1"): ReaderViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return ReaderViewModel(
            novelId = NovelId("n1"),
            variantId = null,
            chapterId = ChapterId(chapterId),
            reading = fixture.reading,
            ioDispatcher = dispatcher,
        )
    }

    // ---------------- 1. 加载章节 + Controlled Markdown 渲染 ----------------

    @Test
    fun `VM1 loads chapter and parses controlled markdown blocks`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1, "开端"))
        f.drafts.save(
            draft(
                "c1",
                "# 第一章 标题\n\n正文第一段，含**加粗**与*斜体*。\n\n- 列表项一\n\n> 引用一句\n\n| a | b |\n| --- | --- |\n",
                DraftFormat.CONTROLLED_MARKDOWN,
            ),
        )
        val vm = viewModel(f)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("开端", state.chapterTitle)
        assertEquals(1, state.chapterOrder)
        assertTrue(state.hasDraft)
        assertTrue(state.isControlledMarkdown, "format=markdown:controlled:v1 → 行内强调可渲染")
        assertNull(state.error)
        assertFalse(state.isLoading)

        // Heading / Paragraph / UnorderedListItem / Quote / Degraded（非法结构降级）均正确映射
        // 注：表格段含 2 行，P2 逐行降级 → 2 个 Degraded 块（P2 既有语义，本测试不改变）
        assertEquals(6, state.blocks.size, "块序列：${state.blocks}")
        assertEquals(MarkdownBlock.Heading(1, "第一章 标题"), state.blocks[0])
        assertEquals(MarkdownBlock.Paragraph("正文第一段，含**加粗**与*斜体*。"), state.blocks[1])
        assertEquals(MarkdownBlock.UnorderedListItem("列表项一"), state.blocks[2])
        assertEquals(MarkdownBlock.Quote("引用一句"), state.blocks[3])
        assertEquals(MarkdownBlock.Degraded("| a | b |"), state.blocks[4], "表格属非法结构 → Degraded")
        assertEquals(MarkdownBlock.Degraded("| --- | --- |"), state.blocks[5], "表格属非法结构 → Degraded")
        Dispatchers.resetMain()
    }

    // ---------------- 2. legacy plain text ----------------

    @Test
    fun `VM2 legacy plain text renders as single plain block`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.drafts.save(draft("c1", "# 这不是标题\n\n第二段 **不是加粗**", format = null))
        val vm = viewModel(f)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isControlledMarkdown, "format=null → plain text 展示，不做 Markdown 解析")
        assertEquals(1, state.blocks.size, "legacy 整段原文单块展示")
        assertEquals(MarkdownBlock.Paragraph("# 这不是标题\n\n第二段 **不是加粗**"), state.blocks[0])
        Dispatchers.resetMain()
    }

    // ---------------- 3. 无 Draft ----------------

    @Test
    fun `VM3 chapter without draft yields no blocks and no error`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        val vm = viewModel(f)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.hasDraft)
        assertTrue(state.blocks.isEmpty())
        assertNull(state.error)
        Dispatchers.resetMain()
    }

    @Test
    fun `VM3b missing chapter maps to readable error`() = runTest {
        val f = Fixture()
        val vm = viewModel(f, chapterId = "ghost")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error != null, "不存在的章节应给出错误而非崩溃")
        Dispatchers.resetMain()
    }

    // ---------------- 4. 上一章 / 下一章 + 边界 ----------------

    @Test
    fun `VM4 previous and next chapter navigation respects boundaries`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1, "第一章"))
        f.chapters.save(chapter("c2", 2, "第二章"))
        f.chapters.save(chapter("c3", 3, "第三章"))
        val vm = viewModel(f, chapterId = "c2") // 中间章
        advanceUntilIdle()

        assertEquals("第二章", vm.uiState.value.chapterTitle)
        assertTrue(vm.uiState.value.canGoPrevious)
        assertTrue(vm.uiState.value.canGoNext)

        vm.goToNextChapter()
        advanceUntilIdle()
        assertEquals("第三章", vm.uiState.value.chapterTitle)
        assertTrue(vm.uiState.value.canGoPrevious)
        assertFalse(vm.uiState.value.canGoNext, "末章 → 下一章不可用")

        vm.goToPreviousChapter()
        advanceUntilIdle()
        assertEquals("第二章", vm.uiState.value.chapterTitle)

        vm.goToPreviousChapter()
        advanceUntilIdle()
        assertEquals("第一章", vm.uiState.value.chapterTitle)
        assertFalse(vm.uiState.value.canGoPrevious, "首章 → 上一章不可用")
        assertTrue(vm.uiState.value.canGoNext)
        Dispatchers.resetMain()
    }

    @Test
    fun `VM4b single chapter disables both directions`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        val vm = viewModel(f)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canGoPrevious)
        assertFalse(vm.uiState.value.canGoNext)
        Dispatchers.resetMain()
    }

    // ---------------- 5. 阅读位置：保存 / 恢复 / 默认 ----------------

    @Test
    fun `VM5 scroll position is persisted`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.drafts.save(draft("c1", "# H\n\n段落\n", DraftFormat.CONTROLLED_MARKDOWN))
        val vm = viewModel(f)
        advanceUntilIdle()

        vm.onPositionChanged(1)
        advanceUntilIdle()

        assertEquals(1, f.progress.saved[ChapterId("c1")]?.position)
        assertEquals(1, vm.uiState.value.position)
        Dispatchers.resetMain()
    }

    @Test
    fun `VM6 persisted position is restored on reopen`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.drafts.save(draft("c1", "段落一\n\n段落二\n\n段落三", DraftFormat.CONTROLLED_MARKDOWN))
        f.progress.save(
            ReadingProgress(novelId = novelId, chapterId = ChapterId("c1"), position = 2, updatedAt = Clock.System.now()),
        )

        val vm = viewModel(f)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.position, "再次进入应恢复阅读位置")
        assertEquals(2, vm.uiState.value.safePosition)
        Dispatchers.resetMain()
    }

    @Test
    fun `VM7 without progress starts at default position`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.drafts.save(draft("c1", "段落", DraftFormat.CONTROLLED_MARKDOWN))
        val vm = viewModel(f)
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.position, "无进度 → 默认位置 0")
        assertNull(f.progress.get(ChapterId("c1")), "未阅读不应伪造进度记录")
        Dispatchers.resetMain()
    }

    @Test
    fun `VM8 switching chapter reads that chapter own progress`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.chapters.save(chapter("c2", 2))
        f.drafts.save(draft("c1", "A1\n\nA2\n\nA3", DraftFormat.CONTROLLED_MARKDOWN))
        f.drafts.save(draft("c2", "B1\n\nB2\n\nB3\n\nB4", DraftFormat.CONTROLLED_MARKDOWN))
        f.progress.save(ReadingProgress(novelId, ChapterId("c1"), 2, Clock.System.now()))
        f.progress.save(ReadingProgress(novelId, ChapterId("c2"), 1, Clock.System.now()))

        val vm = viewModel(f, chapterId = "c1")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.position)

        vm.goToNextChapter()
        advanceUntilIdle()
        assertEquals(ChapterId("c2"), vm.uiState.value.chapterId)
        assertEquals(1, vm.uiState.value.position, "切换章节后应读取该章节自己的阅读位置")
        Dispatchers.resetMain()
    }

    @Test
    fun `VM9 same position is not persisted twice`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.drafts.save(draft("c1", "A\n\nB\n\nC", DraftFormat.CONTROLLED_MARKDOWN))
        val vm = viewModel(f)
        advanceUntilIdle()

        vm.onPositionChanged(1)
        advanceUntilIdle()
        vm.onPositionChanged(1)
        advanceUntilIdle()

        assertEquals(1, f.progress.saveCount, "同值不重复落库")
        Dispatchers.resetMain()
    }

    @Test
    fun `VM10 position beyond block count is clamped for display`() = runTest {
        val f = Fixture()
        f.chapters.save(chapter("c1", 1))
        f.drafts.save(draft("c1", "A\n\nB", DraftFormat.CONTROLLED_MARKDOWN))
        f.progress.save(ReadingProgress(novelId, ChapterId("c1"), 99, Clock.System.now()))

        val vm = viewModel(f)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.blocks.size)
        assertEquals(1, vm.uiState.value.safePosition, "超出块数时展示层钳制，不视为错误")
        assertNull(vm.uiState.value.error)
        Dispatchers.resetMain()
    }
}