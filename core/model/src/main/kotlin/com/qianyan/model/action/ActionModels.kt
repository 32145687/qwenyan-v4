package com.qianyan.model.action

/*
 * Novel IDE · I2：Action Policy（Agent 行动权限）契约。
 *
 * 定位（依据 docs/architecture/qianyan-novel-ide-architecture.md §16）：
 *   回答"Agent 当前准备执行的**系统动作**是否被允许"。
 *
 * 与 P19 DecisionPolicy 严格分离（两者职责不同，不得混用）：
 *   DecisionPolicy = 小说**创作决策**（叙事方向 / 写作政策；P19 SEALED，本文件不涉及）
 *   ActionPolicy   = Agent 能否执行某项**系统动作**（读 / 写草稿 / 改 Canonical …）
 *
 * 边界（I2 只建立契约，不实现后续阶段）：
 *   · 不实现 Agent Session / Tool / Skill / Working Draft / Commit / Validation / Artifact / History / Queue；
 *   · 不新建权限状态机：`ALLOW / DENY / NEEDS_HUMAN` 是**判断结果**，人工确认路径复用既有 Workflow Human Gate；
 *   · 判断必须确定性：无 LLM、无时间、无随机、无外部状态（同输入同输出）。
 */

/**
 * Agent 行动类别（围绕"动作类别"划分，不围绕 UI 按钮）。
 * 取值覆盖 architecture §12 的能力族与 §16 的动作权限清单。
 */
enum class AgentActionKind {
    // ---- 只读 / 派生：不写任何长期事实 ----
    READ,
    SEARCH,
    ANALYZE,
    VALIDATE,

    // ---- 只写 Working Draft（不得影响 Canonical） ----
    CREATE_WORKING_DRAFT,
    EDIT_WORKING_DRAFT,
    PROPOSE_CHANGE,

    // ---- 高风险：改动规划 / 世界模型 / Canonical（须人工确认） ----
    MODIFY_PLAN,
    UPDATE_WORLD_MODEL,
    MODIFY_CANONICAL,
    COMMIT_CANONICAL,

    // ---- 当前范围明确排除的动作类别（FD-10：作品删除等未开放） ----
    DELETE,
}

/** 风险分级（architecture §14 / §16 的低 / 中 / 高）。 */
enum class ActionRisk { LOW, MEDIUM, HIGH }

/**
 * 一次 Agent 行动请求（最小载体）。
 * [target] 为受影响对象的可读标识（章节 / 人物 / 伏笔 / 规则…），仅用于记录与提示，不参与判断。
 */
data class AgentAction(
    val kind: AgentActionKind,
    val target: String = "",
    val note: String = "",
)

/**
 * Action Policy 的判断结果（确定性三态）。
 *
 * 说明：这是**判断结果**，不是第二套状态机 —— 需要人工的行动由调用方经既有
 * Workflow Human Gate（PENDING → RESOLVED + HumanDecision）继续（见 `ActionPolicyUseCases.openHumanGate`）。
 */
sealed interface ActionDecision {

    val action: AgentAction
    val risk: ActionRisk
    /** 判断依据（确定性文案，便于 UI 展示与审计）。 */
    val reason: String

    /** 允许执行（低风险自动 / 中风险执行并记录）。 */
    data class Allowed(
        override val action: AgentAction,
        override val risk: ActionRisk,
        override val reason: String,
    ) : ActionDecision

    /** 当前范围不允许执行（不进入人工确认）。 */
    data class Denied(
        override val action: AgentAction,
        override val risk: ActionRisk,
        override val reason: String,
    ) : ActionDecision

    /** 须人工确认后才允许继续（复用既有 Workflow Human Gate）。 */
    data class NeedsHuman(
        override val action: AgentAction,
        override val risk: ActionRisk,
        override val reason: String,
    ) : ActionDecision
}

/** 是否需要人工确认。 */
val ActionDecision.needsHuman: Boolean get() = this is ActionDecision.NeedsHuman

/** 是否允许直接执行。 */
val ActionDecision.isAllowed: Boolean get() = this is ActionDecision.Allowed