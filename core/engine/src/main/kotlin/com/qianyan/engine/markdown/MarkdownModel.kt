package com.qianyan.engine.markdown

/*
 * P20-P2 · Controlled Markdown v1 Document Model（纯 Kotlin，无第三方依赖）。
 *
 * FD-1 范围：普通段落 / 空行分隔 / H1-H3 / **bold** / *italic* / `- item` / `> quote`。
 * 禁止：表格 / 图片 / HTML / 代码块 / 脚本 / Front Matter / 脚注 / Task List / 复杂嵌套列表 / Anchor。
 * 非法结构 → Validator 标记为 DEGRADED（由 Parser 降级为普通段落），绝不使整篇文档失败。
 *
 * Parser 确定性：同一输入 → 同一 Document Model（无 LLM / 无时间 / 无随机 / 无外部状态）。
 * 后续 Android/Desktop Writer/Reader 复用本 Document Model + Renderer Contract。
 */

/** 受控 Markdown v1 文档：按块序稳定的结构模型。 */
data class MarkdownDocument(
    val blocks: List<MarkdownBlock>,
) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/** 文档块（封闭结构；不支持的结构不出现，仅降级为 [MarkdownBlock.Paragraph]）。 */
sealed interface MarkdownBlock {

    /** 普通段落（含行内强调 span）。 */
    data class Paragraph(val text: String) : MarkdownBlock

    /** 标题（level 1..3）。 */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** 简单无序列表项（`- item`；不支持嵌套）。 */
    data class UnorderedListItem(val text: String) : MarkdownBlock

    /** 引用（`> quote`）。 */
    data class Quote(val text: String) : MarkdownBlock

    /** 非法/不支持结构的降级载体（Validator 分类；保持原文本）。 */
    data class Degraded(val text: String) : MarkdownBlock
}

/** 行内强调 span（Bold / Italic / 普通文本）。 */
sealed interface InlineSpan {
    data class Text(val value: String) : InlineSpan
    data class Bold(val value: String) : InlineSpan
    data class Italic(val value: String) : InlineSpan
}

/** 解析结果：结构文档 + 校验摘要（非法结构计数）。 */
data class ParsedDocument(
    val document: MarkdownDocument,
    val degradedCount: Int,
    val hasIllegalStructure: Boolean,
)
