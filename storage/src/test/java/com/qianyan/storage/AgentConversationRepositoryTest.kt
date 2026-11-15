package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.ConversationMessage
import com.qianyan.model.session.ConversationRole
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteAgentConversationRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DSH-1（T-CONV-F）· AgentConversationMessage 仓储测试。
 *
 * 覆盖：追加 / 回读（时间升序，同毫秒按 message_id 稳定排序）/ 按作品隔离 /
 * 计数 / 同 id 覆盖不产生第二行 / 结构守卫（对话内容不进任何事实源表）。
 */
class AgentConversationRepositoryTest {

    private fun message(
        messageId: String = "m-1",
        projectId: String = "p-1",
        novelId: String = "n-1",
        role: ConversationRole = ConversationRole.USER,
        content: String = "你好",
        at: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000),
    ) = ConversationMessage(
        messageId = messageId,
        projectId = ProjectId(projectId),
        novelId = NovelId(novelId),
        role = role,
        content = content,
        createdAt = at,
    )

    /** 含 Novel（满足 FK）的内存库 + 仓储。 */
    private fun repo(): Pair<SqliteAgentConversationRepository, JdbcSqliteDriver> {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-1', 'p-1', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        return SqliteAgentConversationRepository(h.db) to driver
    }

    @Test
    fun `append then list round trips messages in stable order`() {
        val (repo, driver) = repo()
        val base = 1_700_000_000_000L
        // 同毫秒两条：排序必须由 message_id 兜底，保证回读顺序稳定
        repo.append(message("m-b", content = "第二条", at = Instant.fromEpochMilliseconds(base)))
        repo.append(message("m-a", content = "第一条", at = Instant.fromEpochMilliseconds(base)))
        repo.append(message("m-c", content = "第三条", at = Instant.fromEpochMilliseconds(base + 1)))

        val all = repo.listByNovel(NovelId("n-1"))
        assertEquals(3, all.size)
        assertEquals("m-a", all[0].messageId, "同毫秒按 message_id 稳定排序")
        assertEquals("m-b", all[1].messageId)
        assertEquals("m-c", all[2].messageId)

        assertEquals(ConversationRole.USER, all[0].role)
        assertEquals("第二条", all[1].content)
        assertEquals(ProjectId("p-1"), all[2].projectId)
        assertEquals(Instant.fromEpochMilliseconds(base), all[0].createdAt, "时间戳须原样回读")
    }

    @Test
    fun `messages are isolated per novel`() {
        val (repo, driver) = repo()
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-2', 'p-2', '书二', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        repo.append(message("m-1", projectId = "p-1", novelId = "n-1"))
        repo.append(message("m-2", projectId = "p-2", novelId = "n-2"))

        assertEquals(1, repo.listByNovel(NovelId("n-1")).size, "作品 A 只见自己的消息")
        assertEquals(1, repo.listByNovel(NovelId("n-2")).size, "作品 B 只见自己的消息")
        assertEquals(1L, repo.countByNovel(NovelId("n-1")))
        assertEquals(0L, repo.countByNovel(NovelId("n-ghost")), "不存在作品返回 0，不伪造")
    }

    @Test
    fun `same message id overwrites instead of duplicating`() {
        val (repo, _) = repo()
        repo.append(message("m-1", content = "旧内容"))
        repo.append(message("m-1", content = "新内容"))

        val all = repo.listByNovel(NovelId("n-1"))
        assertEquals(1, all.size, "同 id 覆盖不得产生第二行")
        assertEquals("新内容", all[0].content)
    }

    @Test
    fun `table holds conversation transcript only and never joins fact source tables`() {
        // 结构守卫：对话记忆 ≠ 事实源。表列必须只含会话转录字段，不得夹带小说事实列。
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val cols = h.driver.executeQuery(
            null,
            "PRAGMA table_info(AgentConversationMessage)",
            { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names.add(cursor.getString(1)!!)
                app.cash.sqldelight.db.QueryResult.Value(names)
            },
            0,
        ).value
        assertEquals(
            setOf("message_id", "project_id", "novel_id", "role", "content", "created_at"),
            cols.toSet(),
            "对话记忆表不得夹带事实源字段（Memory ≠ 事实源）",
        )
        h.driver.close()
    }
}
