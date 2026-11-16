package com.qianyan.application.usecase.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.usecase.dsh.InMemoryDshRuntime
import com.qianyan.application.usecase.dsh.RuntimeEvent
import com.qianyan.application.usecase.dsh.RuntimeEventKind
import com.qianyan.application.usecase.dsh.RuntimeTask
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AgentSessionRepository
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import java.nio.file.Files
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DSH-05 · AgentSessionDshRuntime 测试（文件库，含「关库重开」恢复用例）。
 *
 * 事件源用 InMemoryDshRuntime 假件（**契约测试**，非真实 DSH Runtime 调用——skill 10.7 规则）。
 */
class AgentSessionDshRuntimeTest {

    private val projectId = ProjectId("p-dsh05rt")
    private val novelId = NovelId("n-dsh05rt")
    private val noon = Instant.fromEpochMilliseconds(1_700_000_012_000)

    private val emptyTask = RuntimeTask(input = "", allowedTools = emptyList(), maxRounds = 1, budgetMillis = 0)

    private fun newRepo(url: String): Pair<AgentSessionRepository, QianyanDbHandle> {
        val h = QianyanDbFactory.open(url)
        (h.driver as JdbcSqliteDriver).execute(
            null,
            "INSERT OR IGNORE INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('${novelId.value}', '${projectId.value}', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        return SqliteAgentSessionRepository(h.db) to h
    }

    private fun runtimeOf(repo: AgentSessionRepository, now: () -> Instant, eventSource: (String) -> Sequence<RuntimeEvent>) =
        AgentSessionDshRuntime(
            store = DshSessionStore(repo),
            projectId = projectId,
            novelId = novelId,
            workflowRef = null,
            now = now,
            eventSource = eventSource,
        )

    private fun defaultSource(): (String) -> Sequence<RuntimeEvent> = { ref ->
        InMemoryDshRuntime().openSession(ref).dispatch(emptyTask).events()
    }

    private fun failureSource(summary: String): (String) -> Sequence<RuntimeEvent> = { ref ->
        InMemoryDshRuntime(failureSummary = summary).openSession(ref).dispatch(emptyTask).events()
    }

    @Test
    fun `dispatch activates session and draining events completes it`() {
        val dir = Files.createTempDirectory("dsh05-rt-active")
        val url = "jdbc:sqlite:${dir.resolve("s.db").toAbsolutePath()}"
        val (repo, h) = newRepo(url)
        val runtime = runtimeOf(repo, { noon }, defaultSource())

        val session = runtime.openSession("wf-dsh05")
        assertTrue(session.sessionId.startsWith("dsh-"), "sessionId = AgentSession 主键值")
        val sid = AgentSessionId(session.sessionId)
        val handle = session.dispatch(emptyTask)
        assertEquals(AgentSessionStatus.ACTIVE, repo.get(sid)!!.status, "dispatch 前 activate")

        val events = handle.events().toList()
        assertEquals(RuntimeEventKind.PHASE_END, events.last().kind, "默认脚本以 PHASE_END 结束")
        assertEquals(
            AgentSessionStatus.COMPLETED,
            repo.get(sid)!!.status,
            "事件流消费完毕 → COMPLETED（与最后事件一致）",
        )
        h.driver.close()
    }

    @Test
    fun `failure script maps session to failed`() {
        val (repo, h) = newRepo("jdbc:sqlite::memory:")
        val session = runtimeOf(repo, { noon }, failureSource("模型超时")).openSession("wf-f")
        val sid = AgentSessionId(session.sessionId)
        val events = session.dispatch(emptyTask).events().toList()

        assertEquals(RuntimeEventKind.FAILED, events.last().kind)
        assertEquals(
            AgentSessionStatus.FAILED,
            repo.get(sid)!!.status,
            "库终态与最后事件一致（FAILED）",
        )
        h.driver.close()
    }

    @Test
    fun `cancel finishes session cancelled and truncates events`() {
        val (repo, h) = newRepo("jdbc:sqlite::memory:")
        val session = runtimeOf(repo, { noon }, defaultSource()).openSession("wf-c")
        val sid = AgentSessionId(session.sessionId)
        val handle = session.dispatch(emptyTask)

        handle.cancel()
        val events = handle.events().toList()
        assertEquals(RuntimeEventKind.CANCELLED, events.last().kind, "取消后事件流以 CANCELLED 结束")
        assertEquals(AgentSessionStatus.CANCELLED, repo.get(sid)!!.status)
        h.driver.close()
    }

    @Test
    fun `reopening the database resumes the same session and unknown project returns null`() {
        val dir = Files.createTempDirectory("dsh05-rt-reopen")
        val url = "jdbc:sqlite:${dir.resolve("s.db").toAbsolutePath()}"

        val (repo1, h1) = newRepo(url)
        val session1 = runtimeOf(repo1, { noon }, defaultSource()).openSession("wf-reopen")
        val sid = AgentSessionId(session1.sessionId)
        session1.dispatch(emptyTask) // 不消费事件流，保持 ACTIVE（模拟进程中断）
        assertEquals(AgentSessionStatus.ACTIVE, repo1.get(sid)!!.status)
        h1.driver.close()

        // 关库重开 → resume 返回同一会话
        val (repo2, h2) = newRepo(url)
        val store2 = DshSessionStore(repo2)
        val resumed = store2.resume(projectId)
        assertEquals(session1.sessionId, resumed!!.sessionId.value, "重启后恢复同一会话")
        assertEquals(AgentSessionStatus.ACTIVE, resumed.status)
        assertNull(store2.resume(ProjectId("p-ghost")), "未知 project → null 且不建行")
        h2.driver.close()
    }

    @Test
    fun `illegal migration via store is rejected without changing status`() {
        val (repo, h) = newRepo("jdbc:sqlite::memory:")
        val store = DshSessionStore(repo)
        val created = store.openOrCreate(projectId, novelId, "wf-x", noon)

        assertNull(store.finish(created.sessionId, AgentSessionStatus.COMPLETED, Instant.fromEpochMilliseconds(noon.toEpochMilliseconds() + 1)), "CREATED→COMPLETED 拒绝")
        assertEquals(AgentSessionStatus.CREATED, store.resume(projectId)!!.status, "拒绝后状态不变")
        h.driver.close()
    }
}
