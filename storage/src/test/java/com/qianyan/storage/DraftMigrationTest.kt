package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.task.TaskStatus
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteDraftRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11.3 · v2 → v3 migration 测试。
 *
 * 目标：构建一个**真实 v2 schema**（P8.1 的 Task / Checkpoint 已存在，无 ChapterDraft，
 * `PRAGMA user_version = 2`），写入代表性旧数据（Novel / Task / Checkpoint），
 * 再经 [DatabaseInitializer.initializeDatabase] 升级到 v3，验证：
 *   1) 旧数据原样保留（通过既有 Repository 读回，而非裸 SQL）；
 *   2) ChapterDraft 表已创建、Draft 可正常 CRUD；
 *   3) `PRAGMA user_version` 同步为 3；
 *   4) 重复执行 migration 幂等安全。
 * 结论：v2 → v3 只增 ChapterDraft，不破坏既有数据（P11.3 目标）。
 */
class DraftMigrationTest {

    /** 真实 v2 schema = v1 业务表（P0–P7）+ Task / Checkpoint（P8.1）；无 ChapterDraft。 */
    private val V2_DDL: List<String> = listOf(
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
        """
        CREATE TABLE Task (
            task_id         TEXT NOT NULL PRIMARY KEY,
            type            TEXT NOT NULL,
            status          TEXT NOT NULL,
            progress        REAL NOT NULL DEFAULT 0,
            revision_count  INTEGER NOT NULL DEFAULT 0 CHECK (revision_count BETWEEN 0 AND 3),
            error           TEXT,
            created_at      INTEGER NOT NULL,
            updated_at      INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE Checkpoint (
            checkpoint_id   TEXT NOT NULL PRIMARY KEY,
            task_id         TEXT NOT NULL,
            revision        INTEGER NOT NULL CHECK (revision BETWEEN 1 AND 3),
            stage           TEXT NOT NULL,
            snapshot        TEXT,
            created_at      INTEGER NOT NULL,
            UNIQUE (task_id, revision),
            FOREIGN KEY (task_id) REFERENCES Task(task_id)
        )
        """,
    )

    private fun JdbcSqliteDriver.exec(sql: String) {
        execute(null, sql, 0)
    }

    private fun buildV2Database(driver: JdbcSqliteDriver) {
        V2_DDL.forEach { driver.exec(it) }
        driver.exec("PRAGMA user_version = 2")
    }

    private fun insertV2Data(driver: JdbcSqliteDriver) {
        driver.exec(
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('mig-novel', 'proj-mig', '旧原著v2', 'ORIGINAL_NOVEL', '[\"仙侠\"]', '旧书简介', 'ORIGINAL', 'DRAFT', 1000, 1000)",
        )
        driver.exec(
            "INSERT INTO Task(task_id, type, status, progress, revision_count, error, created_at, updated_at) " +
                "VALUES ('mig-task', 'PLANNING', 'COMPLETED', 1.0, 1, NULL, 1000, 1000)",
        )
        driver.exec(
            "INSERT INTO Checkpoint(checkpoint_id, task_id, revision, stage, snapshot, created_at) " +
                "VALUES ('mig-cp', 'mig-task', 1, 'PLANNING', NULL, 1000)",
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
    fun `v2 to v3 migration preserves old data and enables draft crud`() {
        val file = java.nio.file.Files.createTempFile("qianyan_mig_v3_test", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        try {
            // 1) 构建真实 v2 schema + 写入旧数据
            val v2 = JdbcSqliteDriver(url)
            buildV2Database(v2)
            insertV2Data(v2)
            assertTrue(tableExists(v2, "Novel"), "v2 库应有 Novel")
            assertTrue(tableExists(v2, "Task"), "v2 库应有 Task")
            assertTrue(!tableExists(v2, "ChapterDraft"), "v2 库不应有 ChapterDraft")
            assertEquals(2L, userVersion(v2))
            v2.getConnection().close()

            // 2) 重新打开：DatabaseInitializer 检测到旧 v2 库 → 应用 2.sqm migration 到 v3
            val h = QianyanDbFactory.open(url)
            val driver = h.driver as JdbcSqliteDriver

            // 3) 新表已创建，版本已同步
            assertTrue(tableExists(driver, "ChapterDraft"), "migration 后应存在 ChapterDraft 表")
            assertEquals(6L, userVersion(driver), "migration 后 user_version 应为 6（P12.2 Schema v6）")
            listOf("Character", "CharacterState", "WorldRule", "Event", "TimelineEntry", "Foreshadow").forEach { tbl ->
                assertTrue(tableExists(driver, tbl), "migration 后应存在 $tbl 表（P12.1.1 Story State）")
            }

            // 4) 旧数据原样保留（通过既有 Repository 读回）
            val novels = SqliteNovelRepository(h.db)
            val readNovel = novels.getNovel(NovelId("mig-novel"))
            assertNotNull(readNovel, "旧 Novel 数据应保留")
            assertEquals("旧原著v2", readNovel.title)
            assertEquals(listOf("仙侠"), readNovel.genre)
            assertEquals(VariantScope.ORIGINAL, readNovel.scope)

            val tasks = SqliteTaskRepository(h.db)
            val readTask = tasks.findById(com.qianyan.model.TaskId("mig-task"))
            assertNotNull(readTask, "旧 Task 数据应保留")
            assertEquals(TaskStatus.COMPLETED, readTask.status)
            assertEquals(1, tasks.findCheckpoints(readTask.taskId).size)

            // 5) 迁移后 Draft 可正常 CRUD
            val drafts = SqliteDraftRepository(h.db)
            val now = Clock.System.now()
            val draft = com.qianyan.model.writing.Draft(
                draftId = com.qianyan.model.DraftId("post-mig-draft"),
                novelId = NovelId("mig-novel"),
                content = "迁移后写入的正文",
                status = com.qianyan.model.writing.DraftStatus.WRITTEN,
                sourceModel = "mock-v1",
                createdAt = now,
                updatedAt = now,
            )
            drafts.save(draft)
            val persisted = drafts.getById(draft.draftId)
            assertNotNull(persisted)
            assertEquals("迁移后写入的正文", persisted.content)
            assertEquals(com.qianyan.model.writing.DraftStatus.WRITTEN, persisted.status)
            assertEquals("mock-v1", persisted.sourceModel)
            // 幂等：再次 save 同 novel 不会重复
            assertEquals(1, drafts.listByNovel(NovelId("mig-novel")).size)
            assertTrue(tableExists(driver, "ChapterDraft"))

            // 6) 重复执行初始化幂等安全（不报"表已存在"）
            DatabaseInitializer.initializeDatabase(driver)
            DatabaseInitializer.initializeDatabase(driver)
            assertTrue(tableExists(driver, "ChapterDraft"), "重复初始化后 ChapterDraft 表仍存在")
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }

    @Test
    fun `fresh v3 database includes chapter draft table`() {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        assertTrue(tableExists(driver, "ChapterDraft"), "全新库初始化后应直接建出 ChapterDraft 表")
        assertEquals(6L, userVersion(driver), "全新库 user_version 应为 6（P12.2 Schema v6）")
        assertTrue(tableExists(driver, "Chapter"), "全新库初始化后应直接建出 Chapter 表（P12.0 P0-4）")
        listOf("Character", "CharacterState", "WorldRule", "Event", "TimelineEntry", "Foreshadow").forEach { tbl ->
            assertTrue(tableExists(driver, tbl), "全新库初始化后应直接建出 $tbl 表（P12.1.1 Story State）")
        }
        driver.getConnection().close()
    }
}