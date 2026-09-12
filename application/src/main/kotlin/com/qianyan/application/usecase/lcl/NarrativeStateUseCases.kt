package com.qianyan.application.usecase.lcl

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.lcl.NarrativeDelta
import com.qianyan.model.lcl.NarrativeState
import com.qianyan.storage.repository.NarrativeStateRepository

/**
 * Narrative State 叙事账本 Use Case（P13 LCL-A）。
 *
 * 职责：**确定性折叠 + 作用域约束**，不调用 LLM / 不修改 Agent / 不修改 Workflow。
 *  - appendNarrativeDelta：追加本章增量；**Original（variantId=null）账本一律拒绝写入**（保持既有
 *    "Original 只读"原则）；scope 归一化与 variantId 一致；
 *  - projectNarrativeState：确定性折叠各 Delta → 写回快照（version = Delta 数），返回当前状态；
 *  - getNarrativeState：读取最近折叠快照。
 *
 * 变体隔离：账本身份 = (novelId, variantId)；不同 Variant / 不同 Novel 间互不可见（由仓储层隔离）。
 */
class NarrativeStateUseCases(
    private val repository: NarrativeStateRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 追加一条 Narrative Delta；Original 上下文拒绝。返回折叠后 version（便于下一 append 乐观锁）。 */
    fun appendNarrativeDelta(delta: NarrativeDelta, expectedVersion: Long? = null): Long {
        val variantId = requireWritableVariant(delta.variantId, delta.novelId)
        val normalized = delta.copy(
            variantId = variantId,
            scope = if (variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
        )
        guard { repository.appendNarrativeDelta(normalized, expectedVersion) }
        return repository.listNarrativeDeltas(delta.novelId, variantId).size.toLong()
    }

    /** 确定性折叠并持久化快照，返回当前 [NarrativeState]。 */
    fun projectNarrativeState(novelId: NovelId, variantId: VariantId?): NarrativeState =
        guard { repository.project(novelId, variantId) }

    /** 读取最近折叠快照；尚未折叠返回 null。 */
    fun getNarrativeState(novelId: NovelId, variantId: VariantId?): NarrativeState? =
        guard { repository.getNarrativeState(novelId, variantId) }

    /** 列出某 novel+variant 的 Delta（升序）。 */
    fun listNarrativeDeltas(novelId: NovelId, variantId: VariantId?): List<NarrativeDelta> =
        guard { repository.listNarrativeDeltas(novelId, variantId) }

    /** Original 级账本只读：variantId=null 的 append 一律拒绝；返回经校验的 variantId。 */
    private fun requireWritableVariant(variantId: VariantId?, novelId: NovelId): VariantId? {
        if (variantId == null) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Original 叙事账本禁止写入（只读）：novel=${novelId.value}"),
            )
        }
        return variantId
    }
}