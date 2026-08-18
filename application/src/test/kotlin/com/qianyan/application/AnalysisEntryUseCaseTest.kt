package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P7.6 最小 UI 查询入口（application）：Android Analysis 流程进入前所需的两个查询能力。
 *  - [TxtUseCases.findDocumentsByNovel]：Novel → 绑定 TXT 文档（documentId 来源）。
 *  - [VocabularyUseCases.getOrCreateNovelVocabulary]：Novel → NOVEL 词库容器（vocabularyId 来源，复用不重复创建）。
 * 全程经 [ApplicationContainer]（真实内存库 + Mock 网关），不改 Schema / core:model。
 */
class AnalysisEntryUseCaseTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private val validText = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"

    /* 1. findDocumentsByNovel：导入后可查到绑定文档，documentId 与导入结果一致 */
    @Test
    fun `findDocumentsByNovel returns imported document bound to novel`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(TxtSource(validText.toByteArray(), "novel.txt"), title = "测试小说")

        val docs = app.txts.findDocumentsByNovel(out.novelId)

        assertEquals(1, docs.size)
        assertEquals(out.documentId, docs.single().documentId)
        assertEquals(out.novelId, docs.single().novelId)
    }

    /* 2. findDocumentsByNovel：无 TXT 绑定的 Novel 返回空列表（非错误） */
    @Test
    fun `findDocumentsByNovel empty for novel without txt`() {
        val app = container()
        val novelId = app.novels.createOriginal(title = "无TXT小说")

        assertTrue(app.txts.findDocumentsByNovel(novelId).isEmpty())
    }

    /* 3. getOrCreateNovelVocabulary：首次创建、再次复用同一 id，且不产生重复词库 */
    @Test
    fun `getOrCreateNovelVocabulary creates once then reuses same id`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(TxtSource(validText.toByteArray(), "novel.txt"), title = "测试小说")

        val first = app.vocabularies.getOrCreateNovelVocabulary(out.novelId)
        val second = app.vocabularies.getOrCreateNovelVocabulary(out.novelId)

        assertEquals(first, second, "再次获取应复用同一词库 id")
        val novelVocabs = app.vocabularies.query(VocabularyScopeLevel.NOVEL).filter { it.novelId == out.novelId }
        assertEquals(1, novelVocabs.size, "不应创建重复 NOVEL 词库")
        assertEquals(first, novelVocabs.single().vocabularyId)
        assertEquals(VocabularyScopeLevel.NOVEL, novelVocabs.single().scopeLevel)
    }

    /* 4. 端到端：导入 → 查文档 → 取词库 → 分析 → 回读候选，通过新增入口完整跑通 */
    @Test
    fun `full flow through new entry points imports documents and analyses`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(TxtSource(validText.toByteArray(), "novel.txt"), title = "测试小说")

        val document = app.txts.findDocumentsByNovel(out.novelId).single()
        val vocabularyId = app.vocabularies.getOrCreateNovelVocabulary(out.novelId)
        val result = app.analysis.analyzeTxtOriginal(
            documentId = document.documentId,
            vocabularyId = vocabularyId,
            variantContext = com.qianyan.model.core.VariantContext(
                baseNovelId = com.qianyan.model.BaseNovelId(out.novelId.value),
            ),
        )

        assertEquals(listOf("灵石", "丹田"), result.analysisResult.vocabularySuggestions.map { it.canonical })
        val candidates = app.vocabularies.findCandidatesByNovel(out.novelId)
        assertEquals(2, candidates.size)
        assertEquals(vocabularyId, candidates.first().vocabularyId)
    }
}
