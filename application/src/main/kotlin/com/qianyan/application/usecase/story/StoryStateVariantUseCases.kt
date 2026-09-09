package com.qianyan.application.usecase.story

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.OverrideId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.OverridableKind
import com.qianyan.model.core.OverrideOperation
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.world.WorldRule
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.StoryStateRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Story State Variant 修改入口 / seam（P12.3 · TD1）。
 *
 * 与六类 Story State 的关系：
 *  - **ADD**（Variant 新增实体）→ 写入 Variant own 表（新 id，variant_id = 当前 Variant）；
 *  - **OVERRIDE**（替换同 identity 的 Original 实体）→ 写入 [EntityOverride](operation=OVERRIDE)，不写入 own 表、
 *    绝不写入与 Original 相同的全局实体 id；
 *  - **REMOVE**（隐藏 Original 实体）→ 写入 [EntityOverride](operation=REMOVE)；「Variant 表无记录」≠ REMOVE；
 *  - **INHERIT**（显式继承 Original）→ 写入 [EntityOverride](operation=INHERIT)。
 *
 * 边界：
 *  - **Original 上下文一律拒绝**（[ApplicationException] InvalidOperation）；
 *  - Variant 修改不污染 Original / 其它 Variant（scope + variant_id 隔离）；
 *  - 单个 Variant 对同一 targetId 至多一条 override（逻辑唯一键 (targetId, variantId)，写入为 upsert，
 *    支持 REMOVE→OVERRIDE 恢复：直接写入新 OVERRIDE 即替换旧记录）。
 */
