package com.qianyan.application.usecase.change

import com.qianyan.model.change.ChangeKind
import com.qianyan.model.change.DiffLineKind
import com.qianyan.model.change.TextDiffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I9 · 确定性文本 Diff 测试（§12 Diff）。
 *
 * 覆盖：identical / base 空 / working 空 / 双方空 / 原位修改 / 中间插入 / 中间删除 /
 * 多行 / 空字符串 / CRLF 与 LF 等价 / 末尾换行 / 行号 / 确定性 / 稳定顺序。
 */
class TextDifferTest {

    @Test
    fun `identical content is unchanged with a single unchanged hunk`() {
        val diff = TextDiffer.diff("第一段。\n第二段。", "第一段。\n第二段。")

        assertEquals(ChangeKind.UNCHANGED, diff.op)
        assertFalse(diff.hasChanges)
        assertEquals(1, diff.hunks.size)
        assertEquals(DiffLineKind.UNCHANGED, diff.hunks.single().kind)
        assertEquals(listOf("第一段。", "第二段。"), diff.hunks.single().lines)
        assertEquals(1, diff.hunks.single().baseStartLine)
        assertEquals(1, diff.hunks.single().workingStartLine)
        assertEquals(2, diff.unchangedLines)
        assertEquals(0, diff.addedLines)
        assertEquals(0, diff.removedLines)
    }

    @Test
    fun `empty base is added`() {
        val diff = TextDiffer.diff("", "第一段。\n第二段。")

        assertEquals(ChangeKind.ADDED, diff.op)
        assertTrue(diff.hasChanges)
        assertEquals(1, diff.hunks.size)
        assertEquals(DiffLineKind.ADDED, diff.hunks.single().kind)
        assertEquals(listOf("第一段。", "第二段。"), diff.hunks.single().lines)
        assertNull(diff.hunks.single().baseStartLine)
        assertEquals(1, diff.hunks.single().workingStartLine)
        assertEquals(2, diff.addedLines)
        assertEquals(0, diff.removedLines)
    }

    @Test
    fun `empty working is removed`() {
        val diff = TextDiffer.diff("旧一\n旧二", "")

        assertEquals(ChangeKind.REMOVED, diff.op)
        assertEquals(1, diff.hunks.size)
        assertEquals(DiffLineKind.REMOVED, diff.hunks.single().kind)
        assertNull(diff.hunks.single().workingStartLine)
        assertEquals(1, diff.hunks.single().baseStartLine)
        assertEquals(2, diff.removedLines)
        assertEquals(0, diff.addedLines)
    }

    @Test
    fun `both empty or blank only differences are unchanged without hunks`() {
        val empty = TextDiffer.diff("", "")
        assertEquals(ChangeKind.UNCHANGED, empty.op)
        assertTrue(empty.hunks.isEmpty(), "空文本 ⇒ 无 hunk")
        assertEquals(0, empty.unchangedLines)

        assertEquals(ChangeKind.UNCHANGED, TextDiffer.diff("   \n", "\n\n").op, "仅空白差异 ⇒ 无实际变化")
    }

    @Test
    fun `changed line is modified with prefix replace and suffix hunks`() {
        val diff = TextDiffer.diff("一\n二\n三", "一\n二改\n三")

        assertEquals(ChangeKind.MODIFIED, diff.op)
        assertEquals(
            listOf(DiffLineKind.UNCHANGED, DiffLineKind.REMOVED, DiffLineKind.ADDED, DiffLineKind.UNCHANGED),
            diff.hunks.map { it.kind },
            "hunk 顺序固定：不变前缀 → 删除段 → 新增段 → 不变后缀",
        )
        assertEquals(listOf("一"), diff.hunks[0].lines)
        assertEquals(listOf("二"), diff.hunks[1].lines)
        assertEquals(listOf("二改"), diff.hunks[2].lines)
        assertEquals(listOf("三"), diff.hunks[3].lines)
        assertEquals(2, diff.unchangedLines)
        assertEquals(1, diff.addedLines)
        assertEquals(1, diff.removedLines)
    }

