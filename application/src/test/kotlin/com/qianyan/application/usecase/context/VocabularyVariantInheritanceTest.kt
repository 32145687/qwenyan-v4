package com.qianyan.application.usecase.context

import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.VocabularyPayload
import com.qianyan.model.core.VariantContext
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VocabularyVariantInheritanceTest {

    private class Seed(
        val f: ContextFixture,
        val w: SeededProject,
        val variantId: VariantId,
        val variantScope: ContextBuildScope,
        val originalScope: ContextBuildScope,
        val request: ContextRequest,
    ) {
        val source = VocabularySource(f.app.vocabularyRepository)

        fun items(scope: ContextBuildScope) = source.candidates(scope, request)

        fun canonical(item: com.qianyan.model.context.ContextItem): String =
            (item.payload as VocabularyPayload).canonical

        fun close() = f.close()
    }

    private fun seed(): Seed {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val variantId = VariantId("variant-A")
        f.app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(w.novelId.value), variantId = variantId),
            name = "变体A",
            variantId = variantId,
        )
        val novelVocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(w.novelId)
        val variantVocabularyId = VocabularyId("variant-vocab-A")
        f.app.vocabularyRepository.saveVocabulary(
            Vocabulary(
                vocabularyId = variantVocabularyId,
                novelId = w.novelId,
                variantId = variantId,
                scopeLevel = VocabularyScopeLevel.VARIANT,
                name = "VARIANT词库",
            ),
        )

        fun save(
            entryId: String,
            canonical: String,
            variant: Boolean,
            status: VocabularyEntryStatus = VocabularyEntryStatus.APPROVED,
            aliases: List<String> = emptyList(),
            replacement: String? = null,
        ) {
            f.app.vocabularyRepository.saveEntry(
                VocabularyEntry(
                    entryId = VocabularyEntryId(entryId),
                    vocabularyId = if (variant) variantVocabularyId else novelVocabularyId,
                    novelId = w.novelId,
                    variantId = if (variant) variantId else null,
                    scopeLevel = if (variant) VocabularyScopeLevel.VARIANT else VocabularyScopeLevel.NOVEL,
                    canonical = canonical,
                    aliases = aliases,
                    type = VocabularyEntryType.PROPER_NOUN,
                    replacement = replacement,
                    status = status,
                ),
            )
        }

        save("ve-n-only", "N-独有", variant = false)
        save("ve-n-overridden", "覆盖词", variant = false, aliases = listOf("N别名"), replacement = "N替换")
        save("ve-n-candidate", "N-未批准", variant = false, status = VocabularyEntryStatus.CANDIDATE)
        save("ve-n-rejected", "N-拒绝", variant = false, status = VocabularyEntryStatus.REJECTED)
        save("ve-v-only", "V-独有", variant = true)
        save("ve-v-overridden", "覆盖词", variant = true, aliases = listOf("V别名"), replacement = "V替换")
        save("ve-v-candidate", "V-未批准", variant = true, status = VocabularyEntryStatus.CANDIDATE)
        save("ve-v-rejected", "V-拒绝", variant = true, status = VocabularyEntryStatus.REJECTED)

        val variantScope = ContextBuildScope(
            projectId = w.projectId,
            novelId = w.novelId,
            activeVariantId = variantId,
            state = f.app.projects.state(w.projectId),
            focusChapterId = w.chapter2,
        )
        val originalScope = variantScope.copy(activeVariantId = null)
        return Seed(
            f = f,
            w = w,
            variantId = variantId,
            variantScope = variantScope,
            originalScope = originalScope,
            request = ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE),
        )
    }

    @Test
    fun `variant activation includes variant-only approved entries`() {
        val s = seed()
        val items = s.items(s.variantScope)
        assertTrue(items.any { s.canonical(it) == "V-独有" }, "VARIANT 独有条目必须出现")
        s.close()
    }

    @Test
    fun `variant activation includes novel-only approved entries`() {
        val s = seed()
        val items = s.items(s.variantScope)
        assertTrue(items.any { s.canonical(it) == "N-独有" }, "NOVEL 独有条目必须作为基座出现")
        assertTrue(items.any { s.canonical(it) == "A 苏清" }, "seedProject 的 NOVEL 词条也必须继承")
        s.close()
    }

    @Test
    fun `variant entry with same canonical overrides novel entry`() {
        val s = seed()
        val items = s.items(s.variantScope)
        val overridden = items.filter { s.canonical(it) == "覆盖词" }
        assertEquals(1, overridden.size, "同一 canonical 只能保留一条")
        val item = overridden.single()
        assertEquals("vocabulary:ve-v-overridden", item.itemId)
        val payload = item.payload as VocabularyPayload
        assertEquals(listOf("V别名"), payload.aliases)
        assertEquals("V替换", payload.replacement)
        s.close()
    }

    @Test
    fun `only approved entries from both scopes are included`() {
        val s = seed()
        val canonicals = s.items(s.variantScope).map { s.canonical(it) }.toSet()
        assertEquals(setOf("A 苏清", "N-独有", "覆盖词", "V-独有"), canonicals)
        assertTrue("N-未批准" !in canonicals && "N-拒绝" !in canonicals)
        assertTrue("V-未批准" !in canonicals && "V-拒绝" !in canonicals)
        s.close()
    }

    @Test
    fun `variant merge order is deterministic`() {
        val s = seed()
        val first = s.items(s.variantScope).map { s.canonical(it) to it.itemId.removePrefix("vocabulary:") }
        val second = s.items(s.variantScope).map { s.canonical(it) to it.itemId.removePrefix("vocabulary:") }
        assertEquals(first, second)
        assertEquals(first.sortedWith(compareBy({ it.first }, { it.second })), first)
        s.close()
    }

    @Test
    fun `original scope behavior remains novel-only`() {
        val s = seed()
        val items = s.items(s.originalScope)
        val expected = s.f.app.vocabularyRepository.findEntriesByNovel(s.w.novelId)
            .filter { it.status == VocabularyEntryStatus.APPROVED }
            .sortedWith(compareBy({ it.canonical }, { it.entryId.value }))
        assertEquals(expected.map { it.entryId.value }, items.map { it.itemId.removePrefix("vocabulary:") })
        val overridden = items.first { s.canonical(it) == "覆盖词" }.payload as VocabularyPayload
        assertEquals(listOf("N别名"), overridden.aliases)
        assertEquals("N替换", overridden.replacement)
        assertTrue(items.none { s.canonical(it) == "V-独有" })
        s.close()
    }
}
