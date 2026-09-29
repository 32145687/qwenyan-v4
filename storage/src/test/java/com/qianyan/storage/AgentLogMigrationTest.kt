package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.core.Novel
import com.qianyan.model.log.Activity
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteActivityRepository
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteToolCallLogRepository
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I4 · v19 → v20 migration 测试（FD-9 additive only）。
 *
 * 手法：先经同一入口建到最新版本并写入真实数据，再把库**退化为 v19 形态**
 * （`DROP TABLE ToolCallLog / Activity` + `PRAGMA user_version = 19`），重新经 `QianyanDbFactory.open` 打开，验证：
 *   1) 仅新增 Activity / ToolCallLog 表，既有表 / 数据不变（经既有 Repository 读回）；
 *   2) `PRAGMA user_version` 与 `QianyanDb.Schema.version` 同步为最新版本；
 *   3) 被移除的记录**不会凭空产生**（不伪造 Activity / Tool Log）；
 *   4) 重复初始化幂等安全。
 */
class AgentLogMigrationTest {

    @Test
    fun `v19 database migrates to latest with agent log tables and keeps existing data`() {
        val dir = Files.createTempDirectory("qianyan-i4-migration")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"
        val now = Clock.System.now()
        val novelId = NovelId("n-i4-mig")
        val projectId = ProjectId("p-i4-mig")

        // 1) 建到最新版本，写入真实数据
        val first = QianyanDbFactory.open(url)
        SqliteNovelRepository(first.db).createOriginal(
            Novel(novelId = novelId, projectId = projectId, title = "迁移书 I4", createdAt = now, updatedAt = now),
        )
        SqliteAgentSessionRepository(first.db).save(
            AgentSession(
                sessionId = AgentSessionId("s-i4-mig"),
                projectId = projectId,
                novelId = novelId,
                status = AgentSessionStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        SqliteActivityRepository(first.db).save(
            Activity(
                activityId = ActivityId("a-i4-mig"),
                sessionId = AgentSessionId("s-i4-mig"),
                projectId = projectId,
                kind = "RESEARCH",
                status = AgentLogStatus.RUNNING,
                startedAt = now,
            ),
        )

        // 2) 退化为 v19 形态
        first.driver.execute(null, "DROP TABLE ToolCallLog", 0)
        first.driver.execute(null, "DROP TABLE Activity", 0)
        first.driver.execute(null, "PRAGMA user_version = 19", 0)
        first.driver.close()

        // 3) 经同一入口重开 → 自动迁移 v19 → 最新版本
        val second = QianyanDbFactory.open(url)
        assertEquals(20L, QianyanDb.Schema.version, "新增 19.sqm 后 schema 版本应为 20")
        assertEquals(20L, userVersion(second.driver), "旧 v19 库应自动迁移到最新版本")
        assertTrue(tableExists(second.driver, "Activity"), "迁移后应有 Activity 表")
        assertTrue(tableExists(second.driver, "ToolCallLog"), "迁移后应有 ToolCallLog 表")

        // 既有数据原样保留（经既有 Repository 读回）
        assertEquals("迁移书 I4", SqliteNovelRepository(second.db).getNovel(novelId)?.title, "迁移必须保留旧数据")
        assertEquals(
            AgentSessionStatus.ACTIVE,
            SqliteAgentSessionRepository(second.db).get(AgentSessionId("s-i4-mig"))?.status,
            "会话数据不受影响",
        )
        // 被移除的记录不凭空产生（不伪造）
        assertNull(SqliteActivityRepository(second.db).get(ActivityId("a-i4-mig")))
        assertEquals(0, SqliteToolCallLogRepository(second.db).listByProject(projectId).size)

        // 4) 重复初始化幂等
        DatabaseInitializer.initializeDatabase(second.driver)
        DatabaseInitializer.initializeDatabase(second.driver)
        assertEquals(20L, userVersion(second.driver))
        assertEquals("迁移书 I4", SqliteNovelRepository(second.db).getNovel(novelId)?.title)
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