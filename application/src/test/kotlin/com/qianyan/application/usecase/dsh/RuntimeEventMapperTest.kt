package com.qianyan.application.usecase.dsh

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.ActivityRepository
import com.qianyan.storage.repository.SqliteActivityRepository
import com.qianyan.storage.repository.SqliteToolCallLogRepository
import com.qianyan.storage.repository.ToolCallLogRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DSH-08 · RuntimeEventMapper 测试（内存 SQLite，真实 Activity / ToolCallLog 表）。
 *
 * 覆盖：事件序列 → 行数与字段；重复映射幂等（确定性主键）；映射异常不抛；
 * 守卫：表数量与 user_version 不变（零新表零迁移）。
 */
class RuntimeEventMapperTest {

    private val sessionId = AgentSessionId("dsh-test-session")
    private val projectId = ProjectId("p-dsh08")
    private val runId = "run-1"
    private val at = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun db(): QianyanDbHandle {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        h.driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('n-dsh08', '${projectId.value}', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        h.driver.execute(
            null,
            "INSERT INTO AgentSession(session_id, project_id, novel_id, status, created_at, updated_at) " +
                "VALUES ('${sessionId.value}', '${projectId.value}', 'n-dsh08', 'ACTIVE', 1, 1)",
            0,
        )
        return h
    }

    private fun mapper(h: QianyanDbHandle): RuntimeEventMapper =
        RuntimeEventMapper(
            activities = SqliteActivityRepository(h.db),
            toolCallLogs = SqliteToolCallLogRepository(h.db),
        )

    private fun tableCount(h: QianyanDbHandle): Int =
        h.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table'",
            { cursor -> QueryResult.Value(cursor.getLong(0)?.toInt() ?: 0) },
            0,
        ).value

    private fun activityById(h: QianyanDbHandle, id: String) =
        SqliteActivityRepository(h.db).get(ActivityId(id))

    private fun toolLogById(h: QianyanDbHandle, id: String) =
        SqliteToolCallLogRepository(h.db).get(com.qianyan.model.ToolCallId(id))

    @Test
    fun `event sequence maps to activity and tool call rows with correct fields`() {
        val h = db()
        val mapper = mapper(h)

        mapper.map(sessionId, projectId, runId, 0, RuntimeEvent(RuntimeEventKind.PHASE_START, "session", "运行开始"), at)
        mapper.map(
            sessionId, projectId, runId, 1,
            RuntimeEvent(RuntimeEventKind.TOOL_CALL, "tool.list_chapters", "第 1 轮工具调用", resultCount = 1),
            at,
        )
        mapper.map(
            sessionId, projectId, runId, 2,
            RuntimeEvent(RuntimeEventKind.TOOL_RESULT, "tool.list_chapters", "第 1 轮工具返回", resultCount = 3, ok = true),
            at,
        )
        mapper.map(
            sessionId, projectId, runId, 3,
            RuntimeEvent(RuntimeEventKind.CANDIDATE_READY, "candidate", "候选已就绪", resultCount = 1, ok = true),
            at,
        )
        mapper.map(
            sessionId, projectId, runId, 4,
            RuntimeEvent(RuntimeEventKind.FAILED, "session", "模型超时", ok = false),
            at,
        )

        // 非工具事件 → Activity（kind = 事件名；ok → 状态；终态带 completed_at）
        val phaseStart = activityById(h, "dsh-$runId-0")!!
        assertEquals("PHASE_START", phaseStart.kind)
        assertEquals(AgentLogStatus.RUNNING, phaseStart.status, "无结果 → RUNNING")
        assertNullCompleted(phaseStart.completedAt)

        val ready = activityById(h, "dsh-$runId-3")!!
        assertEquals(AgentLogStatus.COMPLETED, ready.status)
        assertEquals(at, ready.completedAt)

        val failed = activityById(h, "dsh-$runId-4")!!
        assertEquals(AgentLogStatus.FAILED, failed.status)
        assertEquals("模型超时", failed.error)

        // 工具事件 → Activity 锚 + ToolCallLog
        val callAnchor = activityById(h, "dsh-$runId-1")!!
        assertEquals("TOOL_CALL", callAnchor.kind)
        val call = toolLogById(h, "dsh-$runId-1")!!
        assertEquals("tool.list_chapters", call.toolName.value)
        assertEquals("第 1 轮工具调用", call.inputSummary)
        assertEquals(AgentLogStatus.RUNNING, call.status, "CALL 无结果 → RUNNING")

        val result = toolLogById(h, "dsh-$runId-2")!!
        assertEquals("第 1 轮工具返回（3 条）", result.outputSummary, "结果量并入输出摘要")
        assertEquals(AgentLogStatus.COMPLETED, result.status)
        h.driver.close()
    }

