package com.qianyan.application.usecase.writing.confirmation

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.repository.DraftRepository
import kotlinx.datetime.Clock

/**
 * 最小 HITL 确认闸门（P12.1.4）。
 *
 * 用户确认的对象是**某个具体的 Final Draft**（而非"当前任务"或整个 lineage）。确认只在 Application /
 * UseCase 业务边界执行，不依赖 UI、不进入 Agent / LLM。
 *
 * 语义：
 * ```
 * FINAL / PENDING_CONFIRMATION → CONFIRMED
 * ```
 * Knowledge Update 只允许对 [CONFIRMED] Final Draft 执行（见 KnowledgeUpdateExecutionUseCase）。
 *
 * 约束（全部类型化，禁止 RuntimeException）：
 *  - Draft 必须存在 → [ApplicationError.EntityNotFound]；
 *  - Novel / Variant 作用域必须匹配调用方上下文 → [ApplicationError.VariantMismatch]；
 *  - 必须是已最终定稿（FINAL / PENDING_CONFIRMATION）或已确认（CONFIRMED，幂等）→ 否则 [ApplicationError.InvalidOperation]；
 *  - 已 CONFIRMED 的重复确认是幂等的（CONFIRMED → CONFIRMED），绝不再触发任何 Agent / LLM / Knowledge Update。
 *
 * 本类只调用 [DraftRepository]（持久化确认状态），不触碰 Agent / 不把正文写入 Checkpoint。
 */
class ConfirmationExecutionUseCase(
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 确认一个具体 Final Draft。
     * @param variantId 调用方当前 Variant 作用域（null = Original）。必须与 Draft 一致，否则跨实体拒绝。
     * @return 确认后的 [Draft]（status = [DraftStatus.CONFIRMED]；已是 CONFIRMED 则原样返回）。
     */
    fun confirmFinalDraft(draftId: DraftId, novelId: NovelId, variantId: VariantId? = null): Draft {
        val persisted = guard { draftRepository.getById(draftId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("待确认 Draft 不存在: ${draftId.value}"),
            )

        // Scope isolation：Novel / Variant 必须匹配调用方上下文，否则跨实体拒绝。
        if (persisted.novelId != novelId) {
            throw ApplicationException(
                ApplicationError.VariantMismatch(
                    "Draft(${draftId.value}) 属于 Novel(${persisted.novelId.value})，与调用方 Novel(${novelId.value}) 不一致，禁止确认",
                ),
            )
        }
        if (persisted.variantId != variantId) {
            throw ApplicationException(
                ApplicationError.VariantMismatch(
                    "Draft(${draftId.value}) 属于 Variant(${persisted.variantId?.value ?: "null"})，与调用方 Variant(${variantId?.value ?: "null"}) 不一致，禁止确认",
                ),
            )
        }

        // Final 前提：仅已最终定稿（FINAL / PENDING_CONFIRMATION）或已确认（CONFIRMED）可确认。
        if (persisted.status !in CONFIRMABLE) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Draft(${draftId.value}) 未最终定稿（status=${persisted.status}），不允许确认",
                ),
            )
        }

        // 幂等：已 CONFIRMED → 原样返回，不再写库 / 不触发任何副作用。
        if (persisted.status == DraftStatus.CONFIRMED) return persisted

        val confirmed = persisted.copy(status = DraftStatus.CONFIRMED, updatedAt = Clock.System.now())
        guard { draftRepository.save(confirmed) }
        return confirmed
    }

    private companion object {
        /** 可提交确认的状态：已最终定稿或已确认。 */
        val CONFIRMABLE = setOf(DraftStatus.FINAL, DraftStatus.PENDING_CONFIRMATION, DraftStatus.CONFIRMED)
    }
}