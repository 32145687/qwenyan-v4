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
import com.qianyan.storage.db.QianyanDb

/** [StoryStateRepository] 的 SQLDelight + SQLite JDBC 实现（P12.1.1 · Story State Persistence）。 */
class SqliteStoryStateRepository(private val db: QianyanDb) : StoryStateRepository {

    /* ---- 按 novel+variant 隔离查询 ---- */

    override fun listCharacters(novelId: NovelId, variantId: VariantId?): List<Character> =
        db.storyStateQueries.listCharactersByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbCharacter(it) }

    override fun listCharacterStates(novelId: NovelId, variantId: VariantId?): List<CharacterState> =
        db.storyStateQueries.listCharacterStatesByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbCharacterState(it) }

    override fun listWorldRules(novelId: NovelId, variantId: VariantId?): List<WorldRule> =
        db.storyStateQueries.listWorldRulesByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbWorldRule(it) }

    override fun listEvents(novelId: NovelId, variantId: VariantId?): List<Event> =
        db.storyStateQueries.listEventsByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbEvent(it) }

    override fun listTimelineEntries(novelId: NovelId, variantId: VariantId?): List<TimelineEntry> =
        db.storyStateQueries.listTimelineEntriesByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbTimelineEntry(it) }

    override fun listForeshadows(novelId: NovelId, variantId: VariantId?): List<Foreshadow> =
        db.storyStateQueries.listForeshadowsByScope(novelId.value, variantId?.value).executeAsList()
            .map { StorageMappers.dbForeshadow(it) }

    /* ---- 写入 ---- */

    override fun saveCharacter(character: Character) {
        val row = StorageMappers.domainCharacter(character)
        db.storyStateQueries.insertCharacter(
            character_id = row.character_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            name = row.name,
            personality = row.personality,
            created_at = row.created_at,
            updated_at = row.updated_at,
        )
    }

    override fun saveCharacterState(state: CharacterState) {
        val row = StorageMappers.domainCharacterState(state)
        db.storyStateQueries.insertCharacterState(
            state_id = row.state_id,
            character_id = row.character_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            chapter_id = row.chapter_id,
            timeline_position = row.timeline_position,
            snapshot_type = row.snapshot_type,
            physical = row.physical,
            emotional = row.emotional,
            current_goal = row.current_goal,
            created_at = row.created_at,
        )
    }

    override fun saveWorldRule(rule: WorldRule) {
        val row = StorageMappers.domainWorldRule(rule)
        db.storyStateQueries.insertWorldRule(
            rule_id = row.rule_id,
            world_id = row.world_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            content = row.content,
            category = row.category,
        )
    }

    override fun saveEvent(event: Event) {
        val row = StorageMappers.domainEvent(event)
        db.storyStateQueries.insertEvent(
            event_id = row.event_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            name = row.name,
            description = row.description,
            type = row.type,
            importance = row.importance,
            when_json = row.when_json,
            who = row.who,
            chapter_id = row.chapter_id,
            status = row.status,
            created_at = row.created_at,
        )
    }

    override fun saveTimelineEntry(entry: TimelineEntry) {
        val row = StorageMappers.domainTimelineEntry(entry)
        db.storyStateQueries.insertTimelineEntry(
            timeline_id = row.timeline_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            position = row.position,
            event_id = row.event_id,
            description = row.description,
            chapter_id = row.chapter_id,
        )
    }

    override fun saveForeshadow(foreshadow: Foreshadow) {
        val row = StorageMappers.domainForeshadow(foreshadow)
        db.storyStateQueries.insertForeshadow(
            foreshadow_id = row.foreshadow_id,
            novel_id = row.novel_id,
            variant_id = row.variant_id,
            scope = row.scope,
            chapter_id = row.chapter_id,
            content = row.content,
            resolved = row.resolved,
            created_at = row.created_at,
        )
    }

    /* ---- 按主键读取 ---- */

    override fun getCharacterById(characterId: CharacterId): Character? =
        db.storyStateQueries.getCharacterById(characterId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbCharacter(it) }

    override fun getCharacterStateById(stateId: StateId): CharacterState? =
        db.storyStateQueries.getCharacterStateById(stateId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbCharacterState(it) }

    override fun getWorldRuleById(ruleId: WorldRuleId): WorldRule? =
        db.storyStateQueries.getWorldRuleById(ruleId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbWorldRule(it) }

    override fun getEventById(eventId: EventId): Event? =
        db.storyStateQueries.getEventById(eventId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbEvent(it) }

    override fun getTimelineEntryById(timelineId: TimelineEntryId): TimelineEntry? =
        db.storyStateQueries.getTimelineEntryById(timelineId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbTimelineEntry(it) }

    override fun getForeshadowById(foreshadowId: ForeshadowingId): Foreshadow? =
        db.storyStateQueries.getForeshadowById(foreshadowId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbForeshadow(it) }
}