package com.qianyan.agent.runtime

import com.qianyan.model.AgentId
import com.qianyan.model.agent.AgentState
import com.qianyan.model.tool.ToolRequest

/** 一次 Agent 执行的结果（P10）。 */
data class AgentResult(
    val agentId: AgentId,
    val state: AgentState,
    val answer: String? = null,
    val steps: Int = 0,
    val toolCalls: List<ToolRequest> = emptyList(),
) {
    val completed: Boolean get() = state == AgentState.COMPLETED
}