package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I3 · AgentSession 仓储测试（FD-9 additive：一张表，只存会话身份 + 引用）。
 *
 * 覆盖：创建 / 读取 / 按 Project 查询 / 更新（同键覆盖）/ 不存在返回 null / 可恢复会话查询 /
 * 结构守卫（只含会话身份与引用列，不含小说事实或 Workflow·Task 状态列）。
 */
class AgentSessionRepositoryTest {

    private fun session(
        sessionId: String = "s-1",
        projectId: String = "p-i3",
        novelId: String = "n-i3",
        workflowId: String? = null,
        taskId: String? = null,
        status: AgentSessionStatus = AgentSessionStatus.CREATED,
        at: Instant = Clock.System.now(),
    ) = AgentSession(
        sessionId = AgentSessionId(sessionId),
        projectId = ProjectId(projectId),
        novelId = NovelId(novelId),
        workflowId = workflowId?.let { WorkflowId(it) },
        taskId = taskId?.let { TaskId(it) },
        status = status,
        createdAt = at,
        updatedAt = at,
    )

    /** 含 Novel（满足 FK）的内存库 + 仓储。 */
    private fun repo(): Pair<SqliteAgentSessionRepository, JdbcSqliteDriver> {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-i3', 'p-i3', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        return SqliteAgentSessionRepository(h.db) to driver
    }

    @Test
    fun `save then get round trips session identity and references`() {
        val (repo, driver) = repo()
        val at = Instant.fromEpochMilliseconds(1_700_000_000_000)
        repo.save(session(workflowId = "wf-1", taskId = "t-1", status = AgentSessionStatus.ACTIVE, at = at))

        val read = repo.get(AgentSessionId("s-1"))
        assertEquals(AgentSessionId("s-1"), read?.sessionId)
        assertEquals(ProjectId("p-i3"), read?.projectId)
        assertEquals(NovelId("n-i3"), read?.novelId)
        assertEquals(WorkflowId("wf-1"), read?.workflowId)
        assertEquals(TaskId("t-1"), read?.taskId)
        assertEquals(AgentSessionStatus.ACTIVE, read?.status)
        assertEquals(at, read?.createdAt)
        assertEquals(at, read?.updatedAt)
        driver.getConnection().close()
    }

    @Test
    fun `missing session returns null`() {
        val (repo, driver) = repo()
        assertNull(repo.get(AgentSessionId("ghost")), "无记录 → null（不伪造会话）")
        driver.getConnection().close()
    }

    @Test
    fun `list by project returns only that project sessions`() {
        val (repo, driver) = repo()
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-other', 'p-other', '另一本', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        repo.save(session("s-1"))
        repo.save(session("s-2"))
        repo.save(session(sessionId = "s-x", projectId = "p-other", novelId = "n-other"))

        assertEquals(listOf(AgentSessionId("s-2"), AgentSessionId("s-1")), repo.listByProject(ProjectId("p-i3")).map { it.sessionId })
        assertEquals(1, repo.listByProject(ProjectId("p-other")).size, "Project 隔离：只返回本项目会话")
        driver.getConnection().close()
    }

    @Test
    fun `resumable query returns latest unfinished session and ignores terminal ones`() {
        val (repo, driver) = repo()
        val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
        repo.save(session("s-old", status = AgentSessionStatus.PAUSED, at = t0))
        repo.save(session("s-new", status = AgentSessionStatus.ACTIVE, at = t0.plus(kotlin.time.Duration.parse("1s"))))
        assertEquals(AgentSessionId("s-new"), repo.findResumableByProject(ProjectId("p-i3"))?.sessionId)

        // 终态会话不算可恢复
        val (repo2, driver2) = repo()
        repo2.save(session("s-done", status = AgentSessionStatus.COMPLETED))
        assertNull(repo2.findResumableByProject(ProjectId("p-i3")), "COMPLETED 不是可恢复会话")
        driver.getConnection().close()
        driver2.getConnection().close()
    }

    @Test
    fun `save is idempotent per session`() {
        val (repo, driver) = repo()
        repo.save(session(status = AgentSessionStatus.CREATED))
        repo.save(session(status = AgentSessionStatus.ACTIVE))

        assertEquals(1L, count(driver, "AgentSession"), "同 sessionId 覆盖，不产生第二行")
        assertEquals(AgentSessionStatus.ACTIVE, repo.get(AgentSessionId("s-1"))?.status)
        driver.getConnection().close()
    }

    @Test
    fun `agent session table stores session identity and references only`() {
        val (_, driver) = repo()
        val columns = columns(driver, "AgentSession")

        assertEquals(
            setOf(
                "session_id", "project_id", "novel_id", "workflow_id", "task_id",
                "status", "created_at", "updated_at", "last_activity_at",
            ),
            columns.toSet(),
            "AgentSession 只持有会话身份与引用，不得复制小说事实或 Workflow/Task 状态列",
        )
        listOf("title", "genre", "synopsis", "content", "text", "progress", "checkpoint", "gate", "decision", "active_chapter_id")
            .forEach { assertTrue(it !in columns, "AgentSession 不应包含列 '$it'") }
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