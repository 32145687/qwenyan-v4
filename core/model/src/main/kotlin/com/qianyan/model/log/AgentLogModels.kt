package com.qianyan.model.log

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.agent.ToolName
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * Novel IDE · I4：Activity / Tool Call Log（已发生行为的事实记录）。
 *
 * 定位（依据 docs/architecture/qianyan-novel-ide-architecture.md §15）：
 *   记录 Agent / Session 在一次工作过程中发生了什么活动、调用了什么 Tool。
 *   后续 I5 Product Tools / I6 Context Engine / I7 Skills / I11 Novel Agent 都依赖这个基础。
 *
 * 严格边界：
 *  - **是记录，不是控制器**：不改写 AgentSession / Workflow / Task 状态，也不创建它们；
 *  - **不是 Workflow / Task / Session 状态机的副本**：`status` 只描述"这条记录本身是否完成"；
 *  - **不是 Tool Registry**：`toolName` 只是调用标识（复用既有 [ToolName]），无实现/权限/注册语义；
 *  - **不承载**：正文 / World Model / Project State / Context 内容 / Skill / Permission
 *      （分别属既有模块或后续阶段）；
 *  - I4 只建立记录能力，**不执行 Tool**（Runtime Integration = DEFERRED）。
 */

/**
 * 记录状态（**复用既有生命周期词汇**：与 `TaskStatus` 的 RUNNING/COMPLETED/FAILED/CANCELLED 一致的子集，
 * 不引入第二套状态词汇）。只描述"记录本身是否完成"。
 */
@Serializable
enum class AgentLogStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    /** 终态：不可再转换。 */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/**
 * 记录生命周期规则（**纯确定性**，无 IO / 无 LLM / 无随机 / 无时间判断）。
 *
 * ```
 * RUNNING → COMPLETED | FAILED | CANCELLED
 * COMPLETED / FAILED / CANCELLED → （终态，不可再转换）
 * ```
 * 同状态重复转换视为幂等（返回 true，由调用方决定是否落库）。
 * 该规则同时约束 [Activity] 与 [ToolCallLog]，是唯一的记录生命周期体系。
 */
object AgentLogLifecycleRules {

    /** 允许的目标状态集合。 */
    fun allowedTargets(from: AgentLogStatus): Set<AgentLogStatus> = when (from) {
        AgentLogStatus.RUNNING -> setOf(AgentLogStatus.COMPLETED, AgentLogStatus.FAILED, AgentLogStatus.CANCELLED)
        AgentLogStatus.COMPLETED, AgentLogStatus.FAILED, AgentLogStatus.CANCELLED -> emptySet()
    }

    /** [from] → [to] 是否合法（同状态幂等；终态不可再转换）。 */
    fun canTransition(from: AgentLogStatus, to: AgentLogStatus): Boolean =
        from == to || to in allowedTargets(from)
}

/**
 * 一次可追踪活动（AgentSession 中发生的行为事实）。
 *
 * `kind` 是活动类别标签（如 "CONTEXT_RESEARCH" / "WRITING"）；**词汇表属未来 Skill / Agent 编排阶段**
 * （I7 / I11），I4 不定义受控分类，只按标签记录。
 */
@Serializable
data class Activity(
    val activityId: ActivityId,
    val sessionId: AgentSessionId,
    val projectId: ProjectId,
    val kind: String,
    val status: AgentLogStatus = AgentLogStatus.RUNNING,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val summary: String? = null,
    val error: String? = null,
)

/**
 * 一次 Tool 调用事实记录（属于某个 [Activity]）。
 *
 * [toolName] 复用既有 `ToolName` 标识；**不含**工具实现 / 注册 / 权限语义（那是 I5 Tool Registry 的事）。
 */
@Serializable
data class ToolCallLog(
    val toolCallId: ToolCallId,
    val activityId: ActivityId,
    val sessionId: AgentSessionId,
    val projectId: ProjectId,
    val toolName: ToolName,
    val status: AgentLogStatus = AgentLogStatus.RUNNING,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val inputSummary: String? = null,
    val outputSummary: String? = null,
    val error: String? = null,
)