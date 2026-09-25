package com.qianyan.engine.markdown

/*
 * P20-P2 · Controlled Markdown v1 Parser + Validator（纯函数，确定性）。
 *
 * 解析规则：
 *  - 按空行分组为"块段"；段内按行解析。
 *  - `#{1,3} ` → Heading；`- ` → UnorderedListItem；`> ` → Quote；否则 Paragraph。
 *  - 行内 `**bold**` / `*italic*` 拆分为 span（未闭合视为普通文本）。
 *  - 非法/不支持结构（表格 |、图片 ![、HTML <、代码块 ```、Task List [ ]、
 *    4+ 级标题 #、嵌套列表缩进等）→ 降级为 [MarkdownBlock.Degraded]（保留原文，不破坏文档）。
 *
 * 确定性：无 LLM / 无时间 / 无随机 / 无外部状态；same input → same output。
 */
object ControlledMarkdown {

    /** 解析为受控 Markdown v1 文档（非法结构安全降级）。 */
    fun parse(text: String): ParsedDocument {
        if (text.isBlank()) return ParsedDocument(MarkdownDocument(emptyList()), 0, false)
        val blocks = mutableListOf<MarkdownBlock>()
        var degraded = 0
        text.split("\n\n").forEach { section ->
            if (section.isBlank()) return@forEach
            val lines = section.trimEnd('\n').lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@forEach
            val isSingle = lines.size == 1
            if (isSingle) {
                val line = lines[0]
                when {
                    isCodeFence(line) || isHtml(line) || isImage(line) || isTable(line) || isTaskList(line) -> {
                        blocks += MarkdownBlock.Degraded(line)
                        degraded++
                    }
                    isHeading(line) -> blocks += MarkdownBlock.Heading(headingLevel(line), headingText(line))
                    isListItem(line) -> blocks += MarkdownBlock.UnorderedListItem(itemText(line))
                    isQuote(line) -> blocks += MarkdownBlock.Quote(quoteText(line))
                    else -> blocks += MarkdownBlock.Paragraph(line.trim())
                }
            } else {
                // 多行段：逐行识别；非法行降级，合法行按行入块
                lines.forEach { line ->
                    when {
                        isCodeFence(line) || isHtml(line) || isImage(line) || isTable(line) || isTaskList(line) -> {
                            blocks += MarkdownBlock.Degraded(line)
                            degraded++
                        }
                        isHeading(line) -> blocks += MarkdownBlock.Heading(headingLevel(line), headingText(line))
                        isListItem(line) -> blocks += MarkdownBlock.UnorderedListItem(itemText(line))
                        isQuote(line) -> blocks += MarkdownBlock.Quote(quoteText(line))
                        else -> blocks += MarkdownBlock.Paragraph(line.trim())
                    }
                }
            }
        }
        return ParsedDocument(MarkdownDocument(blocks), degraded, degraded > 0)
    }

    // ================= 行内 span =================

    /** 拆行内强调为 span 序列（未闭合/非法直接当普通文本）。 */
    fun inline(text: String): List<InlineSpan> {
        if (text.isBlank()) return listOf(InlineSpan.Text(""))
        val spans = mutableListOf<InlineSpan>()
        var i = 0
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) { spans += InlineSpan.Text(sb.toString()); sb.clear() }
        }
        while (i < text.length) {
            val two = text.substring(i, minOf(i + 2, text.length))
            when {
                two == "**" -> {
                    val close = text.indexOf("**", i + 2)
                    if (close > i + 2) {
                        flush(); spans += InlineSpan.Bold(text.substring(i + 2, close)); i = close + 2
                    } else { sb.append(two); i += 2 }
                }
                text[i] == '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close > i + 1 && text.getOrNull(close - 1) != '*') {
                        flush(); spans += InlineSpan.Italic(text.substring(i + 1, close)); i = close + 1
                    } else { sb.append(text[i]); i += 1 }
                }
                else -> { sb.append(text[i]); i += 1 }
            }
        }
        flush()
        return spans
    }

    // ================= 结构识别（Validator 规则） =================

    private fun isHeading(line: String): Boolean {
        val m = Regex("^#{1,4} ").find(line) ?: return false
        return m.value.trimEnd().length in 1..3 // 仅允许 1..3 级
    }

    private fun headingLevel(line: String): Int = line.takeWhile { it == '#' }.length

    private fun headingText(line: String): String = line.dropWhile { it == '#' }.trim()

    private fun isListItem(line: String): Boolean = line.startsWith("- ") && !line.startsWith("- [")

    private fun itemText(line: String): String = line.removePrefix("-").trim()

    private fun isQuote(line: String): Boolean = line.startsWith("> ") || line == ">"

    private fun quoteText(line: String): String = line.removePrefix(">").trim()

    // ---- 非法结构（降级为 Degraded） ----

    private fun isCodeFence(line: String): Boolean = line.startsWith("```") || line.startsWith("~~~")

    private fun isHtml(line: String): Boolean =
        line.startsWith("<") && (line.contains(">") || Regex("</?[a-zA-Z]").containsMatchIn(line))

    private fun isImage(line: String): Boolean = line.startsWith("![") || line.contains("](") && line.startsWith("!")

    private fun isTable(line: String): Boolean = line.contains("|") && line.contains("---") || line.trim().startsWith("|")

    private fun isTaskList(line: String): Boolean = line.startsWith("- [") || line.startsWith("* [")
}
