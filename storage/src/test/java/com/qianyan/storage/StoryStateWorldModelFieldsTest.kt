package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.KnowledgeId
import com.qianyan.model.LocationId
import com.qianyan.model.NovelId
import com.qianyan.model.ParticipantRole
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.StateId
import com.qianyan.model.StateSource
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.character.AbilitySnapshot
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.EmotionalState
import com.qianyan.model.character.PhysicalState
import com.qianyan.model.character.RelationshipSnapshot
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.core.Novel
import com.qianyan.model.knowledge.Evidence
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.knowledge.KnowledgeSource
import com.qianyan.model.knowledge.ReferenceType
import com.qianyan.model.knowledge.SourceReference
import com.qianyan.model.knowledge.SourceType
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventParticipant
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.model.timeline.StoryTime
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2（v3.1 §11）· World Model 字段补全（Schema v27）往返测试。
 *
 * 覆盖 §11.10 的存储层要求：
 *  1) Character 3 字段 / CharacterState 7 字段 / Event 7 字段 save → get 逐字段往返无损；
 *  2) 默认值 / NULL / 枚举语义不发生意外变化；
 *  3) Variant 隔离：不同 scope 的行互不可见，且字段值互不串扰；
 *  4) Original 行在存储层保持 variant_id = NULL / scope = ORIGINAL（不被 Variant 行污染）。
 * 生产写入调用方仍为 0：本测试是 §11.10 允许的 fixture 验证，不接入任何生产 caller。
 */
class StoryStateWorldModelFieldsTest {

    private val now = kotlinx.datetime.Instant.fromEpochSeconds(1787777777, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)

