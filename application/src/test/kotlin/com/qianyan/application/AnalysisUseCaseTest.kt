package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.BaseNovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P6.7 AnalysisUseCases（application）：TXT → AnalysisInput → VariantContext(ORIGINAL) →
 * MockProvider → AnalysisResult → Validation → VocabularyCandidate(PENDING)。
 * 覆盖：正常链路 / Provider 失败 / 非法输出 / 空建议 / Variant 隔离 / 错误映射 / 入库回读 / 失败无半成品。
 * 全程经 ApplicationContainer 访问 Use Case + 仓储接口回读，不触碰 Sqlite 实现。
 */
class AnalysisUseCaseTest {

    private fun source(text: String): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), "novel.txt")

    /** 有效多章正文：2 章节 + 3 段落块，status=SUCCESS。 */
    private val validText = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"

    private fun import(app: ApplicationContainer): Pair<String, String> {
        val out = app.txts.importTxtAsOriginal(source(validText), title = "我的小说")
        return out.documentId.value to out.novelId.value
    }

    private fun vocab(app: ApplicationContainer, novelId: String): VocabularyId {
        val id = VocabularyId("novel-vocab-$novelId")
        app.vocabularies.saveVocabulary(
            Vocabulary(vocabularyId = id, novelId = com.qianyan.model.NovelId(novelId), scopeLevel = VocabularyScopeLevel.NOVEL, name = "NOVEL词库"),
        )
        return id
    }

    private fun originalContext(novelId: String): VariantContext =
        VariantContext(baseNovelId = BaseNovelId(novelId))

    private val twoSuggestionJson =
        "{\"vocabulary\":[" +
            "{\"canonical\":\"灵石\",\"type\":\"WORLD_TERM\",\"aliases\":[]}," +
            "{\"canonical\":\"丹田\",\"type\":\"REALM\",\"aliases\":[\"气海\"]}" +
            "]}"

    private fun jsonResponse(json: String): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, json),
        usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        finishReason = FinishReason.STOP,
    )

    /* 1. 正常 Mock 全链路：2 个 PENDING VocabularyCandidate 入库回读 */
    @Test
    fun `normal mock analysis persists two pending candidates and returns result`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val out = app.analysis.analyzeTxtOriginal(
            com.qianyan.model.TxtDocumentId(docId),
            vocabId,
            originalContext(novelId),
        )

        assertEquals(2, out.candidateIds.size)
        assertEquals(2, out.chapterCount)
        assertEquals(3, out.blockCount)
        assertEquals(true, out.analysisResult.status.name == "SUCCESS")
        assertEquals(listOf("灵石", "丹田"), out.analysisResult.vocabularySuggestions.map { it.canonical })

        // 回读入库：PENDING 状态、NOVEL scope、variantId=null、suggested 条目在 CANDIDATE/APPROVED 边界安全
        val persisted = app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId))
        assertEquals(2, persisted.size)
        val canonicals = persisted.map { it.suggested.canonical }
        assertTrue(canonicals.containsAll(listOf("灵石", "丹田")))
        persisted.forEach { c ->
            assertEquals(VocabularyCandidateStatus.PENDING, c.status)
            assertEquals(VocabularyScopeLevel.NOVEL, c.scopeLevel)
            assertEquals(null, c.variantId)
            assertEquals(VocabularyEntryStatus.CANDIDATE, c.suggested.status)
            assertEquals("AUTO_EXTRACT", c.source.name)
        }
        val realm = persisted.first { it.suggested.canonical == "丹田" }
        assertEquals(listOf("气海"), realm.suggested.aliases)
    }

    /* 2. Provider 失败 → ProviderUnavailable，且无半成品候选 */
    @Test
    fun `provider failure maps to ProviderUnavailable and leaves no half-written candidates`() {
        val failing = MockLLMGateway { _ -> throw ProviderException.ProviderUnavailable("mock down") }
        val app = ApplicationContainer.open(analysisGateway = failing)
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val ex = assertFailsWith<ApplicationException> {
            app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))
        }
        assertIs<ApplicationError.ProviderUnavailable>(ex.error)
        assertTrue(app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId)).isEmpty())
    }

    /* 3. 非法 / 无法解析的 Provider 输出 → InvalidAnalysisOutput，且无半成品候选 */
    @Test
    fun `unparseable provider output maps to InvalidAnalysisOutput and leaves no candidates`() {
        val bad = MockLLMGateway { jsonResponse("this is not json {") }
        val app = ApplicationContainer.open(analysisGateway = bad)
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val ex = assertFailsWith<ApplicationException> {
            app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))
        }
        assertIs<ApplicationError.InvalidAnalysisOutput>(ex.error)
        assertTrue(app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId)).isEmpty())
    }

    /* 4. 空建议输出 → SUCCESS_WITH_WARNINGS，无候选入库 */
    @Test
    fun `empty suggestion output yields success with warnings and no candidates`() {
        val empty = MockLLMGateway { jsonResponse("{\"vocabulary\":[]}") }
        val app = ApplicationContainer.open(analysisGateway = empty)
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val out = app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))
        assertEquals("SUCCESS_WITH_WARNINGS", out.analysisResult.status.name)
        assertNotNull(out.analysisResult.warning)
        assertEquals(0, out.candidateIds.size)
        assertTrue(app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId)).isEmpty())
    }

    /* 5. Variant 上下文（variantId != null）→ InvalidOperation（P6 仅 Original） */
    @Test
    fun `variant context is rejected as invalid operation`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)
        val variantCtx = VariantContext(baseNovelId = BaseNovelId(novelId), variantId = VariantId("var-x"))

        val ex = assertFailsWith<ApplicationException> {
            app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, variantCtx)
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
        assertTrue(app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId)).isEmpty())
    }

    /* 6. baseNovelId 与实际 Novel 不匹配 → VariantMismatch */
    @Test
    fun `novel mismatch maps to VariantMismatch`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)
        val wrongCtx = VariantContext(baseNovelId = BaseNovelId("another-novel"))

        val ex = assertFailsWith<ApplicationException> {
            app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, wrongCtx)
        }
        assertIs<ApplicationError.VariantMismatch>(ex.error)
    }

    /* 7. 文档不存在 → EntityNotFound */
    @Test
    fun `missing document maps to EntityNotFound`() {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val (_, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val ex = assertFailsWith<ApplicationException> {
            app.analysis.analyzeTxtOriginal(
                com.qianyan.model.TxtDocumentId("no-such-doc"),
                vocabId,
                originalContext(novelId),
            )
        }
        assertIs<ApplicationError.EntityNotFound>(ex.error)
    }

    /* 8. 确定性：独立容器中相同输入产生相同 AnalysisResult 与候选顺序 */
    @Test
    fun `identical inputs yield identical analysis result deterministically`() {
        fun run(): List<String> {
            val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
            val (docId, novelId) = import(app)
            val vocabId = vocab(app, novelId)
            val out = app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))
            return out.analysisResult.vocabularySuggestions.map { it.canonical }
        }
        assertEquals(run(), run())
    }
}