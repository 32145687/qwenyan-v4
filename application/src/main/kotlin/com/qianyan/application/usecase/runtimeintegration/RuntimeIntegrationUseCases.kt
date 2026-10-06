package com.qianyan.application.usecase.runtimeintegration

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.model.AgentSessionId
import com.qianyan.model.log.Activity
import com.qianyan.runtime.contract.AgentRuntimeGateway
import com.qianyan.runtime.contract.RuntimeError
import com.qianyan.runtime.contract.RuntimeHandle
import com.qianyan.runtime.contract.RuntimeRunResult
import com.qianyan.runtime.contract.RuntimeSession
import com.qianyan.runtime.contract.RuntimeSessionRequest
import java.nio.file.Path

/**
 * I1 · Runtime Integration（**生命周期桥接**：Qianyan AgentSession ↔ 外部 Runtime Session）。
 *
 * 依据：I0 Final Architecture Review §7（I1 Scope）/ §6（Provider Ownership）。
 *
 * 链路（**只是 seam**，不重写任何既有编排）：
 * ```
 * Qianyan AgentSession（既有 I3 身份与生命周期）
 *        ↓  本类（Integration 层）
 * Runtime Session（契约 :runtime:api，实现由组合根注入，如 DSH Adapter）
 *        ↓
 * 既有 I4 Activity（把"AI 做了什么"落成事实记录）
 * ```
 *
 * 硬边界：
 *  - **只依赖契约**：不 import 任何 Adapter / 供应商类型（DSH / ACP / JSON-RPC）；
 *  - **不重设计 AgentSession 状态机**：本类只**调用**既有 I3 生命周期，不新增状态词汇；
 *  - **不重写 NovelAgent 七相位**：本类不参与相位编排；
 *  - **不碰 Change Layer**：Runtime 输出仍必须经既有 Draft → Validation → Diff → Change → Artifact → Commit；
 *  - **Canonical 永不被 Runtime 直接修改**：本类不提供任何写入 Canonical 的路径；
 *  - **不建第二套 History / Activity 数据库**：事件只投影到既有 I4 Activity；
 *  - **Provider 边界不变**：本类不含任何 LLM/Provider 调用（模型调用归 Adapter 与对端）。
 */
