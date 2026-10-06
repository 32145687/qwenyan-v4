package com.qianyan.runtime.contract

import java.nio.file.Path

/**
 * Qianyan Runtime Contract（I1 · 最小抽象，**vendor-neutral**）。
 *
 * 定位：
 * ```
 * Qianyan（Application / Agent）
 *        ↓  只用本契约
 * Qianyan Runtime Adapter（当前实现：DSH ACP，位于 :runtime:dsh）
 *        ↓
 * 外部 Agent Runtime
 * ```
 *
 * 与既有抽象的关系（**不是第二套重复抽象**）：
 *  - `:agent:runtime.AgentRuntime` = Qianyan **内部** Agent Loop（LLM → Tool → LLM → Final），本阶段不改；
 *  - `:provider:api.LLMGateway` = **单次** completion 能力，本阶段不改（Provider 边界保持独立）；
 *  - 本契约 = **外部 Agent Runtime 会话**（启动 / 建会话 / 发任务 / 取消 / 取最终结果 / 关闭）。
 *
 * 硬边界（I1 收敛）：
 *  - **零依赖**：不依赖 `core:model` / `provider:api` / 任何 Adapter；
 *  - **零厂商概念**：不出现 DSH / ACP / JSON-RPC / `dshSessionId` 等实现或供应商词汇（含错误文案）；
 *  - **零业务概念**：不含 ProjectId / NovelId / TaskId；不进入 Storage 领域模型。
 */
interface AgentRuntimeGateway {

    /** 启动 Runtime 载体（当前 = 外部 Agent 子进程），返回句柄。 */
    fun start(): RuntimeHandle

    /** 新建一个 Runtime Session。 */
    fun createSession(request: RuntimeSessionRequest): RuntimeSession

    /** 向 Session 发送一次 prompt，消费 update，返回最终结果。 */
    fun prompt(session: RuntimeSession, prompt: String): RuntimeRunResult

    /**
     * 取消 Session 上正在进行的工作（幂等）。
     * 无进行中工作时应是空操作；本方法**不等待**取消结算（结算是 prompt 侧的事）。
     */
    fun cancel(session: RuntimeSession)

    /**
     * 按 sessionId 恢复 Session。
     *
     * I1：调用路径与 typed 失败已建立，但真实 resume 语义**仍未验证**（`resume = NOT YET VERIFIED`）。
     */
    fun resume(sessionId: String): RuntimeSession

    /** 关闭 Session（幂等）。 */
    fun close(session: RuntimeSession)

    /** 关闭 Runtime 载体与子进程（幂等；不泄漏进程）。 */
    fun shutdown()
}

/** Runtime 载体句柄（进程级）。 */
interface RuntimeHandle {
    val runtimeName: String
    val alive: Boolean

    /** 载体诊断输出（stderr 等）；**不混入 protocol 流**。 */
    fun diagnostics(): List<String>

    fun shutdown()
}

/** 新建 Session 的请求（只放 Runtime 层关心的输入，不放业务对象）。 */
data class RuntimeSessionRequest(
    val workingDirectory: Path? = null,
    val model: String? = null,
)

/** Runtime Session 句柄：Qianyan 侧仅持有 runtimeSessionId（不是 Qianyan AgentSession）。 */
data class RuntimeSession(
    val sessionId: String,
    val workingDirectory: Path? = null,
)

/** 一次 prompt 的最终结果。 */
data class RuntimeRunResult(
    val sessionId: String,
    val finalText: String,
    val updates: List<RuntimeUpdate>,
    val stopReason: RuntimeStopReason = RuntimeStopReason.UNKNOWN,
    /** 原始 stopReason 文本（未知值兜底；Application 不应依赖它做判断）。 */
    val rawStopReason: String? = null,
)

/**
 * Runtime 流事件的**类型化**类别（Qianyan-owned / runtime-neutral）。
 *
 * Application 只应判断本枚举，**不得**判断实现侧原始字符串（如 `agent_message_chunk` / `usage_update`）。
 */
enum class RuntimeUpdateKind {
    /** assistant 已提交消息分片（最终答案的来源）。 */
    MESSAGE,

    /** assistant 思考分片。 */
    THOUGHT,

    /** 工具生命周期事件（开始 / 更新 / 结束）。 */
    TOOL_CALL,

    /** 计划更新。 */
    PLAN,

    /** 上下文 / token 用量更新。 */
    USAGE,

    /** 未识别类别（保留 [RuntimeUpdate.rawKind] 兜底）。 */
    OTHER,
    ;

