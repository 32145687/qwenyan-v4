package com.qianyan.model.skill

import com.qianyan.model.AgentId
import com.qianyan.model.IntentType
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.Capability
import com.qianyan.model.agent.ToolName
import com.qianyan.model.context.ContextSourceKind
import kotlinx.serialization.Serializable

/*
 * I7 · Skill 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §11）：
 *   Agent   回答"这次用户请求我要做什么"（决策）
 *   **Skill 回答"对于这种任务，我应该采用什么方法"（可复用、可发现、可选择的方法论 / 能力描述）**
 *   Tool    回答"我实际能执行什么操作"（I5）
 *   Context 回答"为当前任务提供哪些上下文"（I6）
 *
 * 硬边界（§3 / §8 / §9 / §10 / §11）：
 *  - Skill **不是** Tool / Agent / Context / Workflow：本文件全部是**数据声明**，不含可执行行为；
 *  - **不持有** Database / LLMGateway / ToolExecutor / ContextEngine / Workflow / AgentSession；
 *  - **无执行生命周期状态机**（执行生命周期属 AgentSession / Activity / Workflow / Task）；
 *  - **不判定权限**：只声明预期动作（[Skill.expectedActions]），是否允许仍由 I2 ActionPolicy 决定；
 *  - **不构建 Context**：只声明需要的类型（[Skill.requiredContext]），构建仍由 I6 Context Engine 执行。
 *
 * 复用已有模型（不重复建模）：[Capability]（既有 Agent 能力描述）、[IntentType]（既有任务目的）、
 * [ToolName]（既有 Tool 注册名）、[AgentActionKind]（既有 I2 动作类别）、[ContextSourceKind]（既有 I6 来源类型）。
 */

/** Skill 稳定标识（注册 / 查询键；不含 UUID / 时间）。 */
@JvmInline
@Serializable
value class SkillId(val value: String)

/**
 * 能力绑定：把 Skill 指向**真实存在的既有能力**（按稳定 ID 指向，不持有实现）。
 *
 * [agentId] 指向既有 `AgentContract.agentId`（现有五 Agent：story-planner / story-writer /
 * story-critic / story-writer-revision / knowledge-update）；不持有 Agent 实例、不调用 LLM。
 */
@Serializable
data class SkillBinding(
    val agentId: AgentId,
    val note: String = "",
)

/**
 * Skill：可复用、可发现、可选择的**任务方法论 / 能力描述**（声明而非执行）。
 *
 * @param capabilities 能力类别声明（复用既有 [Capability] 模型，不新造第二套 capability 类型）。
 * @param purposes 适用任务目的（复用既有 [IntentType]；Registry 匹配以此为准）。
 * @param requiredContext 需要哪些类型的 Context（复用 I6 [ContextSourceKind]；**只声明，不构建**）。
 * @param allowedTools 预期使用的既有 Product Tool（复用 [ToolName]；**只声明，不执行**）。
 * @param expectedActions 预期动作类别（复用 I2 [AgentActionKind]；**只声明，权限仍由 ActionPolicy 判定**）。
 * @param bindings 实现该能力的既有能力绑定（**I7 只建立绑定声明，不执行**）。
 * @param version Skill 版本（metadata；不引入全局版本系统）。
 * @param enabled 是否可用（不可用 Skill 不参与匹配）。
 */
@Serializable
data class Skill(
    val skillId: SkillId,
    val name: String,
    val description: String,
    val capabilities: List<Capability> = emptyList(),
    val purposes: List<IntentType> = emptyList(),
    val requiredContext: List<ContextSourceKind> = emptyList(),
    val allowedTools: List<ToolName> = emptyList(),
    val expectedActions: List<AgentActionKind> = emptyList(),
    val bindings: List<SkillBinding> = emptyList(),
    val version: Int = 1,
    val enabled: Boolean = true,
)

/**
 * 匹配结果：只回答"有哪些 Skill 可以处理这个任务"（§13），
 * **不做** Agent routing、**不决定**执行哪个 Skill（那是 I11 Novel Agent 的事）。
 */
@Serializable
data class SkillMatch(
    val skillId: SkillId,
    val matchedPurpose: IntentType,
    val reason: String = "",
)