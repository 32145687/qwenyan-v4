package com.qianyan.model.novelagent

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.IntentType
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.characterconsistency.CharacterConsistencyResult
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.commit.CommitId
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.skill.SkillId
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workflow.WorkflowHumanGateId
import kotlinx.serialization.Serializable

/*
 * I11 · Novel Agent 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §11 / §12 / §19 / §20）：
 *   Novel Agent = **决定"下一步做什么"**（编排决策）
 *   Skill       = 决定"怎么做"（I7 声明）
 *   Tool        = 提供实际操作能力（I5）
 *   Context     = 提供任务所需上下文（I6）
 *   WorkingDraft / Validation / Diff / Artifact = 临时产出与变更审查（I8 / I9）
 *   ActionPolicy / Human Gate = 系统动作权限与人工确认（I2）
 *   Commit / History = 正式写入 Canonical（I10）
 *
 * 硬边界：
 *  - 本文件只放**编排层输入 / 输出契约**，不含任何执行逻辑；
 *  - **不复制** ProjectState / ContextRequest / Draft / ChangeArtifact：只引用其身份（ID）与状态；
 *  - **不建立第二套生命周期状态机**：运行生命周期仍由既有 AgentSession 表达（本文件的
 *    [NovelAgentOutcome] 只是**一次 run 的结果分类**，不参与状态迁移）；
 *  - **不建立第二套 Step / Plan 模型**：每步的事实记录复用既有 I4 Activity（见 `NovelAgent` 的固定编排相位）。
 */

/**
 * 一次 Novel Agent 请求（编排层输入）。
 *
 * @param userIntent 用户意图原文（自由文本；由 Novel Agent 经**确定性规则**解析为 [IntentType]，不经 LLM）。
 * @param sessionId 既有会话（I3）：续跑/恢复时传入；为 null 时由 Novel Agent 新建会话。
 * @param taskId 既有 Task 引用（可选；只作为**引用**关联，不承载其生命周期）。
 * @param activeChapterId 当前工作章节（任务焦点）：写作 / 改写 / 分析类任务必需。
 * @param activeVariantId 调用方声明的当前作用域（可选）：必须与 Project 运行态一致（一致性守卫，不复制运行态）。
 * @param budget Context 预算（复用 I6 [ContextBudget]；null = 用 I6 默认）。
 * @param preferredSkillId 调用方指定的 Skill（可选）：必须在 SkillRegistry 匹配结果内，否则类型化拒绝。
 */
@Serializable
data class NovelAgentRequest(
    val projectId: ProjectId,
    val userIntent: String,
    val sessionId: AgentSessionId? = null,
    val taskId: TaskId? = null,
    val activeChapterId: ChapterId? = null,
    val activeVariantId: VariantId? = null,
    val budget: ContextBudget = ContextBudget(),
    val preferredSkillId: SkillId? = null,
)

/**
 * 意图解析结果（**确定性、可解释**：无 LLM、无随机、不读时间）。
 *
 * @param intentType 复用既有 [IntentType]（不新造平行词汇）。
 * @param matchedKeyword 命中的关键词（未命中 → null）。
 * @param reason 判定依据的确定性文案（供 UI 展示与审计"为什么是它"）。
 */
@Serializable
data class NovelIntentAnalysis(
    val intentType: IntentType,
    val matchedKeyword: String? = null,
    val reason: String = "",
)

/**
 * 一次 Novel Agent run 的**结果分类**（不是生命周期状态机）。
 *
 * 生命周期（是否活动 / 暂停 / 完成 / 失败 / 取消）仍由既有 AgentSession 表达；
 * 本枚举只回答"这次 run 结束后调用方该做什么"。
 */
@Serializable
enum class NovelAgentOutcome {
    /** 运行结束且无需人工介入（含：分析 / 规划类任务、无变化、已成功提交）。 */
    COMPLETED,

    /** 变更已就绪并**已桥接进正式章节 Workflow**：等待既有 Human Gate 决议（P0-3B D2）。 */
    WAITING_HUMAN,

