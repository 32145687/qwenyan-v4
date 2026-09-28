package com.qianyan.application.usecase.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.session.AgentSessionLifecycleRules
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I3 · Agent Session 应用层测试。
 *
 * 覆盖（对应 I3 要求）：
 *  - Session Repository 行为：创建 / 读取 / 按 Project 查询 / 更新 / 不存在返回；
 *  - 生命周期：合法转换通过、非法转换类型化拒绝、终态不可再转换；
 *  - Project 隔离：Project A 的会话不被 Project B 查到 / 跨 Project 结构上不可达；
 *  - Session ≠ Workflow / Task：建立与推进会话**不凭空创建** Workflow / Task，也不改写其状态；
 *  - 持久化恢复：重建容器后会话身份与关联仍在（**只恢复身份，不实现 Resume Engine**）；
 *  - Determinism：同输入（Project + Workflow + Task）下创建/查询结果稳定。
 */
class AgentSessionUseCasesTest {

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun inMemory(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    private fun onFile(path: String): Fixture {
        val handle = QianyanDbFactory.open("jdbc:sqlite:${Paths.get(path).toAbsolutePath()}")
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    // ---------- Repository 行为（经 UseCase） ----------

    @Test
    fun `start session binds project identity and defaults to created`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")

        val session = f.app.agentSessions.startSession(novelId)

        assertEquals(novelId, session.novelId)
        assertEquals(AgentSessionStatus.CREATED, session.status)
        assertNull(session.workflowId)
        assertNull(session.taskId)
        assertEquals(session, f.app.agentSessions.sessionOf(session.sessionId), "读取应回读同一会话")
        assertEquals(session.projectId, assertNotNull(f.app.novels.getNovel(novelId)).projectId, "projectId 来自 Novel.project_id")
        f.close()
    }

    @Test
    fun `list by project and missing session semantics`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")
        val first = f.app.agentSessions.startSession(novelId)
        val second = f.app.agentSessions.startSession(novelId)

        val listed = f.app.agentSessions.sessionsOfProject(first.projectId)
        assertEquals(2, listed.size)
        assertTrue(listed.map { it.sessionId }.containsAll(listOf(first.sessionId, second.sessionId)))

        val ex = assertFailsWith<ApplicationException> {
            f.app.agentSessions.sessionOf(AgentSessionId("ghost"))
        }
        assertTrue(ex.error is ApplicationError.EntityNotFound, "不存在会话 → EntityNotFound（不伪造）")
        assertNull(f.app.agentSessions.resumableSessionOf(ProjectId("p-ghost")))
        f.close()
    }

    // ---------- 生命周期 ----------

    @Test
    fun `legal transitions are applied and reflected in storage`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")
        val session = f.app.agentSessions.startSession(novelId)

        val active = f.app.agentSessions.activate(session.sessionId)
        assertEquals(AgentSessionStatus.ACTIVE, active.status)
        assertNotNull(active.lastActivityAt, "进入工作应记录活动时间")

        val paused = f.app.agentSessions.pause(session.sessionId)
        assertEquals(AgentSessionStatus.PAUSED, paused.status)
        assertEquals(AgentSessionStatus.PAUSED, f.app.agentSessions.sessionOf(session.sessionId).status, "状态应落库")

        val resumed = f.app.agentSessions.activate(session.sessionId)
        assertEquals(AgentSessionStatus.ACTIVE, resumed.status, "PAUSED → ACTIVE（可恢复）")

