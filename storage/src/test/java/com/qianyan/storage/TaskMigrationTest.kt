package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.task.TaskStatus
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteMemoryRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import com.qianyan.storage.repository.SqliteTxtRepository
import com.qianyan.storage.repository.SqliteVocabularyRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P8.1 · v1 → v2 migration 测试。
 *
 * 目标：构建一个**真实 v1 schema**（仅含 P0–P7 已有业务表，无 Task / Checkpoint，
 * `PRAGMA user_version = 1`），写入代表性旧数据（Novel / Memory / Vocabulary / TXT），
 * 再经 [DatabaseInitializer.initializeDatabase] 升级到 v2，验证：
 *   1) 旧数据原样保留（通过既有 Repository 读回，而非裸 SQL）；
 *   2) Task / Checkpoint 表已创建；
 *   3) 迁移后可正常 CRUD；
 *   4) `PRAGMA user_version` 同步为 2；
 *   5) 重复执行 migration 幂等安全。
 */
class TaskMigrationTest {

    /** 真实 v1 schema（= 当前 Schema.sq 减去 v2 新增的 Task / Checkpoint；SQLDelight 真实 SQL 形态）。 */
    private val V1_DDL: List<String> = listOf(
        """
        CREATE TABLE Novel (
            novel_id    TEXT NOT NULL PRIMARY KEY,
            project_id  TEXT NOT NULL,
            title       TEXT NOT NULL,
            source      TEXT NOT NULL,
            genre       TEXT NOT NULL DEFAULT '[]',
            synopsis    TEXT NOT NULL DEFAULT '',
            scope       TEXT NOT NULL,
            status      TEXT NOT NULL,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE NovelVariant (
            variant_id    TEXT NOT NULL PRIMARY KEY,
            novel_id      TEXT NOT NULL,
            base_novel_id TEXT NOT NULL,
            project_id    TEXT NOT NULL,
            name          TEXT NOT NULL,
            status        TEXT NOT NULL,
            blueprint     TEXT,
            scope_spec    TEXT,
            created_at    INTEGER NOT NULL,
            updated_at    INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
        )
        """,
        """
        CREATE TABLE EntityOverride (
            override_id    TEXT NOT NULL PRIMARY KEY,
            variant_id     TEXT NOT NULL,
            target_kind    TEXT NOT NULL,
            target_id      TEXT NOT NULL,
            operation      TEXT NOT NULL,
            replaced_value TEXT,
            note           TEXT NOT NULL DEFAULT '',
            UNIQUE (target_id, variant_id),
            FOREIGN KEY (variant_id) REFERENCES NovelVariant(variant_id)
        )
        """,
        """
        CREATE TABLE Vocabulary (
            vocabulary_id TEXT NOT NULL PRIMARY KEY,
            novel_id      TEXT,
            variant_id    TEXT,
            scope_level   TEXT NOT NULL,
            name          TEXT NOT NULL DEFAULT ''
        )
        """,
        """
        CREATE TABLE VocabularyEntry (
            entry_id      TEXT NOT NULL PRIMARY KEY,
            vocabulary_id TEXT NOT NULL,
            novel_id      TEXT,
            variant_id    TEXT,
            scope_level   TEXT NOT NULL,
            canonical     TEXT NOT NULL,
            aliases       TEXT NOT NULL DEFAULT '[]',
            type          TEXT NOT NULL,
            replacement   TEXT,
            status        TEXT NOT NULL,
            FOREIGN KEY (vocabulary_id) REFERENCES Vocabulary(vocabulary_id)
        )
        """,
        """
        CREATE TABLE VocabularyRule (
            rule_id           TEXT NOT NULL PRIMARY KEY,
            vocabulary_id     TEXT NOT NULL,
            novel_id          TEXT,
            variant_id        TEXT,
            scope_level       TEXT NOT NULL,
            vocab_from        TEXT NOT NULL,
            vocab_to          TEXT NOT NULL,
            enabled           INTEGER NOT NULL DEFAULT 1,
            deterministic_only INTEGER NOT NULL DEFAULT 1,
            FOREIGN KEY (vocabulary_id) REFERENCES Vocabulary(vocabulary_id)
        )
        """,
        """
        CREATE TABLE VocabularyCandidate (
            candidate_id  TEXT NOT NULL PRIMARY KEY,
            vocabulary_id TEXT NOT NULL,
            novel_id      TEXT,
            variant_id    TEXT,
            scope_level   TEXT NOT NULL,
            suggested     TEXT NOT NULL,
            source        TEXT NOT NULL,
            status        TEXT NOT NULL,
            created_at    INTEGER NOT NULL,
            FOREIGN KEY (vocabulary_id) REFERENCES Vocabulary(vocabulary_id)
        )
        """,
        """
        CREATE TABLE MemoryEntry (
            memory_id   TEXT NOT NULL PRIMARY KEY,
            novel_id    TEXT NOT NULL,
            variant_id  TEXT,
            scope       TEXT NOT NULL,
            layer       TEXT NOT NULL,
            content     TEXT NOT NULL,
            source      TEXT NOT NULL DEFAULT '',
            created_by  TEXT,
            created_at  INTEGER NOT NULL,
            updated_at  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE TxtDocument (
            document_id     TEXT NOT NULL PRIMARY KEY,
            novel_id        TEXT,
            source_name     TEXT NOT NULL DEFAULT '',
            title           TEXT NOT NULL DEFAULT '',
            encoding        TEXT NOT NULL,
            had_bom         INTEGER NOT NULL DEFAULT 0,
            byte_count      INTEGER NOT NULL DEFAULT 0,
            char_count      INTEGER NOT NULL DEFAULT 0,
            original_text   TEXT NOT NULL DEFAULT '',
            normalized_text TEXT NOT NULL DEFAULT '',
            content_hash    TEXT NOT NULL DEFAULT '',
            rule_version    TEXT NOT NULL DEFAULT '',
            status          TEXT NOT NULL,
            created_at      INTEGER NOT NULL,
            FOREIGN KEY (novel_id) REFERENCES Novel(novel_id)
        )
        """,
        """
        CREATE TABLE TxtChapter (
            chapter_id          TEXT NOT NULL PRIMARY KEY,
            document_id         TEXT NOT NULL,
            novel_id            TEXT,
            ordinal             INTEGER NOT NULL,
            title               TEXT NOT NULL DEFAULT '',
            source_start        INTEGER NOT NULL,
            source_end          INTEGER NOT NULL,
            first_block_ordinal INTEGER NOT NULL,
            block_count         INTEGER NOT NULL,
            FOREIGN KEY (document_id) REFERENCES TxtDocument(document_id)
        )
        """,
        """
        CREATE TABLE TextBlock (
            block_id     TEXT NOT NULL PRIMARY KEY,
            chapter_id   TEXT NOT NULL,
            document_id  TEXT NOT NULL,
            novel_id     TEXT,
            ordinal      INTEGER NOT NULL,
            text         TEXT NOT NULL DEFAULT '',
            source_start INTEGER NOT NULL,
            source_end   INTEGER NOT NULL,
            FOREIGN KEY (chapter_id) REFERENCES TxtChapter(chapter_id),
            FOREIGN KEY (document_id) REFERENCES TxtDocument(document_id)
        )
        """,
    )

