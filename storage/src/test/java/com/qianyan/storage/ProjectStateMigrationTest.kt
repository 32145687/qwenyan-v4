package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.core.Novel
import com.qianyan.model.project.ProjectState
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteProjectStateRepository
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I1 · v17 → 最新版本 migration 测试（FD-9 additive only；新增迁移后最新版本随之为 19）。
 *
 * 手法：先经同一入口建到最新版本并写入真实数据，再把库**退化为 v17 形态**
 * （`DROP TABLE ProjectState` + `PRAGMA user_version = 17`），重新经 `QianyanDbFactory.open` 打开，
 * 验证：
 *   1) 仅新增 ProjectState 表，既有表 / 数据不变（经既有 Repository 读回，而非裸 SQL）；
 *   2) `PRAGMA user_version` 同步为 18；`QianyanDb.Schema.version` = 18；
 *   3) 被移除的运行态**不会凭空恢复**（不伪造数据）；
 *   4) 重复初始化幂等安全。
 *
 * 说明：早期版本的迁移骨架测试（v1 / v2 / … / v16）已由既有 `*MigrationTest` 覆盖；
 * 本测试只针对 I1 新增的 v17 分支（迁移到最新版本）。
 */
class ProjectStateMigrationTest {

    @Test
    fun `v17 database migrates to latest with project state and keeps existing data`() {
        val dir = Files.createTempDirectory("qianyan-i1-migration")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"
        val now = Clock.System.now()
        val novelId = NovelId("n-i1-mig")
        val projectId = ProjectId("p-i1-mig")

        // 1) 建到最新版本（全新库），写入真实数据
        val first = QianyanDbFactory.open(url)
        SqliteNovelRepository(first.db).createOriginal(
            Novel(novelId = novelId, projectId = projectId, title = "迁移书", createdAt = now, updatedAt = now),
        )
        SqliteProjectStateRepository(first.db).save(
            ProjectState(projectId = projectId, novelId = novelId, activeChapterId = ChapterId("c-1"), updatedAt = now),
        )

        // 2) 退化为 v17 形态
        first.driver.execute(null, "DROP TABLE ProjectState", 0)
        first.driver.execute(null, "PRAGMA user_version = 17", 0)
        first.driver.close()

        // 3) 经同一入口重开 → 自动迁移到最新版本
        val second = QianyanDbFactory.open(url)
        assertEquals(22L, QianyanDb.Schema.version, "新增 17.sqm~21.sqm 后 schema 版本应为 22")
        assertEquals(22L, userVersion(second.driver), "旧 v17 库应自动迁移到最新版本")
        assertTrue(tableExists(second.driver, "ProjectState"), "迁移后应有 ProjectState 表")

        // 既有数据原样保留（经既有 Repository 读回）
        assertEquals("迁移书", SqliteNovelRepository(second.db).getNovel(novelId)?.title, "迁移必须保留旧数据")
        // 被移除的运行态不凭空恢复（不伪造）
        assertNull(SqliteProjectStateRepository(second.db).get(projectId))

        // 4) 重复初始化幂等
        DatabaseInitializer.initializeDatabase(second.driver)
        DatabaseInitializer.initializeDatabase(second.driver)
        assertEquals(22L, userVersion(second.driver))
        assertEquals("迁移书", SqliteNovelRepository(second.db).getNovel(novelId)?.title)
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