class RuntimeIntegrationUseCases(
    /** 运行时契约（null = 本机/本次装配未启用外部 Runtime；此时所有操作类型化拒绝）。 */
    private val gateway: AgentRuntimeGateway?,
    private val sessions: AgentSessionUseCases,
    private val bindings: RuntimeSessionBindingUseCases,
    private val activities: ActivityUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 是否已装配外部 Runtime。 */
    val available: Boolean get() = gateway != null

    /**
     * 启动运行时载体（幂等）。返回 Runtime 标识（vendor-neutral 不透明串，来自契约句柄）。
     * 未装配 → [ApplicationError.RuntimeUnavailable]。
     */
    fun start(): String = startedHandle().runtimeName

    /**
     * 为既有 AgentSession 建立一次 Runtime 会话，并写入绑定（`1 : N`，每次调用新增一条）。
     * 同时在既有 Activity 上记录一次可追踪的打开动作。
     */
    fun createRuntimeSession(agentSessionId: AgentSessionId, workingDirectory: Path? = null): RuntimeSession {
        sessions.sessionOf(agentSessionId) // 悬挂引用早失败（既有类型化错误）
        val handle = startedHandle()
        val activity = activities.start(agentSessionId, KIND_SESSION_OPEN, "打开外部运行时会话")
        return try {
            val session = runtime { handleRuntime().createSession(RuntimeSessionRequest(workingDirectory = workingDirectory)) }
            bindings.bind(agentSessionId, handle.runtimeName, session.sessionId)
            activities.complete(activity.activityId, "runtime=${handle.runtimeName}")
            session
        } catch (e: ApplicationException) {
            activities.fail(activity.activityId, e.message ?: "runtime session open failed")
            throw e
        }
    }

    /**
     * 经由**最近一次绑定**的 Runtime 会话发送一次 prompt。
     *
     * 事件投影：本次 prompt 落一条既有 Activity；完成后以 update 类别摘要收口（失败则记 failure）。
     */
    fun prompt(agentSessionId: AgentSessionId, prompt: String): RuntimeRunResult {
        val ref = requireLatestBinding(agentSessionId)
        val activity = activities.start(agentSessionId, KIND_PROMPT, "runtime prompt")
        return try {
            val run = runtime { handleRuntime().prompt(RuntimeSession(sessionId = ref.runtimeSessionId), prompt) }
            activities.complete(activity.activityId, summarize(run))
            run
        } catch (e: ApplicationException) {
            activities.fail(activity.activityId, e.message ?: "runtime prompt failed")
            throw e
        }
    }

    /** 取消最近一次绑定会话上的工作进行中工作（幂等；无绑定则类型化拒绝）。 */
    fun cancel(agentSessionId: AgentSessionId) {
        val ref = requireLatestBinding(agentSessionId)
        runtime { handleRuntime().cancel(RuntimeSession(sessionId = ref.runtimeSessionId)) }
    }

    /** 关闭最近一次绑定会话（幂等）。 */
    fun closeSession(agentSessionId: AgentSessionId) {
        val ref = bindings.latest(agentSessionId) ?: return
        handleRuntime().close(RuntimeSession(sessionId = ref.runtimeSessionId))
    }

    /** 关闭运行时载体（幂等；不泄漏子进程）。 */
    fun shutdown() {
        gateway?.shutdown()
        handle = null
    }

    /** 某会话的绑定（新到旧）——供 UI / 审计读取。 */
    fun bindingsOf(agentSessionId: AgentSessionId) = bindings.bindingsOf(agentSessionId)

    /** 最近一次绑定的活动记录（供 UI 展示"AI 做了什么"）。 */
    fun activitiesOf(agentSessionId: AgentSessionId): List<Activity> = activities.listBySession(agentSessionId)

    /* ---------------- internals ---------------- */

    private var handle: RuntimeHandle? = null

    private fun startedHandle(): RuntimeHandle {
        handle?.let { return it }
        val started = runtime { handleRuntime().start() }
        handle = started
        return started
    }

    private fun handleRuntime(): AgentRuntimeGateway =
        gateway ?: throw ApplicationException(ApplicationError.RuntimeUnavailable("外部 Agent Runtime 未装配"))

    private fun requireLatestBinding(agentSessionId: AgentSessionId) =
        bindings.latest(agentSessionId) ?: throw ApplicationException(
            ApplicationError.EntityNotFound("AgentSession ${agentSessionId.value} 尚无 Runtime 会话绑定"),
        )

    /** 运行时 typed 失败 → Application 领域错误（供应商细节不进领域错误文案）。 */
    private fun <T> runtime(block: () -> T): T =
        try {
            block()
        } catch (e: RuntimeError) {
            throw ApplicationException(toApplicationError(e))
        }

    private fun toApplicationError(e: RuntimeError): ApplicationError = when (e) {
        is RuntimeError.NotAvailable, is RuntimeError.StartupFailed ->
            ApplicationError.RuntimeUnavailable(e.message ?: "runtime unavailable")
        else -> ApplicationError.RuntimeExecutionFailed(e.message ?: "runtime execution failed")
    }

    /** 把一次 run 的 update 类别收敛为可读摘要（不落 Transcript，只落既有 Activity 文本字段）。 */
    private fun summarize(run: RuntimeRunResult): String {
        val kinds = run.updates.map { it.kind }.distinct().joinToString(",")
        return "updates=${run.updates.size} kinds=[$kinds] stop=${run.stopReason} chars=${run.finalText.length}"
    }

    private companion object {
        const val KIND_SESSION_OPEN = "runtime.session.open"
        const val KIND_PROMPT = "runtime.prompt"
    }
}