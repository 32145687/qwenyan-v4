package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.core.Novel
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteTaskRepository
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I13 · v21 → v22 migration 测试（additive only）。
 *
 * 手法：先经同一入口建到最新版本并写入真实数据，再把库**退化为 v21 形态**
 * （`DROP TABLE TaskQueueItem` + `PRAGMA user_version = 21`），重新经 `QianyanDbFactory.open` 打开，验证：
 *   1) 仅新增 TaskQueueItem 表，既有表 / 数据不变（经既有 Repository 读回）；
 *   2) `PRAGMA user_version` 与 `QianyanDb.Schema.version` 同步为最新版本；
 *   3) 队列不会被伪造（迁移后 TaskQueueItem 为空）；
 *   4) 重复初始化幂等安全。
 */
class TaskQueueMigrationTest {

    @Test
    fun `v21 database migrates to latest with task queue table and keeps existing data`() {
        val dir = Files.createTempDirectory("qianyan-i13-migration")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"
        val now = Clock.System.now()
        val novelId = NovelId("n-i13-mig")
        val projectId = ProjectId("p-i13-mig")
        val taskId = TaskId("t-i13-mig")

        // 1) 建到最新版本，写入真实数据
        val first = QianyanDbFactory.open(url)
        SqliteNovelRepository(first.db).createOriginal(
            Novel(novelId = novelId, projectId = projectId, title = "迁移书 I13", createdAt = now, updatedAt = now),
        )
        SqliteTaskRepository(first.db).create(
            Task(taskId = taskId, type = TaskType.BACKGROUND, status = TaskStatus.PENDING, createdAt = now, updatedAt = now),
        )

        // 2) 退化为 v21 形态
        first.driver.execute(null, "DROP INDEX idx_task_queue_claim", 0)
        first.driver.execute(null, "DROP TABLE TaskQueueItem", 0)
        // v21 已含 CommitHistory（v20 引入）但**无 gate_id**；回退为 v21 形态（摘除 gate_id，23.sqm 会重加）
        first.driver.execute(null, "CREATE TABLE CommitHistory_v21_sim (" +
            "history_id TEXT NOT NULL PRIMARY KEY, commit_id TEXT NOT NULL, project_id TEXT NOT NULL, operation TEXT NOT NULL, " +
            "artifact_id TEXT, novel_id TEXT NOT NULL, chapter_id TEXT NOT NULL, variant_id TEXT, previous_draft_id TEXT, " +
            "resulting_draft_id TEXT NOT NULL, previous_content TEXT NOT NULL, resulting_content TEXT NOT NULL, " +
            "summary TEXT NOT NULL, reverted_history_id TEXT, created_at INTEGER NOT NULL)", 0)
        first.driver.execute(null, "INSERT INTO CommitHistory_v21_sim " +
            "(history_id, commit_id, project_id, operation, artifact_id, novel_id, chapter_id, variant_id, previous_draft_id, " +
            "resulting_draft_id, previous_content, resulting_content, summary, reverted_history_id, created_at) " +
            "SELECT history_id, commit_id, project_id, operation, artifact_id, novel_id, chapter_id, variant_id, previous_draft_id, " +
            "resulting_draft_id, previous_content, resulting_content, summary, reverted_history_id, created_at FROM CommitHistory", 0)
        first.driver.execute(null, "DROP TABLE CommitHistory", 0)
        first.driver.execute(null, "ALTER TABLE CommitHistory_v21_sim RENAME TO CommitHistory", 0)
        first.driver.execute(null, "CREATE UNIQUE INDEX idx_commit_history_artifact ON CommitHistory(artifact_id)", 0)
        first.driver.execute(null, "CREATE INDEX idx_commit_history_project ON CommitHistory(project_id, created_at)", 0)
        // P1-02j.2：降级到 v24 之前必须真实摘除 round_no（24.sqm 的 ADD COLUMN 不幂等）
        first.driver.execute(null, "ALTER TABLE WorkflowStep DROP COLUMN round_no", 0)
        // P2 v27（26.sqm）：Character/CharacterState/Event 新列不幂等，降级前必须真实摘除
        first.driver.dropWorldModelV27Columns()
        first.driver.execute(null, "PRAGMA user_version = 21", 0)
        first.driver.close()

        // 3) 经同一入口重开 → 自动迁移 v21 → 最新版本
        val second = QianyanDbFactory.open(url)
        assertEquals(27L, QianyanDb.Schema.version, "新增 21.sqm~24.sqm 后 schema 版本应为 27")
        assertEquals(27L, userVersion(second.driver), "旧 v21 库应自动迁移到最新版本")
        assertTrue(tableExists(second.driver, "TaskQueueItem"), "迁移后应有 TaskQueueItem 表")

        // 既有数据原样保留（经既有 Repository 读回）
        assertEquals("迁移书 I13", SqliteNovelRepository(second.db).getNovel(novelId)?.title, "迁移必须保留旧数据")
        assertEquals(TaskType.BACKGROUND, SqliteTaskRepository(second.db).findById(taskId)?.type, "既有 Task 应保留")

        // 重复初始化幂等
        DatabaseInitializer.initializeDatabase(second.driver)
        DatabaseInitializer.initializeDatabase(second.driver)
        assertEquals(27L, userVersion(second.driver))
        assertEquals("迁移书 I13", SqliteNovelRepository(second.db).getNovel(novelId)?.title)
        second.driver.close()
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value
}