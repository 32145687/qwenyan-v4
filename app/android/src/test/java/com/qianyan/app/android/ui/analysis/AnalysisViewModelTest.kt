package com.qianyan.app.android.ui.analysis

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.analysis.AnalysisUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtDocument
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.VocabularyRepository
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P7.6 Analysis ViewModel 单元测试。
 *
 * 通过伪造 [NovelRepository] / [TxtRepository] / [VocabularyRepository]（Storage 契约接口，非 SQLDelight
 * 实现）驱动真实的 [TxtUseCases] / [VocabularyUseCases] / [AnalysisUseCases] + [ErrorMapper] + 注入的
 * [LLMGateway]，验证 [AnalysisViewModel] 的状态机与候选查询：
 *  - 初始 Idle；
 *  - Loading → Success（Mock 网关确定性输出 灵石/丹田 → 2 条候选）；
 *  - Loading → SuccessWithWarnings（网关返回空词汇 → 无有效建议）；
 *  - Loading → Error（网关抛 ProviderUnavailable → 中文错误文案，不崩溃）；
 *  - 分析后 findCandidatesByNovel 被正确调用并展示候选；
 *  - 空候选显示空列表（非错误）；
 *  - 失败 → 重试 → 成功。
 * 不直接依赖 SQLDelight / Android Runtime；用 kotlinx-coroutines-test 注入 Main 调度器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {

    /** 内存版 NovelRepository：createOriginal / getNovel 真实行为，其余为未触发桩。 */
    private class FakeNovelRepository(
        private var originals: List<Novel> = emptyList(),
    ) : NovelRepository {
        override fun createOriginal(novel: Novel): NovelId {
            originals = originals + novel
            return novel.novelId
        }

        override fun getNovel(novelId: NovelId): Novel? = originals.find { it.novelId == novelId }

        override fun listOriginals(): List<Novel> = originals
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

    /** 内存版 TxtRepository：saveImport / getDocument / findByNovelId / getChapters / getBlocks 真实行为，其余为桩。 */
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

    /** 内存版 VocabularyRepository：saveVocabulary / findVocabularyByScope / saveCandidate / findCandidatesByNovel 真实行为，其余为桩。 */
    private class FakeVocabularyRepository : VocabularyRepository {
        private val vocabularies = mutableListOf<Vocabulary>()
        private val candidates = mutableListOf<VocabularyCandidate>()

        override fun saveVocabulary(vocabulary: Vocabulary) {
            if (vocabularies.none { it.vocabularyId == vocabulary.vocabularyId }) vocabularies += vocabulary
        }

        override fun findVocabularyByScope(scopeLevel: VocabularyScopeLevel): List<Vocabulary> =
            vocabularies.filter { it.scopeLevel == scopeLevel }

        override fun saveCandidate(candidate: VocabularyCandidate) {
            if (candidates.none { it.candidateId == candidate.candidateId }) candidates += candidate
        }

        override fun findCandidatesByNovel(novelId: NovelId): List<VocabularyCandidate> =
            candidates.filter { it.novelId == novelId }

        override fun saveEntry(entry: VocabularyEntry) = TODO()
        override fun findEntriesByVariant(variantId: VariantId): List<VocabularyEntry> = TODO()
        override fun findEntriesByNovel(novelId: NovelId): List<VocabularyEntry> = TODO()
        override fun saveRule(rule: VocabularyRule) = TODO()
    }

    /** 固定 JSON 输出的网关（覆盖 warning / empty 场景）。 */
    private class FixedResponseGateway(private val json: String) : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse = ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, json),
            usage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            finishReason = FinishReason.STOP,
        )
    }

    /** 首次抛 ProviderUnavailable、随后委托给定网关（覆盖失败→重试→成功）。 */
    private class FailOnceThenGateway(
        private val delegate: LLMGateway = MockLLMGateway(),
    ) : LLMGateway {
        var calls = 0
        override fun chat(request: ProviderRequest): ProviderResponse {
            calls++
            if (calls == 1) throw ProviderException.ProviderUnavailable("gateway down")
            return delegate.chat(request)
        }
    }

    private val validText = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"

    /** 用与 testScheduler 共享的调度器装配 ViewModel（真实引擎/UseCase + 内存仓储 + 注入网关）。 */
    private fun TestScope.viewModel(gateway: LLMGateway = MockLLMGateway()): AnalysisViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val novelRepo = FakeNovelRepository()
        val txtRepo = FakeTxtRepository()
        val vocabRepo = FakeVocabularyRepository()
        val txts = TxtUseCases(TxtPipeline(), txtRepo, novelRepo, ErrorMapper)
        // 先导入一条 TXT：生成 Original Novel + 绑定 document（Analysis 前置数据）。
        val out = txts.importTxtAsOriginal(TxtSource(validText.toByteArray(), "novel.txt"), title = "测试小说")
        val novel = novelRepo.getNovel(out.novelId) ?: error("导入应创建 Novel")
        val vocabularies = VocabularyUseCases(vocabRepo, ErrorMapper)
        val analysis = AnalysisUseCases(txtRepo, vocabRepo, AnalysisInputBuilder, gateway, ErrorMapper)
        return AnalysisViewModel(analysis, vocabularies, txts, novel, dispatcher)
    }

    /* 1. 初始状态：Idle */
    @Test
    fun `initial state is idle`() = runTest {
        val vm = viewModel()
        assertEquals(AnalysisUiState.Idle, vm.uiState.value)
        assertEquals(false, vm.candidatesLoaded.value)
        assertTrue(vm.candidates.value.isEmpty())
        Dispatchers.resetMain()
    }

    /* 2. 分析成功：Loading → Success，且候选被正确查询并展示 */
    @Test
    fun `successful analysis goes loading then success with candidates`() = runTest {
        val vm = viewModel()
        vm.startAnalysis()
        assertEquals(AnalysisUiState.Loading, vm.uiState.value, "触发后应立即进入 Loading")

        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<AnalysisUiState.Success>(state)
        assertEquals(listOf("灵石", "丹田"), state.suggestions.map { it.canonical }, "Mock 网关确定性输出 2 条建议")
        assertEquals(2, state.candidateCount, "candidateIds 应包含 2 条")

        assertTrue(vm.candidatesLoaded.value, "分析完成后应已执行候选查询")
        assertEquals(2, vm.candidates.value.size, "findCandidatesByNovel 应回读 2 条候选")
        assertEquals(1, vm.candidates.value.map { it.vocabularyId }.distinct().size, "候选应写入同一 NOVEL 词库")
        assertEquals("PENDING", vm.candidates.value.first().status.name)
        Dispatchers.resetMain()
    }

    /* 3. 分析警告：网关返回空词汇 → SuccessWithWarnings（非失败） */
    @Test
    fun `empty vocabulary leads to success with warnings`() = runTest {
        val vm = viewModel(FixedResponseGateway("""{"vocabulary":[]}"""))
        vm.startAnalysis()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<AnalysisUiState.SuccessWithWarnings>(state)
        assertTrue(state.warning.isNotBlank(), "应携带可读警告文案")
        assertTrue(state.suggestions.isEmpty())

        assertTrue(vm.candidatesLoaded.value)
        assertTrue(vm.candidates.value.isEmpty(), "无有效建议 → 空候选而非错误")
        Dispatchers.resetMain()
    }

    /* 4. 分析失败：网关抛 ProviderUnavailable → Error（中文文案，不崩溃） */
    @Test
    fun `provider unavailable maps to error`() = runTest {
        val vm = viewModel(FailOnceThenGateway())
        vm.startAnalysis()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<AnalysisUiState.Error>(state)
        assertEquals("分析服务不可用，请稍后重试", state.message)
        Dispatchers.resetMain()
    }

    /* 5. 失败 → 重试 → 成功：网关第二次可用后进入 Success */
    @Test
    fun `retry after error reloads to success`() = runTest {
        val vm = viewModel(FailOnceThenGateway())
        vm.startAnalysis()
        advanceUntilIdle()
        assertIs<AnalysisUiState.Error>(vm.uiState.value, "首次应失败进入 Error")

        vm.startAnalysis()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<AnalysisUiState.Success>(state, "重试后应成功")
        assertEquals(2, state.suggestions.size)
        assertEquals(2, vm.candidates.value.size, "重试成功后候选应被写入并回读")
        Dispatchers.resetMain()
    }

    /* 6. 未知异常不崩溃，映射为通用失败文案 */
    @Test
    fun `unexpected exception maps to generic error without crashing`() = runTest {
        val vm = viewModel(object : LLMGateway {
            override fun chat(request: ProviderRequest): ProviderResponse =
                throw IllegalStateException("boom")
        })
        vm.startAnalysis()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertIs<AnalysisUiState.Error>(state)
        assertTrue(state.message.contains("分析失败"), "未知异常应映射为用户可读文案")
        Dispatchers.resetMain()
    }
}
