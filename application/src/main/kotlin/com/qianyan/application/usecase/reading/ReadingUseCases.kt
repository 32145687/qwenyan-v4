package com.qianyan.application.usecase.reading

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.engine.markdown.ControlledMarkdown
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.reading.ReadingProgress
import com.qianyan.model.writing.DraftFormat
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.ReadingProgressRepository
import kotlinx.datetime.Clock

/**
 * P20-P4 · Reader 阅读 Use Case（FD-7 / FD-9）。
 *
 * 为 Android Reader 提供**受控的只读**阅读入口（UI 不直接触碰 Repository）：
 *  - [openChapter]：章节信息 + 正文展示块 + 阅读位置 + 上一章 / 下一章；
 *  - [savePosition]：保存阅读位置（块序号，FD-9 最小职责）；无进度时从默认位置 0 开始；
 *  - [position]：读取阅读位置（无记录 → null）。
 *
 * 硬约束：
 *  - **不新建第二套章节正文模型**：正文只来自既有 `Draft`（Chapter 无正文），阅读块来自 P2
 *    [ControlledMarkdown] 解析结果（[MarkdownBlock]）——同一份 P2 文档模型，Reader 只消费；
 *  - legacy（`Draft.format = null`）**不做 Markdown 解析**：整段原文作为单个纯文本块展示（PLAIN TEXT 行为）；
 *  - 章节无 Draft → 空块（不伪造正文）；
 *  - 相邻章节由既有 [ChapterRepository.listByNovel] 的 order 序确定性推导，不在 Android 维护第二套章节顺序；
 *  - 不 Decision、不调 LLM、不写正文；不实现阅读统计 / 时长 / 行为分析 / 云同步（后续阶段议题）。
 */
class ReadingUseCases(
    private val chapterRepository: ChapterRepository,
    private val draftRepository: DraftRepository,
    private val readingProgressRepository: ReadingProgressRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 打开一个章节用于阅读：组装 [ReaderChapter]。
     * @throws [ApplicationError.EntityNotFound] 章节不存在。
     */
    fun openChapter(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ReaderChapter {
        val chapter = guard { chapterRepository.findById(chapterId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
        val draft = guard { draftRepository.latestByChapter(chapterId) }

        val isControlledMarkdown = draft?.format == DraftFormat.CONTROLLED_MARKDOWN
        val blocks: List<MarkdownBlock> = when {
            draft == null -> emptyList()
            // 受控 Markdown v1：复用 P2 解析（Heading/Paragraph/UnorderedListItem/Quote/Degraded）
            isControlledMarkdown -> ControlledMarkdown.parse(draft.content).document.blocks
            // legacy 纯文本：不解析 Markdown，整段原文单块展示
            else -> listOf(MarkdownBlock.Paragraph(draft.content))
        }

        val chapters = guard { chapterRepository.listByNovel(novelId, variantId) }
        val index = chapters.indexOfFirst { it.chapterId == chapterId }
        val previous = if (index > 0) chapters[index - 1].chapterId else null
        val next = if (index >= 0 && index < chapters.lastIndex) chapters[index + 1].chapterId else null

        return ReaderChapter(
            novelId = novelId,
            variantId = variantId,
            chapterId = chapterId,
            chapterTitle = chapter.title,
            chapterOrder = chapter.order,
            hasDraft = draft != null,
            format = draft?.format,
            blocks = blocks,
            // FD-9：无阅读进度 → 默认位置 0（不伪造进度）
            position = guard { readingProgressRepository.get(chapterId) }?.position ?: DEFAULT_POSITION,
            previousChapterId = previous,
            nextChapterId = next,
        )
    }

    /** 保存某章节的阅读位置（块序号；负值归零）。 */
    fun savePosition(novelId: NovelId, chapterId: ChapterId, position: Int): ReadingProgress {
        val progress = ReadingProgress(
            novelId = novelId,
            chapterId = chapterId,
            position = position.coerceAtLeast(0),
            updatedAt = Clock.System.now(),
        )
        guard { readingProgressRepository.save(progress) }
        return progress
    }

    /** 读取某章节的阅读位置；无记录返回 null。 */
    fun position(chapterId: ChapterId): ReadingProgress? = guard { readingProgressRepository.get(chapterId) }

    private companion object {
        /** 无进度时的默认阅读位置（块序号）。 */
        const val DEFAULT_POSITION: Int = 0
    }
}

/**
 * Reader 用户层章节视图（不含 Repository / Draft 正文以外的内部对象）。
 *
 * [blocks] 为既有 P2 [MarkdownBlock] 列表（**同一份**文档模型，不复制第二套）：
 *  - `format = markdown:controlled:v1` → P2 解析结果（行内强调由 UI 经 `ControlledMarkdown.inline` 渲染）；
 *  - `format = null` 且 [hasDraft] → 单块纯文本（legacy PLAIN TEXT，按原文展示，不做 Markdown 解析）；
 *  - 无 Draft → 空列表。
 */
data class ReaderChapter(
    val novelId: NovelId,
    val variantId: VariantId?,
    val chapterId: ChapterId,
    val chapterTitle: String,
    val chapterOrder: Int,
    val hasDraft: Boolean,
    val format: String?,
    val blocks: List<MarkdownBlock>,
    /** 阅读位置（块序号；无进度 → 0）。超出当前块数时由展示层钳制，不视为错误。 */
    val position: Int,
    val previousChapterId: ChapterId?,
    val nextChapterId: ChapterId?,
) {
    val canGoPrevious: Boolean get() = previousChapterId != null
    val canGoNext: Boolean get() = nextChapterId != null
}