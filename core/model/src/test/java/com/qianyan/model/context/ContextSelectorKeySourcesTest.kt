package com.qianyan.model.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 4B.1 · J-2 两阶段预算选择（关键来源 NOVEL / STORY_FOUNDATION / VOCABULARY）。 */
class ContextSelectorKeySourcesTest {

    private fun item(
        id: String,
        source: ContextSourceKind,
        priority: ContextPriority,
        chars: Int,
    ) = ContextItem(
        itemId = id,
        source = source,
        content = "x".repeat(chars),
        priority = priority,
        estimatedSize = chars,
    )

    @Test
    fun `budget sufficient keeps all items in the existing priority order`() {
        val candidates = listOf(
            item("draft:d", ContextSourceKind.DRAFT, ContextPriority.HIGH, 4),
            item("novel:n", ContextSourceKind.NOVEL, ContextPriority.LOW, 4),
            item("story_foundation:f", ContextSourceKind.STORY_FOUNDATION, ContextPriority.MEDIUM, 4),
            item("vocabulary:v", ContextSourceKind.VOCABULARY, ContextPriority.LOW, 4),
        )

        val selection = ContextSelector.select(candidates, ContextBudget(maxTokens = 100))

        assertEquals(
            listOf("draft:d", "story_foundation:f", "novel:n", "vocabulary:v"),
            selection.items.map { it.itemId },
            "预算充足时必须保持既有顺序（priority rank + 同级声明顺序）",
        )
        assertTrue(selection.budgetGuard.omittedItemIds.isEmpty())
        assertEquals(4, selection.budgetGuard.estimatedTokens)
    }

    @Test
    fun `key sources are protected ahead of draft under tight budget`() {
        val candidates = listOf(
            item("draft:d", ContextSourceKind.DRAFT, ContextPriority.HIGH, 100), // 25 tokens
            item("novel:n", ContextSourceKind.NOVEL, ContextPriority.LOW, 4), // 1
            item("story_foundation:f", ContextSourceKind.STORY_FOUNDATION, ContextPriority.MEDIUM, 4), // 1
            item("vocabulary:v", ContextSourceKind.VOCABULARY, ContextPriority.LOW, 4), // 1
        )

        val selection = ContextSelector.select(candidates, ContextBudget(maxTokens = 3))

        assertEquals(
            setOf("novel:n", "story_foundation:f", "vocabulary:v"),
            selection.items.map { it.itemId }.toSet(),
            "关键来源必须优先于 DRAFT",
        )
        assertEquals(listOf("draft:d"), selection.budgetGuard.omittedItemIds)
        assertEquals(3, selection.budgetGuard.estimatedTokens)
    }

    @Test
    fun `key sources over budget are partially covered deterministically`() {
        val candidates = listOf(
            item("novel:n", ContextSourceKind.NOVEL, ContextPriority.LOW, 4), // 1
            item("story_foundation:f", ContextSourceKind.STORY_FOUNDATION, ContextPriority.MEDIUM, 8), // 2
            item("vocabulary:v", ContextSourceKind.VOCABULARY, ContextPriority.LOW, 8), // 2
        )

        val selection = ContextSelector.select(candidates, ContextBudget(maxTokens = 2))

        assertEquals(listOf("novel:n"), selection.items.map { it.itemId })
        assertEquals(
            listOf("story_foundation:f", "vocabulary:v"),
            selection.budgetGuard.omittedItemIds,
            "关键淘汰顺序必须固定（NOVEL → STORY_FOUNDATION → VOCABULARY）",
        )
    }

    @Test
    fun `duplicate source ref is selected once and recorded as omitted`() {
        val candidates = listOf(
            item("draft:d", ContextSourceKind.DRAFT, ContextPriority.HIGH, 4),
            item("draft:d", ContextSourceKind.DRAFT, ContextPriority.HIGH, 4),
        )

        val selection = ContextSelector.select(candidates, ContextBudget(maxTokens = 100))

        assertEquals(listOf("draft:d"), selection.items.map { it.itemId })
        assertEquals(listOf("draft:d"), selection.budgetGuard.omittedItemIds)
        assertEquals(1, selection.budgetGuard.omittedCount)
    }

    @Test
    fun `selection and omission are reproducible`() {
        val candidates = listOf(
            item("draft:d", ContextSourceKind.DRAFT, ContextPriority.HIGH, 40),
            item("novel:n", ContextSourceKind.NOVEL, ContextPriority.LOW, 4),
            item("chapter:c", ContextSourceKind.CHAPTER, ContextPriority.MEDIUM, 40),
        )

        val first = ContextSelector.select(candidates, ContextBudget(maxTokens = 5))
        val second = ContextSelector.select(candidates, ContextBudget(maxTokens = 5))

        assertEquals(first.items.map { it.itemId }, second.items.map { it.itemId })
        assertEquals(first.budgetGuard, second.budgetGuard)
    }
}
