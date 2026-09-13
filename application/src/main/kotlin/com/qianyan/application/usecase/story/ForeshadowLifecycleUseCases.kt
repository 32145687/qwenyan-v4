package com.qianyan.application.usecase.story

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.VariantId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.story.ForeshadowLifecycleRules
import com.qianyan.model.story.ForeshadowLifecycleState
import com.qianyan.storage.repository.StoryStateRepository
import kotlinx.datetime.Instant

/**
 * P13 LCL-C · Foreshadow 生命周期 Use Case。
 *
 * 只提供一个入口 [transitionForeshadow]，统一承载 状态机校验 + Variant 隔离 + 条件 UPDATE。
 * 职责顺序（冻结决策）：load entity → validate Variant → validate lifecycle transition → repository conditional update。
 *
 * 规则：
 *  - **Variant-only**：Original 上下文一律拒绝（复用既有 Variant 权限语义）；
 *  - **状态机**：非法 / 同态转换 → [ApplicationError.InvalidOperation]；终态出向非法；
 *  - **payoffChapterId**：仅 `RESOLVED` 允许落库，其它目标状态自动清除；
 *  - **re|insurance 并发一致**：底层为条件 UPDATE（WHERE id AND expected_state），affect=0 视为并发/状态已变 → 抛错；
 *  - **不新增** create/resolve/abandon 三套冗余 use case；不触碰 NarrativeState（不产生 Delta、不 bump version）。
 */
class ForeshadowLifecycleUseCases(
    private val storyStateRepository: StoryStateRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 迁移某 Foreshadow 到 [targetState]。
     * @throws [ApplicationError.InvalidOperation] Variant 缺失 / 非法过渡 / 并发状态不一致（affect=0）。
     */
    fun transitionForeshadow(
        ctx: VariantContext,
        foreshadowId: ForeshadowingId,
        targetState: ForeshadowLifecycleState,
        reason: String?,
        occurredAt: Instant,
        payoffChapterId: ChapterId? = null,
    ): Foreshadow {
        val vid = requireVariant(ctx)
        // 1) load entity
        val current = guard { storyStateRepository.getForeshadowById(foreshadowId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Foreshadow 不存在: ${foreshadowId.value}"))
        // 2) 仅允许迁移 Variant 自负伏笔（Original 只读；变体差异请走 ADD/Override）
        if (current.variantId != vid) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("仅能迁移 Variant 自有伏笔（Original 只读；变体差异请经 Variant ADD/Override）"),
            )
        }
        // 3) validate lifecycle（含同态非法）
        if (current.state == targetState || !ForeshadowLifecycleRules.canTransition(current.state, targetState)) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("非法 Foreshadow 过渡: ${current.state} -> $targetState"),
            )
        }
        // 4) payoff 仅 RESOLVED 允许
        val payoff = if (targetState == ForeshadowLifecycleState.RESOLVED) payoffChapterId else null
        val affected = guard {
            storyStateRepository.transitionForeshadowState(
                foreshadowId = foreshadowId,
                expectedState = current.state,
                targetState = targetState,
                reason = reason,
                occurredAt = occurredAt,
                payoffChapterId = payoff,
            )
        }
        if (affected != 1) {
            throw ApplicationException(ApplicationError.InvalidOperation("Foreshadow 并发状态变化，迁移未生效，请重试（当前=${current.state} 目标=$targetState）"))
        }
        return current.copy(
            state = targetState,
            updatedAt = occurredAt,
            lastTransitionReason = reason,
            payoffChapterId = payoff,
        )
    }

    private fun requireVariant(ctx: VariantContext): VariantId =
        ctx.variantId ?: throw ApplicationException(
            ApplicationError.InvalidOperation("Original 上下文禁止修改 Foreshadow 生命周期（需 Variant 上下文）"),
        )
}