    @Test
    fun `duplicate mapping with same runId and seq is idempotent`() {
        val h = db()
        val mapper = mapper(h)
        val event = RuntimeEvent(RuntimeEventKind.PHASE_START, "session", "运行开始")

        mapper.map(sessionId, projectId, runId, 0, event, at)
        mapper.map(sessionId, projectId, runId, 0, event, at)
        mapper.map(sessionId, projectId, runId, 0, event, at)

        assertEquals(1, SqliteActivityRepository(h.db).listBySession(sessionId).size, "同键重复映射不产生第二行")
        h.driver.close()
    }

    @Test
    fun `mapping failure never throws to the caller`() {
        val h = db()
        // 抛异常的假仓储：save 必失败 → runCatching 吞掉，不向上抛
        val throwing = object : ActivityRepository {
            override fun save(activity: com.qianyan.model.log.Activity) = throw IllegalStateException("boom")
            override fun get(activityId: ActivityId) = null
            override fun listBySession(sessionId: AgentSessionId): List<com.qianyan.model.log.Activity> = emptyList()
            override fun listByProject(projectId: ProjectId): List<com.qianyan.model.log.Activity> = emptyList()
        }
        val throwingTools = object : ToolCallLogRepository {
            override fun save(call: com.qianyan.model.log.ToolCallLog) = throw IllegalStateException("boom")
            override fun get(toolCallId: com.qianyan.model.ToolCallId) = null
            override fun listByActivity(activityId: ActivityId): List<com.qianyan.model.log.ToolCallLog> = emptyList()
            override fun listBySession(sessionId: AgentSessionId): List<com.qianyan.model.log.ToolCallLog> = emptyList()
            override fun listByProject(projectId: ProjectId): List<com.qianyan.model.log.ToolCallLog> = emptyList()
        }
        val mapper = RuntimeEventMapper(throwing, throwingTools)

        // 不抛（映射失败不阻断运行）
        mapper.map(sessionId, projectId, runId, 0, RuntimeEvent(RuntimeEventKind.PHASE_START, "session", "运行开始"), at)
        mapper.map(
            sessionId, projectId, runId, 1,
            RuntimeEvent(RuntimeEventKind.TOOL_CALL, "tool.x", "调用", resultCount = 1),
            at,
        )
        h.driver.close()
    }

    @Test
    fun `guard keeps table count and user version unchanged`() {
        val h = db()
        val beforeTables = tableCount(h)
        val mapper = mapper(h)
        mapper.map(
            sessionId, projectId, runId, 0,
            RuntimeEvent(RuntimeEventKind.TOOL_CALL, "tool.x", "调用", resultCount = 1),
            at,
        )
        assertEquals(beforeTables, tableCount(h), "零新表")
        h.driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor -> QueryResult.Value(cursor.getLong(0) ?: 0L) },
            0,
        ).value.also { version -> assertEquals(29L, version, "user_version 不变（零迁移）") }
        h.driver.close()
    }

    private fun assertNullCompleted(completedAt: Instant?) {
        assertTrue(completedAt == null, "进行中事件 completed_at 必须为 null")
    }
}
