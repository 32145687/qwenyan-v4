package com.qianyan.application.usecase.tool

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyId
import kotlinx.serialization.Serializable

/*
 * I5 · Product Tool 的类型化 Input / Output DTO（Agent-facing 稳定结构）。
 *
 * 只暴露稳定视图字段（强类型 ID / 枚举名 / ISO 时间），不暴露 SQLite Row / Repository 对象；
 * 与既有 `ToolRequest/ToolResult`（P10 JsonObject 边界）之间仅在 `Tool.execute` 适配器转换。
 */

// ---- Input（类型化；禁止 Map<String, Any> / 裸 JsonObject 作为核心输入）----

@Serializable
data class GetProjectInput(val projectId: String)

@Serializable
data class GetProjectStateInput(val projectId: String)

@Serializable
data class GetNovelInput(val novelId: String)

@Serializable
data class ListChaptersInput(val novelId: String, val variantId: String? = null)

@Serializable
data class GetChapterInput(val chapterId: String)

@Serializable
data class GetLatestDraftInput(val chapterId: String)

@Serializable
data class SearchVocabularyInput(val novelId: String, val query: String? = null)

// ---- Output（稳定视图）----

/** 项目聚合视图（身份 + 运行态引用；不含 Novel 元数据副本）。 */
@Serializable
data class ProjectView(
    val projectId: ProjectId,
    val novelId: NovelId,
    val activeVariantId: VariantId? = null,
    val activeChapterId: ChapterId? = null,
    val activeTaskId: TaskId? = null,
)

/** IDE / Agent 运行态视图（≠ World Model）。 */
@Serializable
data class ProjectStateView(
    val projectId: ProjectId,
    val novelId: NovelId,
    val activeVariantId: VariantId? = null,
    val activeChapterId: ChapterId? = null,
    val activeTaskId: TaskId? = null,
    val updatedAt: String,
)

@Serializable
data class GetProjectStateResult(val state: ProjectStateView?)

@Serializable
data class NovelView(
    val novelId: NovelId,
    val projectId: ProjectId,
    val title: String,
    val source: String,
    val genre: List<String> = emptyList(),
    val synopsis: String = "",
    val scope: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ChapterView(
    val chapterId: ChapterId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: String,
    val title: String,
    val order: Int,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ListChaptersResult(val chapters: List<ChapterView>)

@Serializable
data class DraftView(
    val draftId: DraftId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val chapterId: ChapterId? = null,
    val previousDraftId: DraftId? = null,
    val content: String,
    val format: String? = null,
    val status: String,
    val sourceModel: String = "",
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class VocabularyCandidateView(
    val candidateId: VocabularyCandidateId,
    val vocabularyId: VocabularyId,
    val novelId: NovelId? = null,
    val canonical: String,
    val aliases: List<String> = emptyList(),
    val type: String,
    val source: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class SearchVocabularyResult(val candidates: List<VocabularyCandidateView>)