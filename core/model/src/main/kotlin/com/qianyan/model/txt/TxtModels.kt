package com.qianyan.model.txt

import com.qianyan.model.NovelId
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.spec.IssueSeverity
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * P4 TXT Pipeline 领域模型（core:model，纯数据、可序列化、平台无关）。
 *
 * 为什么新增（最小补充，而非复用已有模型）：
 *  - 现有 com.qianyan.model.story.Chapter 是创作规划节点，仅含元数据（无正文），其注释明确
 *    "正文存储与 Draft 在 Writing/Storage 层"，不能承载 TXT 原始文本结构；
 *  - 现有 com.qianyan.model.spec.ValidationIssue 面向输出校验（field/severity/message/值），
 *    无文本源位置语义；com.qianyan.model.knowledge.SourceReference 是知识引用，不是偏移区间。
 *  因此新增本包表达"原始文本结构 + 管线结果"，复用已有 IssueSeverity / NovelId / Instant。
 *
 * 确定性约定：
 *  - ruleVersion 记录 Normalizer/Detector 规则版本：相同输入 + 相同版本 → 相同结构；
 *  - contentHash = 规范化文本的确定性指纹（SHA-256），用于去重与一致性校验；
 *  - createdAt 仅作书签元数据，不参与业务结果比较（P4 确定性测试以结构 + contentHash 为准）。
 *
 * 与后续阶段关系：
 *  - P5：TextBlock/TxtChapter 是 TextChunk（FTS 可检索单元）与 VocabularyCandidate 输入的结构来源；
 *  - P6：Analysis 以章节粒度为输入。
 */

/** TXT 读取检测到的编码。P4 支持 UTF-8 家族；其它编码在 Importer 明确报错，不静默猜测。 */
@Serializable
enum class TxtEncoding { UTF8, UTF8_BOM }

/** 管线结果状态。Warnings 不导致失败；不可读取/不可解码/空正文必须失败。 */
@Serializable
enum class TxtParseStatus { SUCCESS, SUCCESS_WITH_WARNINGS, FAILED }

/** 解析问题分类（生成 ParseWarning / Issue，供 UI 提示与测试断言）。 */
@Serializable
enum class TxtIssueKind {
    /** 全文未识别到任何章节标题 → 生成单个隐式章节并提示。 */
    NO_CHAPTER_HEADING,
    /** 相邻两个章节标题之间没有正文。 */
    EMPTY_CHAPTER,
    /** 章节标题重复（保留原始结构，按出现顺序切分）。 */
    DUPLICATE_CHAPTER_TITLE,
}

/** 单条解析问题 / 警告。severity 复用 spec.IssueSeverity（ERROR/WARNING/INFO）。 */
@Serializable
data class TxtIssue(
    val severity: IssueSeverity,
    val kind: TxtIssueKind,
    val message: String,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1,
)

/** 在规范化文本中的源位置区间 [startOffset, endOffset)（确定性，可追溯原文）。 */
@Serializable
data class SourceLocation(val startOffset: Int, val endOffset: Int)

/**
 * TXT 文档（导入根实体）。
 * originalText 始终保留；normalizedText 为确定性规范化结果；novelId 可选
 * （P4 导入独立成 Original Source，不自动创建 Novel / Variant；绑定属 P5）。
 */
@Serializable
data class TxtDocument(
    val documentId: TxtDocumentId,
    val novelId: NovelId? = null,
    val sourceName: String = "",
    val title: String = "",
    val encoding: TxtEncoding = TxtEncoding.UTF8,
    val hadBom: Boolean = false,
    val byteCount: Long = 0,
    val charCount: Int = 0,
    val originalText: String = "",
    val normalizedText: String = "",
    val contentHash: String = "",
    val ruleVersion: String = "",
    val status: TxtParseStatus = TxtParseStatus.SUCCESS,
    val createdAt: Instant,
)

/** TXT 结构化章节：原始文本切分出的章节（≠ 创作规划 Chapter）。 */
@Serializable
data class TxtChapter(
    val chapterId: TxtChapterId,
    val documentId: TxtDocumentId,
    val novelId: NovelId? = null,
    /** 0-based 章节序号（保持出现顺序）。 */
    val ordinal: Int = 0,
    /** 标题行（去首尾空白）；前置内容/无章节时为 ""。 */
    val title: String = "",
    /** 标题行在规范化文本中的位置；无标题章节为 [0,0)。 */
    val sourceLocation: SourceLocation = SourceLocation(0, 0),
    /** 本章第一个 TextBlock 在文档块序列中的 ordinal。 */
    val firstBlockOrdinal: Int = 0,
    /** 本章 TextBlock 数量（0 = 空章节）。 */
    val blockCount: Int = 0,
)

/** TXT 段落块：章节内稳定文本顺序单元（正文段落）。 */
@Serializable
data class TextBlock(
    val blockId: TextBlockId,
    val chapterId: TxtChapterId,
    val documentId: TxtDocumentId,
    val novelId: NovelId? = null,
    /** 文档内全局块序号（0-based，不重排、不丢、不重复）。 */
    val ordinal: Int = 0,
    /** 段落文本（已规范化；原始内容保留于 TxtDocument.originalText）。 */
    val text: String = "",
    val sourceLocation: SourceLocation = SourceLocation(0, 0),
)

/** Importer 确定性结果：字节 → 文本 阶段产出。 */
@Serializable
data class ImportResult(
    val documentId: TxtDocumentId,
    val status: TxtParseStatus = TxtParseStatus.SUCCESS,
    val encoding: TxtEncoding = TxtEncoding.UTF8,
    val hadBom: Boolean = false,
    val byteCount: Long = 0,
    val charCount: Int = 0,
    val paragraphCount: Int = 0,
    val warnings: List<TxtIssue> = emptyList(),
)

/** Parser 确定性结果：文本 → 结构化章节/段落 阶段产出。 */
@Serializable
data class ParseResult(
    val documentId: TxtDocumentId,
    val status: TxtParseStatus = TxtParseStatus.SUCCESS,
    val chapters: List<TxtChapter> = emptyList(),
    val blocks: List<TextBlock> = emptyList(),
    val warnings: List<TxtIssue> = emptyList(),
)