    private fun JdbcSqliteDriver.exec(sql: String) {
        execute(null, sql, 0)
    }

    private fun buildV1Database(driver: JdbcSqliteDriver) {
        V1_DDL.forEach { driver.exec(it) }
        driver.exec("PRAGMA user_version = 1")
    }

    private fun insertV1Data(driver: JdbcSqliteDriver) {
        driver.exec(
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('mig-novel', 'proj-mig', '旧原著', 'ORIGINAL_NOVEL', '[\"仙侠\"]', '旧书简介', 'ORIGINAL', 'DRAFT', 1000, 1000)",
        )
        driver.exec(
            "INSERT INTO MemoryEntry(memory_id, novel_id, variant_id, scope, layer, content, source, created_by, created_at, updated_at) " +
                "VALUES ('mig-mem', 'mig-novel', NULL, 'ORIGINAL', 'LONG_TERM', '旧记忆', 'test', NULL, 1000, 1000)",
        )
        driver.exec(
            "INSERT INTO Vocabulary(vocabulary_id, novel_id, variant_id, scope_level, name) " +
                "VALUES ('mig-vocab', 'mig-novel', NULL, 'NOVEL', '旧词库')",
        )
        driver.exec(
            "INSERT INTO VocabularyEntry(entry_id, vocabulary_id, novel_id, variant_id, scope_level, canonical, aliases, type, replacement, status) " +
                "VALUES ('mig-entry', 'mig-vocab', 'mig-novel', NULL, 'NOVEL', '灵石', '[\"石\"]', 'WORLD_TERM', '星石', 'APPROVED')",
        )
        driver.exec(
            "INSERT INTO TxtDocument(document_id, novel_id, source_name, title, encoding, had_bom, byte_count, char_count, " +
                "original_text, normalized_text, content_hash, rule_version, status, created_at) " +
                "VALUES ('mig-txt', 'mig-novel', 'a.txt', '旧文', 'UTF8', 0, 9, 3, 'abc', 'abc', 'hash1', 'v1', 'SUCCESS', 1000)",
        )
    }

