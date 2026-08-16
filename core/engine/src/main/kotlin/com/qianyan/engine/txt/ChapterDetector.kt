package com.qianyan.engine.txt

import com.qianyan.model.NovelId
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.txt.SourceLocation
import com.qianyan.model.txt.TextBlock
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.txt.TxtIssue
import com.qianyan.model.txt.TxtIssueKind

/**
 * 确定性章节检测器（P4）。
 *
 * 输入：规范化文本；输出：结构化章节 + 段落块 + 解析提示。
 * - 单遍懒扫描（按段落），不切全文数组、无 O(n²)，适合大文本；
 * - 命中 [ChapterRules] 的段落作为章节标题，其余作为正文段落；
 * - 标题前的"前置内容"归入无标题隐式章节（title=""）；
 * - 未识别到任何标题 → 单个隐式章节 + NO_CHAPTER_HEADING 提示（不强行猜测）；
 * - 空章节 → EMPTY_CHAPTER；标题重复 → DUPLICATE_CHAPTER_TITLE（仍按出现顺序切分）。
 *
 * 确定性：同一输入 + 同一规则版本 → 完全相同的结构；ID 由 documentId + 序号派生（无随机）。
 */
class ChapterDetector {

    private data class Open(
        val id: TxtChapterId,
        val ordinal: Int,
        val title: String,
        val loc: SourceLocation,
        val firstBlockOrdinal: Int,
    )

    fun detect(documentId: TxtDocumentId, normalizedText: String, novelId: NovelId? = null): DetectResult {
        val chapters = mutableListOf<TxtChapter>()
        val blocks = mutableListOf<TextBlock>()
        val warnings = mutableListOf<TxtIssue>()
        val seenTitles = mutableSetOf<String>()
        var open: Open? = null
        var headingsFound = false

        fun closeOpen() {
            val o = open ?: return
            val blockCount = blocks.size - o.firstBlockOrdinal
            if (blockCount == 0 && o.title.isNotBlank()) {
                warnings.add(
                    TxtIssue(
                        IssueSeverity.WARNING, TxtIssueKind.EMPTY_CHAPTER,
                        "章节「${o.title}」无正文", o.loc.startOffset, o.loc.endOffset,
                    )
                )
            }
            chapters.add(
                TxtChapter(o.id, documentId, novelId, o.ordinal, o.title, o.loc, o.firstBlockOrdinal, blockCount)
            )
            open = null
        }

        for ((text, start) in paragraphs(normalizedText)) {
            if (ChapterRules.isChapterHeading(text)) {
                headingsFound = true
                closeOpen()
                val ordinal = chapters.size
                val loc = SourceLocation(start, start + text.length)
                if (seenTitles.contains(text)) {
                    warnings.add(
                        TxtIssue(
                            IssueSeverity.WARNING, TxtIssueKind.DUPLICATE_CHAPTER_TITLE,
                            "章节标题重复：$text", start, start + text.length,
                        )
                    )
                }
                seenTitles.add(text)
                open = Open(TxtChapterId("$documentId.value#c$ordinal"), ordinal, text, loc, blocks.size)
            } else {
                if (open == null) {
                    // 前置内容（front matter）：无标题隐式章节
                    open = Open(TxtChapterId("$documentId.value#c0"), 0, "", SourceLocation(0, 0), blocks.size)
                }
                val ordinal = blocks.size
                blocks.add(
                    TextBlock(
                        blockId = TextBlockId("$documentId.value#b$ordinal"),
                        chapterId = open!!.id,
                        documentId = documentId,
                        novelId = novelId,
                        ordinal = ordinal,
                        text = text,
                        sourceLocation = SourceLocation(start, start + text.length),
                    )
                )
            }
        }
        closeOpen()

        if (!headingsFound) {
            warnings.add(
                TxtIssue(
                    IssueSeverity.WARNING, TxtIssueKind.NO_CHAPTER_HEADING,
                    "全文未识别到章节标题，已作为单个文档处理",
                )
            )
        }
        return DetectResult(chapters, blocks, warnings)
    }

    /** 懒扫描规范化文本的段落（以 "\n\n" 分隔；规范化保证段落内无换行）。 */
    private fun paragraphs(text: String): Sequence<Pair<String, Int>> = sequence {
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            if (i + 1 < n && text[i] == '\n' && text[i + 1] == '\n') {
                yield(text.substring(start, i) to start)
                i += 2
                start = i
            } else {
                i++
            }
        }
        if (start < n) yield(text.substring(start) to start)
    }
}

/** 检测输出：结构化章节 + 段落块 + 提示。 */
data class DetectResult(
    val chapters: List<TxtChapter>,
    val blocks: List<TextBlock>,
    val warnings: List<TxtIssue>,
)
