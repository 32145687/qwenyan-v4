package com.qianyan.model.session

import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.workflow.WorkflowId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * Novel IDE · I3：Agent Session（一次持续 Agent 工作的可持久化会话边界）。
 *
 * 定位（依据 docs/architecture/qianyan-novel-ide-architecture.md §13）：
 *   回答"当前是哪一次 Agent 工作 / 属于哪个 Project / 关联哪个 Workflow·Task / 是否还能继续"。
 *
 * 严格边界：
 *  - **不是第二套 Workflow / Task**：Session 状态只描述**会话身份**的生命周期，
 *      绝不写入、也不复制 Workflow / Task 的生命周期事实（后者仍以 Workflow / Task / Checkpoint 为准 —— FD-2）；
 *  - **不承载**：小说正文 / Novel World Model / Project State / Tool Log / Activity Log / Context 内容 /
 *      Skill / Permission / Human Gate（分别属既有模块或后续阶段）；
 *  - **不新建第二套状态机**：状态取值沿用既有生命周期词汇（CREATED/ACTIVE/PAUSED/COMPLETED/FAILED/CANCELLED），
 *      转换由确定性规则约束，且与 Workflow 状态互不写入；
 *  - I3 只建立"可持久化 + 可查询 + 可恢复身份"，**不实现 Resume Engine**（真正的恢复执行仍由既有 Workflow / Checkpoint 负责）。
 */

/**
 * 会话身份的生命周期状态（**不是** Workflow / Task 状态的副本；三者相互独立）。
 *
 * 语义：
 *  - [CREATED] 已建立、尚未进入工作；
 *  - [ACTIVE] 正在进行 Agent 工作；
 *  - [PAUSED] 可继续（程序关闭 / 用户暂离后的可恢复态）；
 *  - [COMPLETED] / [FAILED] / [CANCELLED] 终态。
 */
@Serializable
enum class AgentSessionStatus {
    CREATED,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    /** 终态：不可再转换。 */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    /** 可恢复：重新打开 Project 后仍可继续的会话（§八）。 */
    val isResumable: Boolean get() = this == CREATED || this == ACTIVE || this == PAUSED
}

/**
 * 一次 Agent 工作会话（最小模型）。
 *
 * 不复制 Novel 元数据、不复制 Workflow / Task 状态、不承载任何小说事实。
 * [projectId] + [novelId] 与 I1 `ProjectState` 使用同一聚合键对（projectId 来自 `Novel.project_id`）。
 */
@Serializable
data class AgentSession(
    val sessionId: AgentSessionId,
    val projectId: ProjectId,
    val novelId: NovelId,
    /** 关联的既有 Workflow（可为空：会话可先建立、后关联；不复制其状态）。 */
    val workflowId: WorkflowId? = null,
    /** 关联的既有 Task（可为空；不复制其状态）。 */
    val taskId: TaskId? = null,
    val status: AgentSessionStatus = AgentSessionStatus.CREATED,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 最近一次活动时间（用于"找到最近的未完成会话"）。 */
    val lastActivityAt: Instant? = null,
)

/**
 * Session 生命周期规则（**纯确定性**，无 IO / 无时间 / 无随机）。
 *
 * ```
 * CREATED → ACTIVE | CANCELLED
 * ACTIVE  → PAUSED | COMPLETED | FAILED | CANCELLED
 * PAUSED  → ACTIVE | CANCELLED
 * COMPLETED / FAILED / CANCELLED → （终态，不可再转换）
 * ```
 * 同状态重复转换视为幂等（返回 true，由调用方决定是否落库）。
 * 注意：本规则**只约束 Session 自己的状态**，不读取也不改写 Workflow / Task 状态。
 */
object AgentSessionLifecycleRules {

    /** 允许的目标状态集合。 */
    fun allowedTargets(from: AgentSessionStatus): Set<AgentSessionStatus> = when (from) {
        AgentSessionStatus.CREATED ->
            setOf(AgentSessionStatus.ACTIVE, AgentSessionStatus.CANCELLED)
        AgentSessionStatus.ACTIVE ->
            setOf(AgentSessionStatus.PAUSED, AgentSessionStatus.COMPLETED, AgentSessionStatus.FAILED, AgentSessionStatus.CANCELLED)
        AgentSessionStatus.PAUSED ->
            setOf(AgentSessionStatus.ACTIVE, AgentSessionStatus.CANCELLED)
        AgentSessionStatus.COMPLETED,
        AgentSessionStatus.FAILED,
        AgentSessionStatus.CANCELLED,
        -> emptySet()
    }

    /** [from] → [to] 是否合法（同状态幂等；终态不可再转换）。 */
    fun canTransition(from: AgentSessionStatus, to: AgentSessionStatus): Boolean =
        from == to || to in allowedTargets(from)
}