    private fun tableExists(driver: JdbcSqliteDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    private fun userVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

    @Test
    fun `v1 to v2 migration preserves old data and enables task crud`() {
        val file = java.nio.file.Files.createTempFile("qianyan_mig_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"

        // 1) 构建真实 v1 schema + 写入旧数据
        val v1 = JdbcSqliteDriver(url)
        buildV1Database(v1)
        insertV1Data(v1)
        assertTrue(tableExists(v1, "Novel"), "v1 库应有 Novel")
        assertTrue(!tableExists(v1, "Task"), "v1 库不应有 Task")
        assertEquals(1L, userVersion(v1))
        (v1).getConnection().close()

        // 2) 重新打开：DatabaseInitializer 检测到旧 v1 库 → 应用 1.sqm migration 到 v2
        val h = QianyanDbFactory.open(url)
        val driver = h.driver as JdbcSqliteDriver

        // 3) 新表已创建，版本已同步
        assertTrue(tableExists(driver, "Task"), "migration 后应存在 Task 表")
        assertTrue(tableExists(driver, "Checkpoint"), "migration 后应存在 Checkpoint 表")
        assertEquals(2L, userVersion(driver), "migration 后 user_version 应为 2")

        // 4) 旧数据原样保留（通过既有 Repository 读回）
        val novels = SqliteNovelRepository(h.db)
        val readNovel = novels.getNovel(NovelId("mig-novel"))
        assertNotNull(readNovel, "旧 Novel 数据应保留")
        assertEquals("旧原著", readNovel.title)
        assertEquals(listOf("仙侠"), readNovel.genre)
        assertEquals(VariantScope.ORIGINAL, readNovel.scope)

        val memories = SqliteMemoryRepository(h.db)
        val mem = memories.findEntriesByNovel(NovelId("mig-novel"))
        assertEquals(1, mem.size)
        assertEquals("旧记忆", mem[0].content)

        val vocab = SqliteVocabularyRepository(h.db)
        val entries = vocab.findEntriesByNovel(NovelId("mig-novel"))
        assertEquals(1, entries.size)
        assertEquals("灵石", entries[0].canonical)
        assertEquals("星石", entries[0].replacement)

        val txt = SqliteTxtRepository(h.db)
        val doc = txt.getDocument(com.qianyan.model.TxtDocumentId("mig-txt"))
        assertNotNull(doc, "旧 TXT 数据应保留")
        assertEquals("旧文", doc.title)

        // 5) 迁移后 Task / Checkpoint 可正常 CRUD
        val tasks = SqliteTaskRepository(h.db)
        val t = com.qianyan.model.task.Task(
            taskId = com.qianyan.model.TaskId("post-mig-task"),
            type = com.qianyan.model.task.TaskType.IMPORT,
            status = TaskStatus.PENDING,
            createdAt = kotlinx.datetime.Clock.System.now(),
            updatedAt = kotlinx.datetime.Clock.System.now(),
        )
        tasks.create(t)
        assertNotNull(tasks.findById(t.taskId))
        tasks.saveCheckpoint(
            com.qianyan.model.task.Checkpoint(
                checkpointId = com.qianyan.model.CheckpointId("post-mig-cp"),
                taskId = t.taskId,
                revision = 1,
                stage = "import",
                createdAt = kotlinx.datetime.Clock.System.now(),
            ),
        )
        assertEquals(1, tasks.findCheckpoints(t.taskId).size)

        // 6) 重复执行初始化幂等安全（不报"表已存在"）
        DatabaseInitializer.initializeDatabase(driver)
        DatabaseInitializer.initializeDatabase(driver)
        assertTrue(tableExists(driver, "Task"), "重复初始化后 Task 表仍存在")

        (driver).getConnection().close()
        java.nio.file.Files.deleteIfExists(file)
    }
}
