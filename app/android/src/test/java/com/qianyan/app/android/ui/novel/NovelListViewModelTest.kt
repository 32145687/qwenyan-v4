package com.qianyan.app.android.ui.novel

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.VariantId
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.OriginalImmutableException
import com.qianyan.storage.repository.TxtRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7.4 + P7.5 ViewModel 单元测试。
 *
 * 通过伪造 [NovelRepository] / [TxtRepository]（Storage 契约接口，非 SQLDelight 实现）驱动真实的
 * [NovelUseCases] + [TxtUseCases] + [ErrorMapper] 错误映射，验证 [NovelListViewModel] 的状态机：
 *  - P7.4：uiState 的 Loading → Success / Empty / Error，以及 Retry 再次加载；
 *  - P7.5：importState 的导入成功（刷新列表、推导书名）、重复导入（isDuplicate）、
 *    非法文本 / 空文本（映射为可读错误）、平台读取失败（showImportError）。
 * 不直接依赖 SQLDelight / Android Runtime；用 kotlinx-coroutines-test 注入 Main 调度器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelListViewModelTest {

    /** 内存版 NovelRepository：listOriginals / createOriginal / getNovel 真实行为，其余为未触发桩。 */
    private class FakeNovelRepository(
        private var originals: List<Novel> = emptyList(),
        private var failure: RuntimeException? = null,
    ) : NovelRepository {

        fun setOriginals(value: List<Novel>) {
            originals = value
        }

        fun setFailure(value: RuntimeException?) {
            failure = value
        }

        override fun listOriginals(): List<Novel> {
            failure?.let { throw it }
            return originals
        }

        override fun createOriginal(novel: Novel): NovelId {
            originals = originals + novel
            return novel.novelId
        }

        override fun getNovel(novelId: NovelId): Novel? = originals.find { it.novelId == novelId }

        override fun createVariant(variant: NovelVariant): VariantId = TODO()
        override fun getVariant(variantId: VariantId): NovelVariant? = TODO()
        override fun getVariantsOfNovel(novelId: NovelId): List<NovelVariant> = TODO()
        override fun saveVariantData(variant: NovelVariant) = TODO()
        override fun saveOverride(override: EntityOverride) = TODO()
        override fun getOverride(variantId: VariantId, targetId: String): EntityOverride? = TODO()
        override fun getOverrides(variantId: VariantId): List<EntityOverride> = TODO()
        override fun deleteOverride(variantId: VariantId, targetId: String) = TODO()
        override fun resolveOverride(
            variantId: VariantId,
            targetId: String,
            originalValue: JsonElement?,
        ): JsonElement? = TODO()
    }

    /** 内存版 TxtRepository：saveImport / findByContentHash / getChapters / getBlocks 真实行为，其余为桩。 */
    private class FakeTxtRepository : TxtRepository {
        private val documents = mutableMapOf<TxtDocumentId, TxtDocument>()
        private val chaptersByDoc = mutableMapOf<TxtDocumentId, List<TxtChapter>>()
        private val blocksByDoc = mutableMapOf<TxtDocumentId, List<TextBlock>>()

        override fun saveImport(document: TxtDocument, chapters: List<TxtChapter>, blocks: List<TextBlock>) {
            documents[document.documentId] = document
            chaptersByDoc[document.documentId] = chapters
            blocksByDoc[document.documentId] = blocks
        }

        override fun getDocument(documentId: TxtDocumentId): TxtDocument? = documents[documentId]

        override fun findByContentHash(contentHash: String): TxtDocument? =
            documents.values.find { it.contentHash == contentHash }

        override fun findByNovelId(novelId: NovelId): List<TxtDocument> =
            documents.values.filter { it.novelId == novelId }.sortedBy { it.createdAt }

        override fun getChapters(documentId: TxtDocumentId): List<TxtChapter> =
            chaptersByDoc[documentId] ?: emptyList()

        override fun getBlocks(documentId: TxtDocumentId): List<TextBlock> =
            blocksByDoc[documentId] ?: emptyList()

        override fun getBlocksOfChapter(chapterId: TxtChapterId): List<TextBlock> =
            blocksByDoc.values.flatten().filter { it.chapterId == chapterId }

        override fun getBlock(blockId: TextBlockId): TextBlock? =
            blocksByDoc.values.flatten().find { it.blockId == blockId }
    }

    private fun novel(id: String, title: String): Novel = Novel(
        novelId = NovelId(id),
        projectId = ProjectId("project-$id"),
        title = title,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    /** 用与 testScheduler 共享的调度器装配 ViewModel（真实引擎 + 内存仓储），确保 advanceUntilIdle 可推进。 */
    private fun TestScope.viewModel(repo: FakeNovelRepository): NovelListViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val txts = TxtUseCases(
            txtPipeline = TxtPipeline(),
            txtRepository = FakeTxtRepository(),
            novelRepository = repo,
            errorMapper = ErrorMapper,
        )
        return NovelListViewModel(
            novels = NovelUseCases(repo, ErrorMapper),
            txts = txts,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `loading then success`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = listOf(novel("1", "小说A"))))

        assertEquals(NovelListUiState.Loading, vm.uiState.value, "构造后应立即处于 Loading")

        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<NovelListUiState.Success>(state)
        assertEquals(1, state.novels.size)
        assertEquals("小说A", state.novels.single().title)
        Dispatchers.resetMain()
    }

    @Test
    fun `empty list then empty`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = emptyList()))

        advanceUntilIdle()

        assertEquals(NovelListUiState.Empty, vm.uiState.value)
        Dispatchers.resetMain()
    }

    @Test
    fun `query failure then error`() = runTest {
        val vm = viewModel(FakeNovelRepository(failure = OriginalImmutableException("boom")))

        advanceUntilIdle()

        assertEquals(NovelListUiState.Error, vm.uiState.value)
        Dispatchers.resetMain()
    }

    @Test
    fun `retry after error reloads`() = runTest {
        val repo = FakeNovelRepository(failure = OriginalImmutableException("boom"))
        val vm = viewModel(repo)

        advanceUntilIdle()
        assertEquals(NovelListUiState.Error, vm.uiState.value, "首次加载应失败进入 Error")

        // 修复数据源后重试
        repo.setFailure(null)
        repo.setOriginals(listOf(novel("1", "小说A")))
        vm.load()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<NovelListUiState.Success>(state)
        assertEquals(1, state.novels.size)
        Dispatchers.resetMain()
    }

    @Test
    fun `import txt success derives title and refreshes list`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = emptyList()))
        advanceUntilIdle()
        assertEquals(NovelListUiState.Empty, vm.uiState.value, "导入前应为空列表")

        vm.importTxt("第一章 开始\n\n正文内容……".toByteArray(Charsets.UTF_8), "我的小说.txt")
        advanceUntilIdle()

        val import = vm.importState.value
        assertIs<TxtImportUiState.Success>(import)
        assertEquals("我的小说", import.title, "书名应去掉 .txt 后缀")
        assertFalse(import.isDuplicate)

        val state = vm.uiState.value
        assertIs<NovelListUiState.Success>(state)
        assertEquals(1, state.novels.size)
        assertEquals("我的小说", state.novels.single().title, "导入后列表应刷新为新的 Original")
        Dispatchers.resetMain()
    }

    @Test
    fun `import duplicate content returns isDuplicate and keeps single novel`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = emptyList()))
        advanceUntilIdle()

        val bytes = "第一次导入的相同内容".toByteArray(Charsets.UTF_8)
        vm.importTxt(bytes, "a.txt")
        advanceUntilIdle()
        assertIs<TxtImportUiState.Success>(vm.importState.value, "首次导入应成功")

        vm.importTxt(bytes, "b.txt")
        advanceUntilIdle()

        val import = vm.importState.value
        assertIs<TxtImportUiState.Success>(import)
        assertTrue(import.isDuplicate, "相同 contentHash 应判定为重复导入")

        val state = vm.uiState.value
        assertIs<NovelListUiState.Success>(state)
        assertEquals(1, state.novels.size, "重复导入不应新增第二个 Novel")
        Dispatchers.resetMain()
    }

    @Test
    fun `import invalid utf8 maps to user friendly error`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = emptyList()))
        advanceUntilIdle()

        // 0xFF 单独出现不是合法 UTF-8 → 引擎 InvalidText → 用户可读错误
        vm.importTxt(byteArrayOf(0x41, 0xFF.toByte(), 0x42), "bad.txt")
        advanceUntilIdle()

        val import = vm.importState.value
        assertIs<TxtImportUiState.Error>(import)
        assertEquals("不是合法的文本文件，无法导入", import.message)
        assertEquals(NovelListUiState.Empty, vm.uiState.value, "导入失败不应影响列表")
        Dispatchers.resetMain()
    }

    @Test
    fun `import empty text maps to empty document error`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = emptyList()))
        advanceUntilIdle()

        vm.importTxt("   \n  ".toByteArray(Charsets.UTF_8), "blank.txt")
        advanceUntilIdle()

        val import = vm.importState.value
        assertIs<TxtImportUiState.Error>(import)
        assertEquals("文件内容为空，没有可导入的内容", import.message)
        assertEquals(NovelListUiState.Empty, vm.uiState.value, "导入失败不应影响列表")
        Dispatchers.resetMain()
    }

    @Test
    fun `platform read failure reports error without loading`() = runTest {
        val vm = viewModel(FakeNovelRepository(originals = emptyList()))
        // 先推进 init 触发的 load() 协程，避免其在 resetMain 后被收尾排水。
        advanceUntilIdle()

        vm.showImportError("无法读取所选文件")

        val import = vm.importState.value
        assertIs<TxtImportUiState.Error>(import)
        assertEquals("无法读取所选文件", import.message)
        Dispatchers.resetMain()
    }
}
