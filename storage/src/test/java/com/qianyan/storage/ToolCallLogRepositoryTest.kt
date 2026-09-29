package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.log.Activity
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.log.ToolCallLog
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteActivityRepository
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import com.qianyan.storage.repository.SqliteToolCallLogRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I4 · ToolCallLog 仓储测试（FD-9 additive：一张表，只存"一次 Tool 调用事实"的记录）。
 *
 * 覆盖：save/get 往返（含状态字段）/ 不存在 → null / listByActivity / listBySession / listByProject /
 * Project 隔离 / 结构守卫（是 Tool Log，不是 Tool Registry）。
 */
class ToolCallLogRepositoryTest {

    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun call(
        toolCallId: String = "c-1",
        activityId: String = "a-1",
        sessionId: String = "s-1",
        projectId: String = "p-i4",
        toolName: String = "search_character",
        status: AgentLogStatus = AgentLogStatus.RUNNING,
        completedAt: Instant? = null,
        inputSummary: String? = null,
        outputSummary: String? = null,
        error: String? = null,
    ) = ToolCallLog(
        toolCallId = ToolCallId(toolCallId),
        activityId = ActivityId(activityId),
        sessionId = AgentSessionId(sessionId),
        projectId = ProjectId(projectId),
        toolName = ToolName(toolName),
        status = status,
        startedAt = at,
        completedAt = completedAt,
        inputSummary = inputSummary,
        outputSummary = outputSummary,
        error = error,
    )

    /** 含 Novel + AgentSession + Activity（满足 FK）的内存库 + 仓储。 */
    private fun repos(): Triple<SqliteToolCallLogRepository, SqliteActivityRepository, JdbcSqliteDriver> {
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
        val activities = SqliteActivityRepository(h.db)
        activities.save(
            Activity(
                activityId = ActivityId("a-1"),
                sessionId = AgentSessionId("s-1"),
                projectId = ProjectId("p-i4"),
                kind = "RESEARCH",
                status = AgentLogStatus.RUNNING,
                startedAt = at,
            ),
        )
        activities.save(
            Activity(
                activityId = ActivityId("a-b"),
                sessionId = AgentSessionId("s-b"),
                projectId = ProjectId("p-i4-b"),
                kind = "RESEARCH",
                status = AgentLogStatus.RUNNING,
                startedAt = at,
            ),
        )
        return Triple(SqliteToolCallLogRepository(h.db), activities, driver)
    }

    @Test
    fun `save then get round trips fields and status`() {
        val (repo, _, driver) = repos()
        val done = Instant.fromEpochMilliseconds(1_700_000_030_000)
        repo.save(
            call(
                status = AgentLogStatus.COMPLETED, completedAt = done,
                inputSummary = "name=苏清", outputSummary = "1 个结果", toolName = "search_character",
            ),
        )

        val read = repo.get(ToolCallId("c-1"))
        assertEquals(ToolCallId("c-1"), read?.toolCallId)
        assertEquals(ActivityId("a-1"), read?.activityId)
        assertEquals(AgentSessionId("s-1"), read?.sessionId)
        assertEquals(ProjectId("p-i4"), read?.projectId)
        assertEquals(ToolName("search_character"), read?.toolName)
        assertEquals(AgentLogStatus.COMPLETED, read?.status, "状态字段必须原样保存")
        assertEquals(at, read?.startedAt)
        assertEquals(done, read?.completedAt)
        assertEquals("name=苏清", read?.inputSummary)
        assertEquals("1 个结果", read?.outputSummary)
        driver.getConnection().close()
    }

    @Test
    fun `missing tool call returns null`() {
        val (repo, _, driver) = repos()
        assertNull(repo.get(ToolCallId("ghost")), "无记录 → null（不伪造调用）")
        driver.getConnection().close()
    }

    @Test
    fun `list by activity session and project`() {
        val (repo, _, driver) = repos()
        repo.save(call("c-1"))
        repo.save(call("c-2", toolName = "read_chapter"))
        repo.save(call("c-x", activityId = "a-b", sessionId = "s-b", projectId = "p-i4-b"))

        assertEquals(listOf("c-1", "c-2"), repo.listByActivity(ActivityId("a-1")).map { it.toolCallId.value })
        assertEquals(2, repo.listBySession(AgentSessionId("s-1")).size)
        assertEquals(2, repo.listByProject(ProjectId("p-i4")).size)
        driver.getConnection().close()
    }

    @Test
    fun `project isolation never leaks across projects`() {
        val (repo, _, driver) = repos()
        repo.save(call("c-1"))
        repo.save(call("c-x", activityId = "a-b", sessionId = "s-b", projectId = "p-i4-b"))

        assertTrue(repo.listByProject(ProjectId("p-i4")).none { it.toolCallId.value == "c-x" }, "跨 Project 不得泄漏")
        assertTrue(repo.listByActivity(ActivityId("a-1")).none { it.toolCallId.value == "c-x" }, "跨 Activity 不得泄漏")
        assertTrue(repo.listBySession(AgentSessionId("s-b")).none { it.toolCallId.value == "c-1" }, "跨 Session 不得泄漏")
        driver.getConnection().close()
    }

    @Test
    fun `save is idempotent per tool call`() {
        val (repo, _, driver) = repos()
        repo.save(call(status = AgentLogStatus.RUNNING))
        repo.save(call(status = AgentLogStatus.FAILED, error = "tool boom"))

        assertEquals(1L, count(driver, "ToolCallLog"), "同 toolCallId 覆盖，不产生第二行")
        assertEquals(AgentLogStatus.FAILED, repo.get(ToolCallId("c-1"))?.status)
        driver.getConnection().close()
    }

    @Test
    fun `tool call log stores call facts only`() {
        val (_, _, driver) = repos()
        val columns = columns(driver, "ToolCallLog")

        assertEquals(
            setOf(
                "tool_call_id", "activity_id", "session_id", "project_id", "tool_name", "status",
                "started_at", "completed_at", "input_summary", "output_summary", "error",
            ),
            columns.toSet(),
            "ToolCallLog 只持有调用事实记录",
        )
        listOf(
            "tool_implementation", "tool_executor", "permission_policy", "skill_id",
            "context_pack", "world_model", "canonical_content",
        ).forEach { assertTrue(it !in columns, "ToolCallLog 不应包含列 '$it'（是 Tool Log，不是 Tool Registry）") }
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