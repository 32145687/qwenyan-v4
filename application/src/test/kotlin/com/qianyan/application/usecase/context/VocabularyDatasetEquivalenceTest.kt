package com.qianyan.application.usecase.context

import com.qianyan.model.IntentType
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.context.VocabularyPayload
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 4B.1 · J-1：VOCABULARY 数据源与正式 Assembly 的 approved entry 集合一致。 */
class VocabularyDatasetEquivalenceTest {

    @Test
    fun `vocabulary source matches approved entries and excludes pending candidates`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(w.novelId)
        f.app.vocabularyRepository.saveEntry(
            VocabularyEntry(
                entryId = VocabularyEntryId("ve-A-replacement"),
                vocabularyId = vocabularyId,
                novelId = w.novelId,
                canonical = "A 灵石",
                replacement = "A 星石",
                type = VocabularyEntryType.WORLD_TERM,
                status = VocabularyEntryStatus.APPROVED,
            ),
        )
        val scope = ContextBuildScope(w.projectId, w.novelId, null, f.app.projects.state(w.projectId), w.chapter2)

        val items = VocabularySource(f.app.vocabularyRepository).candidates(
            scope,
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE),
        )

        val expected = f.app.vocabularyRepository.findEntriesByNovel(w.novelId)
            .filter { it.status == VocabularyEntryStatus.APPROVED }
        assertEquals(
            expected.map { it.entryId.value }.sorted(),
            items.map { it.itemId.removePrefix("vocabulary:") }.sorted(),
            "必须与 approved entry 集合一一对应",
        )
        assertEquals(2, items.size)
        assertTrue(items.none { it.itemId.contains(w.candidateId.value) }, "pending candidate 不得混入")

        val payloads = items.map { it.payload as VocabularyPayload }
        assertEquals(listOf("A 灵石", "A 苏清"), payloads.map { it.canonical }, "按 canonical 确定性排序")
        val byCanonical = payloads.associateBy { it.canonical }
        assertEquals(listOf("A 苏青"), byCanonical.getValue("A 苏清").aliases)
        assertNull(byCanonical.getValue("A 苏清").replacement)
        assertEquals("A 星石", byCanonical.getValue("A 灵石").replacement)
        assertEquals(ContextSourceKind.VOCABULARY, items[0].source)
        f.close()
    }
}