    /** 候选已生成但**未能接入**正式 Workflow（P0-3B D5）：候选保留、可显式重试；不是失败、不是待审批。 */
    CANDIDATE_UNBRIDGED,

    /** 运行失败（类型化 [NovelAgentResult.failure]）。 */
    FAILED,

    /** 运行被取消（会话已取消 / 人工拒绝）。 */
    CANCELLED,
}

/**
 * P0-3B · 候选与正式审批的接入状态（D2 / D5；UI 不得仅凭 outcome 判断"是否可批准"）。
 *
 *  - [NOT_REQUIRED]：本次运行不产生正文候选（分析 / 规划 / 无变化 / 已直连提交）。
 *  - [BRIDGED]：候选已进入正式章节 Workflow 的人工门（批准 / 拒绝以 `ChapterWorkflowProgress` 为准）。
 *  - [UNBRIDGED]：候选已生成但未接入正式审批（原因见 [NovelAgentResult.failure]）；不构成正式审批。
 */
@Serializable
enum class NovelAgentApprovalState {
    NOT_REQUIRED,
    BRIDGED,
    UNBRIDGED,
}

/** 类型化失败（稳定错误码 + 细节；不吞异常，不泄漏底层原始异常）。 */
@Serializable
data class NovelAgentFailure(
    val code: String,
    val detail: String,
)

/**
 * O-42 · 前章 Canonical 未纳入续写时的稳定原因。
 *
 * 只用于 [NovelAgentResult.continuationAbsentReason]，不是失败错误码：
 * 出现原因值表示任务**按降级语义继续执行**（不硬拒）。
 */
object NovelAgentContinuationReasons {
    /** 当前章不是章程序列中的第 1 章，但无法定位其紧邻前章（数据异常）。 */
    const val NO_PREVIOUS_CHAPTER: String = "NO_PREVIOUS_CHAPTER"

    /** 紧邻前章存在，但没有已提交的 Canonical。 */
    const val NO_CANONICAL: String = "NO_CANONICAL"

    /** 紧邻前章的 Canonical carrier 不是 CONFIRMED（O-23 情形）。 */
    const val CARRIER_NOT_CONFIRMED: String = "CARRIER_NOT_CONFIRMED"

    /** ContinuationResolver 的类型化校验失败（前章 Canonical 已存在，但引用无法解析）。 */
    const val CONTINUATION_UNRESOLVED: String = "CONTINUATION_UNRESOLVED"
}

/**
 * T-43 · 续步后复评失败的稳定原因（不是任务失败错误码）。
 *
 * 出现原因值表示正文仍已产出并进入人工 Gate，只是“改完是否还有 ERROR”无法回答；
 * 禁止静默。
 */
object NovelAgentPostRevisionFailureCodes {
    /** 复评返回非法 / 缺失字段 / 类型错误的输出。 */
    const val INVALID_OUTPUT: String = "INVALID_OUTPUT"

    /** 复评调用失败（AgentRuntime / Provider 错误等）。 */
    const val CALL_FAILED: String = "CALL_FAILED"
}

/**
 * D-36 · 初次只读分析（analysis_critique）的结论摘要。
 *
 * 只承载可观测的结论字段，不复制 CritiqueResult / issues / 正文。
 */
@Serializable
data class NovelAgentAnalysisSummary(
    val passed: Boolean,
    val issueCount: Int,
    val errorCount: Int,
)

/**
 * 一次 Novel Agent run 的结果（**ID + status + summary**，不复制 ContextPack / Draft / Artifact 大对象）。
 *
 * @param activityIds 本次 run 每个编排相位的事实记录（复用 I4 Activity；顺序 = 执行顺序）。
 * @param toolCallCount 经 I5 Product Tool 发生的工具调用次数（调用事实记录复用 I4 ToolCallLog）。
 * @param planChapterId 规划类任务的产物引用（ChapterPlan 绑定的章节）。
 * @param approvalGateId 需要人工确认时，调用方据此引用**既有** Workflow Human Gate（本层不创建 Gate）。
 */
