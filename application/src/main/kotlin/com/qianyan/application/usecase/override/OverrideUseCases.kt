package com.qianyan.application.usecase.override

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.OverrideId
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.VariantContext
import com.qianyan.storage.repository.NovelRepository
import kotlinx.serialization.json.JsonElement

/**
 * EntityOverride 相关 Use Case（P3.2）：
 *  - AddOverride：为某 Variant 的 targetId 写入一项 Override（唯一键 targetId+variantId）。
 *  - RemoveOverride：撤销某 Variant 对 targetId 的 Override。
 *  - ResolveVariantEntity：P2.6 读穿透的应用封装——Variant 命中 Override 用覆盖值，否则回退 Original。
 */
class OverrideUseCases(
    private val repo: NovelRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** AddOverride：仅允许 Variant 上下文添加 Override；Original 上下文拒绝。返回生成的 OverrideId。 */
    fun addOverride(
        context: VariantContext,
        targetKind: OverridableKind,
        targetId: String,
        operation: OverrideOperation,
        replacedValue: JsonElement? = null,
        note: String = "",
    ): OverrideId {
        val variantId = context.variantId
            ?: throw ApplicationException(ApplicationError.InvalidOperation("不能对 Original 添加 Override"))
        val overrideId = OverrideId(nextId())
        guard {
            repo.saveOverride(
                EntityOverride(
                    overrideId = overrideId,
                    variantId = variantId,
                    targetKind = targetKind,
                    targetId = targetId,
                    operation = operation,
                    replacedValue = replacedValue,
                    note = note,
                ),
            )
        }
        return overrideId
    }

    /** RemoveOverride：撤销某 Variant 对 targetId 的 Override（删除/撤销）。 */
    fun removeOverride(context: VariantContext, targetId: String) {
        val variantId = context.variantId
            ?: throw ApplicationException(ApplicationError.InvalidOperation("不能对 Original 撤销 Override"))
        guard { repo.deleteOverride(variantId, targetId) }
    }

    /**
     * ResolveVariantEntity：给定领域实体某属性的 Original 基值，解析当前上下文下应使用的值。
     *  - Original 上下文：直接返回 Original 基值（不经 Override）。
     *  - Variant 上下文：命中 Override 用覆盖值；INHERIT/无 Override 回退基值；REMOVE 返回 null。
     * 不复制 Original 结构，不做递归继承（P2.6/P2.4）。
     */
    fun resolveVariantEntity(
        context: VariantContext,
        targetId: String,
        originalValue: JsonElement?,
    ): JsonElement? {
        val variantId = context.variantId ?: return originalValue
        return guard { repo.resolveOverride(variantId, targetId, originalValue) }
    }

    /** 读取某 Variant 的全部 Override（供调试 / 审计）。 */
    fun overridesOf(context: VariantContext): List<EntityOverride> {
        val variantId = context.variantId ?: return emptyList()
        return guard { repo.getOverrides(variantId) }
    }
}