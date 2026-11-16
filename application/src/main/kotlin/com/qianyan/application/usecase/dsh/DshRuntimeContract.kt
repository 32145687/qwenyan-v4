package com.qianyan.application.usecase.dsh

/**
 * DSH-04 · Runtime Adapter 契约（纯接口与模型，不接任何生产路径）。
 *
 * 本包只描述"运行时能做什么"：打开会话 → 派发任务 → 观察事件 / 取消，
 * 以及权限请求与应答。**不得**出现任何小说领域概念（见
 * `docs/project-control/dsh-runtime-contract.md` 与源码扫描守卫测试）。
 *
 * 事件通道选择 `Sequence`：事件由假件/适配器同步拉取，测试可精确控制消费进度
 * （消费 N 条后取消），且不引入协程/Flow 依赖；取消语义由 [RuntimeHandle.cancel] 显式表达。
 */

/** 运行时事件类型（固定枚举；与文档事件表一一对应）。 */
enum class RuntimeEventKind {
    PHASE_START,
    PHASE_END,
    SKILL_SELECTED,
    TOOL_CALL,
    TOOL_RESULT,
    MODEL_CALL,
    CANDIDATE_READY,
    GATE_WAIT,
    CANCELLED,
    FAILED,
}

/**
 * 一条运行时事件。
 *
 * @param kind 事件类型。
 * @param name 事件主体名（技能名 / 工具名 / 模型名等，不透明字符串）。
 * @param detailSummary 摘要，**≤ [MAX_SUMMARY] 字**；超长必须经 [summarize] 截断。
 * @param resultCount 结果条数（工具返回 / 候选数量等；不适用为 null）。
 * @param durationMillis 耗时毫秒（不适用为 null）。
 * @param ok 结果成功与否（不适用为 null）。
 */
data class RuntimeEvent(
    val kind: RuntimeEventKind,
    val name: String,
    val detailSummary: String,
    val resultCount: Int? = null,
    val durationMillis: Long? = null,
    val ok: Boolean? = null,
) {
    init {
        require(detailSummary.length <= MAX_SUMMARY) {
            "detailSummary 不得超过 $MAX_SUMMARY 字（实际 ${detailSummary.length}）"
        }
    }

    companion object {
        /** 摘要字符上限。 */
        const val MAX_SUMMARY: Int = 80

        /** 超长摘要保留前 MAX_SUMMARY-1 字并追加 `…`，保证结果恰好 ≤ [MAX_SUMMARY]。 */
        fun summarize(text: String): String =
            if (text.length <= MAX_SUMMARY) text else text.take(MAX_SUMMARY - 1) + "…"
    }
}

/**
 * 一次派发请求。
 *
 * @param input 用户/上游输入原文。
 * @param allowedTools 允许的工具名（字符串，不含业务类型）。
 * @param maxRounds 最大轮次。
 * @param budgetMillis 时间预算（毫秒）。
 */
data class RuntimeTask(
    val input: String,
    val allowedTools: List<String>,
    val maxRounds: Int,
    val budgetMillis: Long,
)

/** 一次运行的事件句柄：拉取事件 + 取消。 */
interface RuntimeHandle {
    /** 事件流（同步拉取；取消后以 CANCELLED 结束，之后无事件）。 */
    fun events(): Sequence<RuntimeEvent>

    /** 请求取消；幂等。 */
    fun cancel()
}

/** 一个运行时会话：派发任务获得句柄。 */
interface RuntimeSession {
    /** 会话标识（不透明字符串，同一 workflowRef 应可重复）。 */
    val sessionId: String

    fun dispatch(task: RuntimeTask): RuntimeHandle
}

/** 运行时入口：以不透明 workflowRef 打开会话。 */
interface DshRuntime {
    fun openSession(workflowRef: String): RuntimeSession
}

/** 权限请求（运行中需要人工裁决的动作）。 */
data class PermissionRequest(
    val requestId: String,
    val action: String,
    val summary: String,
)

/** 权限应答三态。 */
enum class PermissionResponse {
    APPROVED,
    DENIED,
    TIMEOUT,
}
