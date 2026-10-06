package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimeError
import com.qianyan.runtime.contract.RuntimeSessionRequest
import com.qianyan.runtime.contract.RuntimeStopReason
import com.qianyan.runtime.contract.RuntimeUpdateKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DSH ACP Runtime Adapter 测试（**不依赖本机安装 DSH**）。
 *
 * 用同一个 JVM 启动 [FakeDshServer] 作为 ACP stdio server：
 * 覆盖启动 / initialize / session-new / prompt / session-update / close / cancel / cleanup，
 * 以及非法可执行文件、超时、非法帧、EOF、stdout-stderr 分流等 typed 失败路径。
 */
class DshRuntimeClientTest {

    private fun fakeConfig(mode: String, requestTimeoutMillis: Long = 6_000): DshRuntimeConfig {
        val javaHome = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val javaBin = File(javaHome, "bin${File.separator}$exe").absolutePath
        val classpath = System.getProperty("java.class.path")
        return DshRuntimeConfig(
            executable = javaBin,
            arguments = listOf("-cp", classpath, "com.qianyan.runtime.dsh.FakeDshServer", mode),
            startupTimeoutMillis = 5_000,
            requestTimeoutMillis = requestTimeoutMillis,
        )
    }

    private fun waitForDiagnostic(client: DshRuntimeClient, token: String): Boolean {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (client.diagnostics().any { it.contains(token) }) return true
            Thread.sleep(25)
        }
        return false
    }

    @Test
    fun `starts acp process`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        val handle = client.start()
        assertTrue(handle.alive, "Runtime 子进程应处于运行状态")
        assertEquals("dsh-acp", handle.runtimeName)
        client.shutdown()
    }

    @Test
    fun `runs full acp flow and gets final assistant output`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        val handle = client.start()
        try {
            client.initialize()

            val session = client.createSession(RuntimeSessionRequest())
            assertEquals("sess-fake-1", session.sessionId, "session/new 应返回 sessionId")

            val run = client.prompt(session, "Return exactly: DSH_POC_OK")
            assertEquals("DSH_POC_OK", run.finalText, "最终 assistant 输出应与 prompt 约定一致")
            assertEquals(RuntimeStopReason.END_TURN, run.stopReason, "stopReason 应收敛为 Qianyan 枚举")
            assertEquals("end_turn", run.rawStopReason, "原始 stopReason 应保留兜底")

            client.close(session)
        } finally {
            client.shutdown()
        }
        assertFalse(handle.alive, "shutdown 后子进程不应存活")
    }

    @Test
    fun `receives session update notifications as typed kinds`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        client.start()
        try {
            client.initialize()
            val session = client.createSession(RuntimeSessionRequest())
            val run = client.prompt(session, "Return exactly: DSH_POC_OK")

            assertTrue(run.updates.isNotEmpty(), "应至少收到一条 session/update")
            assertTrue(
                run.updates.any { it.kind == RuntimeUpdateKind.MESSAGE },
                "应把 agent_message_chunk 映射为 RuntimeUpdateKind.MESSAGE",
            )
            assertEquals(
                "DSH_POC_OK",
                run.updates.mapNotNull { it.text }.joinToString(""),
                "update 分片应拼出完整答案（证明答案来源是 notification 流）",
            )
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `cancel sends notification and does not disturb subsequent work`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        client.start()
        try {
            client.initialize()
            val session = client.createSession(RuntimeSessionRequest())
            client.cancel(session)
            assertTrue(waitForDiagnostic(client, "cancel-received"), "对端应收到 session/cancel 通知")

            // 取消后会话仍可用（cancel 是通知，不破坏 protocol 流）
            val run = client.prompt(session, "Return exactly: DSH_POC_OK")
            assertEquals("DSH_POC_OK", run.finalText)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `handshake is performed automatically before session new`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        client.start()
        try {
            // 契约不暴露 initialize：首次会话操作应由 Adapter 内部完成握手
            val session = client.createSession(RuntimeSessionRequest())
            assertEquals("sess-fake-1", session.sessionId)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `explicit initialize is idempotent`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        client.start()
        try {
            client.initialize()
            client.initialize()
            val session = client.createSession(RuntimeSessionRequest())
            assertEquals("sess-fake-1", session.sessionId)
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `resume is explicitly not verified`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        client.start()
        try {
            val error = assertFailsWith<RuntimeError.NotAvailable> { client.resume("sess-fake-1") }
            assertTrue(error.message.orEmpty().contains("NOT YET VERIFIED"))
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `shutdown is idempotent and leaves no live process`() {
        val client = DshRuntimeClient(fakeConfig("normal"))
        val handle = client.start()
        client.initialize()
        client.shutdown()
        client.shutdown()
        assertFalse(handle.alive, "重复 shutdown 后仍不应有存活进程")
    }

    @Test
    fun `invalid executable fails fast with typed startup error`() {
        val client = DshRuntimeClient(
            DshRuntimeConfig(executable = "definitely-not-a-real-dsh-executable-xyz"),
        )
        val started = System.nanoTime()
        val error = assertFailsWith<RuntimeError.StartupFailed> { client.start() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(error.message.orEmpty().isNotBlank())
        assertTrue(elapsedMs < 3_000, "缺少可执行文件必须立即失败（实际 ${elapsedMs}ms），不能无限等待")
    }

    @Test
    fun `silent server times out with typed error and cleans up`() {
        val client = DshRuntimeClient(fakeConfig("silent", requestTimeoutMillis = 700))
        val handle = client.start()
        val error = assertFailsWith<RuntimeError.TimedOut> { client.initialize() }
        assertEquals("initialize", error.operation)
        client.shutdown()
        assertFalse(handle.alive, "超时后必须清理子进程")
    }

    @Test
    fun `malformed jsonrpc frame raises typed protocol violation`() {
        val client = DshRuntimeClient(fakeConfig("garbage"))
        client.start()
        try {
            assertFailsWith<RuntimeError.ProtocolViolation> { client.initialize() }
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `eof while waiting raises typed stream closed`() {
        val client = DshRuntimeClient(fakeConfig("eof"))
        client.start()
        try {
            client.initialize()
            val error = assertFailsWith<RuntimeError> { client.createSession(RuntimeSessionRequest()) }
            assertTrue(
                error is RuntimeError.StreamClosed || error is RuntimeError.ServerError,
                "EOF 应映射为 typed StreamClosed/ServerError，实际 ${error::class.simpleName}",
            )
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `stderr diagnostics do not pollute protocol stream`() {
        val client = DshRuntimeClient(fakeConfig("stderr-noise"))
        client.start()
        try {
            client.initialize()
            val session = client.createSession(RuntimeSessionRequest())
            val run = client.prompt(session, "Return exactly: DSH_POC_OK")
            assertEquals("DSH_POC_OK", run.finalText, "stderr 噪声不应影响 protocol 解析")
            assertTrue(
                client.diagnostics().any { it.contains("diagnostic line") },
                "stderr 诊断应被独立收集",
            )
        } finally {
            client.shutdown()
        }
    }
}