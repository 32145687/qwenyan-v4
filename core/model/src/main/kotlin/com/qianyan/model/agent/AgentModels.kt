package com.qianyan.model.agent

import com.qianyan.model.AgentId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Agent 契约与状态（架构 §20 / §23.2 / §23.3）。
 * 仅建立领域契约；AgentRuntime / Orchestration 行为不在 P1 实现。
 */

/** Agent 能力声明。 */
@Serializable
data class Capability(
    val name: String,
    val description: String = "",
)

/** 允许调用的工具名（Tool System 注册名）。 */
@JvmInline
@Serializable
value class ToolName(val value: String)

/** Agent 契约（架构 §20.1）。schema 用结构化 JSON（JSON Schema 表达），避免 Any。 */
@Serializable
data class AgentContract(
    val agentId: AgentId,
    val name: String,
    val capabilities: List<Capability> = emptyList(),
    val allowedTools: List<ToolName> = emptyList(),
    val inputSchema: JsonObject = JsonObject(emptyMap()),
    val outputSchema: JsonObject = JsonObject(emptyMap()),
    val maxRetries: Int = 3,
)

/** 单个 Agent 的执行状态（架构 §20.2，唯一来源）。 */
@Serializable
enum class AgentState {
    IDLE, RUNNING, WAITING_TOOL, WAITING_HUMAN, RETRYING, FAILED, COMPLETED,
}

/**
 * 整个创作 Workflow 运行到哪一阶段（架构 §23.2，唯一 Workflow 状态机）。
 * 系统中"整个写作工作流处于哪一阶段"的表达只能来自此枚举（ISSUE-1 已裁决）。
 */
@Serializable
enum class WorkflowState {
    INTENT_PARSING,
    RESEARCH,
    PLANNING,
    PLAN_REVIEW,        // HITL：规划确认
    WRITING,
    CRITIQUE,
    REVISION,           // 计数 ≤ 3
    KNOWLEDGE_UPDATE,
    CONFLICT_HITL,      // HITL：冲突裁决
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED,
}