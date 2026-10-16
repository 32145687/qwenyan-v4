package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
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
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.core.Novel
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.model.timeline.TimelineEntry
import com.qianyan.model.timeline.TimelinePosition
import com.qianyan.model.world.WorldRule
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P2 收口（v3.1 §11.10）· Story State 六表 Original 写保护测试。
 *
 * 通过真实 Storage / SQLite 初始化路径验证（非 SQL 字符串断言）：
 *  1) 六表 Original 行的 UPDATE / DELETE 被触发器拒绝（`... is immutable`）；
 *  2) 六表 Original 行被 `INSERT OR REPLACE` 覆盖被拒绝（`... cannot be overwritten`；
 *     recursive_triggers=OFF 下 REPLACE 隐式 DELETE 不走 DELETE 触发器，故必须有显式覆盖保护）；
 *  3) 六表新 Original 行首插、Variant 行按契约重存均不受影响；
 *  4) Variant Foreshadow 生命周期 UPDATE（transitionForeshadowState）仍可执行；
 *  5) `initializeDatabase()` 幂等重装不报错，21 个守卫触发器（3 既有 + 18 新增）全部存在。
 * 触发器安装失败时 `initializeDatabase()` 中断并向上传播异常 —— 既有 GUARD_DDL 机制行为，本轮未改变。
 */
class StoryStateOriginalProtectionTest {

    private val now: Instant = Instant.fromEpochSeconds(1787999000, 0)

    private class F(val handle: QianyanDbHandle) {
        val driver = handle.driver as JdbcSqliteDriver
        val repo = SqliteStoryStateRepository(QianyanDb(driver))
        fun close() = handle.driver.close()
    }

    private fun fixture(): F {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return F(handle)
    }

