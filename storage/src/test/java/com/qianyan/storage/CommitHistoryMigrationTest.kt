package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.core.Novel
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteCommitHistoryRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I10 · v20 → v21 migration 测试（FD-9 additive only）。
 *
 * 手法：先经同一入口建到最新版本并写入真实数据，再把库**退化为 v20 形态**
 * （`DROP TABLE CommitHistory` + `PRAGMA user_version = 20`），重新经 `QianyanDbFactory.open` 打开，验证：
 *   1) 仅新增 CommitHistory 表，既有表 / 数据不变（经既有 Repository 读回）；
 *   2) `PRAGMA user_version` 与 `QianyanDb.Schema.version` 同步为最新版本；
 *   3) 历史不会被伪造（迁移后 CommitHistory 为空）；
 *   4) 重复初始化幂等安全。
 */
class CommitHistoryMigrationTest {

    @Test
    fun `v20 database migrates to latest with commit history table and keeps existing data`() {
        val dir = Files.createTempDirectory("qianyan-i10-migration")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"
        val now = Clock.System.now()
        val novelId = NovelId("n-i10-mig")
        val projectId = ProjectId("p-i10-mig")

        // 1) 建到最新版本，写入真实数据
        val first = QianyanDbFactory.open(url)
        SqliteNovelRepository(first.db).createOriginal(
            Novel(novelId = novelId, projectId = projectId, title = "迁移书 I10", createdAt = now, updatedAt = now),
        )

        // 2) 退化为 v20 形态
        first.driver.execute(null, "DROP INDEX idx_commit_history_project", 0)
        first.driver.execute(null, "DROP INDEX idx_commit_history_artifact", 0)
        first.driver.execute(null, "DROP TABLE CommitHistory", 0)
        first.driver.execute(null, "PRAGMA user_version = 20", 0)
        first.driver.close()

        // 3) 经同一入口重开 → 自动迁移 v20 → 最新版本
        val second = QianyanDbFactory.open(url)
        assertEquals(22L, QianyanDb.Schema.version, "新增 20.sqm~21.sqm 后 schema 版本应为 22")
        assertEquals(22L, userVersion(second.driver), "旧 v20 库应自动迁移到最新版本")
        assertTrue(tableExists(second.driver, "CommitHistory"), "迁移后应有 CommitHistory 表")

        // 既有数据原样保留（经既有 Repository 读回）
        assertEquals("迁移书 I10", SqliteNovelRepository(second.db).getNovel(novelId)?.title, "迁移必须保留旧数据")
        // 历史不会被伪造
        assertEquals(0, SqliteCommitHistoryRepository(second.db).listByProject(projectId).size)

        // 4) 重复初始化幂等
        DatabaseInitializer.initializeDatabase(second.driver)
        DatabaseInitializer.initializeDatabase(second.driver)
        assertEquals(22L, userVersion(second.driver))
        assertEquals("迁移书 I10", SqliteNovelRepository(second.db).getNovel(novelId)?.title)
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