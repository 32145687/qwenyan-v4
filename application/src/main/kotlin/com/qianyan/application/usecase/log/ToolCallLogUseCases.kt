package com.qianyan.application.usecase.log

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.log.AgentLogLifecycleRules
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.log.ToolCallLog
import com.qianyan.storage.repository.ActivityRepository
import com.qianyan.storage.repository.ToolCallLogRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I4 · Tool Call Log 记录（一次 Tool 调用事实的记录）。
 *
 * 依据：`docs/architecture/qianyan-novel-ide-architecture.md` §15。
 *
 * **是 Tool Log，不是 Tool Registry**：`toolName` 只是调用标识（复用既有 `ToolName`），
 * 不含工具实现 / 注册 / 权限语义（那是 I5 Tool Registry 的事）。
 *
 * 归属一致性：`sessionId` / `projectId` 一律从所属 [com.qianyan.model.log.Activity] 派生，
 * 调用方不传 —— 保证记录永远与 Activity 归属一致且不得跨 Project。
 *
 * 明确不做：不创建 Activity / AgentSession、不改写任何业务状态、不执行 Tool、
 * 不实现 Tool Registry / Permission / Skill / Context（后续阶段）。
 */
class ToolCallLogUseCases(
    private val activities: ActivityRepository,
    private val toolCallLogs: ToolCallLogRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 记录一次 Tool 调用的开始（状态 `RUNNING`）。
     * 必须挂在**既有** Activity 上（拒绝悬挂引用）；sessionId / projectId 从 Activity 派生。
     */
    fun start(activityId: ActivityId, toolName: ToolName, inputSummary: String? = null): ToolCallLog {
        val activity = guard { activities.get(activityId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Activity 不存在: ${activityId.value}"))
        val now = now()
        val call = ToolCallLog(
            toolCallId = ToolCallId(nextId()),
            activityId = activity.activityId,
            sessionId = activity.sessionId,
            projectId = activity.projectId,
            toolName = toolName,
            status = AgentLogStatus.RUNNING,
            startedAt = now,
            inputSummary = inputSummary,
        )
        guard { toolCallLogs.save(call) }
        return call
    }

    /** 记录成功（`RUNNING → COMPLETED`，写 completedAt + outputSummary）。 */
    fun succeed(toolCallId: ToolCallId, outputSummary: String? = null): ToolCallLog =
        transition(toolCallId, AgentLogStatus.COMPLETED) { it.copy(outputSummary = outputSummary ?: it.outputSummary) }

    /** 记录失败（`RUNNING → FAILED`，写 completedAt + error）。 */
    fun fail(toolCallId: ToolCallId, error: String): ToolCallLog =
        transition(toolCallId, AgentLogStatus.FAILED) { it.copy(error = error) }

    /** 记录取消（`RUNNING → CANCELLED`，写 completedAt）。 */
    fun cancel(toolCallId: ToolCallId): ToolCallLog = transition(toolCallId, AgentLogStatus.CANCELLED) { it }

    /** 读取记录；不存在 → [ApplicationError.EntityNotFound]（不伪造）。 */
    fun get(toolCallId: ToolCallId): ToolCallLog =
        guard { toolCallLogs.get(toolCallId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("ToolCallLog 不存在: ${toolCallId.value}"))

    /** Activity 内调用（时序升序）。 */
    fun listByActivity(activityId: ActivityId): List<ToolCallLog> = guard { toolCallLogs.listByActivity(activityId) }

    /** 会话内调用（时序升序）。 */
    fun listBySession(sessionId: AgentSessionId): List<ToolCallLog> = guard { toolCallLogs.listBySession(sessionId) }

    /** 项目内调用（时序升序；不得跨 Project 泄漏）。 */
    fun listByProject(projectId: ProjectId): List<ToolCallLog> = guard { toolCallLogs.listByProject(projectId) }

    /** 统一状态转换入口：复用 [AgentLogLifecycleRules] 确定性规则（与 Activity 同一套记录生命周期）。 */
    private inline fun transition(
        toolCallId: ToolCallId,
        target: AgentLogStatus,
        refine: (ToolCallLog) -> ToolCallLog,
    ): ToolCallLog {
        val current = get(toolCallId)
        if (!AgentLogLifecycleRules.canTransition(current.status, target)) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "ToolCallLog ${toolCallId.value} 不允许从 ${current.status} 转换到 $target" +
                        "（允许：${AgentLogLifecycleRules.allowedTargets(current.status)}）",
                ),
            )
        }
        if (current.status == target) return current
        val updated = refine(current.copy(status = target, completedAt = now()))
        guard { toolCallLogs.save(updated) }
        return updated
    }

    /** 时间戳精度与存储一致（epoch 毫秒）。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
}