package com.qianyan.application.usecase.context

import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextPackVersion
import com.qianyan.model.context.ContextPriority
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSelector
import com.qianyan.model.context.ContextSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * I6 · Selection / Priority / Budget（纯函数，无存储；§26 Selection / Budget）。
 *
 * 估算规则：token ≈ ceil(len / 4)，因此 `body(tokens)` 的估算 token 恰为 `tokens`。
 */
class ContextSelectionTest {

    private fun item(id: String, priority: ContextPriority, body: String): ContextItem = ContextItem(
        itemId = id,
        source = ContextSourceKind.NOVEL,
        content = body,
        priority = priority,
        estimatedSize = body.length,
    )

    private fun body(tokens: Int): String = "x".repeat(tokens * 4)

    @Test
    fun `priority ranking is high to low`() {
        assertTrue(ContextPriority.HIGH.rank < ContextPriority.MEDIUM.rank, "HIGH 先于 MEDIUM")
        assertTrue(ContextPriority.MEDIUM.rank < ContextPriority.LOW.rank, "MEDIUM 先于 LOW")
    }

    @Test
    fun `high priority items are selected before low priority ones`() {
        val high = item("high", ContextPriority.HIGH, body(10))
        val medium = item("medium", ContextPriority.MEDIUM, body(10))
        val low = item("low", ContextPriority.LOW, body(10))

        val selection = ContextSelector.select(listOf(low, medium, high), ContextBudget(maxTokens = 20))

        assertEquals(listOf("high", "medium"), selection.items.map { it.itemId }, "预算有限时高优先级先入选")
        assertEquals(listOf("low"), selection.budgetGuard.omittedItemIds)
        assertTrue(selection.budgetGuard.isTruncated)
        assertEquals(20L, selection.budgetGuard.estimatedTokens)
    }

    @Test
    fun `ties keep candidate declaration order`() {
        val first = item("m1", ContextPriority.MEDIUM, body(10))
        val second = item("m2", ContextPriority.MEDIUM, body(10))
        val third = item("m3", ContextPriority.MEDIUM, body(10))

        val selection = ContextSelector.select(listOf(first, second, third), ContextBudget(maxTokens = 20))

        assertEquals(listOf("m1", "m2"), selection.items.map { it.itemId }, "同级保持候选声明顺序（稳定排序）")
    }

    @Test
    fun `oversized single item is omitted without blocking smaller ones`() {
        val huge = item("huge", ContextPriority.HIGH, body(1000))
        val small = item("small-medium", ContextPriority.MEDIUM, body(5))

        val selection = ContextSelector.select(listOf(huge, small), ContextBudget(maxTokens = 20))

        assertEquals(listOf("small-medium"), selection.items.map { it.itemId }, "单条超预算被淘汰，不阻断其余条目")
        assertEquals(listOf("huge"), selection.budgetGuard.omittedItemIds, "淘汰记录可追踪")
    }

    @Test
    fun `several items compete for a limited budget by priority`() {
        val candidates = listOf(
            item("high-1", ContextPriority.HIGH, body(6)),
            item("high-2", ContextPriority.HIGH, body(6)),
            item("medium-1", ContextPriority.MEDIUM, body(6)),
            item("low-1", ContextPriority.LOW, body(6)),
        )

        val selection = ContextSelector.select(candidates, ContextBudget(maxTokens = 12))

        assertEquals(listOf("high-1", "high-2"), selection.items.map { it.itemId })
        assertEquals(2, selection.budgetGuard.omittedCount)
    }

    @Test
    fun `sufficient budget keeps every candidate`() {
        val candidates = listOf(
            item("a", ContextPriority.HIGH, body(10)),
            item("b", ContextPriority.MEDIUM, body(10)),
        )

        val selection = ContextSelector.select(candidates, ContextBudget(maxTokens = 20))

        assertEquals(listOf("a", "b"), selection.items.map { it.itemId })
        assertFalse(selection.budgetGuard.isTruncated)
        assertEquals(20L, selection.budgetGuard.estimatedTokens)
        assertTrue(selection.budgetGuard.omittedItemIds.isEmpty())
    }

    @Test
    fun `selection is repeatable for identical candidates`() {
        val candidates = listOf(
            item("b", ContextPriority.MEDIUM, body(10)),
            item("a", ContextPriority.HIGH, body(10)),
        )

        assertEquals(
            ContextSelector.select(candidates, ContextBudget(maxTokens = 20)),
            ContextSelector.select(candidates, ContextBudget(maxTokens = 20)),
            "同输入 ⇒ 同选择结果（确定性）",
        )
    }

    @Test
    fun `pack version is stable for identical inputs and changes with content`() {
        val request = ContextRequest(projectId = ProjectId("p-a"), purpose = IntentType.CONTINUE)
        val novelId = NovelId("n-a")
        val same = ContextSelector.select(listOf(item("a", ContextPriority.HIGH, body(2))), ContextBudget())
        val other = ContextSelector.select(listOf(item("a", ContextPriority.HIGH, body(3))), ContextBudget())

        assertEquals(
            ContextPackVersion.of(request, novelId, null, same),
            ContextPackVersion.of(request, novelId, null, same),
            "同输入 ⇒ 同版本指纹",
        )
        assertNotEquals(
            ContextPackVersion.of(request, novelId, null, same),
            ContextPackVersion.of(request, novelId, null, other),
            "内容变化 ⇒ 版本指纹变化",
        )
    }
}