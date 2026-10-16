package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.StateId
import com.qianyan.model.StateSource
import com.qianyan.model.VariantScope
import com.qianyan.model.character.Character
import com.qianyan.model.character.CharacterState
import com.qianyan.model.character.SnapshotType
import com.qianyan.model.core.Novel
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2（v3.1 §11.9）· Schema v26 → v27（26.sqm）值级迁移验证。
 *
 * 用真实 v26 形态数据库：真实建库（最新 v28）→ 播种行 → 真实摘除 17 个新列 → user_version = 26 →
 * 重开触发迁移 → 断言：
 *  1) 17 列全部加回，版本升到最新（28）；
 *  2) 旧行的**既有字段**原样保留；
 *  3) 旧行的**新字段**读回为领域默认值（'[]' / '' / NULL / INFERRED / EXPLICIT）——
 *     这些字段此前从未持久化，默认值是可证明重建，不是猜测性回填；
 *  4) 重复初始化幂等。
 */
class StoryStateWorldModelFieldsMigrationTest {

    /** v27 新增的 17 列（表名, 列名），与 26.sqm 一一对应。 */
    private val NEW_COLUMNS: List<Pair<String, String>> = listOf(
        "Character" to "goals",
        "Character" to "fears",
        "Character" to "description",
        "CharacterState" to "location",
        "CharacterState" to "current_motivation",
        "CharacterState" to "relationships",
        "CharacterState" to "known_facts",
        "CharacterState" to "unknown_facts",
        "CharacterState" to "abilities",
        "CharacterState" to "source",
        "Event" to "what",
        "Event" to "where_json",
        "Event" to "cause",
        "Event" to "consequence",
        "Event" to "participants",
        "Event" to "evidence",
        "Event" to "fact_level",
    )

    private val now = kotlinx.datetime.Instant.fromEpochSeconds(1787777777, 0)

    private fun seed(driver: JdbcSqliteDriver) {
        val db = QianyanDb(driver)
        SqliteNovelRepository(db).createOriginal(
            Novel(
                novelId = NovelId("mig"), projectId = ProjectId("proj-mig"), title = "迁移书",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now, updatedAt = now,
            ),
        )
        val repo = SqliteStoryStateRepository(db)
        val novel = NovelId("mig")
        repo.saveCharacter(
            Character(
                characterId = CharacterId("mig-c"), novelId = novel, name = "林晚",
                personality = listOf("冷静"), createdAt = now, updatedAt = now,
            ),
        )
        repo.saveCharacterState(
            CharacterState(
                id = StateId("mig-s"), characterId = CharacterId("mig-c"), novelId = novel,
                snapshotType = SnapshotType.CHAPTER_START, currentGoal = "旧目标",
                createdAt = now,
            ),
        )
        repo.saveEvent(
            Event(
                id = EventId("mig-e"), novelId = novel, name = "旧事件",
                description = "旧描述", type = EventType.WORLD_EVENT, importance = 7,
                status = EventStatus.CONFIRMED, createdAt = now,
            ),
        )
    }

