package com.qianyan.model.workspace

import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.IntentType
import com.qianyan.model.TaskId
import com.qianyan.model.ToolCallId
import com.qianyan.model.VariantId
import com.qianyan.model.ActivityId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.change.ChangeKind
import com.qianyan.model.change.DiffLineKind
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.commit.CommitOperation
import com.qianyan.model.context.ContextItemRef
import com.qianyan.model.context.ContextPriority
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.log.AgentLogStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I14 · Context Inspector 的**只读投影**模型（纯领域数据；无 storage / provider / agent / UI 依赖）。
 *
 * 关键区分（§3）：
 *   **Context Domain Model**（I6：ContextRequest / ContextItem / ContextPack / ContextSelector）
 *   ≠
 *   **Context Inspector View Model**（本文件：供 Workspace / Inspector 消费的投影）
 *
 * 投影模型**不重新选择 Context**：条目与预算一律来自既有 `ContextPack` / `ContextBudgetGuard`
 * （不在 UI 里再写 limit / slice 之类的第二套选择算法，§10）。`preview` 只做**展示用**截断，不改变 pack 内容。
 *
 * 投影模型**不重新建模** Activity / Change / History / Task：字段直接来自
 * I4 Activity·ToolCallLog、I9 ChangeArtifact、I10 CommitHistory（§11 / §12）。
 */

/* ---------------- Context Tab（I6 投影） ---------------- */

/** 一条 Context 条目投影（来自既有 `ContextPack.items`）。 */
@Serializable
data class InspectorContextEntry(
    val itemId: String,
    val source: ContextSourceKind,
    /** 人类可读标题（由来源 + 稳定引用派生；不是第二套领域数据）。 */
    val title: String,
    /** 展示用内容预览（可能被截断；**不放回** Context，也不改变 pack）。 */
    val preview: String,
    val priority: ContextPriority,
    val estimatedTokens: Long,
    val sourceVersion: String,
    /** 入选 / 排序依据（来自 I6 `ContextItem.reason`；用于回答"为什么它进了 Context"）。 */
    val reason: String,
    val ref: ContextItemRef? = null,
)

/** 按来源分组的 Context 条目（分组顺序 = [ContextSourceKind] 声明序，确定性）。 */
@Serializable
data class InspectorContextGroup(
    val source: ContextSourceKind,
    val title: String,
    val entries: List<InspectorContextEntry>,
)

/** 预算展示（**直接来自 I6 `ContextBudgetGuard` / `ContextBudget`**，UI 不重算选择）。 */
@Serializable
data class InspectorBudgetView(
    val budgetTokens: Long,
    val estimatedTokens: Long,
    val isTruncated: Boolean,
    val omittedCount: Int,
    val omittedItemIds: List<String>,
    val estimator: String,
) {
    /** 已用比例（仅展示；不参与任何选择决策）。 */
    val usedRatio: Float get() = if (budgetTokens <= 0L) 0f else (estimatedTokens.toFloat() / budgetTokens).coerceIn(0f, 1f)
}

/** Context Tab 的一次完整投影。 */
@Serializable
data class InspectorContextView(
    val selection: WorkspaceSelection,
    /** 焦点对象标题（如「第 87 章 · 夜袭」）。 */
    val focusLabel: String,
    val purpose: IntentType,
    val packId: String,
    val packVersion: Long,
    /** 所属小说标题（来自既有 Novel）。 */
    val novelTitle: String,
    val activeVariantId: VariantId? = null,
    val groups: List<InspectorContextGroup> = emptyList(),
    val budget: InspectorBudgetView,
    /** 如实说明（例如"该对象当前没有稳定 Canonical 来源"）；**不伪造**数据（§16）。 */
    val notices: List<String> = emptyList(),
)

/**
 * Phase 4B.1a：Inspector 任务上下文的可用状态。
 *
 * - [NO_TASK]：当前选择没有可展示的 Agent 任务；
 * - [PACK_UNAVAILABLE]：任务存在，但实际任务 Pack 不可获取；
 * - [RUNNING_WITHOUT_PACK]：任务仍在执行，但尚未产生可展示的 Pack；
 * - [PACK_AVAILABLE]：真实任务 Pack 可获取（A2：从 ContextPackStore 读取；无记录时不可达）。
 *
 * 该枚举只为状态表达而存在，不承载 Pack 本体，也不改变 Agent/ContextPack 契约。
 */
@Serializable
enum class InspectorTaskContextState {
    NO_TASK,
    PACK_UNAVAILABLE,
    RUNNING_WITHOUT_PACK,
    PACK_AVAILABLE,
}

