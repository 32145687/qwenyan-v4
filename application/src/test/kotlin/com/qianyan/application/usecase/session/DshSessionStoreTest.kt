package com.qianyan.application.usecase.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AgentSessionRepository
import com.qianyan.storage.repository.SqliteAgentSessionRepository
import java.util.UUID
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * DSH-05 · DshSessionStore 测试（内存库；复用既有 AgentSession 表与生命周期规则）。
 *
 * 覆盖：复用（同 workflowRef）/ workflowId 不匹配新建 / workflowRef=null 只复用无引用会话 /
 * activate / finish 三终态与非法迁移拒绝 / 同终态幂等 / resume。
 */
class DshSessionStoreTest {

    private val projectId = ProjectId("p-dsh05")
    private val novelId = NovelId("n-dsh05")
    private val t1 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val t2 = Instant.fromEpochMilliseconds(1_700_000_060_000)

    private fun store(): Pair<DshSessionStore, JdbcSqliteDriver> {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val driver = h.driver as JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('${novelId.value}', '${projectId.value}', '书', 'ORIGINAL_NOVEL', '[]', '', 'ORIGINAL', 'DRAFT', 1, 1)",
            0,
        )
        val repo: AgentSessionRepository = SqliteAgentSessionRepository(h.db)
        return DshSessionStore(repo) to driver
    }

    @Test
    fun `same project and workflow ref reuses one session row`() {
        val (store, _) = store()
        val first = store.openOrCreate(projectId, novelId, "wf-1", t1)
        val second = store.openOrCreate(projectId, novelId, "wf-1", t2)

        assertEquals(first.sessionId, second.sessionId, "同 project + 同 workflowRef 必须复用")
        assertEquals(AgentSessionStatus.CREATED, second.status, "复用不改变状态")
        assertEquals(t2, second.lastActivityAt, "复用仅推进 lastActivityAt")
    }

    @Test
    fun `mismatched workflow ref creates a new session`() {
        val (store, _) = store()
        val first = store.openOrCreate(projectId, novelId, "wf-1", t1)
        val second = store.openOrCreate(projectId, novelId, "wf-2", t2)

        assertNotEquals(first.sessionId, second.sessionId, "workflowRef 不同必须新建")
        assertEquals(first.workflowId!!.value, "wf-1")
        assertEquals(second.workflowId!!.value, "wf-2")
    }

    @Test
    fun `null workflow ref only reuses sessions without workflow`() {
        val (store, _) = store()
        val withWorkflow = store.openOrCreate(projectId, novelId, "wf-1", t1)
        val noRef = store.openOrCreate(projectId, novelId, null, t2)

        assertNotEquals(withWorkflow.sessionId, noRef.sessionId, "null ref 不得复用带 workflow 的会话")
        assertNull(noRef.workflowId)

        val again = store.openOrCreate(projectId, novelId, null, Instant.fromEpochMilliseconds(t2.toEpochMilliseconds() + 1_000))
        assertEquals(noRef.sessionId, again.sessionId, "null ref 复用无 workflow 会话")
    }

    @Test
    fun `activate moves created to active`() {
        val (store, _) = store()
        val session = store.openOrCreate(projectId, novelId, "wf-1", t1)
        val active = store.activate(session.sessionId, t2)

        assertEquals(AgentSessionStatus.ACTIVE, active.status)
        assertEquals(t2, active.updatedAt)
        assertEquals(t2, active.lastActivityAt)
    }

    @Test
    fun `finish from active maps to terminals and rejects illegal migration`() {
        val (store, _) = store()
        val created = store.openOrCreate(projectId, novelId, "wf-1", t1)

        // 非法：CREATED 直接 COMPLETED → 返回 null，状态不变
        assertNull(store.finish(created.sessionId, AgentSessionStatus.COMPLETED, t2), "CREATED→COMPLETED 必须拒绝")
        assertEquals(AgentSessionStatus.CREATED, store.resume(projectId)!!.status)

        // 合法：ACTIVE → FAILED
        store.activate(created.sessionId, t2)
        val failed = store.finish(created.sessionId, AgentSessionStatus.FAILED, t2)!!
        assertEquals(AgentSessionStatus.FAILED, failed.status)

        // 同终态幂等
        val again = store.finish(created.sessionId, AgentSessionStatus.FAILED, Instant.fromEpochMilliseconds(t2.toEpochMilliseconds() + 1_000))
        assertEquals(AgentSessionStatus.FAILED, again!!.status)
    }

    @Test
    fun `cancel from created is allowed by lifecycle rules`() {
        val (store, _) = store()
        val created = store.openOrCreate(projectId, novelId, "wf-1", t1)
        val cancelled = store.finish(created.sessionId, AgentSessionStatus.CANCELLED, t2)!!
        assertEquals(AgentSessionStatus.CANCELLED, cancelled.status, "CREATED→CANCELLED 属既有规则")
        assertNull(store.resume(projectId), "终态后不可恢复")
    }

    @Test
    fun `resume returns latest resumable session or null`() {
        val (store, _) = store()
        assertNull(store.resume(projectId), "无会话 → null（不建行）")

        val session = store.openOrCreate(projectId, novelId, "wf-1", t1)
        assertEquals(session.sessionId, store.resume(projectId)!!.sessionId)

        val other = store.resume(ProjectId("p-ghost"))
        assertNull(other)
    }
}
