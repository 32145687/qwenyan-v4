package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JSON-RPC / framing 单元测试（不依赖 DSH，也不需要子进程）。 */
class DshJsonRpcTest {

    @Test
    fun `encodes request with id method and params`() {
        val line = DshJsonRpc.encodeRequest(7, "initialize", null)
        assertTrue(line.startsWith("{"))
        assertTrue("\"jsonrpc\":\"2.0\"" in line)
        assertTrue("\"id\":7" in line)
        assertTrue("\"method\":\"initialize\"" in line)
        assertTrue("params" !in line, "params 为空时不应写入")
    }

    @Test
    fun `encodes notification without id`() {
        val line = DshJsonRpc.encodeNotification("session/update", null)
        assertTrue("\"id\"" !in line)
        assertTrue("\"method\":\"session/update\"" in line)
    }

    @Test
    fun `encodes response for server initiated request`() {
        val line = DshJsonRpc.encodeResponse(900, null)
        assertTrue("\"id\":900" in line)
        assertTrue("\"method\"" !in line, "应答不是请求，不应带 method")
        assertTrue("\"result\"" in line)
    }

    @Test
    fun `encodes error response for unsupported server request`() {
        val line = DshJsonRpc.encodeErrorResponse(901, -32601, "unsupported")
        assertTrue("\"id\":901" in line)
        assertTrue("-32601" in line)
        assertTrue("unsupported" in line)
    }

    @Test
    fun `parses response with result`() {
        val message = DshJsonRpc.parseLine("""{"jsonrpc":"2.0","id":3,"result":{"sessionId":"s1"}}""")
        val response = message as DshJsonRpc.Message.Response
        assertEquals(3L, response.id)
        assertNull(response.error)
        assertTrue(response.result.toString().contains("s1"))
    }

    @Test
    fun `parses response with jsonrpc error`() {
        val message = DshJsonRpc.parseLine("""{"jsonrpc":"2.0","id":4,"error":{"code":-32601,"message":"nope"}}""")
        val response = message as DshJsonRpc.Message.Response
        assertEquals(-32601, response.error?.code)
        assertEquals("nope", response.error?.message)
    }

    @Test
    fun `parses session update notification`() {
        val line = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"hi"}}}}"""
        val message = DshJsonRpc.parseLine(line)
        val notification = message as DshJsonRpc.Message.Notification
        assertEquals("session/update", notification.method)
        assertTrue(notification.params.toString().contains("agent_message_chunk"))
    }

    @Test
    fun `parses server initiated request with id and method`() {
        val line = """{"jsonrpc":"2.0","id":900,"method":"session/request_permission","params":{"sessionId":"s1"}}"""
        val message = DshJsonRpc.parseLine(line)
        val request = message as DshJsonRpc.Message.Request
        assertEquals(900L, request.id)
        assertEquals("session/request_permission", request.method)
    }

    @Test
    fun `blank line is skipped`() {
        assertNull(DshJsonRpc.parseLine("   "))
    }

    @Test
    fun `malformed json raises typed protocol violation`() {
        assertFailsWith<RuntimeError.ProtocolViolation> { DshJsonRpc.parseLine("not-json") }
    }

    @Test
    fun `response without id raises typed protocol violation`() {
        assertFailsWith<RuntimeError.ProtocolViolation> { DshJsonRpc.parseLine("""{"jsonrpc":"2.0","result":{}}""") }
    }
}