package com.qianyan.application.usecase.session

import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.AgentSession
import com.qianyan.model.session.AgentSessionLifecycleRules
import com.qianyan.model.session.AgentSessionStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.storage.repository.AgentSessionRepository
import java.util.UUID
import kotlinx.datetime.Instant

/**
 * DSH-05 · Runtime 会话存储：把 DSH-04 的 Runtime 生命周期落到**既有** AgentSession 表。
 *
 * 边界（与 DSH-05 提示词一致）：
 *  - 不建第二套会话存储、零新表、零迁移、不加列；
 *  - 状态推进一律经 [AgentSessionLifecycleRules]（不跳态、同状态幂等）；
 *  - 所有时间由调用方显式传入（可测试、可重放），本类不做时钟兜底；
 *  - [finish] 非法迁移返回 null（不抛给调用方），由 Runtime 适配层决定降级。
 */
class DshSessionStore(private val sessions: AgentSessionRepository) {

    /**
     * 打开或复用一次会话：
     *  - 命中该 Project 最近的**可恢复**会话（CREATED/ACTIVE/PAUSED）且 workflowId 匹配
     *    （[workflowRef] 为 null 时要求会话也无 workflowId）→ 复用，仅推进 `lastActivityAt`；
     *  - 否则新建 `CREATED` 会话并落库。
     *
     * [workflowRef] 是 Runtime 契约里的不透明字符串；非 null 时按 [WorkflowId] 语义匹配既有引用。
     */
    fun openOrCreate(projectId: ProjectId, novelId: NovelId, workflowRef: String?, now: Instant): AgentSession {
        val wantWorkflowId = workflowRef?.let { WorkflowId(it) }
        val reusable = sessions.findResumableByProject(projectId)?.takeIf { session ->
            if (wantWorkflowId == null) session.workflowId == null else session.workflowId == wantWorkflowId
        }
        if (reusable != null) {
            val touched = reusable.copy(lastActivityAt = now)
            sessions.save(touched)
            return touched
        }
        val created = AgentSession(
            sessionId = AgentSessionId("dsh-${UUID.randomUUID()}"),
            projectId = projectId,
            novelId = novelId,
            workflowId = wantWorkflowId,
            status = AgentSessionStatus.CREATED,
            createdAt = now,
            updatedAt = now,
            lastActivityAt = now,
        )
        sessions.save(created)
        return created
    }

    /** `CREATED → ACTIVE`（经生命周期规则；非法转换抛 [IllegalArgumentException]，由调用方降级）。 */
    fun activate(sessionId: AgentSessionId, now: Instant): AgentSession =
        transition(sessionId, AgentSessionStatus.ACTIVE, now)

    /**
     * 终态收口（`ACTIVE → COMPLETED|FAILED|CANCELLED`；`CREATED → CANCELLED` 亦合法——既有规则）。
     * 非法迁移或会话不存在返回 **null**（不抛）；同状态重复收口幂等返回当前会话。
     */
    fun finish(sessionId: AgentSessionId, status: AgentSessionStatus, now: Instant): AgentSession? {
        require(status in TERMINALS) { "finish 只接受终态：$TERMINALS" }
        val current = sessions.get(sessionId) ?: return null
        if (current.status == status) return current
        if (!AgentSessionLifecycleRules.canTransition(current.status, status)) return null
        val updated = current.copy(status = status, updatedAt = now, lastActivityAt = now)
        sessions.save(updated)
        return updated
    }

    /** 恢复：该 Project 最近的未完成会话；无则 null（不建行）。 */
    fun resume(projectId: ProjectId): AgentSession? = sessions.findResumableByProject(projectId)

    private fun transition(sessionId: AgentSessionId, target: AgentSessionStatus, now: Instant): AgentSession {
        val current = sessions.get(sessionId)
            ?: throw IllegalArgumentException("AgentSession 不存在: ${sessionId.value}")
        if (current.status == target) return current
        if (!AgentSessionLifecycleRules.canTransition(current.status, target)) {
            throw IllegalArgumentException(
                "AgentSession ${sessionId.value} 不允许从 ${current.status} 转换到 $target" +
                    "（允许：${AgentSessionLifecycleRules.allowedTargets(current.status)}）",
            )
        }
        val updated = current.copy(status = target, updatedAt = now, lastActivityAt = now)
        sessions.save(updated)
        return updated
    }

    private companion object {
        val TERMINALS = setOf(
            AgentSessionStatus.COMPLETED,
            AgentSessionStatus.FAILED,
            AgentSessionStatus.CANCELLED,
        )
    }
}
