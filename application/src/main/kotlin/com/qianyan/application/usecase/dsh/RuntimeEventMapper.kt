package com.qianyan.application.usecase.dsh

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.log.Activity
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.log.ToolCallLog
import com.qianyan.storage.repository.ActivityRepository
import com.qianyan.storage.repository.ToolCallLogRepository
import kotlinx.datetime.Instant

/**
 * DSH-08 · RuntimeEvent → **既有** Activity / ToolCallLog 映射（零新表、零新事实源）。
 *
 * 幂等：两表均无 eventId 列 → 行主键由 **(runId, seq) 确定性生成**
 * （activity_id / tool_call_id = `dsh-{runId}-{seq}`，工具事件加锚后缀），INSERT OR REPLACE
 * → 同键重复映射不产生第二行（在报告说明）。
 * 失败不阻断：单事件映射异常 runCatching + 一行 stderr，任务继续。
 *
 * 映射表：
 *  - PHASE_START/END、SKILL_SELECTED、MODEL_CALL、CANDIDATE_READY、GATE_WAIT、CANCELLED、FAILED
 *      → Activity（kind = 事件 kind 名；ok→COMPLETED/FAILED、CANCELLED→CANCELLED、null→RUNNING）；
 *  - TOOL_CALL → Activity 锚（kind=TOOL_CALL）+ ToolCallLog（tool_name=事件名、input_summary=detail、RUNNING）；
 *  - TOOL_RESULT → Activity 锚 + ToolCallLog（output_summary=detail+结果量、ok→状态）；
 *  - 终态事件 completed_at = at；进行中为 null。本单元不改任务状态（终态由既有调用方处理）。
 */
class RuntimeEventMapper(
    private val activities: ActivityRepository,
    private val toolCallLogs: ToolCallLogRepository,
) {

    /** 映射一条事件；异常吞掉并打一行 stderr（不阻断运行）。 */
    fun map(sessionId: AgentSessionId, projectId: ProjectId, runId: String, seq: Int, event: RuntimeEvent, at: Instant) {
        runCatching {
            mapEvent(sessionId, projectId, runId, seq, event, at)
        }.onFailure {
            System.err.println("DSH-08: 事件映射失败（不阻断）: ${it.message}")
        }
    }

    private fun mapEvent(
        sessionId: AgentSessionId,
        projectId: ProjectId,
        runId: String,
        seq: Int,
        event: RuntimeEvent,
        at: Instant,
    ) {
        val rowKey = "dsh-$runId-$seq"
        val status = statusOf(event)
        val errorText = if (event.ok == false) event.detailSummary else null

        if (event.kind == RuntimeEventKind.TOOL_CALL || event.kind == RuntimeEventKind.TOOL_RESULT) {
            val anchor = Activity(
                activityId = ActivityId(rowKey),
                sessionId = sessionId,
                projectId = projectId,
                kind = event.kind.name,
                status = status,
                startedAt = at,
                completedAt = completedAtOf(status, at),
                summary = event.detailSummary,
                error = errorText,
            )
            activities.save(anchor)
            toolCallLogs.save(
                ToolCallLog(
                    toolCallId = ToolCallId(rowKey),
                    activityId = anchor.activityId,
                    sessionId = sessionId,
                    projectId = projectId,
                    toolName = ToolName(event.name),
                    status = status,
                    startedAt = at,
                    completedAt = completedAtOf(status, at),
                    inputSummary = if (event.kind == RuntimeEventKind.TOOL_CALL) event.detailSummary else null,
                    outputSummary = outputSummaryOf(event),
                    error = errorText,
                ),
            )
            return
        }

        activities.save(
            Activity(
                activityId = ActivityId(rowKey),
                sessionId = sessionId,
                projectId = projectId,
                kind = event.kind.name,
                status = status,
                startedAt = at,
                completedAt = completedAtOf(status, at),
                summary = event.detailSummary,
                error = errorText,
            ),
        )
    }

    /** 事件 → 日志状态：CANCELLED 事件→CANCELLED；ok→COMPLETED/FAILED；无结果→RUNNING（如 GATE_WAIT）。 */
    private fun statusOf(event: RuntimeEvent): AgentLogStatus = when {
        event.kind == RuntimeEventKind.CANCELLED -> AgentLogStatus.CANCELLED
        event.ok == true -> AgentLogStatus.COMPLETED
        event.ok == false -> AgentLogStatus.FAILED
        else -> AgentLogStatus.RUNNING
    }

    private fun completedAtOf(status: AgentLogStatus, at: Instant): Instant? =
        if (status.isTerminal) at else null

    /** 结果量并入输出摘要（ToolCallLog 无结果量列——并入口径在报告说明）。 */
    private fun outputSummaryOf(event: RuntimeEvent): String? =
        event.resultCount?.let { "${event.detailSummary}（$it 条）" } ?: event.detailSummary
}
