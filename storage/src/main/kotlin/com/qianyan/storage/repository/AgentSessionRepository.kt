package com.qianyan.storage.repository

import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.AgentSession

/**
 * Agent Session 持久化仓储（I3 · FD-9）。
 *
 * 只承担"一次 Agent 工作会话身份"的读写：会话身份 / Project 归属 / Workflow·Task 引用 / 会话自身生命周期。
 *
 * 明确不做（属后续阶段或既有模块）：
 *  - 不承载 Workflow / Task 生命周期状态（仍以 Workflow / Task / Checkpoint 为准 —— FD-2）；
 *  - 不存 Activity Log / Context 快照内容 / Working Draft / Artifact / Commit 历史（I4 及以后）；
 *  - 不复制 Novel 元数据与任何小说事实。
 */
interface AgentSessionRepository {

    /** 创建/覆盖一次会话（同 sessionId 覆盖，不产生第二行）。 */
    fun save(session: AgentSession)

    /** 读取会话；不存在返回 null（不伪造会话）。 */
    fun get(sessionId: AgentSessionId): AgentSession?

    /** 按 Project 列出会话（创建时间倒序）。 */
    fun listByProject(projectId: ProjectId): List<AgentSession>

    /** 最近的**未完成（可恢复）**会话（CREATED / ACTIVE / PAUSED）；无则返回 null。 */
    fun findResumableByProject(projectId: ProjectId): AgentSession?
}