/** Inspector 任务上下文状态投影（不包含 Pack 内容；不伪造版本号 / 条目）。 */
@Serializable
data class InspectorTaskContextView(
    val state: InspectorTaskContextState,
    /** 面向作者的占位说明；不得包含伪造的 Pack 信息。 */
    val message: String,
    /** 关联的既有 Task / AgentSession 引用；仅供追溯，不展示内部 ID。 */
    val taskId: TaskId? = null,
    val sessionId: AgentSessionId? = null,
)

/** 当前小说事实的只读展示项（与 Agent 任务快照严格分开）。 */
@Serializable
data class InspectorCurrentFact(
    val label: String,
    val value: String,
)


/* ---------------- Activity Tab（I4 投影） ---------------- */

/** Tool 调用投影（来自既有 `ToolCallLog`）。 */
@Serializable
data class InspectorToolCallView(
    val toolCallId: ToolCallId,
    val toolName: String,
    val status: AgentLogStatus,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val inputSummary: String? = null,
    val outputSummary: String? = null,
    val error: String? = null,
)

/** 活动投影（来自既有 `Activity`）。 */
@Serializable
data class InspectorActivityView(
    val activityId: ActivityId,
    val sessionId: AgentSessionId,
    val kind: String,
    val title: String,
    val status: AgentLogStatus,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val summary: String? = null,
    val error: String? = null,
    val toolCalls: List<InspectorToolCallView> = emptyList(),
)

/* ---------------- Changes Tab（I9 / I10 投影） ---------------- */

/** Diff 行投影（来自既有 I9 `TextDiff.hunks`）。 */
@Serializable
data class InspectorDiffLineView(
    val kind: DiffLineKind,
    val text: String,
)

/** 待确认修改投影（来自既有 I9 `ChangeArtifact`）。 */
@Serializable
data class InspectorChangeView(
    val artifactId: ChangeArtifactId,
    val kind: ChangeKind,
    val title: String,
    val summary: String,
    val status: ChangeArtifactStatus,
    val addedLines: Int,
    val removedLines: Int,
    val createdAt: Instant,
    val targetChapterId: ChapterId? = null,
    val validationPassed: Boolean,
    val diffLines: List<InspectorDiffLineView> = emptyList(),
    val note: String? = null,
)

/** 已提交历史投影（来自既有 I10 `CommitHistoryEntry`；**只读**，Revert 仍走 I10）。 */
@Serializable
data class InspectorHistoryView(
    val historyId: CommitHistoryId,
    val operation: CommitOperation,
    val title: String,
    val summary: String,
    val chapterId: ChapterId,
    val createdAt: Instant,
    val revertedHistoryId: CommitHistoryId? = null,
)

/* ---------------- 一次 Inspector 读取的完整结果 ---------------- */

/**
 * Inspector 一次读取的完整投影（三个 tab 的数据都在这里；**UI 只负责切 tab**，§8）。
 *
 * [notices] 用于如实说明"暂无数据 / 没有稳定来源"，UI 不得据此伪造内容（§16）。
 */
@Serializable
data class WorkspaceInspectorView(
    val selection: WorkspaceSelection,
    val title: String,
    val detail: String = "",
    /** A2：真实任务 Pack 投影；无 Store 记录时为 null（不得用重建 Pack 填充）。 */
    val context: InspectorContextView? = null,
    /** A2：任务上下文四状态；不包含 Pack 本体。 */
    val taskContext: InspectorTaskContextView? = null,
    /** 当前小说事实（独立只读，不是 Agent 任务快照）。 */
    val currentFacts: List<InspectorCurrentFact> = emptyList(),
    /** TASK 选择时的任务投影（状态仍以既有 Task / TaskQueueItem 为准，§19）。 */
    val task: InspectorTaskView? = null,
    val activities: List<InspectorActivityView> = emptyList(),
    val changes: List<InspectorChangeView> = emptyList(),
    val history: List<InspectorHistoryView> = emptyList(),
    val notices: List<String> = emptyList(),
) {
    /** 当前对象是否有任何可展示的修改 / 历史（供 UI 显示空态）。 */
    val hasChangeData: Boolean get() = changes.isNotEmpty() || history.isNotEmpty()
}

/** Task 选择的附加投影（Task 状态仍以既有 Task / TaskQueueItem 为准，§19）。 */
@Serializable
data class InspectorTaskView(
    val taskId: TaskId,
    val taskType: String,
    val taskStatus: String,
    val progress: Float,
    val queueStatus: String? = null,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
    val claimedBy: String? = null,
    val lastError: String? = null,
)