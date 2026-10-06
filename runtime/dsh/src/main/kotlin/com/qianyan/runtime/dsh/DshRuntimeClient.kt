package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.AgentRuntimeGateway
import com.qianyan.runtime.contract.RuntimeError
import com.qianyan.runtime.contract.RuntimeHandle
import com.qianyan.runtime.contract.RuntimePermissionOutcome
import com.qianyan.runtime.contract.RuntimePermissionRequest
import com.qianyan.runtime.contract.RuntimePermissionResponder
import com.qianyan.runtime.contract.RuntimeRunResult
import com.qianyan.runtime.contract.RuntimeSession
import com.qianyan.runtime.contract.RuntimeSessionRequest
import com.qianyan.runtime.contract.RuntimeStopReason
import com.qianyan.runtime.contract.RuntimeUpdate
import com.qianyan.runtime.contract.RuntimeUpdateKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * DSH ACP Runtime Adapter（I1）。
 *
 * 最小 ACP 调用顺序（严格按序）：
 * ```
 * start process → initialize → session/new → session/prompt → 消费 session/update
 *               → prompt settlement → 取最终 assistant 输出 → session/close / session/cancel
 *               → shutdown
 * ```
 * 本阶段**不**做：MCP / Skill / Tool Runtime 产品化 / Subagent / Model routing /
 * Context 注入 / Change Layer 接入（全部记 NEXT PHASE）。
 *
 * 方向约束（I1 §九）：对端 `session/request_permission` **只**经 [permissionResponder] 代答，
 * Adapter 绝不自行询问用户 —— 用户永远只在 Qianyan 层被问一次。
 *
 * 线程模型：单 reader 线程逐行解析；响应按 `id` 派发到各自等待队列；通知按 sessionId 归集；
 * 对端请求在 reader 线程内同步应答（写操作经 [DshProcess.send] 同步）。
 */
