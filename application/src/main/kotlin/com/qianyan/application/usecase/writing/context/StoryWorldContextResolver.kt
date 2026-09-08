package com.qianyan.application.usecase.writing.context

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.world.WorldRule
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.StoryStateRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 故事世界上下文解析器（P11.6 + P12.1.2 + P12.3）。
 *
 * 职责：**Repository data → 确定性 Entity Merge → Effective Story State → [StoryWorldContext]**。
 *  - deterministic：相同输入 → 相同 Context（分层固定、层内按 createdAt+id 稳定排序），不调 LLM / Provider，
 *    不产生随机结果；不修改数据库 / Canon / Memory，不执行 Apply；**只读，不写库**；
 *  - 作用域：存在 variantId → 该 Variant（own + 自身 Override）+ Original 基座；否则读 Novel 全部（ORIGINAL 基座）；
 *  - Layer 分层遵循现有 [MemoryLayer] 语义（canon / worldState / facts / memories），canon 优先；
 *  - P12.1.2：读取已持久化结构化 Story State（Character / CharacterState / WorldRule / Event / TimelineEntry / Foreshadow）；
 *  - P12.3（TD1）：Entity Merge —— 六类实体以 **实体 identity（全局 id）** 为 merge key：
 *      - 无 Override / 显式 INHERIT → 继承 Original（base）实体；
 *      - Override(OVERRIDE) → 用 replacedValue（整实体 JSON）替换同 id 的 Original 实体；
 *      - Override(REMOVE) → 从 Effective State 剔除该 Original 实体；
 *      - Variant own 表（variant_id 非空）实体 = ADD，追加到 Effective State。
 *    Original 场景仅读 Original 基座，无 Override，行为不变。
 */
class StoryWorldContextResolver(
    private val memoryRepository: MemoryRepository,
    errorMapper: ErrorMapper,
    storyStateRepository: StoryStateRepository,
    private val novelRepository: NovelRepository,
) : UseCase(errorMapper) {

    private val storyState = storyStateRepository
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 解析某 Novel（可选 Variant）下的故事世界上下文。
     * worldSummary 由调用方提供（Novel 标题/简介最小投影）。
     *
     * 作用域语义（P12.0 P0-1 修复）：
     *  - 存在 variantId → **Original 基座（variant_id IS NULL，只读 canon）+ 当前 Variant 记忆 + 当前 Variant own/Override**；
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
        // P12.3：Variant 作用域一次性读取其全部 Override，按 kind 筛分后做实体级 merge；Original 作用域无 Override。
        val overrides = if (variantId != null) guard { novelRepository.getOverrides(variantId) } else emptyList()

        val characters = mergeState(
            base = guardedList { storyState.listCharacters(novelId, null) },
            own = guardedList { if (variantId != null) storyState.listCharacters(novelId, variantId) else emptyList() },
            overrides = overrideMap(overrides, OverridableKind.CHARACTER),
            idOf = { it.characterId.value },
            decode = { json.decodeFromJsonElement(Character.serializer(), it) },
        )
        val characterStates = mergeState(
            base = guardedList { storyState.listCharacterStates(novelId, null) },
            own = guardedList { if (variantId != null) storyState.listCharacterStates(novelId, variantId) else emptyList() },
            overrides = overrideMap(overrides, OverridableKind.CHARACTER_STATE),
            idOf = { it.id.value },
            decode = { json.decodeFromJsonElement(CharacterState.serializer(), it) },
        )
        val worldRules = mergeState(
            base = guardedList { storyState.listWorldRules(novelId, null) },
            own = guardedList { if (variantId != null) storyState.listWorldRules(novelId, variantId) else emptyList() },
            overrides = overrideMap(overrides, OverridableKind.WORLD_RULE),
            idOf = { it.ruleId.value },
            decode = { json.decodeFromJsonElement(WorldRule.serializer(), it) },
        )
        val events = mergeState(
            base = guardedList { storyState.listEvents(novelId, null) },
            own = guardedList { if (variantId != null) storyState.listEvents(novelId, variantId) else emptyList() },
            overrides = overrideMap(overrides, OverridableKind.EVENT),
            idOf = { it.id.value },
            decode = { json.decodeFromJsonElement(Event.serializer(), it) },
        )
        val timelineEntries = mergeState(
            base = guardedList { storyState.listTimelineEntries(novelId, null) },
            own = guardedList { if (variantId != null) storyState.listTimelineEntries(novelId, variantId) else emptyList() },
            overrides = overrideMap(overrides, OverridableKind.TIMELINE_ENTRY),
            idOf = { it.id.value },
            decode = { json.decodeFromJsonElement(TimelineEntry.serializer(), it) },
        )
        val foreshadows = mergeState(
            base = guardedList { storyState.listForeshadows(novelId, null) },
            own = guardedList { if (variantId != null) storyState.listForeshadows(novelId, variantId) else emptyList() },
            overrides = overrideMap(overrides, OverridableKind.FORESHADOW),
            idOf = { it.foreshadowId.value },
            decode = { json.decodeFromJsonElement(Foreshadow.serializer(), it) },
        )
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

    // ---- P12.3 Entity Merge（确定性、只读、不写库） ----

    /** 该 kind 在该 variant 下的 Override，按 targetId 索引（同一 targetId 至多一条，由 UNIQUE 保证）。 */
    private fun overrideMap(all: List<EntityOverride>, kind: OverridableKind): Map<String, EntityOverride> =
        all.filter { it.targetKind == kind }.associateBy { it.targetId }

    /** 经 Repository 读取结构化 Story State（ORM 层抛错→映射 error；Origin 只读 / Variant 自身优先，隔离由 scope 查询保证）。 */
    private inline fun <T> guardedList(crossinline block: () -> List<T>): List<T> = guard { block() }

    /**
     * 核心 Merge：Original(base) → 逐实体查 Override → REMOVE 剔除 / OVERRIDE 替换 / 其余继承；
     * 再追加 Variant own(ADD)。结果保持 base 排序 + own 追加，顺序稳定、确定性。
     */
    private fun <T> mergeState(
        base: List<T>,
        own: List<T>,
        overrides: Map<String, EntityOverride>,
        idOf: (T) -> String,
        decode: (JsonElement) -> T,
    ): List<T> {
        val out = ArrayList<T>(base.size + own.size)
        for (entity in base) {
            val ov = overrides[idOf(entity)]
            when (ov?.operation) {
                OverrideOperation.REMOVE -> Unit              // 剔除 Original 实体
                OverrideOperation.OVERRIDE -> {
                    val v = ov.replacedValue
                    out += if (v != null) decode(v) else entity
                }
                else -> out += entity                          // 无 override / INHERIT → 继承 Original
            }
        }
        out += own
        return out
    }
}