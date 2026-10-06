package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimePermissionOutcome
import com.qianyan.runtime.contract.RuntimePermissionRequest
import com.qianyan.runtime.contract.RuntimePermissionResponder
import com.qianyan.runtime.contract.RuntimeSessionRequest
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I1 §九 · Permission 映射测试（Adapter 单向应答）。
 *
 * 验证方向：**Qianyan 决策 → Adapter 翻译 → 对端 allow/reject**。
 * 断言点是对端（fake server）实际收到的 option —— 证明 Adapter 没有自行询问用户，
 * 也没有把 ACP 结构泄漏给 Application。
 */
class DshPermissionTest {

    private fun fakeConfig(): DshRuntimeConfig {
        val javaHome = System.getProperty("java.home")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val javaBin = File(javaHome, "bin${File.separator}$exe").absolutePath
        val classpath = System.getProperty("java.class.path")
        return DshRuntimeConfig(
            executable = javaBin,
            arguments = listOf("-cp", classpath, "com.qianyan.runtime.dsh.FakeDshServer", "permission"),
            startupTimeoutMillis = 5_000,
            requestTimeoutMillis = 8_000,
        )
    }

    private fun ask(outcome: RuntimePermissionOutcome, seen: MutableList<RuntimePermissionRequest>): String {
        val responder = RuntimePermissionResponder { request ->
            seen.add(request)
            outcome
        }
        val client = DshRuntimeClient(fakeConfig(), responder)
        client.start()
        try {
            client.initialize()
            val session = client.createSession(RuntimeSessionRequest())
            return client.prompt(session, "Return exactly: DSH_POC_OK").finalText
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `allowed decision is translated to allow option`() {
        val seen = CopyOnWriteArrayList<RuntimePermissionRequest>()
        val finalText = ask(RuntimePermissionOutcome.ALLOW, seen)
        assertEquals("PERM:selected:allow-once", finalText, "ALLOW 应翻译为对端 allow 选项")
        assertEquals(1, seen.size, "应答端口应被调用一次")
        assertTrue(seen.first().runtimeSessionId.isNotBlank(), "请求应带 runtimeSessionId（vendor-neutral）")
    }

    @Test
    fun `rejected decision is translated to reject option`() {
        val seen = CopyOnWriteArrayList<RuntimePermissionRequest>()
        val finalText = ask(RuntimePermissionOutcome.REJECT, seen)
        assertEquals("PERM:selected:reject-once", finalText, "REJECT 应翻译为对端 reject 选项")
    }

    @Test
    fun `missing responder fails closed with reject`() {
        // 未注入应答端口 ⇒ 不得默认放行（fail-closed）
        val client = DshRuntimeClient(fakeConfig())
        client.start()
        try {
            client.initialize()
            val session = client.createSession(RuntimeSessionRequest())
            val run = client.prompt(session, "Return exactly: DSH_POC_OK")
            assertEquals("PERM:selected:reject-once", run.finalText, "无应答端口必须 fail-closed")
        } finally {
            client.shutdown()
        }
    }
}