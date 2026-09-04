package com.qianyan.application.usecase.writing.context

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.StoryStateRepository

/**
 * 故事世界上下文解析器（P11.6 + P12.1.2，确定性）。
 *
 * 职责：**Repository data → 确定性解析 → [StoryWorldContext]**。
 *  - deterministic：相同输入 → 相同 Context（分层固定、层内按 createdAt+id 稳定排序），不调 LLM / Provider，
 *    不产生随机结果；不修改数据库 / Canon / Memory，不执行 Apply；
 *  - 作用域：存在 variantId → 该 Variant 记忆 + Original 基座；否则读 Novel 全部（含 ORIGINAL 基座）；
 *  - Layer 分层遵循现有 [MemoryLayer] 语义（canon / worldState / facts / memories），canon 优先，
 *    普通 Memory 不会被抬到 canon 之前。
 *  - 只读 Repository（[MemoryRepository] + [StoryStateRepository]）；本文档不触碰 SQLDelight。
 *  - P12.1.2：读取已持久化的结构化 Story State（Character / CharacterState / WorldRule / Event / TimelineEntry /
 *    Foreshadow）。Variant 场景读取 `本 Variant（优先）+ Original 基座`；Original 场景仅读 Original 基座
 *    （不跨 Variant / 不跨 Novel）。本阶段不做复杂继承 merge（自身+基座拼接置前即最小编码），未来扩展点见 KDoc。
 */
class StoryWorldContextResolver(
    private val memoryRepository: MemoryRepository,
    errorMapper: ErrorMapper,
    storyStateRepository: StoryStateRepository,
) : UseCase(errorMapper) {

    private val storyState = storyStateRepository

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
        // P12.1.2：结构化 Story State —— Variant 场景 = 本 Variant（优先）+ Original 基座；Original 场景 = 仅 Original 基座。
// 与 Memory 语义一致：Variant 继承 Original 基座，且隔离保证不同 Variant 互不可见。
        fun <T> combined(base: List<T>, own: List<T>): List<T> = own + base
        val characters = structured { combined(
            storyState.listCharacters(novelId, null),
            if (variantId != null) storyState.listCharacters(novelId, variantId) else emptyList(),
        ) }
        val characterStates = structured { combined(
            storyState.listCharacterStates(novelId, null),
            if (variantId != null) storyState.listCharacterStates(novelId, variantId) else emptyList(),
        ) }
        val worldRules = structured { combined(
            storyState.listWorldRules(novelId, null),
            if (variantId != null) storyState.listWorldRules(novelId, variantId) else emptyList(),
        ) }
        val events = structured { combined(
            storyState.listEvents(novelId, null),
            if (variantId != null) storyState.listEvents(novelId, variantId) else emptyList(),
        ) }
        val timelineEntries = structured { combined(
            storyState.listTimelineEntries(novelId, null),
            if (variantId != null) storyState.listTimelineEntries(novelId, variantId) else emptyList(),
        ) }
        val foreshadows = structured { combined(
            storyState.listForeshadows(novelId, null),
            if (variantId != null) storyState.listForeshadows(novelId, variantId) else emptyList(),
        ) }
        return StoryWorldContext(
            novelId = novelId,
            variantId = variantId,
            scope = scope,
            worldSummary = worldSummary,
            canon = entries.filter { it.layer == MemoryLayer.ORIGINAL }.map { it.content },
            worldState = entries.filter { it.layer == MemoryLayer.CURRENT_STATE }.map { it.content },
            facts = entries.filter { it.layer == MemoryLayer.LONG_TERM }.map { it.content },
            memories = entries.filter { it.layer == MemoryLayer.WRITING }.map { it.content },
            characters = characters,
            characterStates = characterStates,
            worldRules = worldRules,
            events = events,
            timelineEntries = timelineEntries,
            foreshadows = foreshadows,
        )
    }

    /** 经 Repository 读取结构化 Story State（Origin 只读 / Variant 自身优先，隔离由 Repository scope 查询保证）。 */
    private inline fun <T> structured(crossinline block: () -> List<T>): List<T> = guard { block() }
}