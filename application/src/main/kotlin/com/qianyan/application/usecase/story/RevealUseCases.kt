package com.qianyan.application.usecase.story

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.RevealId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Reveal
import com.qianyan.storage.repository.StoryStateRepository

/**
 * P13 LCL-D · Reveal Use Case（Reader-only Story State fact）。
 *
 * 冻结边界：
 *  - **Variant-only**：Original 上下文拒绝（Original READ ONLY）；Variant own+Override。
 *  - **duplicate handling**：同一 revealId 重复创建 → [ApplicationError.DuplicateTarget]（stable identity）。
 *  - **deterministic ordering**：listByScope 按 occurredAt asc → revealId asc。
 *  - **不进入** NarrativeState（不产 NarrativeDelta、不 bump version）；**不触发** Foreshadow transition。
 *  - 主体仅 READER：无 revealedTo / CHARACTER 语义。
 */
class RevealUseCases(
    private val storyStateRepository: StoryStateRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 创建一个 Reveal（Variant 作用域；Original 拒绝；重复 revealId 拒绝）。 */
    fun createReveal(ctx: VariantContext, entity: Reveal): Reveal {
        val vid = requireVariant(ctx)
        if (guard { storyStateRepository.getRevealById(entity.revealId) } != null) {
            throw ApplicationException(
                ApplicationError.DuplicateTarget("Reveal 已存在（同理 attribution，stable identity）: ${entity.revealId.value}"),
            )
        }
        val e = entity.copy(
            novelId = NovelId(ctx.baseNovelId.value),
            variantId = vid,
            scope = VariantScope.VARIANT,
        )
        guard { storyStateRepository.saveReveal(e) }
        return e
    }

    fun getRevealById(revealId: RevealId): Reveal? =
        guard { storyStateRepository.getRevealById(revealId) }

    /** 按 novel+variant 列出（occurredAt asc → revealId asc；Original 只读只读此 scope 无 reveal）。 */
    fun listByScope(ctx: VariantContext): List<Reveal> =
        guard { storyStateRepository.listReveals(NovelId(ctx.baseNovelId.value), ctx.variantId) }

    private fun requireVariant(ctx: VariantContext): VariantId =
        ctx.variantId ?: throw ApplicationException(
            ApplicationError.InvalidOperation("Original 上下文禁止创建 Reveal（需 Variant 上下文；Original READ ONLY）"),
        )
}