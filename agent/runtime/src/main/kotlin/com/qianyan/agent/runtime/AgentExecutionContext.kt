package com.qianyan.agent.runtime

import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentContract
import com.qianyan.model.agent.AgentState
import com.qianyan.model.tool.ToolRequest

/**
 * 单次 Agent 执行上下文（P10）。
 *
 * 承载一次 Agent 运行的全部瞬时状态：agent 契约、输入、当前状态、已执行步数、
 * 已发生的工具调用列表。默认保持 transient，不持久化（P10 无 DB 需求）。
 */
class AgentExecutionContext(
    val agent: AgentContract,
    val input: String,
) {
    val agentId: AgentId get() = agent.agentId
    var state: AgentState = AgentState.RUNNING
    var steps: Int = 0
    val toolCalls: MutableList<ToolRequest> = mutableListOf()

    override fun toString(): String =
        "AgentExecutionContext(agentId=${agentId.value}, state=$state, steps=$steps, toolCalls=${toolCalls.size})"
}