@Serializable
data class NovelAgentResult(
    val outcome: NovelAgentOutcome,
    val projectId: ProjectId,
    val sessionId: AgentSessionId? = null,
    val intent: NovelIntentAnalysis,
    val skillId: SkillId? = null,
    val contextPackId: String? = null,
    val contextPackVersion: Long? = null,
    val activityIds: List<ActivityId> = emptyList(),
    val toolCallCount: Int = 0,
    val planChapterId: ChapterId? = null,
    /** T-2B-3：CharacterConsistency 只读结果；其他 Skill / 未执行时为 null。 */
    val characterConsistency: CharacterConsistencyResult? = null,
    /** D-36：初次 analysis_critique 结论；非分析任务 / 未执行时为 null。 */
    val analysis: NovelAgentAnalysisSummary? = null,
    /**
     * T-43：续步（rewrite-revision）之后对**修订稿**的复评结论；无续步时为 null。
     * 与 [analysis] 分开，供 UI/下游判断“改完之后是否仍有 ERROR”。
     */
    val postRevisionAnalysis: NovelAgentAnalysisSummary? = null,
    /** T-43：复评失败原因（[NovelAgentPostRevisionFailureCodes]）；正常 / 无续步时为 null。 */
    val postRevisionCheckFailure: String? = null,
    /** D-36：analysis 发现 ERROR 后唯一一次续步的 Skill；未触发时为 null。 */
    val followUpSkillId: SkillId? = null,
    val workingDraftId: WorkingDraftId? = null,
    val artifactId: ChangeArtifactId? = null,
    val artifactStatus: ChangeArtifactStatus? = null,
    /**
     * O-42：本次运行是否把**紧邻前章**的 CONFIRMED Canonical 摘录注入 Planner/Writer。
     * 非续写类任务（分析 / 规划）与第 1 章恒为 false。
     */
    val continuationIncluded: Boolean = false,
    /** O-42：被纳入续写来源的前章（[continuationIncluded]=true 时非 null）。 */
    val continuationSourceChapterId: ChapterId? = null,
    /** O-42：未纳入前章时的显式原因（降级但不静默；第 1 章正常无前章时为 null）。 */
    val continuationAbsentReason: String? = null,
    val approvalGateId: WorkflowHumanGateId? = null,
    /** P0-3B · 候选与正式审批的接入状态（见 [NovelAgentApprovalState]；UI 事实源仍是正式 Workflow 投影）。 */
    val approvalState: NovelAgentApprovalState = NovelAgentApprovalState.NOT_REQUIRED,
    val commitId: CommitId? = null,
    val historyId: CommitHistoryId? = null,
    val summary: String = "",
    val failure: NovelAgentFailure? = null,
)

/**
 * Novel Agent 的稳定错误码（承载在 [NovelAgentFailure.code]）。
 *
 * 只描述"这次编排为什么停下来了"，不描述创作质量判断（创作决策属 P19，本层不涉及）。
 */
object NovelAgentErrorCodes {

    /** Project 不存在。 */
    const val PROJECT_NOT_FOUND: String = "PROJECT_NOT_FOUND"

    /** 会话不存在或不属于本 Project（越界统一表现为不存在，不泄漏存在性）。 */
    const val SESSION_NOT_FOUND: String = "SESSION_NOT_FOUND"

    /** 会话创建 / 关联失败（如引用了不存在的既有 Task）。 */
    const val SESSION_START_FAILED: String = "SESSION_START_FAILED"

    /** 会话当前状态不允许运行（已 COMPLETED / FAILED 等）。 */
    const val SESSION_STATE_INVALID: String = "SESSION_STATE_INVALID"

    /** 会话已被取消。 */
    const val SESSION_CANCELLED: String = "SESSION_CANCELLED"

    /** P0-3B D3：续跑 / 拒绝必须携带原 sessionId（禁止隐式新建会话操作旧候选）。 */
    const val SESSION_REQUIRED: String = "SESSION_REQUIRED"

    /** P0-3B D3：候选（WorkingDraft）不属于请求声明的会话。 */
    const val CANDIDATE_SESSION_MISMATCH: String = "CANDIDATE_SESSION_MISMATCH"

