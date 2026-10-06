package com.qianyan.application.usecase.runtimeintegration

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.runtime.RuntimeSessionRef
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.runtime.contract.AgentRuntimeGateway
import com.qianyan.runtime.contract.RuntimeError
import com.qianyan.runtime.contract.RuntimeHandle
import com.qianyan.runtime.contract.RuntimeRunResult
import com.qianyan.runtime.contract.RuntimeSession
import com.qianyan.runtime.contract.RuntimeSessionRequest
import com.qianyan.runtime.contract.RuntimeStopReason
import com.qianyan.runtime.contract.RuntimeUpdate
import com.qianyan.runtime.contract.RuntimeUpdateKind
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I1 · Runtime Integration 应用层测试（**不依赖 DSH**：用契约级的假 Adapter）。
 *
 * 覆盖：
 *  - 生命周期桥接：AgentSession → 运行时会话（create/prompt/cancel/close）；
 *  - 绑定 `1 : N` + 持久化恢复（重建容器后绑定仍在）；
 *  - 事件/错误投影：RuntimeRunResult → 既有 Activity；RuntimeError → 类型化 ApplicationError；
 *  - 未装配 Runtime 时的类型化拒绝（不伪装可用）。
 */
class RuntimeIntegrationTest {

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fakeGateway(): FakeRuntimeGateway = FakeRuntimeGateway()

