package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-O71（O-71②）· 词条只读列表：`VocabularyUseCases.listEntries(novelId)`。
 *
 * UI「词条」tab 需要列出**已确认词条**（`canonical → replacement/aliases`、作用域）。
 * 读侧只做委托（`repo.findEntriesByNovel`），不新增 Schema / 不改仓储接口；
 * 本类锁定"按作品隔离、字段原样、空库为空"三条。
 */
class VocabularyEntryListTest {

    private fun app(): ApplicationContainer =
        ApplicationContainer.open(analysisGateway = MockLLMGateway { error("本用例不应调用 LLM") })

    private fun entry(
        app: ApplicationContainer,
        novelId: NovelId,
        id: String,
        canonical: String,
        replacement: String? = null,
        aliases: List<String> = emptyList(),
    ) {
        val vocabularyId = app.vocabularies.getOrCreateNovelVocabulary(novelId)
        app.vocabularies.saveEntry(
            VocabularyEntry(
                entryId = VocabularyEntryId(id),
                vocabularyId = vocabularyId,
                novelId = novelId,
                variantId = null,
                scopeLevel = VocabularyScopeLevel.NOVEL,
                canonical = canonical,
                aliases = aliases,
                type = VocabularyEntryType.WORLD_TERM,
                replacement = replacement,
                status = VocabularyEntryStatus.APPROVED,
            ),
        )
    }

    @Test
    fun `list entries returns only the given novel entries`() {
        val app = app()
        val novelA = app.novels.createOriginal(title = "词条作品 A")
        val novelB = app.novels.createOriginal(title = "词条作品 B")
        entry(app, novelA, "e1", "星石", replacement = "星石", aliases = listOf("灵石"))
        entry(app, novelA, "e2", "北境", aliases = listOf("关外"))
        entry(app, novelB, "e3", "南疆")

        val entries = app.vocabularies.listEntries(novelA)

        assertEquals(2, entries.size, "只返回该作品的词条（跨作品隔离）")
        assertEquals(setOf("星石", "北境"), entries.map { it.canonical }.toSet())
        assertTrue(entries.none { it.canonical == "南疆" }, "不得泄漏其它作品的词条")
    }

    @Test
    fun `list entries is empty when the novel has none`() {
        val app = app()
        val novel = app.novels.createOriginal(title = "空词条作品")

        assertEquals(emptyList(), app.vocabularies.listEntries(novel))
    }

    @Test
    fun `list entries preserves replacement aliases and scope`() {
        val app = app()
        val novel = app.novels.createOriginal(title = "字段保真")
        entry(app, novel, "e1", "星石", replacement = "星石", aliases = listOf("灵石", "星矿"))

        val only = app.vocabularies.listEntries(novel).single()

        assertEquals("星石", only.canonical)
        assertEquals("星石", only.replacement, "replacement 必须原样返回（词表明细的 from → to 目标）")
        assertEquals(listOf("灵石", "星矿"), only.aliases)
        assertEquals(VocabularyScopeLevel.NOVEL, only.scopeLevel)
        assertEquals(VocabularyEntryStatus.APPROVED, only.status)
        assertEquals(VocabularyId("novel-vocab-${novel.value}"), only.vocabularyId)
    }

    @Test
    fun `list entries is a pure read that does not create a vocabulary container`() {
        val app = app()
        val novel = app.novels.createOriginal(title = "只读不改库")

        app.vocabularies.listEntries(novel)

        assertTrue(
            app.vocabularyRepository.findVocabularyByScope(VocabularyScopeLevel.NOVEL)
                .none { it.novelId == novel },
            "读侧不得顺带创建词库容器（写操作只应发生在 saveEntry / getOrCreate）",
        )
    }
}