    companion object {
        /** 原始值 → 类型化类别（未知 → [OTHER]，绝不抛错）。 */
        fun fromRaw(raw: String?): RuntimeUpdateKind = when (raw) {
            "agent_message_chunk" -> MESSAGE
            "agent_thought_chunk" -> THOUGHT
            "tool_call", "tool_call_update" -> TOOL_CALL
            "plan" -> PLAN
            "usage_update" -> USAGE
            else -> OTHER
        }
    }
}

/** Runtime 结束原因（Qianyan-owned / runtime-neutral）。 */
enum class RuntimeStopReason {
    /** 模型正常结束本轮。 */
    END_TURN,

    /** 达到 token 上限。 */
    MAX_TOKENS,

    /** 达到轮次请求上限。 */
    MAX_TURN_REQUESTS,

    /** 模型拒绝。 */
    REFUSAL,

    /** 被取消。 */
    CANCELLED,

    /** 未识别（保留 [RuntimeRunResult.rawStopReason] 兜底）。 */
    UNKNOWN,
    ;

    companion object {
        /** 原始值 → 类型化结束原因（未知 → [UNKNOWN]，绝不抛错）。 */
        fun fromRaw(raw: String?): RuntimeStopReason = when (raw) {
            "end_turn" -> END_TURN
            "max_tokens" -> MAX_TOKENS
            "max_turn_requests" -> MAX_TURN_REQUESTS
            "refusal" -> REFUSAL
            "cancelled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/**
 * 一次 Runtime 流事件（I1：只做类型化映射，投影到既有 Activity 由 Application 负责）。
 *
 * [rawKind] 原样保留实现侧取值，供排障；Application **不应**据此判断业务分支。
 */
data class RuntimeUpdate(
    val kind: RuntimeUpdateKind,
    val rawKind: String,
    val text: String? = null,
)

/* ---------------- Permission（单向应答端口） ---------------- */

/**
 * 一次权限请求（vendor-neutral：只暴露会话与可读描述，不含实现侧 option 结构）。
 *
 * 语义（I1 硬约束）：用户**永远只在 Qianyan 层**被询问一次。
 * Adapter 收到本请求后只调用 [RuntimePermissionResponder]，绝不自行弹窗/询问用户。
 */
data class RuntimePermissionRequest(
    val runtimeSessionId: String,
    val title: String? = null,
    val detail: String? = null,
)

/** 权限应答（二元）：已在 Qianyan 层完成决策后的结果，Adapter 据此翻译为对端 allow / reject。 */
enum class RuntimePermissionOutcome {
    ALLOW,
    REJECT,
}

/**
 * 权限应答端口（Application 注入）。
 *
 * 契约语义：
 *  - Qianyan `Allowed` → 返回 [RuntimePermissionOutcome.ALLOW]；
 *  - Qianyan `Denied`  → 返回 [RuntimePermissionOutcome.REJECT]；
 *  - Qianyan `NeedsHuman` → **先**在 Qianyan 走 Human Gate，用户确认一次后再返回对应结果。
 */
fun interface RuntimePermissionResponder {
    fun decide(request: RuntimePermissionRequest): RuntimePermissionOutcome
}

/* ---------------- typed 失败 ---------------- */

/**
 * Runtime 层 typed 失败（不把所有失败压成一句 "failed"）。
 *
 * **文案 vendor-neutral**：实现侧（供应商）细节只允许留在 Adapter 的 diagnostics（stderr）中。
 */
sealed class RuntimeError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 可执行文件不存在 / 进程无法启动。 */
    class StartupFailed(message: String, cause: Throwable? = null) : RuntimeError(message, cause)

    /** Framing / 消息结构错误（含非法 JSON 行）。 */
    class ProtocolViolation(message: String, cause: Throwable? = null) : RuntimeError(message, cause)

    /** 对端返回 JSON-RPC error 响应。 */
    class ServerError(val code: Int, val serverMessage: String, val data: String? = null) :
        RuntimeError("runtime error response: $code $serverMessage")

    /** 等待超时（请求 / 启动）。 */
    class TimedOut(val operation: String, val timeoutMillis: Long) :
        RuntimeError("runtime $operation timed out (${timeoutMillis}ms)")

    /** 流意外结束 / 进程提前退出。 */
    class StreamClosed(val operation: String, val exitCode: Int?) :
        RuntimeError("runtime stream closed ($operation, exitCode=$exitCode)")

    /** 已在关闭 / 未启动状态下使用。 */
    class NotAvailable(message: String) : RuntimeError(message)
}