package com.qianyan.application.usecase.writing.consistency

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.story.ChapterPlan

/**
 * CharacterConsistency 的静态输入（Application 层 DTO；MVP 不序列化、不落库）。
 *
 * 当前章 Draft 只来自 ContextPack 的 DRAFT item；前章正文只来自 CanonicalRead。
 * World Model（Character / State / rules / events / memories）不在本模型复制，
 * 由调用方传入的 [com.qianyan.model.context.StoryWorldContext] 快照供 Validator 使用。
 */
internal data class CharacterConsistencyDraft(
    val draftId: DraftId,
    val content: String,
)

internal data class CharacterConsistencyCanonicalChapter(
    val chapterId: ChapterId,
    val chapterOrder: Int,
    val draftId: DraftId,
    val content: String,
    val variantId: VariantId? = null,
)

internal data class CharacterConsistencyInputCoverage(
    val currentDraftFromContextPack: Boolean,
    val planProvided: Boolean,
    val priorCanonicalCount: Int,
    val priorCanonicalGapChapterIds: List<ChapterId> = emptyList(),
)

internal data class CharacterConsistencyInput(
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val chapterId: ChapterId,
    val scope: CharacterConsistencyScope = CharacterConsistencyScope.CURRENT_CHAPTER,
    val contextPackId: String? = null,
    val currentDraft: CharacterConsistencyDraft? = null,
    val plan: ChapterPlan? = null,
    val priorCanonical: List<CharacterConsistencyCanonicalChapter> = emptyList(),
    val coverage: CharacterConsistencyInputCoverage,
    val missingInputs: List<CharacterConsistencyMissingInput> = emptyList(),
)
