package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.StateId
import com.qianyan.model.TimelineEntryId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.EmotionalState
import com.qianyan.model.character.PhysicalState
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.model.timeline.StoryTime
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.world.WorldRule
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story State Persistence（P12.1.1）仓储测试。
 * 覆盖：① scope 隔离（Original/Variant、不同 Variant、不同 Novel）；② 每表 save→get 往返（含 JSON 字段）；
 * ③ Foreshadow resolved 默认 false 且可改为 true；④ 列表按 scope 过滤。
 */
class StoryStateRepositoryTest {

    private val now = kotlinx.datetime.Instant.fromEpochSeconds(1787777777, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)

    /** 建父 Novel（P12.4-M01 外键：各 StoryState 表 novel_id → Novel）。 */
    private fun seedNovel(db: QianyanDb, novelId: String) {
        SqliteNovelRepository(db).createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now, updatedAt = now,
            ),
        )
    }

    /* ---------- 领域实例构造 ---------- */

    private fun character(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        scope: VariantScope,
        name: String,
        personality: List<String> = listOf("深情", "执拗"),
    ) = Character(
        characterId = CharacterId(id),
        novelId = novel,
        variantId = variant,
        scope = scope,
        name = name,
        personality = personality,
        createdAt = now,
        updatedAt = now,
    )

    private fun characterState(
        id: String,
        characterId: String,
        novel: NovelId,
        variant: VariantId?,
        scope: VariantScope,
    ) = CharacterState(
        id = StateId(id),
        characterId = CharacterId(characterId),
        novelId = novel,
        variantId = variant,
        scope = scope,
        chapterId = ChapterId("ch-$variant-$scope"),
        timelinePosition = TimelinePosition(storyTime = StoryTime(year = 2024, description = "初春")),
        snapshotType = SnapshotType.CHAPTER_START,
        physicalState = PhysicalState(label = "尚可", details = "轻伤"),
        emotionalState = EmotionalState(label = "愤怒", intensity = 0.8f),
        currentGoal = "复仇",
        createdAt = now,
    )

    private fun worldRule(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        scope: VariantScope,
        content: String,
    ) = WorldRule(
        ruleId = WorldRuleId(id),
        worldId = WorldId("w-$novel"),
        novelId = novel,
        variantId = variant,
        scope = scope,
        content = content,
        category = "magic",
    )

    private fun event(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        scope: VariantScope,
        name: String,
    ) = Event(
        id = EventId(id),
        novelId = novel,
        variantId = variant,
        scope = scope,
        name = name,
        description = "desc-$name",
        type = EventType.WORLD_EVENT,
        importance = 8,
        `when` = TimelinePosition(storyTime = StoryTime(year = 2024, month = 5, description = "初夏雨夜")),
        who = listOf(CharacterId("ch-1"), CharacterId("ch-2")),
        status = EventStatus.CONFIRMED,
        chapterId = ChapterId("ch-9"),
        createdAt = now,
    )

    private fun timelineEntry(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        scope: VariantScope,
        eventId: String,
    ) = TimelineEntry(
        id = TimelineEntryId(id),
        novelId = novel,
        variantId = variant,
        scope = scope,
        position = TimelinePosition(storyTime = StoryTime(year = 2024, month = 5, day = 1)),
        eventId = EventId(eventId),
        description = "entry-$id",
        chapterId = ChapterId("ch-timeline"),
    )

    private fun foreshadow(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        scope: VariantScope,
        content: String,
        resolved: Boolean = false,
    ) = Foreshadow(
        foreshadowId = ForeshadowingId(id),
        novelId = novel,
        variantId = variant,
        scope = scope,
        chapterId = ChapterId("ch-fs"),
        content = content,
        resolved = resolved,
        createdAt = now,
    )

    /* ---------- 测试 1：scope / novel 隔离 ---------- */

    @Test
    fun `story state is isolated by novel and variant scope across all tables`() {
        val h = handle()
        seedNovel(h.db, "n1")
        seedNovel(h.db, "n2")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("n1")
        val otherNovel = NovelId("n2")
        val v1 = VariantId("v1")
        val v2 = VariantId("v2")

        // Character
        repo.saveCharacter(character("c-orig", novel, null, VariantScope.ORIGINAL, "原著"))
        repo.saveCharacter(character("c-v1", novel, v1, VariantScope.VARIANT, "改1"))
        repo.saveCharacter(character("c-v2", novel, v2, VariantScope.VARIANT, "改2"))
        repo.saveCharacter(character("c-n2", otherNovel, null, VariantScope.ORIGINAL, "他书"))
        assertEquals(listOf("c-orig"), repo.listCharacters(novel, null).map { it.characterId.value })
        assertEquals(listOf("c-v1"), repo.listCharacters(novel, v1).map { it.characterId.value })
        assertEquals(listOf("c-v2"), repo.listCharacters(novel, v2).map { it.characterId.value })

        // CharacterState
        repo.saveCharacterState(characterState("s-orig", "c-orig", novel, null, VariantScope.ORIGINAL))
        repo.saveCharacterState(characterState("s-v1", "c-v1", novel, v1, VariantScope.VARIANT))
        repo.saveCharacterState(characterState("s-v2", "c-v2", novel, v2, VariantScope.VARIANT))
        assertEquals(listOf("s-orig"), repo.listCharacterStates(novel, null).map { it.id.value })
        assertEquals(listOf("s-v1"), repo.listCharacterStates(novel, v1).map { it.id.value })
        assertEquals(listOf("s-v2"), repo.listCharacterStates(novel, v2).map { it.id.value })

        // WorldRule
        repo.saveWorldRule(worldRule("r-orig", novel, null, VariantScope.ORIGINAL, "灵石不能凡人碰"))
        repo.saveWorldRule(worldRule("r-v1", novel, v1, VariantScope.VARIANT, "改1规则"))
        repo.saveWorldRule(worldRule("r-v2", novel, v2, VariantScope.VARIANT, "改2规则"))
        assertEquals(listOf("r-orig"), repo.listWorldRules(novel, null).map { it.ruleId.value })
        assertEquals(listOf("r-v1"), repo.listWorldRules(novel, v1).map { it.ruleId.value })
        assertEquals(listOf("r-v2"), repo.listWorldRules(novel, v2).map { it.ruleId.value })

        // Event
        repo.saveEvent(event("e-orig", novel, null, VariantScope.ORIGINAL, "原著事件"))
        repo.saveEvent(event("e-v1", novel, v1, VariantScope.VARIANT, "改1事件"))
        repo.saveEvent(event("e-v2", novel, v2, VariantScope.VARIANT, "改2事件"))
        assertEquals(listOf("e-orig"), repo.listEvents(novel, null).map { it.id.value })
        assertEquals(listOf("e-v1"), repo.listEvents(novel, v1).map { it.id.value })
        assertEquals(listOf("e-v2"), repo.listEvents(novel, v2).map { it.id.value })

        // TimelineEntry
        repo.saveTimelineEntry(timelineEntry("t-orig", novel, null, VariantScope.ORIGINAL, "e-orig"))
        repo.saveTimelineEntry(timelineEntry("t-v1", novel, v1, VariantScope.VARIANT, "e-v1"))
        repo.saveTimelineEntry(timelineEntry("t-v2", novel, v2, VariantScope.VARIANT, "e-v2"))
        assertEquals(listOf("t-orig"), repo.listTimelineEntries(novel, null).map { it.id.value })
        assertEquals(listOf("t-v1"), repo.listTimelineEntries(novel, v1).map { it.id.value })
        assertEquals(listOf("t-v2"), repo.listTimelineEntries(novel, v2).map { it.id.value })

        // Foreshadow
        repo.saveForeshadow(foreshadow("f-orig", novel, null, VariantScope.ORIGINAL, "埋玉"))
        repo.saveForeshadow(foreshadow("f-v1", novel, v1, VariantScope.VARIANT, "改1伏笔"))
        repo.saveForeshadow(foreshadow("f-v2", novel, v2, VariantScope.VARIANT, "改2伏笔"))
        assertEquals(listOf("f-orig"), repo.listForeshadows(novel, null).map { it.foreshadowId.value })
        assertEquals(listOf("f-v1"), repo.listForeshadows(novel, v1).map { it.foreshadowId.value })
        assertEquals(listOf("f-v2"), repo.listForeshadows(novel, v2).map { it.foreshadowId.value })

        // 不同 Novel 互相不可见：otherNovel 的 Original 不出现在 novel 任何 scope 中
        assertTrue(repo.listCharacters(novel, null).all { it.characterId.value != "c-n2" })
        assertTrue(repo.listCharacters(novel, v1).all { it.characterId.value != "c-n2" })
        assertTrue(repo.listCharacters(otherNovel, null).any { it.characterId.value == "c-n2" })

        // Mutual exclusion：某个 scope 的列表不含其它 scope 数据
        assertTrue(repo.listCharacters(novel, v1).none { it.characterId.value in listOf("c-orig", "c-v2") })
    }

    /* ---------- 测试 2：每表 save→get 往返（含 JSON 字段） ---------- */

    @Test
    fun `save and get roundtrip each table including json fields`() {
        val h = handle()
        seedNovel(h.db, "rt")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("rt")
        val v = VariantId("var")

        // Character
        repo.saveCharacter(character("rt-c", novel, null, VariantScope.ORIGINAL, "林晚", personality = listOf("冷静", "谋略")))
        val c = repo.getCharacterById(CharacterId("rt-c"))
        assertNotNull(c)
        assertEquals("林晚", c.name)
        assertEquals(listOf("冷静", "谋略"), c.personality)

        // CharacterState（JSON: timeline_position / physical / emotional）
        repo.saveCharacterState(characterState("rt-s", "rt-c", novel, v, VariantScope.VARIANT))
        val s = repo.getCharacterStateById(StateId("rt-s"))
        assertNotNull(s)
        assertEquals(SnapshotType.CHAPTER_START, s.snapshotType)
        assertEquals(PhysicalState(label = "尚可", details = "轻伤"), s.physicalState)
        assertEquals(EmotionalState(label = "愤怒", intensity = 0.8f), s.emotionalState)
        assertEquals("复仇", s.currentGoal)
        assertEquals(2024, s.timelinePosition?.storyTime?.year)
        assertEquals("初春", s.timelinePosition?.storyTime?.description)

        // WorldRule
        repo.saveWorldRule(worldRule("rt-r", novel, null, VariantScope.ORIGINAL, "不得以灵石害人"))
        val r = repo.getWorldRuleById(WorldRuleId("rt-r"))
        assertNotNull(r)
        assertEquals("不得以灵石害人", r.content)
        assertEquals("magic", r.category)

        // Event（JSON: when_json / who）
        repo.saveEvent(event("rt-e", novel, null, VariantScope.ORIGINAL, "灵石现世"))
        val e = repo.getEventById(EventId("rt-e"))
        assertNotNull(e)
        assertEquals("灵石现世", e.name)
        assertEquals(EventType.WORLD_EVENT, e.type)
        assertEquals(8, e.importance)
        assertEquals(5, e.`when`?.storyTime?.month)
        assertEquals(listOf(CharacterId("ch-1"), CharacterId("ch-2")), e.who)

        // TimelineEntry（JSON: position）
        repo.saveTimelineEntry(timelineEntry("rt-t", novel, null, VariantScope.ORIGINAL, "rt-e"))
        val t = repo.getTimelineEntryById(TimelineEntryId("rt-t"))
        assertNotNull(t)
        assertEquals(2024, t.position.storyTime?.year)
        assertEquals(1, t.position.storyTime?.day)
        assertEquals(EventId("rt-e"), t.eventId)

        // Foreshadow
        repo.saveForeshadow(foreshadow("rt-f", novel, null, VariantScope.ORIGINAL, "遗落玉镯"))
        val f = repo.getForeshadowById(ForeshadowingId("rt-f"))
        assertNotNull(f)
        assertEquals("遗落玉镯", f.content)
        assertFalse(f.resolved)
    }

    /* ---------- 测试 3：Foreshadow resolved 默认 false 且可改为 true ---------- */

    @Test
    fun `foreshadow resolved defaults false and can be updated to true`() {
        val h = handle()
        seedNovel(h.db, "foreshadow-update")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("foreshadow-update")

        repo.saveForeshadow(foreshadow("fs-1", novel, null, VariantScope.ORIGINAL, "墙上剑痕"))
        assertFalse(repo.getForeshadowById(ForeshadowingId("fs-1"))!!.resolved, "新建 Foreshadow resolved 默认应为 false")

        // 更新为已兑现（同主键覆盖）
        repo.saveForeshadow(foreshadow("fs-1", novel, null, VariantScope.ORIGINAL, "墙上剑痕", resolved = true))
        assertTrue(repo.getForeshadowById(ForeshadowingId("fs-1"))!!.resolved, "更新后 resolved 应为 true")
        // 列表同样反映出兑现状态
        val listed = repo.listForeshadows(novel, null)
        assertEquals(1, listed.size)
        assertTrue(listed.single().resolved)
    }

    /* ---------- 测试 4：列表按 scope 过滤正确 ---------- */

    @Test
    fun `lists filter by scope - original and variant counts are independent`() {
        val h = handle()
        seedNovel(h.db, "filter")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("filter")
        val v1 = VariantId("fv1")
        val v2 = VariantId("fv2")

        repo.saveForeshadow(foreshadow("f-orig-a", novel, null, VariantScope.ORIGINAL, "a"))
        repo.saveForeshadow(foreshadow("f-orig-b", novel, null, VariantScope.ORIGINAL, "b"))
        repo.saveForeshadow(foreshadow("f-v1-x", novel, v1, VariantScope.VARIANT, "x"))
        repo.saveForeshadow(foreshadow("f-v2-y", novel, v2, VariantScope.VARIANT, "y"))

        val original = repo.listForeshadows(novel, null)
        val scopeV1 = repo.listForeshadows(novel, v1)
        val scopeV2 = repo.listForeshadows(novel, v2)

        assertEquals(listOf("f-orig-a", "f-orig-b"), original.map { it.foreshadowId.value })
        assertEquals(listOf("f-v1-x"), scopeV1.map { it.foreshadowId.value })
        assertEquals(listOf("f-v2-y"), scopeV2.map { it.foreshadowId.value })

        // 未命中主键返回 null
        assertNull(repo.getForeshadowById(ForeshadowingId("missing")))
        assertNull(repo.getCharacterById(CharacterId("missing")))
        assertNull(repo.getEventById(EventId("missing")))
    }
}