    @Test
    fun `middle insertion is added with unchanged context`() {
        val diff = TextDiffer.diff("一\n三", "一\n二\n三")

        assertEquals(ChangeKind.ADDED, diff.op)
        assertEquals(listOf(DiffLineKind.UNCHANGED, DiffLineKind.ADDED, DiffLineKind.UNCHANGED), diff.hunks.map { it.kind })
        assertEquals(listOf("二"), diff.hunks[1].lines)
        assertEquals(2, diff.hunks[1].workingStartLine, "插入位置为 1-based 第 2 行")
        assertEquals(1, diff.addedLines)
        assertEquals(0, diff.removedLines)
        assertEquals(2, diff.unchangedLines)
    }

    @Test
    fun `middle deletion is removed with unchanged context`() {
        val diff = TextDiffer.diff("一\n二\n三", "一\n三")

        assertEquals(ChangeKind.REMOVED, diff.op)
        assertEquals(listOf(DiffLineKind.UNCHANGED, DiffLineKind.REMOVED, DiffLineKind.UNCHANGED), diff.hunks.map { it.kind })
        assertEquals(listOf("二"), diff.hunks[1].lines)
        assertEquals(2, diff.hunks[1].baseStartLine)
        assertEquals(1, diff.removedLines)
        assertEquals(0, diff.addedLines)
    }

    @Test
    fun `line numbers are one based and reference the correct side`() {
        val diff = TextDiffer.diff("一\n二\n三", "一\n二改\n三")

        assertEquals(1, diff.hunks[0].baseStartLine)
        assertEquals(1, diff.hunks[0].workingStartLine)
        assertEquals(2, diff.hunks[1].baseStartLine)
        assertNull(diff.hunks[1].workingStartLine, "删除段无 working 侧行号")
        assertNull(diff.hunks[2].baseStartLine, "新增段无 base 侧行号")
        assertEquals(2, diff.hunks[2].workingStartLine)
        assertEquals(3, diff.hunks[3].baseStartLine)
        assertEquals(3, diff.hunks[3].workingStartLine)
    }

    @Test
    fun `crlf cr and lf newlines are equivalent`() {
        assertEquals(ChangeKind.UNCHANGED, TextDiffer.diff("一\r\n二\r\n", "一\n二").op)
        assertEquals(ChangeKind.UNCHANGED, TextDiffer.diff("一\r二", "一\n二").op)
        assertEquals(listOf("一", "二"), TextDiffer.lines("一\r\n二\r\n"))
    }

    @Test
    fun `trailing newline does not create an extra empty line`() {
        assertEquals(listOf("一"), TextDiffer.lines("一\n"))
        assertEquals(emptyList(), TextDiffer.lines(""))
        assertEquals(ChangeKind.UNCHANGED, TextDiffer.diff("一\n", "一").op)
    }

    @Test
    fun `multi line replacement keeps the full changed block`() {
        val diff = TextDiffer.diff("头\n旧一\n旧二\n尾", "头\n新一\n新二\n尾")

        assertEquals(ChangeKind.MODIFIED, diff.op)
        assertEquals(listOf("旧一", "旧二"), diff.hunks.single { it.kind == DiffLineKind.REMOVED }.lines)
        assertEquals(listOf("新一", "新二"), diff.hunks.single { it.kind == DiffLineKind.ADDED }.lines)
        assertEquals(2, diff.unchangedLines)
    }

    @Test
    fun `diff is deterministic across repeated runs`() {
        val first = TextDiffer.diff("一\n二\n三", "一\n二改\n三\n四")
        val second = TextDiffer.diff("一\n二\n三", "一\n二改\n三\n四")
        val third = TextDiffer.diff("一\n二\n三", "一\n二改\n三\n四")

        assertEquals(first, second, "同输入 ⇒ 同结果（无随机 / 无时间 / 无 LLM）")
        assertEquals(second, third)
        assertEquals(
            first.hunks.map { it.kind },
            third.hunks.map { it.kind },
            "hunk 顺序稳定",
        )
    }
}