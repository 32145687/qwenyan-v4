package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.log.Activity
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteActivityRepository
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I4 · Activity 仓储测试（FD-9 additive：一张表，只存"已发生活动"的事实记录）。
 *
 * 覆盖：save/get 往返（含时间字段）/ 不存在 → null / listBySession / listByProject /
 * Project 隔离 / 同 ID 覆盖（不产生第二行）/ 结构守卫（是事实记录，不是业务状态容器）。
 */
class ActivityRepositoryTest {

    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun activity(
        activityId: String = "a-1",
        sessionId: String = "s-1",
        projectId: String = "p-i4",
        kind: String = "CONTEXT_RESEARCH",
        status: AgentLogStatus = AgentLogStatus.RUNNING,
        completedAt: Instant? = null,
        summary: String? = null,
        error: String? = null,
    ) = Activity(
        activityId = ActivityId(activityId),
        sessionId = AgentSessionId(sessionId),
        projectId = ProjectId(projectId),
        kind = kind,
        status = status,
        startedAt = at,
        completedAt = completedAt,
        summary = summary,
        error = error,
    )

    /** 含 Novel + AgentSession（满足 FK）的内存库 + 两个仓储。 */
    private fun repos(): Triple<SqliteActivityRepository, SqliteAgentSessionRepository, JdbcSqliteDriver> {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-i4', 'p-i4', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-i4-b', 'p-i4-b', '另一本', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        val sessions = SqliteAgentSessionRepository(h.db)
        sessions.save(
            AgentSession(
                sessionId = AgentSessionId("s-1"),
                projectId = ProjectId("p-i4"),
                novelId = NovelId("n-i4"),
                status = AgentSessionStatus.ACTIVE,
                createdAt = at,
                updatedAt = at,
            ),
        )
        sessions.save(
            AgentSession(
                sessionId = AgentSessionId("s-b"),
                projectId = ProjectId("p-i4-b"),
                novelId = NovelId("n-i4-b"),
                status = AgentSessionStatus.ACTIVE,
                createdAt = at,
                updatedAt = at,
            ),
        )
        return Triple(SqliteActivityRepository(h.db), sessions, driver)
    }

    @Test
    fun `save then get round trips fields and times`() {
        val (repo, _, driver) = repos()
        val done = Instant.fromEpochMilliseconds(1_700_000_060_000)
        repo.save(activity(status = AgentLogStatus.COMPLETED, completedAt = done, summary = "已读取人物", error = null))

        val read = repo.get(ActivityId("a-1"))
        assertEquals(ActivityId("a-1"), read?.activityId)
        assertEquals(AgentSessionId("s-1"), read?.sessionId)
        assertEquals(ProjectId("p-i4"), read?.projectId)
        assertEquals("CONTEXT_RESEARCH", read?.kind)
        assertEquals(AgentLogStatus.COMPLETED, read?.status)
        assertEquals(at, read?.startedAt, "startedAt 必须原样保存")
        assertEquals(done, read?.completedAt, "completedAt 必须原样保存")
        assertEquals("已读取人物", read?.summary)
        driver.getConnection().close()
    }

    @Test
    fun `missing activity returns null`() {
        val (repo, _, driver) = repos()
        assertNull(repo.get(ActivityId("ghost")), "无记录 → null（不伪造活动）")
        driver.getConnection().close()
    }

    @Test
    fun `list by session and by project`() {
        val (repo, _, driver) = repos()
        repo.save(activity("a-1", kind = "RESEARCH"))
        repo.save(activity("a-2", kind = "WRITING"))
        repo.save(activity(sessionId = "s-b", activityId = "a-x", projectId = "p-i4-b"))

        assertEquals(
            listOf("a-1", "a-2"),
            repo.listBySession(AgentSessionId("s-1")).map { it.activityId.value },
            "会话内按时间/ID 升序（日志时序）",
        )
        assertEquals(2, repo.listByProject(ProjectId("p-i4")).size)
        driver.getConnection().close()
    }

    @Test
    fun `project isolation never leaks across projects`() {
        val (repo, _, driver) = repos()
        repo.save(activity("a-1"))
        repo.save(activity(sessionId = "s-b", activityId = "a-x", projectId = "p-i4-b"))

        assertTrue(repo.listByProject(ProjectId("p-i4")).none { it.activityId.value == "a-x" }, "跨 Project 不得泄漏")
        assertTrue(repo.listBySession(AgentSessionId("s-b")).none { it.activityId.value == "a-1" }, "跨 Session 不得泄漏")
        assertEquals(ProjectId("p-i4"), repo.get(ActivityId("a-1"))?.projectId)
        driver.getConnection().close()
    }

    @Test
    fun `save is idempotent per activity`() {
        val (repo, _, driver) = repos()
        repo.save(activity(status = AgentLogStatus.RUNNING))
        repo.save(activity(status = AgentLogStatus.FAILED, error = "boom"))

        assertEquals(1L, count(driver, "Activity"), "同 activityId 覆盖，不产生第二行")
        assertEquals(AgentLogStatus.FAILED, repo.get(ActivityId("a-1"))?.status)
        assertEquals("boom", repo.get(ActivityId("a-1"))?.error)
        driver.getConnection().close()
    }

    @Test
    fun `activity table stores fact record only`() {
        val (_, _, driver) = repos()
        val columns = columns(driver, "Activity")

        assertEquals(
            setOf(
                "activity_id", "session_id", "project_id", "kind", "status",
                "started_at", "completed_at", "summary", "error",
            ),
            columns.toSet(),
            "Activity 只持有活动事实记录",
        )
        listOf(
            "title", "content", "draft_content", "world_model", "workflow_state", "task_state",
            "project_state", "tool_definition", "skill_definition",
        ).forEach { assertTrue(it !in columns, "Activity 不应包含列 '$it'（是事实记录，不是业务状态容器）") }
        driver.getConnection().close()
    }

    private fun columns(driver: JdbcSqliteDriver, table: String): List<String> =
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                val out = mutableListOf<String>()
                while (cursor.next().value) out += cursor.getString(1) ?: ""
                QueryResult.Value(out.toList())
            },
            0,
        ).value

    private fun count(driver: JdbcSqliteDriver, table: String): Long =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}