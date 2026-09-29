package com.qianyan.application.usecase.log

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.log.AgentLogLifecycleRules
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I4 · Activity / Tool Call Log 应用层测试。
 *
 * 覆盖（对应 I4 要求）：
 *  - Activity 生命周期：start / complete / fail / cancel、非法转换拒绝、终态行为；
 *  - Tool Call Log：start / succeed / fail / cancel、状态保存；
 *  - Session 归属与 Project 隔离；
 *  - **观察记录，不是控制器**：Activity 不创建/修改 Session / Workflow / Task；ToolLog 不创建 Activity / Session；
 *  - 引用关系：Activity.sessionId、ToolCallLog.activityId / sessionId / projectId 正确；
 *  - 结构守卫（§十九）与确定性（§二十一）。
 */
class AgentLogUseCasesTest {

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fixture(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    // ---------- Activity 生命周期 ----------

    @Test
    fun `start activity binds session identity and defaults to running`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))

        val activity = f.app.activities.start(session.sessionId, "CONTEXT_RESEARCH")

        assertEquals(session.sessionId, activity.sessionId, "Activity 归属既有 Session（引用）")
        assertEquals(session.projectId, activity.projectId, "projectId 从 Session 派生")
        assertEquals(AgentLogStatus.RUNNING, activity.status)
        assertNull(activity.completedAt)
        assertEquals(activity, f.app.activities.get(activity.activityId), "读取应回读同一条记录")
        f.close()
    }

    @Test
    fun `complete fail and cancel are recorded with terminal facts`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))

        val done = f.app.activities.start(session.sessionId, "WRITING").let {
            f.app.activities.complete(it.activityId, summary = "写了 3 段")
        }
        assertEquals(AgentLogStatus.COMPLETED, done.status)
        assertNotNull(done.completedAt, "终态必须记录 completedAt")
        assertEquals("写了 3 段", done.summary)

        val failed = f.app.activities.fail(f.app.activities.start(session.sessionId, "WRITING").activityId, error = "provider down")
        assertEquals(AgentLogStatus.FAILED, failed.status)
        assertEquals("provider down", failed.error)

        val cancelled = f.app.activities.cancel(f.app.activities.start(session.sessionId, "WRITING").activityId)
        assertEquals(AgentLogStatus.CANCELLED, cancelled.status)
        assertEquals(3, f.app.activities.listBySession(session.sessionId).size)
        f.close()
    }

    @Test
    fun `illegal transitions are rejected and terminal state is final`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))
        val activity = f.app.activities.start(session.sessionId, "RESEARCH")

        // 终态后不可**跨状态**再转换（同状态重复为幂等，与 AgentSessionLifecycleRules 一致）
        val cancelled = f.app.activities.cancel(activity.activityId)
        assertEquals(AgentLogStatus.CANCELLED, cancelled.status)
        listOf(
            { f.app.activities.complete(activity.activityId) },
            { f.app.activities.fail(activity.activityId, error = "x") },
        ).forEach { op ->
            val ex = assertFailsWith<ApplicationException> { op() }
            assertTrue(ex.error is ApplicationError.InvalidOperation, "终态不可跨状态再转换")
        }
        // 同状态重复 → 幂等，不抛异常、不改记录
        assertEquals(AgentLogStatus.CANCELLED, f.app.activities.cancel(activity.activityId).status)
        assertEquals(AgentLogStatus.CANCELLED, f.app.activities.get(activity.activityId).status, "非法转换不得落库")
        f.close()
    }

    // ---------- Tool Call Log ----------

    @Test
    fun `tool call log records facts under its activity`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))
        val activity = f.app.activities.start(session.sessionId, "CONTEXT_RESEARCH")

        val call = f.app.toolCallLogs.start(activity.activityId, ToolName("search_character"), inputSummary = "name=苏清")
        assertEquals(activity.activityId, call.activityId)
        assertEquals(activity.sessionId, call.sessionId, "sessionId 从 Activity 派生")
        assertEquals(activity.projectId, call.projectId)
        assertEquals(ToolName("search_character"), call.toolName)
        assertEquals(AgentLogStatus.RUNNING, call.status)

        val succeeded = f.app.toolCallLogs.succeed(call.toolCallId, outputSummary = "1 个结果")
        assertEquals(AgentLogStatus.COMPLETED, succeeded.status)
        assertEquals("1 个结果", succeeded.outputSummary)
        assertNotNull(succeeded.completedAt)
        assertEquals(succeeded, f.app.toolCallLogs.get(call.toolCallId))
        assertEquals(1, f.app.toolCallLogs.listByActivity(activity.activityId).size)
        assertEquals(1, f.app.toolCallLogs.listBySession(session.sessionId).size)
        f.close()
    }

    @Test
    fun `tool call lifecycle transitions and terminal final`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))
        val activity = f.app.activities.start(session.sessionId, "WRITING")

        val failed = f.app.toolCallLogs.start(activity.activityId, ToolName("read_chapter"))
            .let { f.app.toolCallLogs.fail(it.toolCallId, error = "tool boom") }
        assertEquals(AgentLogStatus.FAILED, failed.status)
        assertEquals("tool boom", failed.error)

        val cancelled = f.app.toolCallLogs.cancel(
            f.app.toolCallLogs.start(activity.activityId, ToolName("read_chapter")).toolCallId,
        )
        assertEquals(AgentLogStatus.CANCELLED, cancelled.status)

        val ex = assertFailsWith<ApplicationException> { f.app.toolCallLogs.succeed(failed.toolCallId) }
        assertTrue(ex.error is ApplicationError.InvalidOperation, "终态不可再转换")
        f.close()
    }

    @Test
    fun `tool log does not create activity or session`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))
        val activity = f.app.activities.start(session.sessionId, "RESEARCH")
        val sessionsBefore = count(f.handle, "AgentSession")
        val activitiesBefore = count(f.handle, "Activity")

        // 悬挂引用必须拒绝（不伪造关联）
        val ex = assertFailsWith<ApplicationException> {
            f.app.toolCallLogs.start(ActivityId("a-ghost"), ToolName("search_character"))
        }
        assertTrue(ex.error is ApplicationError.EntityNotFound)

        f.app.toolCallLogs.start(activity.activityId, ToolName("search_character"))
            .let { f.app.toolCallLogs.succeed(it.toolCallId) }
        assertEquals(activitiesBefore, count(f.handle, "Activity"), "ToolLog 不得创建 Activity")
        assertEquals(sessionsBefore, count(f.handle, "AgentSession"), "ToolLog 不得创建 Session")
        f.close()
    }

    // ---------- 观察记录，不是控制器 ----------

    @Test
    fun `log records never create or modify sessions workflows and tasks`() {
        val f = fixture()
        val session = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "I4 书"))
        val sessionsBefore = count(f.handle, "AgentSession")
        val workflowsBefore = count(f.handle, "Workflow")
        val tasksBefore = count(f.handle, "Task")
        val sessionStatusBefore = f.app.agentSessions.sessionOf(session.sessionId).status

        val activity = f.app.activities.start(session.sessionId, "WRITING")
        f.app.toolCallLogs.start(activity.activityId, ToolName("generate_draft"))
            .let { f.app.toolCallLogs.succeed(it.toolCallId) }
        f.app.activities.complete(activity.activityId)

        assertEquals(sessionsBefore, count(f.handle, "AgentSession"), "Activity 不得创建 Session")
        assertEquals(workflowsBefore, count(f.handle, "Workflow"), "Activity 不得创建 Workflow")
        assertEquals(tasksBefore, count(f.handle, "Task"), "Activity 不得创建 Task")
        assertEquals(
            sessionStatusBefore,
            f.app.agentSessions.sessionOf(session.sessionId).status,
            "日志记录不得改写 AgentSession 状态",
        )
        f.close()
    }

    @Test
    fun `activity is rejected for unknown session`() {
        val f = fixture()
        val ex = assertFailsWith<ApplicationException> {
            f.app.activities.start(AgentSessionId("s-ghost"), "RESEARCH")
        }
        assertTrue(ex.error is ApplicationError.EntityNotFound, "Activity 必须挂在既有 Session 上")
        f.close()
    }

    // ---------- Project 隔离 ----------

    @Test
    fun `project isolation holds for activities and tool calls`() {
        val f = fixture()
        val a = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "书A"))
        val b = f.app.agentSessions.startSession(f.app.novels.createOriginal(title = "书B"))
        val actA = f.app.activities.start(a.sessionId, "RESEARCH")
        val actB = f.app.activities.start(b.sessionId, "WRITING")
        val callA = f.app.toolCallLogs.start(actA.activityId, ToolName("search_character"))
        f.app.toolCallLogs.start(actB.activityId, ToolName("read_chapter"))

        val idsA = f.app.activities.listByProject(a.projectId).map { it.activityId }
        assertTrue(idsA.containsAll(listOf(actA.activityId)) && actB.activityId !in idsA, "Project A 不得看到 Project B 的活动")
        val callsA = f.app.toolCallLogs.listByProject(a.projectId).map { it.toolCallId }
        assertTrue(callA.toolCallId in callsA && callsA.size == 1, "Project A 不得看到 Project B 的调用")
        assertEquals(1, f.app.activities.listBySession(b.sessionId).size, "会话维度也不跨会话")
        f.close()
    }

    // ---------- 确定性（§二十一） ----------

    @Test
    fun `lifecycle rules are deterministic and reuse existing status vocabulary`() {
        // 状态词汇是 TaskStatus 既有词汇的子集（RUNNING/COMPLETED/FAILED/CANCELLED），不造平行体系
        assertTrue(AgentLogStatus.entries.all { it.name in setOf("RUNNING", "COMPLETED", "FAILED", "CANCELLED") })
        assertTrue(AgentLogLifecycleRules.canTransition(AgentLogStatus.RUNNING, AgentLogStatus.COMPLETED))
        assertTrue(AgentLogLifecycleRules.canTransition(AgentLogStatus.RUNNING, AgentLogStatus.FAILED))
        assertTrue(AgentLogLifecycleRules.canTransition(AgentLogStatus.RUNNING, AgentLogStatus.CANCELLED))
        assertTrue(AgentLogLifecycleRules.canTransition(AgentLogStatus.RUNNING, AgentLogStatus.RUNNING), "同状态幂等")
        assertTrue(!AgentLogLifecycleRules.canTransition(AgentLogStatus.COMPLETED, AgentLogStatus.RUNNING))
        AgentLogStatus.entries.filter { it.isTerminal }.forEach {
            assertEquals(emptySet(), AgentLogLifecycleRules.allowedTargets(it), "$it 是终态")
        }
        // 同输入同输出（纯确定性，无 LLM / 无随机 / 无时间判断）
        AgentLogStatus.entries.forEach { from ->
            AgentLogStatus.entries.forEach { to ->
                repeat(3) { assertEquals(AgentLogLifecycleRules.canTransition(from, to), AgentLogLifecycleRules.canTransition(from, to)) }
            }
        }
    }

    // ---------- 结构守卫（§十九：Log 是事实记录，不是业务状态容器） ----------

    @Test
    fun `log packages do not absorb other domain responsibilities`() {
        val dirs = listOf(
            File("src/main/kotlin/com/qianyan/application/usecase/log"),
            File("../model/src/main/kotlin/com/qianyan/model/log").canonicalFile,
        )
        val sources = dirs.filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(sources.isNotEmpty(), "找不到日志源码目录")

        // 只扫描代码行（去掉注释行），避免注释中的说明被误判
        val code = sources.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
        listOf(
            // 业务状态容器 / Tool Registry / 权限 / 上下文（属后续阶段或既有模块）
            "worldModel", "WorldModel", "workflowState", "taskState", "projectState",
            "toolDefinition", "ToolDefinition", "toolExecutor", "ToolExecutor", "ToolRegistry",
            "permissionPolicy", "ActionPolicy", "skillId", "Skill", "contextPack", "ContextPack",
            "canonicalContent", "draftContent", "LLMGateway", "WorkflowOrchestrator",
        ).forEach { token -> assertTrue(token !in code, "日志层不得吸收职责 '$token'（是事实记录）") }
    }

    private fun count(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}