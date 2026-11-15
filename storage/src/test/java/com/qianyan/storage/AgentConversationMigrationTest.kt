package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.core.Novel
import com.qianyan.model.session.ConversationMessage
import com.qianyan.model.session.ConversationRole
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteAgentConversationRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import kotlinx.datetime.Clock
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DSH-1 · v28 → v29 migration 测试（FD-9 additive only；新增 AgentConversationMessage 对话记忆表）。
 *
 * 手法（与 AgentSessionMigrationTest 同口径）：先建到最新版本并写入真实数据，再把库**退化为 v28 形态**
 * （`DROP TABLE AgentConversationMessage` + `PRAGMA user_version = 28`），重新经 `QianyanDbFactory.open` 打开，验证：
 *   1) 仅新增 AgentConversationMessage 表，既有表 / 数据不变（经既有 Repository 读回）；
 *   2) `PRAGMA user_version` 同步为 29；`QianyanDb.Schema.version` 同步；
 *   3) 被移除的消息**不会凭空恢复**（不伪造数据）；
 *   4) 重复初始化幂等安全。
 */
class AgentConversationMigrationTest {

    @Test
    fun `v28 database migrates to v29 and keeps existing data`() {
        val dir = Files.createTempDirectory("qianyan-dsh1-migration")
        val url = "jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}"
        val now = Clock.System.now()
        val novelId = NovelId("n-dsh1-mig")
        val projectId = ProjectId("p-dsh1-mig")

        // 1) 建到最新版本（此时已含 v29 表），写入真实数据
        val first = QianyanDbFactory.open(url)
        SqliteNovelRepository(first.db).createOriginal(
            Novel(novelId = novelId, projectId = projectId, title = "迁移书 DSH1", createdAt = now, updatedAt = now),
        )
        val repo = SqliteAgentConversationRepository(first.db)
        repo.append(
            ConversationMessage(
                messageId = "m-1",
                projectId = projectId,
                novelId = novelId,
                role = ConversationRole.USER,
                content = "迁移前消息",
                createdAt = now,
            ),
        )

        // 2) 退化为 v28 形态（本表为最新增量，降级仅需摘除本表）
        first.driver.execute(null, "DROP TABLE AgentConversationMessage", 0)
        first.driver.execute(null, "PRAGMA user_version = 28", 0)
        first.driver.close()

        // 3) 重开 → 自动迁移 v28 → v29
        val second = QianyanDbFactory.open(url)
        assertEquals(29L, QianyanDb.Schema.version, "新增 28.sqm 后 schema 版本应为 29")
        assertEquals(29L, userVersion(second.driver), "旧 v28 库应自动迁移到最新版本")
        assertTrue(tableExists(second.driver, "AgentConversationMessage"), "迁移后应有 AgentConversationMessage 表")

        // 迁移不伪造数据：被摘除的消息不会凭空恢复
        assertEquals(0L, SqliteAgentConversationRepository(second.db).countByNovel(novelId))

        // 4) 迁移后可正常写入（幂等安全：重复初始化不炸、不重复建表）
        SqliteAgentConversationRepository(second.db).append(
            ConversationMessage(
                messageId = "m-2",
                projectId = projectId,
                novelId = novelId,
                role = ConversationRole.ASSISTANT,
                content = "迁移后写入",
                createdAt = Clock.System.now(),
            ),
        )
        DatabaseInitializer.initializeDatabase(second.driver)
        assertEquals(1L, SqliteAgentConversationRepository(second.db).countByNovel(novelId))
        assertEquals("迁移书 DSH1", SqliteNovelRepository(second.db).getNovel(novelId)?.title, "既有数据不变")
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
