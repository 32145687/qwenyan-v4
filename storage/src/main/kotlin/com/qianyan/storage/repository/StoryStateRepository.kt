package com.qianyan.storage.repository

import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.VariantId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.world.WorldRule

/**
 * Story State 仓储（P12.1.1 · Story State Persistence）。
 *
 * 语法语义：每张表均带 novel_id + variant_id(NULL=Original) + scope，查询**一律按 novel+variant 隔离**：
 *  - `variantId = null`：Original 作用域（只读语义由更高层校验，本层仅暴露写入方法）；
 *  - `variantId = 指定值`：该 Variant 作用域（Variant 数据 variant_id=该 Variant、scope=VARIANT）。
 * 不同 Variant / 不同 Novel 之间互不可见。
 *
 * 写函数接受完整 domain 对象（含 scope），由调用方在 Application 层保证写 Original 用
 * scope=ORIGINAL、写 Variant 用 scope=VARIANT 且 variant_id 与 scope 一致。
 */
interface StoryStateRepository {

    /* ---- 按 novel+variant 隔离查询（Original: variantId=null；Variant: variantId=指定值） ---- */
    fun listCharacters(novelId: NovelId, variantId: VariantId?): List<Character>
    fun listCharacterStates(novelId: NovelId, variantId: VariantId?): List<CharacterState>
    fun listWorldRules(novelId: NovelId, variantId: VariantId?): List<WorldRule>
    fun listEvents(novelId: NovelId, variantId: VariantId?): List<Event>
    fun listTimelineEntries(novelId: NovelId, variantId: VariantId?): List<TimelineEntry>
    fun listForeshadows(novelId: NovelId, variantId: VariantId?): List<Foreshadow>

    /* ---- 写入（insert；同主键覆盖） ---- */
    fun saveCharacter(character: Character)
    fun saveCharacterState(state: CharacterState)
    fun saveWorldRule(rule: WorldRule)
    fun saveEvent(event: Event)
    fun saveTimelineEntry(entry: TimelineEntry)
    fun saveForeshadow(foreshadow: Foreshadow)

    /* ---- 按主键读取（未命中返回 null） ---- */
    fun getCharacterById(characterId: CharacterId): Character?
    fun getCharacterStateById(stateId: StateId): CharacterState?
    fun getWorldRuleById(ruleId: WorldRuleId): WorldRule?
    fun getEventById(eventId: EventId): Event?
    fun getTimelineEntryById(timelineId: TimelineEntryId): TimelineEntry?
    fun getForeshadowById(foreshadowId: ForeshadowingId): Foreshadow?
}