package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimeError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * ACP 使用的 JSON-RPC 2.0（newline-delimited，over stdio）。
 *
 * 本文件**不拼字符串**：一律经 kotlinx.serialization 构树与解析；
 * 解析失败一律抛 typed [RuntimeError.ProtocolViolation]。
 *
 * Framing：一行一条消息（`\n` 分隔）；空行忽略；非 JSON 行 ⇒ ProtocolViolation。
 *
 * 消息方向：
 *  - 客户端 → 服务端：Request（有 id，需响应）/ Notification（无 id，不等响应）；
 *  - 服务端 → 客户端：Response（对客户端请求）/ Notification（如 session/update）/
 *    **Request（如 session/request_permission，必须由客户端应答）**。
 */
internal object DshJsonRpc {

    const val JSONRPC_VERSION = "2.0"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    sealed interface Message {
        data class Response(val id: Long, val result: JsonElement?, val error: Error?) : Message
        data class Notification(val method: String, val params: JsonElement?) : Message
        data class Request(val id: Long?, val method: String, val params: JsonElement?) : Message
    }

    data class Error(val code: Int, val message: String, val data: String? = null)

    /** 编码请求（id 由调用方分配；params 省略时不写入）。 */
    fun encodeRequest(id: Long, method: String, params: JsonElement? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", id)
            put("method", method)
            if (params != null && params !is JsonNull) put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** 编码通知（无 id）。 */
    fun encodeNotification(method: String, params: JsonElement? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("method", method)
            if (params != null && params !is JsonNull) put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** 编码**服务端请求**的应答（客户端 → 服务端，如 session/request_permission）。 */
    fun encodeResponse(id: Long, result: JsonElement? = null): String {
        val obj = buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", id)
            put("result", result ?: JsonObject(emptyMap()))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** 编码**服务端请求**的错误应答（如无法处理的 server → client 请求）。 */
    fun encodeErrorResponse(id: Long, code: Int, message: String): String {
        val obj = buildJsonObject {
            put("jsonrpc", JSONRPC_VERSION)
            put("id", id)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** 解析一行；空行返回 null（由调用方跳过）。 */
    fun parseLine(line: String): Message? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val root = try {
            json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            throw RuntimeError.ProtocolViolation("非法 JSON-RPC 行：${trimmed.take(120)}", e)
        }
        val method = root["method"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
        val id = root["id"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        if (method != null) {
            return if (id == null) {
                Message.Notification(method, root["params"])
            } else {
                Message.Request(id, method, root["params"])
            }
        }
        if (id == null) throw RuntimeError.ProtocolViolation("响应缺少 id：${trimmed.take(120)}")
        val error = root["error"]?.let { e ->
            val eo = runCatching { e.jsonObject }.getOrNull() ?: return@let null
            Error(
                code = runCatching { eo["code"]!!.jsonPrimitive.intOrZero() }.getOrDefault(0),
                message = runCatching { eo["message"]!!.jsonPrimitive.content }.getOrDefault("unknown"),
                data = eo["data"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
            )
        }
        return Message.Response(id, root["result"], error)
    }

    private fun JsonPrimitive.intOrZero(): Int = runCatching { content.toInt() }.getOrDefault(0)
}