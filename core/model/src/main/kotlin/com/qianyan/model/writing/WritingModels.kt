package com.qianyan.model.writing

import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * 创作写作最小领域模型（P11 写作 Pipeline 产物）。
 *
 * Draft 承载写作 Pipeline 生成的正文草稿，是最小必需的写作结果载体。只表达"一次写作的正文产物"，
 * 不做版本管理（多稿/历史由后续 Phase 决定），不承担规划/评审/修订的领域表达——
 * 规划复用 core:model.story 的 ChapterPlan，评审复用 core:model.spec 的 ValidationResult。
 *
 * 作用域语义（与 Novel / Variant 一致）：
 *  - variantId == null → scope = ORIGINAL（写入基座被禁止，见 ImmutableOriginal）。
 *  - variantId != null → scope = VARIANT（写作产物默认进入 Variant，不污染 Original）。
 *
 * 明确范围外（P11.1 Scaffold）：正文持久化（SQLDelight / Repository）、版本管理、真实验证。
 * sourceModel 用字符串承载模型标识（ModelProfile.id），领域层不依赖 :provider。
 */
@Serializable
data class Draft(
    val draftId: DraftId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    /** 目标章节（可空：尚未落到具体章节的草稿）。 */
    val chapterId: ChapterId? = null,
    /** 依据的章节规划（可空：无规划直写）。 */
    val planId: ChapterPlanId? = null,
    val content: String = "",
    val status: DraftStatus = DraftStatus.DRAFTING,
    /** 产出模型标识（ModelProfile.id），领域层以字符串承载，不依赖 :provider。 */
    val sourceModel: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 草稿生命周期（最小；与 ChapterStatus 解耦，写作产物独立表达）。 */
@Serializable
enum class DraftStatus {
    DRAFTING, WRITTEN, REVISED, FINAL,
}