    /** P0-3B D3/D9：候选已被正式提交，不能再拒绝 / 丢弃。 */
    const val CANDIDATE_ALREADY_COMMITTED: String = "CANDIDATE_ALREADY_COMMITTED"

    /** P0-3B D6：候选仍在正式 Gate 待决中；必须先经正式 reject 决议，不能直接丢弃。 */
    const val CANDIDATE_PENDING_GATE: String = "CANDIDATE_PENDING_GATE"

    /** P0-3B D6/D9：候选已获正式批准但尚未观察到提交事实；不能丢弃，需先完成正式收敛。 */
    const val CANDIDATE_APPROVED_NOT_COMMITTED: String = "CANDIDATE_APPROVED_NOT_COMMITTED"

    /** P0-3B D5：候选未能接入正式 Workflow（原因见 detail；候选保留、可显式重试）。 */
    const val BRIDGE_FAILED: String = "BRIDGE_FAILED"

    /** 请求声明的 Variant 作用域与 Project 运行态不一致。 */
    const val SCOPE_MISMATCH: String = "SCOPE_MISMATCH"

    /** 任务类型需要焦点章节，但请求未提供（I11 不自行创建章节）。 */
    const val MISSING_FOCUS_CHAPTER: String = "MISSING_FOCUS_CHAPTER"

    /** SkillRegistry 中没有可处理该意图的 Skill。 */
    const val SKILL_NOT_FOUND: String = "SKILL_NOT_FOUND"

    /** 调用方指定的 Skill 不在本次匹配结果内（未声明该 purpose / 已 disabled）。 */
    const val SKILL_NOT_MATCHED: String = "SKILL_NOT_MATCHED"

    /** 该 Skill 在本阶段没有执行绑定（如世界模型维护属既有 Knowledge Update 链）。 */
    const val SKILL_NOT_EXECUTABLE: String = "SKILL_NOT_EXECUTABLE"

    /** Context 构建失败（越界 / 非法预算等；复用 I6 的类型化拒绝）。 */
    const val CONTEXT_BUILD_FAILED: String = "CONTEXT_BUILD_FAILED"

    /** 请求的工具不在该 Skill 的 `allowedTools` 内（禁止 Skill 任意调用 Tool）。 */
    const val TOOL_NOT_ALLOWED: String = "TOOL_NOT_ALLOWED"

    /** 工具调用失败（拒绝执行之外的业务 / 输出解析失败）。 */
    const val TOOL_FAILED: String = "TOOL_FAILED"

    /** 既有 Agent 执行失败（LLM / 解析 / 运行期错误）。 */
    const val EXECUTION_FAILED: String = "EXECUTION_FAILED"

    /** 该任务需要章节已有 Canonical 正文载体，但不存在。 */
    const val BASE_DRAFT_NOT_FOUND: String = "BASE_DRAFT_NOT_FOUND"

    /** Working Draft 创建 / 校验失败（复用 I8 的类型化拒绝）。 */
    const val WORKING_DRAFT_FAILED: String = "WORKING_DRAFT_FAILED"

    /** Artifact 不存在或不属于本 Project。 */
    const val ARTIFACT_NOT_FOUND: String = "ARTIFACT_NOT_FOUND"

    /** Artifact Validation 存在 ERROR（不可进入后续人工确认流程）。 */
    const val ARTIFACT_INVALID: String = "ARTIFACT_INVALID"

    /** Artifact 生成失败（复用 I9 的类型化拒绝）。 */
    const val ARTIFACT_FAILED: String = "ARTIFACT_FAILED"

    /** I2 ActionPolicy 判定为 Denied。 */
    const val ACTION_DENIED: String = "ACTION_DENIED"

    /** 需要人工确认但缺少有效批准凭据。 */
    const val APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"

    /** Commit 失败（越界 / 过期 / 重复提交 / 事务回滚等；复用 I10 的类型化拒绝）。 */
    const val COMMIT_FAILED: String = "COMMIT_FAILED"
}