        assertEquals(AgentSessionStatus.COMPLETED, f.app.agentSessions.complete(session.sessionId).status)
        assertTrue(f.app.agentSessions.sessionOf(session.sessionId).status.isTerminal)
        assertNull(f.app.agentSessions.resumableSessionOf(session.projectId), "终态会话不是可恢复会话")
        f.close()
    }

    @Test
    fun `illegal transitions are rejected and terminal state is final`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")

        // CREATED → COMPLETED 非法（必须先 ACTIVE）
        val created = f.app.agentSessions.startSession(novelId)
        val ex1 = assertFailsWith<ApplicationException> { f.app.agentSessions.complete(created.sessionId) }
        assertTrue(ex1.error is ApplicationError.InvalidOperation)
        assertEquals(AgentSessionStatus.CREATED, f.app.agentSessions.sessionOf(created.sessionId).status, "非法转换不得落库")

        // 终态不可再转换
        val done = f.app.agentSessions.startSession(novelId).let {
            f.app.agentSessions.activate(it.sessionId)
            f.app.agentSessions.fail(it.sessionId)
        }
        assertEquals(AgentSessionStatus.FAILED, done.status)
        listOf(
            { f.app.agentSessions.activate(done.sessionId) },
            { f.app.agentSessions.pause(done.sessionId) },
            { f.app.agentSessions.cancel(done.sessionId) },
            { f.app.agentSessions.complete(done.sessionId) },
        ).forEach { op ->
            val ex = assertFailsWith<ApplicationException> { op() }
            assertTrue(ex.error is ApplicationError.InvalidOperation, "终态不可再转换")
        }
    }

    @Test
    fun `lifecycle rules are deterministic and cover required states`() {
        // 规则表本身（纯确定性）
        assertTrue(AgentSessionLifecycleRules.canTransition(AgentSessionStatus.CREATED, AgentSessionStatus.ACTIVE))
        assertTrue(AgentSessionLifecycleRules.canTransition(AgentSessionStatus.ACTIVE, AgentSessionStatus.PAUSED))
        assertTrue(AgentSessionLifecycleRules.canTransition(AgentSessionStatus.PAUSED, AgentSessionStatus.ACTIVE))
        assertTrue(AgentSessionLifecycleRules.canTransition(AgentSessionStatus.ACTIVE, AgentSessionStatus.CANCELLED))
        assertTrue(AgentSessionLifecycleRules.canTransition(AgentSessionStatus.ACTIVE, AgentSessionStatus.ACTIVE), "同状态幂等")
        assertTrue(!AgentSessionLifecycleRules.canTransition(AgentSessionStatus.CREATED, AgentSessionStatus.COMPLETED))
        AgentSessionStatus.entries.filter { it.isTerminal }.forEach {
            assertEquals(emptySet(), AgentSessionLifecycleRules.allowedTargets(it), "$it 是终态")
        }
    }

    // ---------- Project 隔离 ----------

    @Test
    fun `sessions are isolated per project`() {
        val f = inMemory()
        val a = f.app.novels.createOriginal(title = "书A")
        val b = f.app.novels.createOriginal(title = "书B")
        val sessionA = f.app.agentSessions.startSession(a)
        val sessionB = f.app.agentSessions.startSession(b)

        assertEquals(listOf(sessionA.sessionId), f.app.agentSessions.sessionsOfProject(sessionA.projectId).map { it.sessionId })
        assertEquals(listOf(sessionB.sessionId), f.app.agentSessions.sessionsOfProject(sessionB.projectId).map { it.sessionId })
        assertTrue(sessionA.projectId != sessionB.projectId, "不同 Novel 对应不同 Project 身份")
        // 跨 Project 的可恢复查询互不可见
        assertNull(f.app.agentSessions.resumableSessionOf(ProjectId("p-none")))
        f.close()
    }

    // ---------- Session ≠ Workflow / Task ----------

    @Test
    fun `session operations never create or modify workflows and tasks`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")
        val workflowsBefore = count(f.handle, "Workflow")
        val tasksBefore = count(f.handle, "Task")

        val session = f.app.agentSessions.startSession(novelId)
        f.app.agentSessions.activate(session.sessionId)
        f.app.agentSessions.pause(session.sessionId)
        f.app.agentSessions.touch(session.sessionId)
        f.app.agentSessions.cancel(session.sessionId)

        assertEquals(workflowsBefore, count(f.handle, "Workflow"), "建立/推进会话不得凭空创建 Workflow")
        assertEquals(tasksBefore, count(f.handle, "Task"), "建立/推进会话不得凭空创建 Task")
        assertEquals(1L, count(f.handle, "AgentSession"), "只有会话表被写入")
        f.close()
    }

    @Test
    fun `attaching references validates existence and does not modify them`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")
        val session = f.app.agentSessions.startSession(novelId)

        // 悬挂引用必须被拒绝（不伪造关联）
        val exWf = assertFailsWith<ApplicationException> {
            f.app.agentSessions.attachWorkflow(session.sessionId, WorkflowId("wf-ghost"))
        }
        assertTrue(exWf.error is ApplicationError.EntityNotFound)
        val exTask = assertFailsWith<ApplicationException> {
            f.app.agentSessions.attachTask(session.sessionId, TaskId("t-ghost"))
        }
        assertTrue(exTask.error is ApplicationError.EntityNotFound)

        // 真实 Task 可关联（由既有 TaskManager 创建），且**不改其状态**
        val taskId = f.app.tasks.create(com.qianyan.model.task.TaskType.PLANNING)
        val before = assertNotNull(f.app.tasks.findById(taskId))
        val linked = f.app.agentSessions.attachTask(session.sessionId, taskId)
        assertEquals(taskId, linked.taskId)
        assertEquals(before.status, assertNotNull(f.app.tasks.findById(taskId)).status, "关联会话不得改写 Task 状态")
        f.close()
    }

    // ---------- 持久化恢复（只恢复身份） ----------

    @Test
    fun `session identity and references survive container reopen`() {
        val dir = Files.createTempDirectory("qianyan-i3-session")
        val path = dir.resolve("qianyan.db").toString()

        val first = onFile(path)
        val novelId = first.app.novels.createOriginal(title = "重启书")
        val session = first.app.agentSessions.startSession(novelId)
        first.app.agentSessions.activate(session.sessionId)
        first.app.agentSessions.pause(session.sessionId)
        first.close()

        val second = onFile(path)
        val restored = assertNotNull(second.app.agentSessions.sessionOf(session.sessionId), "会话必须跨容器恢复身份")
        assertEquals(session.projectId, restored.projectId)
        assertEquals(novelId, restored.novelId)
        assertEquals(AgentSessionStatus.PAUSED, restored.status)
        assertEquals(restored.sessionId, assertNotNull(second.app.agentSessions.resumableSessionOf(session.projectId)).sessionId)
        second.close()
    }

    // ---------- Determinism ----------

    @Test
    fun `session creation and lookup are deterministic for the same project`() {
        val f = inMemory()
        val novelId = f.app.novels.createOriginal(title = "I3 书")
        val taskId = f.app.tasks.create(com.qianyan.model.task.TaskType.WRITING)

        val a = f.app.agentSessions.startSession(novelId, taskId = taskId)
        val b = f.app.agentSessions.startSession(novelId, taskId = taskId)

        // 同输入 → 归属与关联一致（会话身份各自唯一，但归属/关联/状态确定）
        assertEquals(a.projectId, b.projectId)
        assertEquals(a.novelId, b.novelId)
        assertEquals(a.taskId, b.taskId)
        assertEquals(a.status, b.status)
        assertEquals(a, f.app.agentSessions.sessionOf(a.sessionId), "查询稳定")
        assertEquals(a, f.app.agentSessions.sessionOf(a.sessionId))
        f.close()
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