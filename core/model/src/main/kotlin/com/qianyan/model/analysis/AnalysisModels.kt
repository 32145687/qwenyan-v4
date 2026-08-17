package com.qianyan.model.analysis

import com.qianyan.model.NovelId
import com.qianyan.model.TextBlockId
import com.qianyan.model.TxtChapterId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.vocabulary.VocabularyEntryType
import kotlinx.serialization.Serializable

/**
 * Analysis 领域（P6）：AI 语义分析的**确定性/中间态**最小模型。
 *
 * 边界（P6 决策）：
 *  - AnalysisInput 来自确定性 TXT 结构，**不含 LLM**，可溯源到原 TXT（sourceLocation）；
 *  - AnalysisResult 是 AI 输出的**可验证中间产物**（transient，不建表、不落正式 Domain）；
 *  - 不为"完整"过度建模：只有 Input/Result/Status；AnalysisTask 复用 TaskType.ANALYSIS+TaskStatus，不重复建模。
 */
@Serializable
enum class AnalysisStatus {
    /** 解析并校验成功。 */
    SUCCESS,
    /** 部分成功：有效输出 + 附带可忽略的警告。 */
    SUCCESS_WITH_WARNINGS,
    /** 无法得到可用结构化结果。 */
    FAILED,
}

/** 一个段落块的 Analysis 输入（保留到原 TXT 的偏移定位）。 */
@Serializable
data class AnalysisBlockInput(
    val blockId: TextBlockId,
    val ordinal: Int,
    val text: String,
    val sourceLocation: com.qianyan.model.txt.SourceLocation,
)

/** 一个章节的 Analysis 输入（章节粒度）。 */
@Serializable
data class AnalysisChapterInput(
    val chapterId: TxtChapterId,
    val ordinal: Int,
    val title: String,
    val blocks: List<AnalysisBlockInput>,
)

/** 整个 TXT 文档的 Analysis 输入：章节按出现顺序稳定排列。 */
@Serializable
data class AnalysisInput(
    val novelId: NovelId,
    val documentId: TxtDocumentId,
    val title: String,
    val chapters: List<AnalysisChapterInput>,
)

/** AI 输出的一个"词汇建议"（未确认前仅作为候选来源）。 */
@Serializable
data class VocabularySuggestion(
    val canonical: String,
    val type: VocabularyEntryType = VocabularyEntryType.PROPER_NOUN,
    val aliases: List<String> = emptyList(),
)

/** Analysis 结果（transient）：AI 输出的结构化、已校验中间产物，绝不直接写入正式实体。 */
@Serializable
data class AnalysisResult(
    val novelId: NovelId,
    val documentId: TxtDocumentId,
    val status: AnalysisStatus = AnalysisStatus.SUCCESS,
    val vocabularySuggestions: List<VocabularySuggestion> = emptyList(),
    val warning: String? = null,
)