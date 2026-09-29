package com.qianyan.storage.repository

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.ToolCallId
import com.qianyan.model.log.ToolCallLog

/**
 * ToolCallLog 持久化仓储（I4 · FD-9）。
 *
 * 只承担"一次 Tool 调用事实"的记录读写。**是 Tool Log，不是 Tool Registry**：
 *  - 不含工具实现 / 注册 / 权限语义；
 *  - 不创建 Activity / AgentSession，也不改写任何业务状态。
 */
interface ToolCallLogRepository {

    /** 创建/覆盖一条调用记录（同 toolCallId 覆盖，不产生第二行）。 */
    fun save(call: ToolCallLog)

    /** 读取调用记录；不存在返回 null（不伪造记录）。 */
    fun get(toolCallId: ToolCallId): ToolCallLog?

    /** Activity 内调用（时序升序）。 */
    fun listByActivity(activityId: ActivityId): List<ToolCallLog>

    /** 会话内调用（时序升序）。 */
    fun listBySession(sessionId: AgentSessionId): List<ToolCallLog>

    /** 项目内调用（时序升序；不得跨 Project 泄漏）。 */
    fun listByProject(projectId: ProjectId): List<ToolCallLog>
}