class StoryStateVariantUseCases(
    private val storyStateRepository: StoryStateRepository,
    private val novelRepository: NovelRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun novelOf(ctx: VariantContext): NovelId = NovelId(ctx.baseNovelId.value)

    /** ADD 守卫：Variant ADD 的实体 ID 不得已存在于任何作用域（含 Original base / 其它 Variant own）。
     *  全局实体 ID 即主键，若已存在则 INSERT OR REPLACE 会覆盖/污染既有行——必须拒绝。O(1) 单条查询。 */
    private fun requireNewEntityId(existing: Any?, kind: OverridableKind, id: String) {
        if (existing != null) {
            throw ApplicationException(
                ApplicationError.DuplicateTarget("Variant ADD 的 ${kind.name.lowercase()} ID 已存在，不能覆盖已有实体: $id"),
            )
        }
    }

    // ---- ADD ----------
    fun addCharacter(ctx: VariantContext, entity: Character): Character {
        val vid = requireVariant(ctx)
        requireNovel(ctx, entity.novelId)
        requireNewEntityId(storyStateRepository.getCharacterById(entity.characterId), OverridableKind.CHARACTER, entity.characterId.value)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        guard { storyStateRepository.saveCharacter(e) }
        return e
    }

    fun addCharacterState(ctx: VariantContext, entity: CharacterState): CharacterState {
        val vid = requireVariant(ctx)
        requireNovel(ctx, entity.novelId)
        requireNewEntityId(storyStateRepository.getCharacterStateById(entity.id), OverridableKind.CHARACTER_STATE, entity.id.value)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        guard { storyStateRepository.saveCharacterState(e) }
        return e
    }

    fun addWorldRule(ctx: VariantContext, entity: WorldRule): WorldRule {
        val vid = requireVariant(ctx)
        requireNovel(ctx, entity.novelId)
        requireNewEntityId(storyStateRepository.getWorldRuleById(entity.ruleId), OverridableKind.WORLD_RULE, entity.ruleId.value)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        guard { storyStateRepository.saveWorldRule(e) }
        return e
    }

    fun addEvent(ctx: VariantContext, entity: Event): Event {
        val vid = requireVariant(ctx)
        requireNovel(ctx, entity.novelId)
        requireNewEntityId(storyStateRepository.getEventById(entity.id), OverridableKind.EVENT, entity.id.value)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        guard { storyStateRepository.saveEvent(e) }
        return e
    }

    fun addTimelineEntry(ctx: VariantContext, entity: TimelineEntry): TimelineEntry {
        val vid = requireVariant(ctx)
        requireNovel(ctx, entity.novelId)
        requireNewEntityId(storyStateRepository.getTimelineEntryById(entity.id), OverridableKind.TIMELINE_ENTRY, entity.id.value)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        guard { storyStateRepository.saveTimelineEntry(e) }
        return e
    }

    fun addForeshadow(ctx: VariantContext, entity: Foreshadow): Foreshadow {
        val vid = requireVariant(ctx)
        requireNovel(ctx, entity.novelId)
        requireNewEntityId(storyStateRepository.getForeshadowById(entity.foreshadowId), OverridableKind.FORESHADOW, entity.foreshadowId.value)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        guard { storyStateRepository.saveForeshadow(e) }
        return e
    }

    // ---- OVERRIDE（整实体替换同 identity 的 Original 实体） ----------

    fun overrideCharacter(ctx: VariantContext, entity: Character): OverrideId {
        val vid = requireVariant(ctx); requireNovel(ctx, entity.novelId)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        return writeOverride(vid, OverridableKind.CHARACTER, entity.characterId.value, OverrideOperation.OVERRIDE, json.encodeToJsonElement(e))
    }

    fun overrideCharacterState(ctx: VariantContext, entity: CharacterState): OverrideId {
        val vid = requireVariant(ctx); requireNovel(ctx, entity.novelId)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        return writeOverride(vid, OverridableKind.CHARACTER_STATE, entity.id.value, OverrideOperation.OVERRIDE, json.encodeToJsonElement(e))
    }

    fun overrideWorldRule(ctx: VariantContext, entity: WorldRule): OverrideId {
        val vid = requireVariant(ctx); requireNovel(ctx, entity.novelId)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        return writeOverride(vid, OverridableKind.WORLD_RULE, entity.ruleId.value, OverrideOperation.OVERRIDE, json.encodeToJsonElement(e))
    }

    fun overrideEvent(ctx: VariantContext, entity: Event): OverrideId {
        val vid = requireVariant(ctx); requireNovel(ctx, entity.novelId)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        return writeOverride(vid, OverridableKind.EVENT, entity.id.value, OverrideOperation.OVERRIDE, json.encodeToJsonElement(e))
    }

    fun overrideTimelineEntry(ctx: VariantContext, entity: TimelineEntry): OverrideId {
        val vid = requireVariant(ctx); requireNovel(ctx, entity.novelId)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        return writeOverride(vid, OverridableKind.TIMELINE_ENTRY, entity.id.value, OverrideOperation.OVERRIDE, json.encodeToJsonElement(e))
    }

    fun overrideForeshadow(ctx: VariantContext, entity: Foreshadow): OverrideId {
        val vid = requireVariant(ctx); requireNovel(ctx, entity.novelId)
        val e = entity.copy(novelId = novelOf(ctx), variantId = vid, scope = VariantScope.VARIANT)
        return writeOverride(vid, OverridableKind.FORESHADOW, entity.foreshadowId.value, OverrideOperation.OVERRIDE, json.encodeToJsonElement(e))
    }

    // ---- REMOVE / INHERIT（通用：仅需 kind + targetId） ----------

    /** 隐藏某 Original 实体：写入 REMOVE override；「数据库无记录」≠ REMOVE。 */
    fun remove(ctx: VariantContext, targetKind: OverridableKind, targetId: String): OverrideId {
        val vid = requireVariant(ctx)
        return writeOverride(vid, targetKind, targetId, OverrideOperation.REMOVE, null)
    }

    /** 显式继承某 Original 实体：写入 INHERIT override（效果等于无 override）。 */
    fun inherit(ctx: VariantContext, targetKind: OverridableKind, targetId: String): OverrideId {
        val vid = requireVariant(ctx)
        return writeOverride(vid, targetKind, targetId, OverrideOperation.INHERIT, null)
    }

    // ---- internals ----------

    private fun requireVariant(ctx: VariantContext): VariantId =
        ctx.variantId ?: throw ApplicationException(
            ApplicationError.InvalidOperation("Original 上下文禁止修改 Story State（需 Variant 上下文）"),
        )

    private fun requireNovel(ctx: VariantContext, entityNovel: NovelId) {
        if (entityNovel.value != ctx.baseNovelId.value) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("实体所属 Novel 与上下文不一致（实体=$entityNovel, 上下文=${ctx.baseNovelId.value}）"),
            )
        }
    }

    private fun writeOverride(
        variantId: VariantId,
        targetKind: OverridableKind,
        targetId: String,
        operation: OverrideOperation,
        replacedValue: JsonElement?,
    ): OverrideId {
        val overrideId = OverrideId(nextId())
        // 同一 (variantId, targetId) 至多一条 override（UNIQUE 约束）。Variant 修改需 replace 语义
        //（例如 REMOVE 之后 OVERRIDE 恢复同一 identity）：用单事务 upsert 原子替换，避免「删旧成功、写新失败」中间态。
        guard {
            novelRepository.replaceOverride(
                EntityOverride(
                    overrideId = overrideId,
                    variantId = variantId,
                    targetKind = targetKind,
                    targetId = targetId,
                    operation = operation,
                    replacedValue = replacedValue,
                    note = "",
                ),
            )
        }
        return overrideId
    }
}