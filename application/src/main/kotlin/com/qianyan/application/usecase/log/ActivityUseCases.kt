package com.qianyan.application.usecase.log

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.log.Activity
import com.qianyan.model.log.AgentLogLifecycleRules
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.storage.repository.ActivityRepository
import com.qianyan.storage.repository.AgentSessionRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I4 · Activity 记录（AgentSession 内已发生活动的事实记录）。
 *
 * 依据：`docs/architecture/qianyan-novel-ide-architecture.md` §15。
 *
 * **是记录，不是控制器**：只落"发生了什么活动"的事实，绝不创建或改写
 * AgentSession / Workflow / Task / Project 的任何状态（观察记录 ≠ 状态机）。
 *
 * 明确不做（后续阶段或既有模块职责）：
 *  - 不执行 Tool / 不实现 Tool Registry / Skill / Context Engine / Novel Agent；
 *  - 不实现 Diff / Artifact / Commit / History / Queue / UI；
 *  - 不改 AgentRuntime（I4 Runtime Integration = DEFERRED）。
 */
class ActivityUseCases(
    private val sessions: AgentSessionRepository,
    private val activities: ActivityRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 记录一次活动的开始（状态 `RUNNING`）。
     * 必须挂在**既有** Session 上（拒绝悬挂引用）；projectId 从 Session 派生，保证归属一致。
     */
    fun start(sessionId: AgentSessionId, kind: String, summary: String? = null): Activity {
        val session = guard { sessions.get(sessionId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("AgentSession 不存在: ${sessionId.value}"))
        val now = now()
        val activity = Activity(
            activityId = ActivityId(nextId()),
            sessionId = session.sessionId,
            projectId = session.projectId,
            kind = kind,
            status = AgentLogStatus.RUNNING,
            startedAt = now,
            summary = summary,
        )
        guard { activities.save(activity) }
        return activity
    }

    /** 记录完成（`RUNNING → COMPLETED`，写 completedAt）。 */
    fun complete(activityId: ActivityId, summary: String? = null): Activity =
        transition(activityId, AgentLogStatus.COMPLETED) { it.copy(summary = summary ?: it.summary) }

    /** 记录失败（`RUNNING → FAILED`，写 completedAt + error）。 */
    fun fail(activityId: ActivityId, error: String): Activity =
        transition(activityId, AgentLogStatus.FAILED) { it.copy(error = error) }

    /** 记录取消（`RUNNING → CANCELLED`，写 completedAt）。 */
    fun cancel(activityId: ActivityId): Activity = transition(activityId, AgentLogStatus.CANCELLED) { it }

    /** 读取记录；不存在 → [ApplicationError.EntityNotFound]（不伪造）。 */
    fun get(activityId: ActivityId): Activity =
        guard { activities.get(activityId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Activity 不存在: ${activityId.value}"))

    /** 会话内活动（时序升序）。 */
    fun listBySession(sessionId: AgentSessionId): List<Activity> = guard { activities.listBySession(sessionId) }

    /** 项目内活动（时序升序；不得跨 Project 泄漏）。 */
    fun listByProject(projectId: ProjectId): List<Activity> = guard { activities.listByProject(projectId) }

    /** 统一状态转换入口：复用 [AgentLogLifecycleRules] 确定性规则（无 LLM / 无随机 / 无时间判断）。 */
    private inline fun transition(
        activityId: ActivityId,
        target: AgentLogStatus,
        refine: (Activity) -> Activity,
    ): Activity {
        val current = get(activityId)
        if (!AgentLogLifecycleRules.canTransition(current.status, target)) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Activity ${activityId.value} 不允许从 ${current.status} 转换到 $target" +
                        "（允许：${AgentLogLifecycleRules.allowedTargets(current.status)}）",
                ),
            )
        }
        if (current.status == target) return current
        val updated = refine(current.copy(status = target, completedAt = now()))
        guard { activities.save(updated) }
        return updated
    }

    /** 时间戳精度与存储一致（epoch 毫秒）。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
}