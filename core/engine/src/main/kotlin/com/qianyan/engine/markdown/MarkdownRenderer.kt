package com.qianyan.engine.markdown

/*
 * P20-P2 · Controlled Markdown v1 Renderer Contract（FD-1；稳定核心接口）。
 *
 * 本阶段只建立 contract，不实现平台 UI Renderer。
 * Android/Desktop Writer/Reader 在后续 Phase 复用本接口消费同一 [MarkdownDocument] 语义，
 * 避免各自实现一套解析/语义。
 *
 * 实现方职责：把 [MarkdownDocument]（或单块）渲染为平台可显示形式；不得改变文档语义。
 */
interface MarkdownRenderer {

    /** 渲染整篇文档。 */
    fun render(document: MarkdownDocument): String

    /** 渲染单个块（供增量渲染/选区）。 */
    fun renderBlock(block: MarkdownBlock): String

    /** 渲染行内 span（供段内强调展示）。 */
    fun renderInline(spans: List<InlineSpan>): String
}

/**
 * 纯文本 Renderer（无 Markdown 语法，仅用于 legacy/预览；文档语义不改变）。
 * 供测试与最小文本消费方复用；平台 UI Renderer 后续实现。
 */
object PlainTextMarkdownRenderer : MarkdownRenderer {

    override fun render(document: MarkdownDocument): String =
        document.blocks.joinToString("\n") { renderBlock(it) }

    override fun renderBlock(block: MarkdownBlock): String = when (block) {
        is MarkdownBlock.Heading -> "#".repeat(block.level) + " " + block.text
        is MarkdownBlock.UnorderedListItem -> "- " + block.text
        is MarkdownBlock.Quote -> "> " + block.text
        is MarkdownBlock.Degraded -> block.text
        is MarkdownBlock.Paragraph -> renderInline(parseInline(block.text))
    }

    override fun renderInline(spans: List<InlineSpan>): String = spans.joinToString("") { span ->
        when (span) {
            is InlineSpan.Text -> span.value
            is InlineSpan.Bold -> "**" + span.value + "**"
            is InlineSpan.Italic -> "*" + span.value + "*"
        }
    }

    private fun parseInline(text: String): List<InlineSpan> =
        ControlledMarkdown.inline(text).ifEmpty { listOf(InlineSpan.Text(text)) }
}
