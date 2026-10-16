package com.qianyan.application.usecase.writing.planning

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.StateId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.VariantContext
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.StateSource
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2 Unit 2 · PlanningContextAssembly 的 World Model **真实消费**测试（v3.1 §11.5）。
 *
 * 验证：
 *  1) `PlanningContext.characters` 不再是占位空列表，而是来自 StoryState 持久化数据（经 Resolver 确定性合并），
 *     且携带 v27 补齐字段（personality / **goals**）；
 *  2) 空数据 ⇒ 稳定空投影（不报错、不伪造）；
 *  3) Variant / Novel 隔离在投影层保持；
 *  4) 读取路径**零写入**（Character / CharacterState / Event / CommitHistory / ChapterDraft 行数不变）。
 */
class PlanningContextWorldModelTest {

    private val now = Clock.System.now()

    private class F(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fixture(): F {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return F(ApplicationContainer.fromDriver(h.driver, MockLLMGateway()), h)
    }

    private fun request(novelId: NovelId, variantId: VariantId? = null) = UserWritingRequest(
        requestId = RequestId("req-wm"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        variantId = variantId,
    )

    private fun character(id: String, novel: NovelId, variant: VariantId?, scope: VariantScope, goals: List<String>) =
        Character(
            characterId = CharacterId(id),
            novelId = novel,
            variantId = variant,
            scope = scope,
            name = "人物-$id",
            personality = listOf("冷静"),
            goals = goals,
            createdAt = now,
            updatedAt = now,
        )

    private fun event(id: String, novel: NovelId) = Event(
        id = EventId(id),
        novelId = novel,
        name = "事件-$id",
        type = EventType.MAIN_PLOT,
        what = "What-$id",
        factLevel = FactLevel.INFERRED,
        status = EventStatus.CONFIRMED,
        createdAt = now,
    )

    private fun state(id: String, characterId: String, novel: NovelId) = CharacterState(
        id = StateId(id),
        characterId = CharacterId(characterId),
        novelId = novel,
        snapshotType = SnapshotType.CHAPTER_START,
        currentMotivation = "动机-$id",
        source = StateSource.USER,
        createdAt = now,
    )

    private fun countRows(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null, "SELECT COUNT(*) FROM $table",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
        ).value

    /* ---------- 1. 真实数据组装（含 v27 goals 字段） ---------- */

    @Test
    fun `characters projection consumes persisted world model data including v27 goals`() {
        val f = fixture()
        val app = f.app
        val novelId = app.novels.createOriginal(title = "星辰大陆", synopsis = "少年陈夜探索星辰之谜。")
        app.storyState.saveCharacter(
            character("c-hero", novelId, null, VariantScope.ORIGINAL, goals = listOf("夺回家产", "查明真相")),
        )
        app.storyState.saveCharacterState(state("s-1", "c-hero", novelId))
        app.storyState.saveEvent(event("e-1", novelId))
        val tables = listOf("Character", "CharacterState", "Event", "CommitHistory", "ChapterDraft")
        val countsBefore = tables.associateWith { countRows(f.handle, it) }

        val ctx = app.planningContextAssembly.assemble(request(novelId))

        val lite = ctx.characters.single()
        assertEquals(CharacterId("c-hero"), lite.characterId)
        assertEquals("人物-c-hero", lite.name)
        assertEquals(listOf("冷静"), lite.personality)
        assertEquals(listOf("夺回家产", "查明真相"), lite.goals, "v27 补齐的 goals 字段必须进入 Planner 投影")

        val world = ctx.worldContext!!
        assertEquals(1, world.events.size)
        assertEquals("What-e-1", world.events.single().what, "v27 what 字段经 Resolver 可读")
        assertEquals(1, world.characterStates.size)
        assertEquals("动机-s-1", world.characterStates.single().currentMotivation, "v27 current_motivation 字段经 Resolver 可读")

        // 读取零写入：组装前后行数不变
        val countsAfter = tables.associateWith { countRows(f.handle, it) }
        assertEquals(countsBefore, countsAfter, "读侧组装不得产生任何 World Model / Canonical 写入")
        f.close()
    }

    /* ---------- 2. 空数据 ⇒ 稳定空投影 ---------- */

    @Test
    fun `novel without world model data yields stable empty projection`() {
        val f = fixture()
        val novelId = f.app.novels.createOriginal(title = "空世界")

        val ctx = f.app.planningContextAssembly.assemble(request(novelId))

        assertTrue(ctx.characters.isEmpty(), "无 World Model 数据 ⇒ characters 空列表")
        val world = ctx.worldContext!!
        assertTrue(world.characters.isEmpty())
        assertTrue(world.events.isEmpty())
        assertTrue(world.characterStates.isEmpty())
        f.close()
    }

    /* ---------- 3. Variant / Novel 隔离 ---------- */

    @Test
    fun `characters projection keeps variant and novel isolation`() {
        val f = fixture()
        val app = f.app
        val novelId = app.novels.createOriginal(title = "隔离书")
        val v1 = VariantId("wm-v1")
        val v2 = VariantId("wm-v2")
        app.novels.createVariant(
            VariantContext(baseNovelId = BaseNovelId(novelId.value), variantId = v1), name = "改一", variantId = v1,
        )
        app.novels.createVariant(
            VariantContext(baseNovelId = BaseNovelId(novelId.value), variantId = v2), name = "改二", variantId = v2,
        )
        app.storyState.saveCharacter(character("c-orig", novelId, null, VariantScope.ORIGINAL, goals = listOf("原著目标")))
        app.storyState.saveCharacter(character("c-v1", novelId, v1, VariantScope.VARIANT, goals = listOf("改一目标")))
        app.storyState.saveCharacter(character("c-v2", novelId, v2, VariantScope.VARIANT, goals = listOf("改二目标")))
        val otherNovelId = app.novels.createOriginal(title = "别家书")
        app.storyState.saveCharacter(character("c-other", otherNovelId, null, VariantScope.ORIGINAL, goals = listOf("别家目标")))

        // Original：只有 Original 行
        val original = app.planningContextAssembly.assemble(request(novelId))
        assertEquals(listOf("c-orig"), original.characters.map { it.characterId.value })
        assertEquals(listOf("原著目标"), original.characters.single().goals)

        // Variant v1：base(Original) + own(v1)，不见 v2 / 别家 Novel
        val withV1 = app.planningContextAssembly.assemble(request(novelId, v1))
        assertEquals(setOf("c-orig", "c-v1"), withV1.characters.map { it.characterId.value }.toSet())
        assertTrue(withV1.characters.none { it.characterId.value == "c-v2" }, "其他 Variant 的行不得泄漏")
        assertTrue(withV1.characters.none { it.characterId.value == "c-other" }, "其他 Novel 的行不得泄漏")

        // Variant v2：同样隔离
        val withV2 = app.planningContextAssembly.assemble(request(novelId, v2))
        assertEquals(setOf("c-orig", "c-v2"), withV2.characters.map { it.characterId.value }.toSet())
        f.close()
    }

    /* ---------- 4. ChapterId 引用（无 Location 表前提下的既有契约不变） ---------- */

    @Test
    fun `character state chapter reference stays a plain reference without resolution`() {
        val f = fixture()
        val app = f.app
        val novelId = app.novels.createOriginal(title = "引用书")
        app.storyState.saveCharacterState(
            CharacterState(
                id = StateId("s-ref"), characterId = CharacterId("c-x"), novelId = novelId,
                chapterId = ChapterId("ch-not-exist"),
                snapshotType = SnapshotType.KEY_EVENT, createdAt = now,
            ),
        )
        val ctx = app.planningContextAssembly.assemble(request(novelId))
        assertEquals(ChapterId("ch-not-exist"), ctx.worldContext!!.characterStates.single().chapterId)
        assertTrue(ctx.characters.isEmpty(), "仅有 CharacterState 行 ⇒ characters 仍为空")
        f.close()
    }
}
