package com.qianyan.engine.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P20-P2 · Controlled Markdown v1（core:engine）测试。
 * 覆盖：Parser（段落/空行/标题/强调/列表/引用/混合）、Validator（合法/非法结构）、
 *       非法结构安全降级（不破坏整篇文档）、Renderer（PlainText 往返）、确定性。
 */
class ControlledMarkdownTest {

    // ================= Parser：基本元素 =================

    @Test
    fun `plain paragraph parsed`() {
        val p = ControlledMarkdown.parse("第一段文字。")
        assertEquals(1, p.document.blocks.size)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("第一段文字。", (p.document.blocks[0] as MarkdownBlock.Paragraph).text)
        assertFalse(p.hasIllegalStructure)
    }

    @Test
    fun `blank line separates paragraphs`() {
        val p = ControlledMarkdown.parse("第一段。\n\n第二段。")
        assertEquals(2, p.document.blocks.size)
        assertTrue(p.document.blocks.all { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `headings level one to three`() {
        val p = ControlledMarkdown.parse("# 第一章\n\n## 第一节\n\n### 小节")
        val headings = p.document.blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(listOf(1, 2, 3), headings.map { it.level })
        assertEquals(listOf("第一章", "第一节", "小节"), headings.map { it.text })
    }

    @Test
    fun `bold and italic inline spans`() {
        val spans = ControlledMarkdown.inline("这是**重点**和*斜体*。")
        assertEquals(
            listOf(
                InlineSpan.Text("这是"),
                InlineSpan.Bold("重点"),
                InlineSpan.Text("和"),
                InlineSpan.Italic("斜体"),
                InlineSpan.Text("。"),
            ),
            spans,
        )
    }

    @Test
    fun `unordered list items`() {
        val p = ControlledMarkdown.parse("- 第一项\n- 第二项")
        val items = p.document.blocks.filterIsInstance<MarkdownBlock.UnorderedListItem>()
        assertEquals(listOf("第一项", "第二项"), items.map { it.text })
    }

    @Test
    fun `quote parsed`() {
        val p = ControlledMarkdown.parse("> 他说：你好")
        assertTrue(p.document.blocks[0] is MarkdownBlock.Quote)
        assertEquals("他说：你好", (p.document.blocks[0] as MarkdownBlock.Quote).text)
    }

    @Test
    fun `mixed elements parsed in order`() {
        val text = "# 标题\n\n段落一。\n\n- 列表项\n\n> 引用"
        val p = ControlledMarkdown.parse(text)
        assertEquals(4, p.document.blocks.size)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Heading)
        assertTrue(p.document.blocks[1] is MarkdownBlock.Paragraph)
        assertTrue(p.document.blocks[2] is MarkdownBlock.UnorderedListItem)
        assertTrue(p.document.blocks[3] is MarkdownBlock.Quote)
        assertFalse(p.hasIllegalStructure)
    }

    // ================= Validator：非法结构 =================

    @Test
    fun `table degraded not failure`() {
        val p = ControlledMarkdown.parse("| 列A | 列B |\n| --- | --- |")
        assertTrue(p.hasIllegalStructure)
        assertEquals(2, p.degradedCount)
        assertTrue(p.document.blocks.all { it is MarkdownBlock.Degraded })
    }

    @Test
    fun `image degraded`() {
        val p = ControlledMarkdown.parse("![图片](http://x)")
        assertTrue(p.hasIllegalStructure)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Degraded)
    }

    @Test
    fun `html degraded`() {
        val p = ControlledMarkdown.parse("<div>html</div>")
        assertTrue(p.hasIllegalStructure)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Degraded)
    }

    @Test
    fun `code fence degraded`() {
        val p = ControlledMarkdown.parse("```kotlin\nfun x() {}\n```")
        assertTrue(p.hasIllegalStructure)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Degraded)
    }

    @Test
    fun `task list degraded`() {
        val p = ControlledMarkdown.parse("- [ ] 待办")
        assertTrue(p.hasIllegalStructure)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Degraded)
    }

    @Test
    fun `nested list degraded to paragraphs`() {
        val p = ControlledMarkdown.parse("- 一级\n  - 二级")
        // 缩进嵌套行无法识别为受控列表元素 → 安全降级为普通段落（不破坏整篇文档）
        assertTrue(p.document.blocks.any { it is MarkdownBlock.UnorderedListItem })
        assertTrue(p.document.blocks.any { it is MarkdownBlock.Paragraph }, "嵌套行应降级为段落而非失败")
    }

    // ================= Degrade：不破坏整篇文档 =================

    @Test
    fun `illegal structure in middle degrades while surroundings survive`() {
        val text = "第一段。\n\n| 表格 | 内容 |\n\n最后一段。"
        val p = ControlledMarkdown.parse(text)
        assertTrue(p.hasIllegalStructure)
        assertEquals(1, p.degradedCount)
        assertEquals(3, p.document.blocks.size)
        assertTrue(p.document.blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(p.document.blocks[1] is MarkdownBlock.Degraded)
        assertTrue(p.document.blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `blank input yields empty document`() {
        val p = ControlledMarkdown.parse("   ")
        assertTrue(p.document.isEmpty)
        assertFalse(p.hasIllegalStructure)
    }

    // ================= Renderer =================

    @Test
    fun `plain text renderer preserves block semantics`() {
        val text = "# 标题\n\n段落一。\n\n- 列表\n\n> 引用"
        val parsed = ControlledMarkdown.parse(text)
        val rendered = PlainTextMarkdownRenderer.render(parsed.document)
        // 渲染后重新解析：块语义不变（Renderer 不改变文档语义）
        val reparsed = ControlledMarkdown.parse(rendered)
        assertEquals(parsed.document.blocks.size, reparsed.document.blocks.size)
        assertEquals(parsed.document.blocks, reparsed.document.blocks)
    }

    // ================= Determinism =================

    @Test
    fun `same input yields identical parse on repeat`() {
        val text = "# 标题\n\n段落**强调**。\n\n- 项"
        val a = ControlledMarkdown.parse(text)
        val b = ControlledMarkdown.parse(text)
        val c = ControlledMarkdown.parse(text)
        assertEquals(a, b)
        assertEquals(a, c)
    }
}