    private fun seedNovel(f: F, novelId: String) {
        SqliteNovelRepository(QianyanDb(f.driver)).createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "保护书",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now, updatedAt = now,
            ),
        )
    }

    /* ---------- 六表种子（Original + Variant 各一行；走真实仓储写入路径） ---------- */

    private fun seedAll(f: F, novel: String) {
        val n = NovelId(novel)
        val v = VariantId("prot-v")
        val repo = f.repo
        repo.saveCharacter(
            Character(CharacterId("p-c"), n, null, VariantScope.ORIGINAL, name = "原著人物", createdAt = now, updatedAt = now),
        )
        repo.saveCharacter(
            Character(CharacterId("p-cv"), n, v, VariantScope.VARIANT, name = "变体人物", createdAt = now, updatedAt = now),
        )
        repo.saveCharacterState(
            CharacterState(StateId("p-s"), CharacterId("p-c"), n, null, VariantScope.ORIGINAL, snapshotType = SnapshotType.CHAPTER_START, createdAt = now),
        )
        repo.saveCharacterState(
            CharacterState(StateId("p-sv"), CharacterId("p-cv"), n, v, VariantScope.VARIANT, snapshotType = SnapshotType.CHAPTER_START, createdAt = now),
        )
        repo.saveEvent(
            Event(EventId("p-e"), n, null, VariantScope.ORIGINAL, name = "原著事件", status = EventStatus.CONFIRMED, createdAt = now),
        )
        repo.saveEvent(
            Event(EventId("p-ev"), n, v, VariantScope.VARIANT, name = "变体事件", status = EventStatus.CONFIRMED, createdAt = now),
        )
        repo.saveWorldRule(
            WorldRule(WorldRuleId("p-r"), WorldId("w-$novel"), n, null, VariantScope.ORIGINAL, content = "原著规则"),
        )
        repo.saveWorldRule(
            WorldRule(WorldRuleId("p-rv"), WorldId("w-$novel"), n, v, VariantScope.VARIANT, content = "变体规则"),
        )
        repo.saveTimelineEntry(
            TimelineEntry(TimelineEntryId("p-t"), n, null, VariantScope.ORIGINAL, position = TimelinePosition(), description = "原著节点"),
        )
        repo.saveTimelineEntry(
            TimelineEntry(TimelineEntryId("p-tv"), n, v, VariantScope.VARIANT, position = TimelinePosition(), description = "变体节点"),
        )
        repo.saveForeshadow(
            Foreshadow(ForeshadowingId("p-f"), n, null, VariantScope.ORIGINAL, content = "原著伏笔", createdAt = now),
        )
        repo.saveForeshadow(
            Foreshadow(ForeshadowingId("p-fv"), n, v, VariantScope.VARIANT, content = "变体伏笔", createdAt = now),
        )
    }

    /* ---------- 1/2/3：六表 Original 行 UPDATE / DELETE / REPLACE 全部被拒 ---------- */

    @Test
    fun `original rows reject update delete and replace overwrite across all six tables`() {
        val f = fixture()
        seedNovel(f, "prot")
        seedAll(f, "prot")

        // 每表：raw UPDATE（改动非主键列）、raw DELETE、仓储 save 同 PK Original（INSERT OR REPLACE）
        val cases: List<Triple<String, () -> Unit, () -> Unit>> = listOf(
            Triple("Character",
                { f.driver.execute(null, "UPDATE Character SET name = '篡改' WHERE character_id = 'p-c'", 0) },
                { f.repo.saveCharacter(Character(CharacterId("p-c"), NovelId("prot"), null, VariantScope.ORIGINAL, name = "篡改", createdAt = now, updatedAt = now)) }),
            Triple("CharacterState",
                { f.driver.execute(null, "UPDATE CharacterState SET current_goal = '篡改' WHERE state_id = 'p-s'", 0) },
                { f.repo.saveCharacterState(CharacterState(StateId("p-s"), CharacterId("p-c"), NovelId("prot"), null, VariantScope.ORIGINAL, snapshotType = SnapshotType.CHAPTER_START, currentGoal = "篡改", createdAt = now)) }),
            Triple("Event",
                { f.driver.execute(null, "UPDATE Event SET name = '篡改' WHERE event_id = 'p-e'", 0) },
                { f.repo.saveEvent(Event(EventId("p-e"), NovelId("prot"), null, VariantScope.ORIGINAL, name = "篡改", status = EventStatus.CONFIRMED, createdAt = now)) }),
            Triple("WorldRule",
                { f.driver.execute(null, "UPDATE WorldRule SET content = '篡改' WHERE rule_id = 'p-r'", 0) },
                { f.repo.saveWorldRule(WorldRule(WorldRuleId("p-r"), WorldId("w-prot"), NovelId("prot"), null, VariantScope.ORIGINAL, content = "篡改")) }),
            Triple("TimelineEntry",
                { f.driver.execute(null, "UPDATE TimelineEntry SET description = '篡改' WHERE timeline_id = 'p-t'", 0) },
                { f.repo.saveTimelineEntry(TimelineEntry(TimelineEntryId("p-t"), NovelId("prot"), null, VariantScope.ORIGINAL, position = TimelinePosition(), description = "篡改")) }),
            Triple("Foreshadow",
                { f.driver.execute(null, "UPDATE Foreshadow SET content = '篡改' WHERE foreshadow_id = 'p-f'", 0) },
                { f.repo.saveForeshadow(Foreshadow(ForeshadowingId("p-f"), NovelId("prot"), null, VariantScope.ORIGINAL, content = "篡改", createdAt = now)) }),
        )

        for ((table, updateBlock, replaceBlock) in cases) {
            assertRejectedWith("$table UPDATE", "is immutable") { updateBlock() }
            assertRejectedWith("$table DELETE", "is immutable") {
                f.driver.execute(null, "DELETE FROM $table WHERE ${pkOf(table)} = '${pkValue(table)}'", 0)
            }
            assertRejectedWith("$table REPLACE", "cannot be overwritten") { replaceBlock() }
        }

        // 被拒后原行内容未被改动
        assertEquals("原著人物", f.repo.getCharacterById(CharacterId("p-c"))!!.name)
        assertEquals("原著规则", f.repo.getWorldRuleById(WorldRuleId("p-r"))!!.content)
        assertEquals("原著伏笔", f.repo.getForeshadowById(ForeshadowingId("p-f"))!!.content)
        f.close()
    }

    /* ---------- 4：新 Original 首插与 Variant 重存不受影响 ---------- */

    @Test
    fun `fresh original inserts and variant resaves remain allowed`() {
        val f = fixture()
        seedNovel(f, "prot2")
        seedAll(f, "prot2")

        // 新 Original 行首插（INSERT OR REPLACE 无同 PK 既有 Original）→ 允许
        f.repo.saveCharacter(
            Character(CharacterId("p-c2"), NovelId("prot2"), null, VariantScope.ORIGINAL, name = "新增人物", createdAt = now, updatedAt = now),
        )
        assertEquals("新增人物", f.repo.getCharacterById(CharacterId("p-c2"))!!.name)

        // Variant 行按契约重存（REPLACE 同 Variant PK）→ 允许
        f.repo.saveCharacter(
            Character(CharacterId("p-cv"), NovelId("prot2"), VariantId("prot-v"), VariantScope.VARIANT, name = "变体人物改", createdAt = now, updatedAt = now),
        )
        assertEquals("变体人物改", f.repo.getCharacterById(CharacterId("p-cv"))!!.name)

        // Variant Foreshadow 生命周期 UPDATE（transitionForeshadowState）→ 仍可执行
        assertEquals(1, f.repo.transitionForeshadowState(
            ForeshadowingId("p-fv"), com.qianyan.model.story.ForeshadowLifecycleState.PLANTED,
            com.qianyan.model.story.ForeshadowLifecycleState.ACTIVE, "test", now, null,
        ))
        f.close()
    }

    /* ---------- 5：幂等重装 + 21 个守卫触发器全部存在 ---------- */

    @Test
    fun `repeated initialization is idempotent and installs all guard triggers`() {
        val file = java.nio.file.Files.createTempFile("qianyan_wm_protect", ".db").toAbsolutePath()
        try {
            val h = QianyanDbFactory.open("jdbc:sqlite:$file")
            val driver = h.driver as JdbcSqliteDriver
            try {
                // 旧 v27 库重开（含迁移路径）后同样补装；此处额外重复初始化两次验证幂等
                DatabaseInitializer.initializeDatabase(driver)
                DatabaseInitializer.initializeDatabase(driver)

                val expected = listOf(
                    // 既有 3 个（Novel / NovelVariant 层）
                    "novel_original_update_protect", "novel_original_delete_protect", "variant_base_must_be_original",
                    // P2 收口新增 18 个（Story State 六表 × UPDATE/DELETE/OVERWRITE）
                    "character_original_update_protect", "character_original_delete_protect", "character_original_overwrite_protect",
                    "character_state_original_update_protect", "character_state_original_delete_protect", "character_state_original_overwrite_protect",
                    "event_original_update_protect", "event_original_delete_protect", "event_original_overwrite_protect",
                    "world_rule_original_update_protect", "world_rule_original_delete_protect", "world_rule_original_overwrite_protect",
                    "timeline_entry_original_update_protect", "timeline_entry_original_delete_protect", "timeline_entry_original_overwrite_protect",
                    "foreshadow_original_update_protect", "foreshadow_original_delete_protect", "foreshadow_original_overwrite_protect",
                )
                val present = triggerNames(driver)
                expected.forEach { name ->
                    assertTrue(name in present, "守卫触发器应存在: $name（实际: $present）")
                }
                assertEquals(expected.size, present.size, "不应有未预期的额外触发器")
            } finally {
                driver.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    /* ---------- helpers ---------- */

    private fun pkOf(table: String) = when (table) {
        "Character" -> "character_id"
        "CharacterState" -> "state_id"
        "Event" -> "event_id"
        "WorldRule" -> "rule_id"
        "TimelineEntry" -> "timeline_id"
        "Foreshadow" -> "foreshadow_id"
        else -> throw IllegalArgumentException(table)
    }

    private fun pkValue(table: String) = when (table) {
        "Character" -> "p-c"
        "CharacterState" -> "p-s"
        "Event" -> "p-e"
        "WorldRule" -> "p-r"
        "TimelineEntry" -> "p-t"
        "Foreshadow" -> "p-f"
        else -> throw IllegalArgumentException(table)
    }

    /** 真实执行写入并断言被触发器拒绝，且错误信息点名预期语义（immutable / cannot be overwritten）。 */
    private inline fun assertRejectedWith(what: String, messagePart: String, block: () -> Unit) {
        val err = runCatching(block).exceptionOrNull()
        assertNotNull(err, "$what 必须被 Original 写保护触发器拒绝，实际未抛异常")
        assertTrue(
            err.message.orEmpty().contains(messagePart),
            "$what 的拒绝信息应含 \"$messagePart\"，实际: ${err.message}",
        )
    }

    private fun triggerNames(driver: JdbcSqliteDriver): List<String> =
        driver.executeQuery(
            null, "SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name",
            { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names.add(cursor.getString(0)!!)
                QueryResult.Value(names)
            },
            0,
        ).value
}