    @Test
    fun `v26 to v27 migration adds columns and keeps old rows at domain defaults`() {
        val file = java.nio.file.Files.createTempFile("qianyan_wm_fields_v26", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 真实建到 v27 并播种（新列有值）
            val first = QianyanDbFactory.open(url)
            val d1 = first.driver as JdbcSqliteDriver
            seed(d1)
            NEW_COLUMNS.forEach { (tbl, col) ->
                assertTrue(columnExists(d1, tbl, col), "前置：v27 全新库应含 $tbl.$col")
            }
            assertEquals(28L, userVersion(d1), "前置：全新库版本应为 28")
            d1.getConnection().close()

            // 2) 真实回退为 v26 形态：摘除 17 列 + user_version = 26
            val down = QianyanDbFactory.open(url)
            val dd = down.driver as JdbcSqliteDriver
            NEW_COLUMNS.forEach { (tbl, col) -> dd.execute(null, "ALTER TABLE $tbl DROP COLUMN $col", 0) }
            // P3 v28（27.sqm）：CandidateFact 由迁移建出，降级前必须真实摘除（CREATE TABLE 不幂等）
            dd.execute(null, "DROP TABLE IF EXISTS CandidateFact", 0)
            dd.execute(null, "PRAGMA user_version = 26", 0)
            assertEquals(26L, userVersion(dd), "前置：模拟库应为 v26")
            NEW_COLUMNS.forEach { (tbl, col) ->
                assertTrue(!columnExists(dd, tbl, col), "前置：v26 形态无 $tbl.$col")
            }
            dd.getConnection().close()

            // 3) 重开 → DatabaseInitializer 执行 v26 → v27
            val second = QianyanDbFactory.open(url)
            val d2 = second.driver as JdbcSqliteDriver
            try {
                assertEquals(28L, userVersion(d2), "v26 库应迁移到最新版本")
                NEW_COLUMNS.forEach { (tbl, col) ->
                    assertTrue(columnExists(d2, tbl, col), "迁移后应含 $tbl.$col")
                }

                // 4) 值级断言：既有字段原样保留 + 新字段 = 领域默认值
                val repo = SqliteStoryStateRepository(QianyanDb(d2))

                val c = repo.getCharacterById(CharacterId("mig-c"))!!
                assertEquals("林晚", c.name, "旧行既有字段必须原样保留")
                assertEquals(listOf("冷静"), c.personality, "旧行既有字段必须原样保留")
                assertEquals(emptyList(), c.goals, "旧行 goals = 领域默认 '[]'")
                assertEquals(emptyList(), c.fears, "旧行 fears = 领域默认 '[]'")
                assertEquals("", c.description, "旧行 description = 领域默认空串")

                val s = repo.getCharacterStateById(StateId("mig-s"))!!
                assertEquals("旧目标", s.currentGoal, "旧行既有字段必须原样保留")
                assertNull(s.location, "旧行 location = NULL（领域默认）")
                assertNull(s.currentMotivation, "旧行 current_motivation = NULL（领域默认）")
                assertEquals(emptyList(), s.relationships, "旧行 relationships = 领域默认 '[]'")
                assertEquals(emptyList(), s.knownFacts, "旧行 known_facts = 领域默认 '[]'")
                assertEquals(emptyList(), s.unknownFacts, "旧行 unknown_facts = 领域默认 '[]'")
                assertEquals(emptyList(), s.abilities, "旧行 abilities = 领域默认 '[]'")
                assertEquals(StateSource.INFERRED, s.source, "旧行 source = 领域默认 INFERRED（DEFAULT 列值）")

                val e = repo.getEventById(EventId("mig-e"))!!
                assertEquals("旧事件", e.name, "旧行既有字段必须原样保留")
                assertEquals("旧描述", e.description, "旧行既有字段必须原样保留")
                assertEquals(7, e.importance, "旧行既有字段必须原样保留")
                assertEquals("", e.what, "旧行 what = 领域默认空串")
                assertEquals(emptyList(), e.where, "旧行 where_json = 领域默认 '[]'")
                assertEquals(emptyList(), e.cause, "旧行 cause = 领域默认 '[]'")
                assertEquals(emptyList(), e.consequence, "旧行 consequence = 领域默认 '[]'")
                assertEquals(emptyList(), e.participants, "旧行 participants = 领域默认 '[]'")
                assertEquals(emptyList(), e.evidence, "旧行 evidence = 领域默认 '[]'")
                assertEquals(FactLevel.EXPLICIT, e.factLevel, "旧行 fact_level = 领域默认 EXPLICIT（DEFAULT 列值）")

                // 5) 幂等：重复初始化不改值
                DatabaseInitializer.initializeDatabase(d2)
                DatabaseInitializer.initializeDatabase(d2)
                assertEquals(28L, userVersion(d2))
                assertEquals(emptyList(), repo.getCharacterById(CharacterId("mig-c"))!!.goals, "幂等重跑后默认值不变")
                assertEquals("旧事件", repo.getEventById(EventId("mig-e"))!!.name, "幂等重跑后旧行不变")
            } finally {
                d2.getConnection().close()
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    /* ---------------- helpers ---------------- */

    private fun columnExists(driver: JdbcSqliteDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null, "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) }, 0,
        ).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            null, "PRAGMA user_version",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
        ).value
}
