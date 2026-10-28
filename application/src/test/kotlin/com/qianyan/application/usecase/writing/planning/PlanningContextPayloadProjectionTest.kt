package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.context.SeededProject
import com.qianyan.application.usecase.context.contextFixture
import com.qianyan.application.usecase.context.seedProject
import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.ContextBudgetGuard
import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextPayload
import com.qianyan.model.context.ContextPriority
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.context.FoundationPayload
import com.qianyan.model.context.NovelPayload
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.context.VocabularyPayload
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.WritingPolicy
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Phase 4B.1 · PlanningContext 的 Pack 覆盖字段必须从 payload 投影（不得回退 Repository）。 */
class PlanningContextPayloadProjectionTest {

    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun request(w: SeededProject) = UserWritingRequest(
        requestId = RequestId("req-pack"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, com.qianyan.model.context.TargetRefId(w.chapter2.value)),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(w.novelId.value),
        variantId = null,
        scope = VariantScope.ORIGINAL,
    )

    private fun item(
        source: ContextSourceKind,
        refKey: String,
        payload: ContextPayload?,
        content: String = "pack-content",
    ) = ContextItem(
        itemId = "${source.name.lowercase()}:$refKey",
        source = source,
        content = content,
        priority = ContextPriority.MEDIUM,
        estimatedSize = content.length,
        payload = payload,
    )

    private fun pack(w: SeededProject, items: List<ContextItem>) = ContextPack(
        packId = "ctx-pack-test",
        request = ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2),
        novelId = w.novelId,
        activeVariantId = null,
        items = items,
        budgetGuard = ContextBudgetGuard(budgetTokens = 1200, estimatedTokens = 1, isTruncated = false),
        packVersion = 1L,
        createdAt = now,
    )

    private fun coveredItems() = listOf(
        item(
            ContextSourceKind.NOVEL,
            "pack-novel",
            NovelPayload("PACK-书", listOf("pack-genre"), "pack-synopsis"),
        ),
        item(
            ContextSourceKind.STORY_FOUNDATION,
            "pack-foundation",
            FoundationPayload(
                genre = listOf(GenreId("pack-genre")),
                direction = StoryDirection(theme = "pack-theme", conflict = "pack-conflict"),
                audience = NarrativeProfile(pov = "pack-pov"),
                policy = WritingPolicy(rules = listOf("pack-rule")),
            ),
        ),
        item(
            ContextSourceKind.VOCABULARY,
            "pack-vocab",
            VocabularyPayload("pack-canon", listOf("pack-alias"), "pack-replacement"),
        ),
    )

    @Test
    fun `covered fields come from payload and resolver fields keep their path`() {
        val f = contextFixture()
        val w = seedProject(f, "REPO-书", "A")

        val context = f.app.planningContextAssembly.assemble(request(w), pack(w, coveredItems()))

        assertEquals("PACK-书", context.novelTitle, "novelTitle 必须来自 NovelPayload")
        assertNotEquals("REPO-书", context.novelTitle, "不得回退到 Repository 读取")
        assertEquals(listOf("pack-genre"), context.novelGenre)
        assertEquals("pack-synopsis", context.novelSynopsis)
        assertEquals("pack-theme", context.foundation?.direction?.theme)
        assertEquals(listOf(GenreId("pack-genre")), context.foundation?.confirmedGenre)
        assertEquals("pack-canon", context.vocabulary.single().canonical)
        assertEquals("pack-replacement", context.vocabulary.single().replacement)

        // characters / memories 仍来自 StoryWorldContextResolver（不属于 Pack payload 范围）
        val resolver = f.app.storyWorldContextResolver.resolve(
            novelId = w.novelId,
            variantId = null,
            scope = VariantScope.ORIGINAL,
            worldSummary = "PACK-书\npack-synopsis",
        )
        assertEquals(resolver.orderedVisible, context.memories)
        assertEquals(resolver.characters.map { it.characterId }, context.characters.map { it.characterId })
        f.close()
    }

    @Test
    fun `missing novel payload fails fast`() {
        val f = contextFixture()
        val w = seedProject(f, "REPO-书", "A")
        val items = coveredItems().filterNot { it.source == ContextSourceKind.NOVEL }

        val error = assertFailsWith<ApplicationException> {
            f.app.planningContextAssembly.assemble(request(w), pack(w, items))
        }
        assertIs<ApplicationError.InvalidOperation>(error.error)
        f.close()
    }

    @Test
    fun `wrong payload type fails fast`() {
        val f = contextFixture()
        val w = seedProject(f, "REPO-书", "A")
        val items = coveredItems().map { item ->
            if (item.source == ContextSourceKind.NOVEL) {
                item.copy(payload = VocabularyPayload("wrong", emptyList(), null))
            } else {
                item
            }
        }

        val error = assertFailsWith<ApplicationException> {
            f.app.planningContextAssembly.assemble(request(w), pack(w, items))
        }
        assertIs<ApplicationError.InvalidOperation>(error.error)
        f.close()
    }
}
