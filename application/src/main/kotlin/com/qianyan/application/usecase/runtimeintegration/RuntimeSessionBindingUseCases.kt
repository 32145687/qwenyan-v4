package com.qianyan.application.usecase.runtimeintegration

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.AgentSessionId
import com.qianyan.model.RuntimeSessionRefId
import com.qianyan.model.runtime.RuntimeSessionRef
import com.qianyan.storage.repository.AgentSessionRepository
import com.qianyan.storage.repository.RuntimeSessionRefRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I1 · Runtime Session 绑定（Qianyan AgentSession ↔ 外部 Runtime Session，`1 : N`）。
 *
 * 依据：I0 Final Architecture Review §3（Session Mapping）。
 *
 * 职责（只做这些）：
 *  - 记录 / 查询"某个 Qianyan 会话用过哪一次 Runtime 会话"；
 *  - 拒绝悬挂引用（AgentSession 必须真实存在）；
 *  - 保持绑定**厂商中立**（runtimeName / runtimeSessionId 对 Domain 均为不透明字符串）。
 *
 * 明确不做：
 *  - **不给 AgentSession 增加任何运行时字段**（不改 Domain 模型、不改 AgentSession 表语义）；
 *  - 不实现 Resume Engine（本类只恢复**身份**，不重放执行）；
 *  - 不存 Runtime transcript / 用量统计 / analytics；
 *  - 不产生第二套会话状态机。
 */
class RuntimeSessionBindingUseCases(
    private val sessions: AgentSessionRepository,
    private val refs: RuntimeSessionRefRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 建立一条绑定（`agentSessionId → runtimeSessionId`）。
     *
     * 同一会话可多次绑定不同 Runtime 会话（`1 : N`：Runtime 会话是进程/连接作用域的）。
     */
    fun bind(agentSessionId: AgentSessionId, runtimeName: String, runtimeSessionId: String): RuntimeSessionRef {
        requireNonBlank(runtimeName, "runtimeName")
        requireNonBlank(runtimeSessionId, "runtimeSessionId")
        guard { sessions.get(agentSessionId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("AgentSession 不存在: ${agentSessionId.value}"))

        val ref = RuntimeSessionRef(
            refId = RuntimeSessionRefId(nextId()),
            agentSessionId = agentSessionId,
            runtimeName = runtimeName,
            runtimeSessionId = runtimeSessionId,
            createdAt = now(),
        )
        guard { refs.save(ref) }
        return ref
    }

    /** 某会话的全部绑定（新到旧）。 */
    fun bindingsOf(agentSessionId: AgentSessionId): List<RuntimeSessionRef> = guard { refs.listByAgentSession(agentSessionId) }

    /** 某会话最近一次绑定；无则返回 null（不伪造）。 */
    fun latest(agentSessionId: AgentSessionId): RuntimeSessionRef? = guard { refs.latestByAgentSession(agentSessionId) }

    private fun requireNonBlank(value: String, field: String) {
        if (value.isBlank()) {
            throw ApplicationException(ApplicationError.InvalidOperation("$field 不得为空"))
        }
    }

    /** 时间戳精度与存储一致（epoch 毫秒）。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
}