class DshRuntimeClient(
    private val config: DshRuntimeConfig = DshRuntimeConfig.resolveDefault(),
    /**
     * 权限应答端口（可为空 = fail-closed：优先选择拒绝选项，无法拒绝则回 `cancelled`）。
     * 由组合根注入；Application 只提供 vendor-neutral 的决策回调。
     */
    private val permissionResponder: RuntimePermissionResponder? = null,
) : AgentRuntimeGateway {

    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, ArrayBlockingQueue<DshJsonRpc.Message.Response>>()
    private val updatesBySession = ConcurrentHashMap<String, MutableList<RuntimeUpdate>>()
    private var process: DshProcess? = null
    private var started = false
    private var initialized = false

    /** 最近一次致命失败（EOF / 非法帧）：让等待中的请求抛**精确**的 typed 失败而非泛化超时。 */
    private val lastFailure = java.util.concurrent.atomic.AtomicReference<RuntimeError?>(null)

    val runtimeName: String = RUNTIME_NAME

    /** 最近一次启动的进程诊断（stderr）——测试与排障用，**不是** protocol 流。 */
    fun diagnostics(): List<String> = process?.diagnostics().orEmpty()

    override fun start(): RuntimeHandle {
        if (started) return handle()
        val proc = DshProcess(
            config = config,
            onLine = { line -> dispatch(line) },
            onClosed = { code -> failPending(RuntimeError.StreamClosed("stdout", code)) },
        )
        proc.start()
        process = proc
        started = true
        return handle()
    }

    private fun handle(): RuntimeHandle = object : RuntimeHandle {
        override val runtimeName: String get() = this@DshRuntimeClient.runtimeName
        override val alive: Boolean get() = process?.alive == true
        override fun diagnostics(): List<String> = this@DshRuntimeClient.diagnostics()
        override fun shutdown() = this@DshRuntimeClient.shutdown()
    }

    /** initialize：客户端能力声明（只声明最小能力，不声称支持未实现特性）。幂等。 */
    fun initialize(): JsonElement? {
        val result = request(
            method = METHOD_INITIALIZE,
            params = buildJsonObject {
                put("protocolVersion", ACP_PROTOCOL_VERSION)
                put("clientCapabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", "qianyan")
                    put("version", "0.1.0-i1")
                })
            },
            timeoutMillis = config.requestTimeoutMillis,
        )
        initialized = true
        return result
    }

    override fun createSession(request: RuntimeSessionRequest): RuntimeSession {
        // 契约不暴露握手：首次会话操作自动完成 initialize（ACP 顺序由 Adapter 内部保证）
        if (!initialized) initialize()
        val params = buildJsonObject {
            request.workingDirectory?.let { put("cwd", it.toString()) }
            request.model?.let { put("model", it) }
            put("mcpServers", JsonArray(emptyList()))
        }
        val result = request(METHOD_SESSION_NEW, params, config.requestTimeoutMillis)
        val sessionId = result?.jsonObject?.get("sessionId")?.jsonPrimitive?.contentOrNull()
            ?: throw RuntimeError.ProtocolViolation("session/new 未返回 sessionId")
        updatesBySession[sessionId] = java.util.Collections.synchronizedList(mutableListOf())
        return RuntimeSession(sessionId, request.workingDirectory)
    }

    override fun prompt(session: RuntimeSession, prompt: String): RuntimeRunResult {
        val params = buildJsonObject {
            put("sessionId", session.sessionId)
            put("prompt", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                })
            })
        }
        val result = request(METHOD_SESSION_PROMPT, params, config.requestTimeoutMillis)
        val updates = updatesBySession[session.sessionId]?.toList().orEmpty()
        val rawStop = result?.jsonObject?.get("stopReason")?.jsonPrimitive?.contentOrNull()
        return RuntimeRunResult(
            sessionId = session.sessionId,
            finalText = extractFinalText(result, updates),
            updates = updates,
            stopReason = RuntimeStopReason.fromRaw(rawStop),
            rawStopReason = rawStop,
        )
    }

    /**
     * 取消 Session 上正在进行的工作。
     *
     * ACP 中 `session/cancel` 是**通知**（无响应，不等待结算）；未启动时为幂等空操作。
     */
    override fun cancel(session: RuntimeSession) {
        val proc = process ?: return
        proc.send(
            DshJsonRpc.encodeNotification(
                METHOD_SESSION_CANCEL,
                buildJsonObject { put("sessionId", session.sessionId) },
            ),
        )
    }

    /** I1：resume 调用路径存在但语义**未验证**（不做跨 process restart 恢复断言）。 */
    override fun resume(sessionId: String): RuntimeSession =
        throw RuntimeError.NotAvailable("runtime session resume 在 I1 未验证（resume = NOT YET VERIFIED）")

    override fun close(session: RuntimeSession) {
        if (process == null) return
        runCatching {
            request(METHOD_SESSION_CLOSE, buildJsonObject { put("sessionId", session.sessionId) }, config.requestTimeoutMillis)
        }
    }

    override fun shutdown() {
        failPending(RuntimeError.NotAvailable("runtime shutdown"))
        process?.destroy()
        process = null
        started = false
        initialized = false
        lastFailure.set(null)
        updatesBySession.clear()
    }

    /* ---------------- 内部：请求 / 派发 ---------------- */

    private fun request(method: String, params: JsonElement?, timeoutMillis: Long): JsonElement? {
        val proc = process ?: throw RuntimeError.NotAvailable("Runtime 进程尚未启动（先调用 start()）")
        val id = nextId.incrementAndGet()
        val queue = ArrayBlockingQueue<DshJsonRpc.Message.Response>(1)
        pending[id] = queue
        try {
            proc.send(DshJsonRpc.encodeRequest(id, method, params))
            val response = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                ?: throw (lastFailure.get() ?: RuntimeError.TimedOut(method, timeoutMillis))
            response.error?.let { error ->
                if (error.code == INTERNAL_FAILURE_CODE) {
                    throw (lastFailure.get() ?: RuntimeError.StreamClosed(method, proc.exitCode))
                }
                throw RuntimeError.ServerError(error.code, error.message, error.data)
            }
            return response.result
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RuntimeError.StreamClosed(method, proc.exitCode)
        } finally {
            pending.remove(id)
        }
    }

    private fun dispatch(line: String) {
        val message = try {
            DshJsonRpc.parseLine(line)
        } catch (e: RuntimeError) {
            // 非法帧：不能让 reader 线程静默死亡，必须收敛为 typed 失败并唤醒等待者
            failPending(e)
            return
        } ?: return
        when (message) {
            is DshJsonRpc.Message.Response -> pending.remove(message.id)?.offer(message)
            is DshJsonRpc.Message.Notification -> recordNotification(message)
            is DshJsonRpc.Message.Request -> handleServerRequest(message)
        }
    }

    /**
     * 对端（server → client）请求：I1 只映射 `session/request_permission`。
     *
     * 未映射的请求**显式回错误**（不回错误会让对端永久挂起）。
     */
    private fun handleServerRequest(request: DshJsonRpc.Message.Request) {
        val id = request.id ?: return
        val proc = process ?: return
        val line = if (request.method == METHOD_REQUEST_PERMISSION) {
            DshJsonRpc.encodeResponse(id, permissionResult(request.params))
        } else {
            DshJsonRpc.encodeErrorResponse(id, METHOD_NOT_FOUND, "unsupported server request: ${request.method}")
        }
        runCatching { proc.send(line) }
    }

    /**
     * 把一次权限请求翻译为 ACP 应答。
     *
     * 规则（I1 §九）：Qianyan 决策 → ALLOW / REJECT；Adapter 只负责选对应 option。
     * 无应答端口或找不到目标 option ⇒ `cancelled`（fail-closed，绝不默认放行）。
     */
    private fun permissionResult(params: JsonElement?): JsonElement {
        val obj = runCatching { params?.jsonObject }.getOrNull()
        val sessionId = obj?.get("sessionId")?.jsonPrimitive?.contentOrNull().orEmpty()
        val options = obj?.get("options")?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        val detail = obj?.get("toolCall")?.jsonObject?.get("toolCallId")?.jsonPrimitive?.contentOrNull()

        val outcome = permissionResponder
            ?.decide(RuntimePermissionRequest(runtimeSessionId = sessionId, detail = detail))
            ?: RuntimePermissionOutcome.REJECT
        val optionId = pickOptionId(options, outcome)
            ?: return buildJsonObject { put("outcome", buildJsonObject { put("outcome", OUTCOME_CANCELLED) }) }
        return buildJsonObject {
            put(
                "outcome",
                buildJsonObject {
                    put("outcome", OUTCOME_SELECTED)
                    put("optionId", optionId)
                },
            )
        }
    }

    /** 依 kind 前缀（allow / reject）选择 option；找不到返回 null（调用方回 cancelled）。 */
    private fun pickOptionId(options: JsonArray, outcome: RuntimePermissionOutcome): String? {
        val wanted = if (outcome == RuntimePermissionOutcome.ALLOW) "allow" else "reject"
        return options.asSequence()
            .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .firstOrNull { o ->
                (o["kind"]?.jsonPrimitive?.contentOrNull() ?: "").startsWith(wanted)
            }
            ?.get("optionId")
            ?.jsonPrimitive
            ?.contentOrNull()
    }

    private fun recordNotification(notification: DshJsonRpc.Message.Notification) {
        if (notification.method != METHOD_SESSION_UPDATE) return
        val params = notification.params?.jsonObject ?: return
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull() ?: return
        val update = params["update"]?.jsonObject
        val rawKind = update?.get("sessionUpdate")?.jsonPrimitive?.contentOrNull()
            ?: params["sessionUpdate"]?.jsonPrimitive?.contentOrNull()
            ?: UNKNOWN_UPDATE_KIND
        val text = extractText(update)
        updatesBySession.getOrPut(sessionId) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add(RuntimeUpdate(kind = RuntimeUpdateKind.fromRaw(rawKind), rawKind = rawKind, text = text))
    }

    /** 从 `content`（数组或对象）或 `text` 取文本。 */
    private fun extractText(node: JsonObject?): String? {
        if (node == null) return null
        node["text"]?.jsonPrimitive?.contentOrNull()?.let { return it }
        val content = node["content"] ?: return null
        return when (content) {
            is JsonArray -> content.mapNotNull { it.jsonObjectOrNull()?.let { o -> extractText(o) } }.joinToString("").ifBlank { null }
            is JsonObject -> extractText(content)
            else -> content.jsonPrimitive.contentOrNull()
        }
    }

    /** 最终文本：优先 prompt 响应内联文本，否则回落到 session/update 累积的 assistant 文本。 */
    private fun extractFinalText(result: JsonElement?, updates: List<RuntimeUpdate>): String {
        val fromResult = (result as? JsonObject)?.let { extractText(it) }
        if (!fromResult.isNullOrBlank()) return fromResult
        return updates.filter { it.kind == RuntimeUpdateKind.MESSAGE }
            .mapNotNull { it.text }
            .joinToString("")
    }

    private fun failPending(failure: RuntimeError) {
        lastFailure.compareAndSet(null, failure)
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.offer(
                DshJsonRpc.Message.Response(id, null, DshJsonRpc.Error(INTERNAL_FAILURE_CODE, failure.message ?: "runtime failure")),
            )
        }
    }

    companion object {
        /** Runtime 载体标识（写入 runtimeSessionRef.runtimeName；vendor-neutral 字段，非领域概念）。 */
        const val RUNTIME_NAME = "dsh-acp"

        /** 内部哨兵：表示"由 Runtime 层失败唤醒"，具体异常取 lastFailure。 */
        const val INTERNAL_FAILURE_CODE = -32_000

        /** ACP v1 协议版本。 */
        const val ACP_PROTOCOL_VERSION = 1

        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_SESSION_NEW = "session/new"
        const val METHOD_SESSION_PROMPT = "session/prompt"
        const val METHOD_SESSION_CANCEL = "session/cancel"
        const val METHOD_SESSION_CLOSE = "session/close"
        const val METHOD_SESSION_UPDATE = "session/update"
        const val METHOD_REQUEST_PERMISSION = "session/request_permission"
        const val METHOD_NOT_FOUND = -32601
        const val UNKNOWN_UPDATE_KIND = "update"
        const val OUTCOME_SELECTED = "selected"
        const val OUTCOME_CANCELLED = "cancelled"
    }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()