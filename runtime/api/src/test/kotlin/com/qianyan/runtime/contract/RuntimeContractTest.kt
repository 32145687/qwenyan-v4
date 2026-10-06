package com.qianyan.runtime.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I1 · Runtime Contract 守卫测试。
 *
 * 守两件事：
 *  1. **类型化映射**：原始取值 → Qianyan 枚举（未知值兜底为 OTHER / UNKNOWN，绝不抛错）；
 *  2. **文案 vendor-neutral**：契约层的错误文案**不得**出现实现/供应商词汇（DSH / ACP / JSON-RPC）。
 */
class RuntimeContractTest {

    @Test
    fun `update kind maps known raw values and falls back to OTHER`() {
        assertEquals(RuntimeUpdateKind.MESSAGE, RuntimeUpdateKind.fromRaw("agent_message_chunk"))
        assertEquals(RuntimeUpdateKind.THOUGHT, RuntimeUpdateKind.fromRaw("agent_thought_chunk"))
        assertEquals(RuntimeUpdateKind.TOOL_CALL, RuntimeUpdateKind.fromRaw("tool_call"))
        assertEquals(RuntimeUpdateKind.TOOL_CALL, RuntimeUpdateKind.fromRaw("tool_call_update"))
        assertEquals(RuntimeUpdateKind.PLAN, RuntimeUpdateKind.fromRaw("plan"))
        assertEquals(RuntimeUpdateKind.USAGE, RuntimeUpdateKind.fromRaw("usage_update"))
        assertEquals(RuntimeUpdateKind.OTHER, RuntimeUpdateKind.fromRaw("something_new_from_future"))
        assertEquals(RuntimeUpdateKind.OTHER, RuntimeUpdateKind.fromRaw(null))
    }

    @Test
    fun `stop reason maps known raw values and falls back to UNKNOWN`() {
        assertEquals(RuntimeStopReason.END_TURN, RuntimeStopReason.fromRaw("end_turn"))
        assertEquals(RuntimeStopReason.MAX_TOKENS, RuntimeStopReason.fromRaw("max_tokens"))
        assertEquals(RuntimeStopReason.MAX_TURN_REQUESTS, RuntimeStopReason.fromRaw("max_turn_requests"))
        assertEquals(RuntimeStopReason.REFUSAL, RuntimeStopReason.fromRaw("refusal"))
        assertEquals(RuntimeStopReason.CANCELLED, RuntimeStopReason.fromRaw("cancelled"))
        assertEquals(RuntimeStopReason.UNKNOWN, RuntimeStopReason.fromRaw("whatever"))
        assertEquals(RuntimeStopReason.UNKNOWN, RuntimeStopReason.fromRaw(null))
    }

    @Test
    fun `runtime update keeps raw kind for unknown values`() {
        val update = RuntimeUpdate(kind = RuntimeUpdateKind.OTHER, rawKind = "brand_new_kind", text = "x")
        assertEquals("brand_new_kind", update.rawKind)
    }

    @Test
    fun `runtime error messages are vendor neutral`() {
        val banned = listOf("dsh", "acp", "json-rpc", "deepseek")
        val messages = listOf(
            RuntimeError.StartupFailed("cannot start runtime process").message,
            RuntimeError.ProtocolViolation("bad frame").message,
            RuntimeError.ServerError(-32603, "Internal error").message,
            RuntimeError.TimedOut("initialize", 1000).message,
            RuntimeError.StreamClosed("stdout", 7).message,
            RuntimeError.NotAvailable("runtime not started").message,
        )
        messages.forEach { message ->
            val lower = message.orEmpty().lowercase()
            banned.forEach { token ->
                assertFalse(lower.contains(token), "契约错误文案不得包含 '$token'：$message")
            }
        }
    }

    @Test
    fun `server error keeps structured code for typed handling`() {
        val error = RuntimeError.ServerError(-32603, "Internal error", "detail")
        assertEquals(-32603, error.code)
        assertEquals("Internal error", error.serverMessage)
        assertEquals("detail", error.data)
        assertTrue(error.message.orEmpty().contains("-32603"))
    }
}