package com.qianyan.application.usecase.session

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.core.Novel
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionLifecycleRules
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.storage.repository.AgentSessionRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I3 · Agent Session（一次持续 Agent 工作的可持久化会话边界）。
 *
 * 依据：`docs/architecture/qianyan-novel-ide-architecture.md` §13 / §41（Session 生命周期）。
 *
 * 职责（只做这些）：
 *  - 会话身份与 **Project 归属**（projectId + novelId，与 I1 `ProjectState` 同一聚合键对）；
 *  - 与**既有** Workflow / Task 的**引用关联**（不复制其状态）；
 *  - 会话自身生命周期（确定性规则约束）；
 *  - 可恢复身份查询（重新打开 Project → 找到未完成会话 → 知道它关联的 Workflow/Task）。
 *
 * 明确不做（后续阶段或既有模块职责）：
 *  - **不实现 Resume Engine**：真正的恢复执行仍由既有 Workflow / Checkpoint 机制负责（本类只恢复身份）；
 *  - **不改 Workflow / Task 事实**：创建/推进会话不会**凭空创建** Workflow / Task，也不改写其状态（FD-2）；
 *  - **不实现** Activity Log / Tool / Skill / Context Engine / Working Draft / Validation / Diff / Artifact /
 *      Commit / History / Queue / UI；
 *  - **不改 AgentRuntime**（I3 Runtime Integration = DEFERRED，见 §九）；本类不依赖 `:agent:runtime`。
 */
class AgentSessionUseCases(
    private val novelRepository: NovelRepository,
    private val sessions: AgentSessionRepository,
    private val workflowRepository: WorkflowRepository,
    private val taskRepository: TaskRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 开始一次 Agent 工作会话（初始状态 `CREATED`）。
     * [workflowId] / [taskId] 为可选**既有**实体引用：若提供则必须真实存在（拒绝悬挂引用），
     * 且不会因此创建或修改 Workflow / Task。
     */
    fun startSession(
        novelId: NovelId,
        workflowId: WorkflowId? = null,
        taskId: TaskId? = null,
    ): AgentSession {
        val novel = requireNovel(novelId)
        if (workflowId != null) requireWorkflow(workflowId)
        if (taskId != null) requireTask(taskId)

        val now = now()
        val session = AgentSession(
            sessionId = AgentSessionId(nextId()),
            projectId = novel.projectId,
            novelId = novel.novelId,
            workflowId = workflowId,
            taskId = taskId,
            status = AgentSessionStatus.CREATED,
            createdAt = now,
            updatedAt = now,
        )
        guard { sessions.save(session) }
        return session
    }

    /** 读取会话；不存在 → [ApplicationError.EntityNotFound]（不伪造会话）。 */
    fun sessionOf(sessionId: AgentSessionId): AgentSession =
        guard { sessions.get(sessionId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("AgentSession 不存在: ${sessionId.value}"))

    /** 按 Project 列出会话（创建时间倒序）。 */
    fun sessionsOfProject(projectId: ProjectId): List<AgentSession> = guard { sessions.listByProject(projectId) }

    /**
     * 找到该 Project 最近的**未完成（可恢复）**会话；无则返回 null。
     * 只恢复**身份**（含关联的 Workflow / Task 引用），不执行恢复编排（§八）。
     */
    fun resumableSessionOf(projectId: ProjectId): AgentSession? = guard { sessions.findResumableByProject(projectId) }

    // ---- 生命周期（确定性转换；非法转换类型化拒绝） ----

    /** 进入工作（`CREATED|PAUSED → ACTIVE`）。 */
    fun activate(sessionId: AgentSessionId): AgentSession = transition(sessionId, AgentSessionStatus.ACTIVE)

    /** 暂停（可恢复；`ACTIVE → PAUSED`）。 */
    fun pause(sessionId: AgentSessionId): AgentSession = transition(sessionId, AgentSessionStatus.PAUSED)

    /** 完成（`ACTIVE → COMPLETED`）。 */
    fun complete(sessionId: AgentSessionId): AgentSession = transition(sessionId, AgentSessionStatus.COMPLETED)

    /** 失败（`ACTIVE → FAILED`）。 */
    fun fail(sessionId: AgentSessionId): AgentSession = transition(sessionId, AgentSessionStatus.FAILED)

    /** 取消（`CREATED|ACTIVE|PAUSED → CANCELLED`）。 */
    fun cancel(sessionId: AgentSessionId): AgentSession = transition(sessionId, AgentSessionStatus.CANCELLED)

    /** 记录一次活动（更新 `lastActivityAt`；不改变状态、不改 Workflow / Task）。 */
    fun touch(sessionId: AgentSessionId): AgentSession {
        val current = sessionOf(sessionId)
        val now = now()
        val updated = current.copy(lastActivityAt = now, updatedAt = now)
        guard { sessions.save(updated) }
        return updated
    }

    /** 关联既有 Workflow（必须真实存在；不改其状态）。 */
    fun attachWorkflow(sessionId: AgentSessionId, workflowId: WorkflowId): AgentSession {
        requireWorkflow(workflowId)
        val current = sessionOf(sessionId)
        val updated = current.copy(workflowId = workflowId, updatedAt = now())
        guard { sessions.save(updated) }
        return updated
    }

    /** 关联既有 Task（必须真实存在；不改其状态）。 */
    fun attachTask(sessionId: AgentSessionId, taskId: TaskId): AgentSession {
        requireTask(taskId)
        val current = sessionOf(sessionId)
        val updated = current.copy(taskId = taskId, updatedAt = now())
        guard { sessions.save(updated) }
        return updated
    }

    // ---- internals ----

    /** 统一状态转换入口：复用 `AgentSessionLifecycleRules` 确定性规则，非法转换类型化拒绝。 */
    private fun transition(sessionId: AgentSessionId, target: AgentSessionStatus): AgentSession {
        val current = sessionOf(sessionId)
        if (!AgentSessionLifecycleRules.canTransition(current.status, target)) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "AgentSession ${sessionId.value} 不允许从 ${current.status} 转换到 $target" +
                        "（允许：${AgentSessionLifecycleRules.allowedTargets(current.status)}）",
                ),
            )
        }
        if (current.status == target) return current
        val now = now()
        val updated = current.copy(status = target, updatedAt = now, lastActivityAt = now)
        guard { sessions.save(updated) }
        return updated
    }

    private fun requireNovel(novelId: NovelId): Novel =
        guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))

    private fun requireWorkflow(workflowId: WorkflowId) {
        guard { workflowRepository.getWorkflow(workflowId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${workflowId.value}"))
    }

    private fun requireTask(taskId: TaskId) {
        guard { taskRepository.findById(taskId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Task 不存在: ${taskId.value}"))
    }

    /** 时间戳精度与存储一致（epoch 毫秒）：避免内存值与回读值因精度不同而不等价。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
}