    private fun inMemory(gateway: AgentRuntimeGateway? = null): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway(), runtimeGateway = gateway), handle)
    }

    private fun onFile(path: String, gateway: AgentRuntimeGateway? = null): Fixture {
        val handle = QianyanDbFactory.open("jdbc:sqlite:${Paths.get(path).toAbsolutePath()}")
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway(), runtimeGateway = gateway), handle)
    }

    // ---------- 生命周期桥接 ----------

    @Test
    fun `create runtime session binds agent session and records activity`() {
        val gateway = fakeGateway()
        val f = inMemory(gateway)
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)

        val runtimeSession = f.app.runtimeIntegration.createRuntimeSession(session.sessionId)

        assertEquals("rt-sess-1", runtimeSession.sessionId)
        val bindings = f.app.runtimeSessionBindings.bindingsOf(session.sessionId)
        assertEquals(1, bindings.size, "应写入一条绑定")
        assertEquals("fake-runtime", bindings.first().runtimeName, "runtimeName 来自契约句柄（不透明标识）")
        assertEquals(session.sessionId, bindings.first().agentSessionId)

        val kinds = f.app.runtimeIntegration.activitiesOf(session.sessionId).map { it.kind }
        assertTrue("runtime.session.open" in kinds, "打开运行时会话应落既有 Activity")
        f.close()
    }

    @Test
    fun `binding is one to many across runtime sessions`() {
        val gateway = fakeGateway()
        val f = inMemory(gateway)
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)

        f.app.runtimeIntegration.createRuntimeSession(session.sessionId)
        gateway.nextSessionId = "rt-sess-2"
        f.app.runtimeIntegration.createRuntimeSession(session.sessionId)

        val bindings = f.app.runtimeSessionBindings.bindingsOf(session.sessionId)
        assertEquals(2, bindings.size, "AgentSession : Runtime Session 必须是 1 : N")
        assertEquals(setOf("rt-sess-1", "rt-sess-2"), bindings.map { it.runtimeSessionId }.toSet())
        f.close()
    }

    @Test
    fun `prompt projects runtime updates into activity and returns typed result`() {
        val gateway = fakeGateway()
        val f = inMemory(gateway)
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)
        f.app.runtimeIntegration.createRuntimeSession(session.sessionId)

        val run = f.app.runtimeIntegration.prompt(session.sessionId, "ping")

        assertEquals("OK", run.finalText)
        assertEquals(RuntimeStopReason.END_TURN, run.stopReason)
        assertEquals(RuntimeUpdateKind.MESSAGE, run.updates.single().kind)

        val promptActivity = f.app.runtimeIntegration.activitiesOf(session.sessionId)
            .first { it.kind == "runtime.prompt" }
        assertEquals("COMPLETED", promptActivity.status.name)
        assertTrue(promptActivity.summary.orEmpty().contains("stop=END_TURN"), "摘要应含类型化 stopReason")
        f.close()
    }

    @Test
    fun `cancel and close are delegated to the runtime binding`() {
        val gateway = fakeGateway()
        val f = inMemory(gateway)
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)
        f.app.runtimeIntegration.createRuntimeSession(session.sessionId)

        f.app.runtimeIntegration.cancel(session.sessionId)
        f.app.runtimeIntegration.closeSession(session.sessionId)

        assertEquals(listOf("rt-sess-1"), gateway.cancelled)
        assertEquals(listOf("rt-sess-1"), gateway.closed)
        f.close()
    }

    // ---------- 错误 / 投影 ----------

    @Test
    fun `runtime server error maps to typed application error and activity failure`() {
        val gateway = fakeGateway().apply { failPrompt = true }
        val f = inMemory(gateway)
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)
        f.app.runtimeIntegration.createRuntimeSession(session.sessionId)

        val error = assertFailsWith<ApplicationException> { f.app.runtimeIntegration.prompt(session.sessionId, "ping") }
        assertTrue(
            error.error is ApplicationError.RuntimeExecutionFailed,
            "对端失败应归一为 RuntimeExecutionFailed，实际 ${error.error::class.simpleName}",
        )
        val promptActivity = f.app.runtimeIntegration.activitiesOf(session.sessionId)
            .first { it.kind == "runtime.prompt" }
        assertEquals("FAILED", promptActivity.status.name)
        f.close()
    }

    @Test
    fun `unavailable runtime rejects with typed error instead of pretending`() {
        val f = inMemory(gateway = null)
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)

        assertFalse(f.app.runtimeIntegration.available, "未装配 Runtime 时 available 必须为 false")
        val error = assertFailsWith<ApplicationException> { f.app.runtimeIntegration.createRuntimeSession(session.sessionId) }
        assertTrue(error.error is ApplicationError.RuntimeUnavailable, "应类型化拒绝，而不是伪装可用")
        f.close()
    }

    @Test
    fun `prompt without binding is rejected`() {
        val f = inMemory(fakeGateway())
        val novelId = f.app.novels.createOriginal(title = "I1 书")
        val session = f.app.agentSessions.startSession(novelId)

        val error = assertFailsWith<ApplicationException> { f.app.runtimeIntegration.prompt(session.sessionId, "ping") }
        assertTrue(error.error is ApplicationError.EntityNotFound)
        f.close()
    }

    // ---------- 持久化恢复 ----------

    @Test
    fun `binding survives container rebuild`() {
        val path = Files.createTempDirectory("qianyan-i1-binding").resolve("qianyan.db").toString()
        val first = onFile(path, fakeGateway())
        val novelId = first.app.novels.createOriginal(title = "I1 书")
        val session = first.app.agentSessions.startSession(novelId)
        first.app.runtimeIntegration.createRuntimeSession(session.sessionId)
        first.close()

        val second = onFile(path)
        val restored: List<RuntimeSessionRef> = second.app.runtimeSessionBindings.bindingsOf(session.sessionId)
        assertEquals(1, restored.size, "绑定应跨容器重建恢复（只恢复身份，不重放执行）")
        assertEquals("rt-sess-1", assertNotNull(second.app.runtimeSessionBindings.latest(session.sessionId)).runtimeSessionId)
        second.close()
    }

    /** 契约级假 Adapter（不依赖 DSH / 进程）。 */
    private class FakeRuntimeGateway : AgentRuntimeGateway {
        var nextSessionId = "rt-sess-1"
        var failPrompt = false
        val cancelled = mutableListOf<String>()
        val closed = mutableListOf<String>()

        override fun start(): RuntimeHandle = object : RuntimeHandle {
            override val runtimeName: String = "fake-runtime"
            override val alive: Boolean = true
            override fun diagnostics(): List<String> = emptyList()
            override fun shutdown() = Unit
        }

        override fun createSession(request: RuntimeSessionRequest): RuntimeSession =
            RuntimeSession(sessionId = nextSessionId, workingDirectory = request.workingDirectory)

        override fun prompt(session: RuntimeSession, prompt: String): RuntimeRunResult {
            if (failPrompt) throw RuntimeError.ServerError(-32603, "Internal error")
            return RuntimeRunResult(
                sessionId = session.sessionId,
                finalText = "OK",
                updates = listOf(RuntimeUpdate(kind = RuntimeUpdateKind.MESSAGE, rawKind = "agent_message_chunk", text = "OK")),
                stopReason = RuntimeStopReason.END_TURN,
                rawStopReason = "end_turn",
            )
        }

        override fun cancel(session: RuntimeSession) {
            cancelled.add(session.sessionId)
        }

        override fun resume(sessionId: String): RuntimeSession =
            throw RuntimeError.NotAvailable("resume not verified")

        override fun close(session: RuntimeSession) {
            closed.add(session.sessionId)
        }

        override fun shutdown() = Unit
    }
}