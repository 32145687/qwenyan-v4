package com.qianyan.application.usecase.writing.context

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.storage.repository.MemoryRepository

/**
 * 故事世界上下文解析器（P11.6，确定性）。
 *
 * 职责：**Repository data → 确定性解析 → [StoryWorldContext]**。
 *  - deterministic：相同输入 → 相同 Context（分层固定、层内按 createdAt+id 稳定排序），不调 LLM / Provider，
 *    不产生随机结果；不修改数据库 / Canon / Memory，不执行 Apply；
 *  - 作用域：存在 variantId → 只读该 Variant 记忆；否则读 Novel 全部（含 ORIGINAL 基座）；
 *  - Layer 分层遵循现有 [MemoryLayer] 语义（canon / worldState / facts / memories），canon 优先，
 *    普通 Memory 不会被抬到 canon 之前。
 *  - 只读 Repository（[MemoryRepository]）；本文档不触碰 SQLDelight。
 */
class StoryWorldContextResolver(
    private val memoryRepository: MemoryRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 解析某 Novel（可选 Variant）下的故事世界上下文。
     * worldSummary 由调用方提供（Novel 标题/简介最小投影）。
     *
     * 作用域语义（P12.0 P0-1 修复）：
     *  - 存在 variantId → **Original 基座（variant_id IS NULL，只读 canon）+ 当前 Variant 记忆**；
     *  - 无 variant（ORIGINAL 上下文）→ 仅 **Original 基座**（不跨 Variant 读取其它 Variant 记忆，也不暴露给非该 Variant）。
     */
    fun resolve(
        novelId: NovelId,
        variantId: VariantId? = null,
        scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        worldSummary: String = "",
    ): StoryWorldContext {
        val base = guard { memoryRepository.findOriginalBase(novelId) }
        val variant = if (variantId != null) {
            guard { memoryRepository.findEntriesByVariant(novelId, variantId) }
        } else {
            emptyList()
        }
        val entries = (base + variant).sortedWith(compareBy({ it.createdAt }, { it.id.value }))
        return StoryWorldContext(
            novelId = novelId,
            variantId = variantId,
            scope = scope,
            worldSummary = worldSummary,
            canon = entries.filter { it.layer == MemoryLayer.ORIGINAL }.map { it.content },
            worldState = entries.filter { it.layer == MemoryLayer.CURRENT_STATE }.map { it.content },
            facts = entries.filter { it.layer == MemoryLayer.LONG_TERM }.map { it.content },
            memories = entries.filter { it.layer == MemoryLayer.WRITING }.map { it.content },
        )
    }
}