    private fun seedNovel(db: QianyanDb, novelId: String) {
        SqliteNovelRepository(db).createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now, updatedAt = now,
            ),
        )
    }

    /* ---------- 带全部新字段的领域实例 ---------- */

    private fun fullCharacter(id: String, novel: NovelId, variant: VariantId?, scope: VariantScope) = Character(
        characterId = CharacterId(id),
        novelId = novel,
        variantId = variant,
        scope = scope,
        name = "沈青梧",
        personality = listOf("冷静", "执拗"),
        goals = listOf("夺回家产", "查明真相"),
        fears = listOf("黑暗", "被抛弃"),
        description = "沉静外表下藏着执念的年轻掌柜",
        createdAt = now,
        updatedAt = now,
    )

    private fun fullState(id: String, characterId: String, novel: NovelId, variant: VariantId?, scope: VariantScope) =
        CharacterState(
            id = StateId(id),
            characterId = CharacterId(characterId),
            novelId = novel,
            variantId = variant,
            scope = scope,
            chapterId = ChapterId("ch-1"),
            timelinePosition = TimelinePosition(storyTime = StoryTime(year = 2024, description = "初春")),
            snapshotType = SnapshotType.KEY_EVENT,
            location = LocationId("loc-teahouse"),
            physicalState = PhysicalState(label = "尚可", details = "左臂轻伤"),
            emotionalState = EmotionalState(label = "警觉", intensity = 0.6f),
            currentGoal = "查清账目",
            currentMotivation = "为父洗冤",
            relationships = listOf(RelationshipSnapshot(otherCharacterId = CharacterId("c-rival"), relation = "宿敌")),
            knownFacts = listOf(KnowledgeId("k-ledger"), KnowledgeId("k-seal")),
            unknownFacts = listOf(KnowledgeId("k-heir")),
            abilities = listOf(AbilitySnapshot(name = "心算", description = "过目不忘")),
            source = StateSource.USER,
            createdAt = now,
        )

    private fun fullEvent(id: String, novel: NovelId, variant: VariantId?, scope: VariantScope) = Event(
        id = EventId(id),
        novelId = novel,
        variantId = variant,
        scope = scope,
        name = "茶楼对质",
        description = "当众揭穿假账",
        type = EventType.MAIN_PLOT,
        importance = 9,
        what = "沈青梧出示真账本，逼得对手当场失态",
        `when` = TimelinePosition(storyTime = StoryTime(year = 2024, month = 5, description = "初夏雨夜")),
        where = listOf(LocationId("loc-teahouse"), LocationId("loc-backroom")),
        who = listOf(CharacterId("c-hero"), CharacterId("c-rival")),
        cause = listOf(EventId("e-ledger-found")),
        consequence = listOf(EventId("e-rival-flees")),
        participants = listOf(
            EventParticipant(
                characterId = CharacterId("c-hero"),
                role = ParticipantRole.ACTOR,
                actions = listOf("出示账本", "质问"),
            ),
        ),
        evidence = listOf(
            Evidence(
                evidenceId = com.qianyan.model.EvidenceId("ev-1"),
                knowledgeId = KnowledgeId("k-ledger"),
                source = KnowledgeSource(type = SourceType.ORIGINAL_TEXT, references = listOf(SourceReference(refType = ReferenceType.ORIGINAL_CHAPTER, refId = "ch-1"))),
                excerpt = "账册第三页",
                description = "原文描述的真账本",
            ),
        ),
        status = EventStatus.CONFIRMED,
        chapterId = ChapterId("ch-2"),
        factLevel = FactLevel.INFERRED,
        createdAt = now,
    )

    /* ---------- 测试 1：17 个新字段逐字段往返无损 ---------- */

    @Test
    fun `all new world model fields survive save and get roundtrip`() {
        val h = handle()
        seedNovel(h.db, "wm")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("wm")

        // Original 行：variantId = null（Original 保护：存储层不得携带 Variant 标识）
        repo.saveCharacter(fullCharacter("wm-c", novel, null, VariantScope.ORIGINAL))
        repo.saveCharacterState(fullState("wm-s", "wm-c", novel, null, VariantScope.ORIGINAL))
        repo.saveEvent(fullEvent("wm-e", novel, null, VariantScope.ORIGINAL))

        val c = repo.getCharacterById(CharacterId("wm-c"))!!
        assertEquals(listOf("夺回家产", "查明真相"), c.goals)
        assertEquals(listOf("黑暗", "被抛弃"), c.fears)
        assertEquals("沉静外表下藏着执念的年轻掌柜", c.description)
        assertNull(c.variantId, "Original 行 variant_id 必须为 NULL")
        assertEquals(VariantScope.ORIGINAL, c.scope)
        assertEquals(listOf("冷静", "执拗"), c.personality, "既有字段不受影响")

        val s = repo.getCharacterStateById(StateId("wm-s"))!!
        assertEquals(LocationId("loc-teahouse"), s.location)
        assertEquals("为父洗冤", s.currentMotivation)
        assertEquals(listOf(RelationshipSnapshot(otherCharacterId = CharacterId("c-rival"), relation = "宿敌")), s.relationships)
        assertEquals(listOf(KnowledgeId("k-ledger"), KnowledgeId("k-seal")), s.knownFacts)
        assertEquals(listOf(KnowledgeId("k-heir")), s.unknownFacts)
        assertEquals(listOf(AbilitySnapshot(name = "心算", description = "过目不忘")), s.abilities)
        assertEquals(StateSource.USER, s.source)
        assertEquals("查清账目", s.currentGoal, "既有字段不受影响")

        val e = repo.getEventById(EventId("wm-e"))!!
        assertEquals("沈青梧出示真账本，逼得对手当场失态", e.what)
        assertEquals(listOf(LocationId("loc-teahouse"), LocationId("loc-backroom")), e.where)
        assertEquals(listOf(EventId("e-ledger-found")), e.cause)
        assertEquals(listOf(EventId("e-rival-flees")), e.consequence)
        assertEquals(
            listOf(EventParticipant(characterId = CharacterId("c-hero"), role = ParticipantRole.ACTOR, actions = listOf("出示账本", "质问"))),
            e.participants,
        )
        assertEquals(1, e.evidence.size)
        assertEquals(com.qianyan.model.EvidenceId("ev-1"), e.evidence.single().evidenceId)
        assertEquals(FactLevel.INFERRED, e.factLevel)
        assertEquals(listOf(CharacterId("c-hero"), CharacterId("c-rival")), e.who, "既有字段不受影响")

        // 列表读取与主键读取一致
        assertEquals(c, repo.listCharacters(novel, null).single())
        assertEquals(s, repo.listCharacterStates(novel, null).single())
        assertEquals(e, repo.listEvents(novel, null).single())
    }

    /* ---------- 测试 2：默认值 / NULL / 枚举语义不发生意外变化 ---------- */

    @Test
    fun `default and null semantics survive the roundtrip`() {
        val h = handle()
        seedNovel(h.db, "wmdef")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("wmdef")

        // 全默认 Character
        repo.saveCharacter(
            Character(characterId = CharacterId("def-c"), novelId = novel, name = "默认人", createdAt = now, updatedAt = now),
        )
        val c = repo.getCharacterById(CharacterId("def-c"))!!
        assertEquals(emptyList(), c.goals)
        assertEquals(emptyList(), c.fears)
        assertEquals("", c.description)

        // 全默认 / 可空 CharacterState
        repo.saveCharacterState(
            CharacterState(
                id = StateId("def-s"), characterId = CharacterId("def-c"), novelId = novel,
                snapshotType = SnapshotType.CHAPTER_START, createdAt = now,
            ),
        )
        val s = repo.getCharacterStateById(StateId("def-s"))!!
        assertNull(s.location, "未定位必须保持 NULL")
        assertNull(s.currentMotivation, "无动机必须保持 NULL")
        assertEquals(emptyList(), s.relationships)
        assertEquals(emptyList(), s.knownFacts)
        assertEquals(emptyList(), s.unknownFacts)
        assertEquals(emptyList(), s.abilities)
        assertEquals(StateSource.INFERRED, s.source, "source 的领域默认值必须原样往返")

        // 全默认 Event
        repo.saveEvent(
            Event(id = EventId("def-e"), novelId = novel, name = "默认事件", createdAt = now),
        )
        val e = repo.getEventById(EventId("def-e"))!!
        assertEquals("", e.what)
        assertEquals(emptyList(), e.where)
        assertEquals(emptyList(), e.cause)
        assertEquals(emptyList(), e.consequence)
        assertEquals(emptyList(), e.participants)
        assertEquals(emptyList(), e.evidence)
        assertEquals(FactLevel.EXPLICIT, e.factLevel, "factLevel 的领域默认值必须原样往返")
    }

    /* ---------- 测试 3：Variant 隔离——字段值不跨 scope 串扰 ---------- */

    @Test
    fun `variant scopes stay isolated including the new field values`() {
        val h = handle()
        seedNovel(h.db, "wmiso")
        val repo = SqliteStoryStateRepository(h.db)
        val novel = NovelId("wmiso")
        val v1 = VariantId("wv1")
        val v2 = VariantId("wv2")

        repo.saveCharacter(fullCharacter("iso-orig", novel, null, VariantScope.ORIGINAL))
        repo.saveCharacter(fullCharacter("iso-v1", novel, v1, VariantScope.VARIANT))
        repo.saveCharacter(fullCharacter("iso-v2", novel, v2, VariantScope.VARIANT))
        repo.saveEvent(fullEvent("iso-e-orig", novel, null, VariantScope.ORIGINAL))
        repo.saveEvent(fullEvent("iso-e-v1", novel, v1, VariantScope.VARIANT))

        // Original 列表只见 Original 行，且新字段值是 Original 自己的
        val originals = repo.listCharacters(novel, null)
        assertEquals(listOf("iso-orig"), originals.map { it.characterId.value })
        assertEquals(listOf("夺回家产", "查明真相"), originals.single().goals)
        // Variant 行携带自己的 variantId / scope 与字段值
        val v1Rows = repo.listCharacters(novel, v1)
        assertEquals(listOf("iso-v1"), v1Rows.map { it.characterId.value })
        assertEquals(v1, v1Rows.single().variantId)
        assertEquals(VariantScope.VARIANT, v1Rows.single().scope)
        assertEquals(listOf("夺回家产", "查明真相"), v1Rows.single().goals, "同字段值在不同行间各自持久化")
        assertEquals(listOf("iso-v2"), repo.listCharacters(novel, v2).map { it.characterId.value })

        // Event 隔离
        assertEquals(listOf("iso-e-orig"), repo.listEvents(novel, null).map { it.id.value })
        assertEquals(listOf("iso-e-v1"), repo.listEvents(novel, v1).map { it.id.value })
        assertEquals(
            FactLevel.INFERRED,
            repo.getEventById(EventId("iso-e-v1"))!!.factLevel,
            "Variant 行的新字段独立读回",
        )
        assertTrue(repo.listEvents(novel, null).none { it.id.value == "iso-e-v1" })
    }
}
