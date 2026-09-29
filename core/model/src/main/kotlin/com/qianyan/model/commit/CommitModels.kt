package com.qianyan.model.commit

import com.qianyan.model.DraftId
import com.qianyan.model.ProjectId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.WorkflowHumanGateId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I10 · Canonical Commit + History / Revert 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §19 / §20 / §26）：
 *   Change Artifact（I9 冻结审查载体）
 *        ↓
 *   **Commit Request（由 Artifact 派生；不可篡改）**
 *        ↓
 *   ActionPolicy / Human Gate（I2 复用）
 *        ↓
 *   **Atomic Commit**（唯一 Canonical 写入入口）
 *        ↓
 *   Canonical Story（Draft 正文版本链）
 *        ↓
 *   **Commit History**（Canonical 为何变化的事实记录）
 *        ↓
 *   Revert（一次**新的** Commit；绝不删除历史）
 *
 * 硬边界：
 *  - **唯一入口**：Canonical 正文只能经 `CommitUseCases.commit(...)` 写入；输入必须是 I9 [ChangeArtifactId]，
 *    不接受调用方直接构造正文；
 *  - **不复用也不重造权限**：Commit / Revert 的放行判定复用 I2 `ActionPolicyUseCases`，
 *    人工确认复用**既有** Workflow Human Gate（见 [CommitApproval]）；本文件不定义第二套 Policy / Gate 状态机；
 *  - **不复用也不重造历史模型**：History 只描述"Canonical 为何变化"，
 *    不复制整套 Canonical 数据之外的内容，也不建立 Git-like 版本控制系统；
 *  - **无第二套状态机**：本层不定义 AgentSession / Workflow / Task / WorkingDraft / Artifact 的生命周期。
 */

/** 一次 Canonical Commit 的身份（写入 History 与调用方返回值的稳定标识）。 */
@JvmInline
@Serializable
value class CommitId(val value: String)

/** 一条 Commit History 记录的身份。 */
@JvmInline
@Serializable
value class CommitHistoryId(val value: String)

/**
 * 历史条目的操作类别。
 *
 * 有意只区分两种：Revert **不是**删除历史，而是产生一条新的 [REVERT] 历史（§10）。
 */
@Serializable
enum class CommitOperation {
    /** 由 Change Artifact 触发的正式提交。 */
    COMMIT,

    /** 由既有历史触发的回退提交（本人亦是一条新的 Canonical Commit）。 */
    REVERT,
}

/**
 * 一条 Commit History 记录：**回答 Canonical 为什么发生了变化**（§8）。
 *
 * 能回答：谁（[operation] / [artifactId]）基于什么（[artifactId]）改了什么（[summary]）、
 * 修改前是什么（[previousContent]）、修改后是什么（[resultingContent]）、什么时候（[createdAt]）、
 * 属于哪个 Project（[projectId]）、Target 是什么（[target]）、是否来自 Revert（[operation] / [revertedHistoryId]）。
 *
 * [previousContent] / [resultingContent] 是**恢复所必需**的最小快照（§9）：没有它们，Revert 无法重建
 * "该历史之前的 Canonical 内容"。它们不复制 Draft 元数据（lineage / status / format 仍以 ChapterDraft 为唯一事实来源）。
 */
@Serializable
data class CommitHistoryEntry(
    val historyId: CommitHistoryId,
    val commitId: CommitId,
    val projectId: ProjectId,
    val operation: CommitOperation,
    /** 触发本次提交的 Change Artifact；[CommitOperation.REVERT] 时无 Artifact（为 null）。 */
    val artifactId: ChangeArtifactId? = null,
    /** Canonical 目标（复用 I8 target；不新建 target 模型）。 */
    val target: WorkingDraftTarget,
    /** 提交前的 Canonical 正文载体（无 → null）。 */
    val previousDraftId: DraftId? = null,
    /** 提交后新建的 Canonical 正文载体（版本链 head）。 */
    val resultingDraftId: DraftId,
    /** 提交前的 Canonical 正文（无 → 空串）。 */
    val previousContent: String,
    /** 提交后的 Canonical 正文。 */
    val resultingContent: String,
    /** 确定性可读摘要（来源 Artifact 摘要或 Revert 摘要）。 */
    val summary: String,
    /** [CommitOperation.REVERT] 时：本次回退针对哪一条历史；[CommitOperation.COMMIT] 时为 null。 */
    val revertedHistoryId: CommitHistoryId? = null,
    val createdAt: Instant,
)

/**
 * 人工批准凭据：复用**既有** Workflow Human Gate 记录（不新建 Gate、不新建审批表、不修改其状态语义）。
 *
 * 依据 §13：`COMMIT_CANONICAL` / `MODIFY_CANONICAL` 属高风险动作，I2 判定为 `NeedsHuman` 时
 * 必须先经既有 Human Gate 得到 `RESOLVED + APPROVED`，本凭据只是那一决议的**引用**。
 */
@Serializable
data class CommitApproval(val gateId: WorkflowHumanGateId)

/**
 * Commit 预备结果（`prepareCommit` 产物）：**事实**，不是执行命令。
 *
 * 供 UI / Human Gate 审查"这次要写什么、基于什么、结果是什么"，本身不修改任何数据。
 */
@Serializable
data class CommitProposal(
    val artifactId: ChangeArtifactId,
    val projectId: ProjectId,
    val workingDraftId: WorkingDraftId,
    val target: WorkingDraftTarget,
    val summary: String,
    /** 基线（当前 Canonical 正文载体）；无 → null（本次为章节首次写入）。 */
    val previousDraftId: DraftId? = null,
    /** 当前 Canonical 正文（无 → 空串）。 */
    val previousContent: String,
    /** 本次将写入的 Canonical 正文。 */
    val resultingContent: String,
)

/** Commit 成功结果（§7：commitId / artifactId / projectId / target / 前后 Canonical 引用 / createdAt / 历史条目）。 */
@Serializable
data class CommitResult(
    val commitId: CommitId,
    val artifactId: ChangeArtifactId,
    val projectId: ProjectId,
    val target: WorkingDraftTarget,
    val previousDraftId: DraftId? = null,
    val resultingDraftId: DraftId,
    val createdAt: Instant,
    val historyEntry: CommitHistoryEntry,
)

/**
 * Revert 预备结果（`prepareRevert` 产物）：**事实**，不是执行命令（§12：不让 `revert(historyId)` 直接改 Canonical）。
 */
@Serializable
data class RevertProposal(
    val projectId: ProjectId,
    /** 被回退的既有历史（COMMIT）。 */
    val history: CommitHistoryEntry,
    /** 当前 Canonical 正文载体（冲突判定基线）。 */
    val currentDraftId: DraftId,
    /** 当前 Canonical 正文。 */
    val currentContent: String,
    /** 将恢复到的正文（= 该历史记录的 `previousContent`）。 */
    val restoreContent: String,
    val summary: String,
)

/** Revert 成功结果（本人亦是一次新的 Canonical Commit）。 */
@Serializable
data class RevertResult(
    val commitId: CommitId,
    val projectId: ProjectId,
    val target: WorkingDraftTarget,
    /** 本次回退针对的历史（原历史**保留**）。 */
    val revertedHistoryId: CommitHistoryId,
    val previousDraftId: DraftId? = null,
    val resultingDraftId: DraftId,
    val createdAt: Instant,
    val historyEntry: CommitHistoryEntry,
)

/**
 * Commit / Revert 的稳定错误码（承载在 [com.qianyan.application.error.ApplicationError] 的 detail 中）。
 *
 * 只描述"Canonical 写入是否安全"，不描述"用户是否喜欢这次创作"（§13）。
 */
object CommitErrorCodes {

    /** Artifact 的基线 / Working Draft 已被后续变化取代（禁止自动 merge / 覆盖 / 静默重算）。 */
    const val STALE_ARTIFACT: String = "STALE_ARTIFACT"

    /** 同一 Artifact 已被成功提交过（不产生重复 Canonical Draft / 重复 History）。 */
    const val ALREADY_COMMITTED: String = "ALREADY_COMMITTED"

    /** Artifact 与基线无任何变化（无可提交内容）。 */
    const val NO_CHANGES: String = "NO_CHANGES"

    /** Artifact 未通过 Validation（存在 ERROR）。 */
    const val INVALID_ARTIFACT: String = "INVALID_ARTIFACT"

    /** Working Draft 已处于终态（DISCARDED），不可提交。 */
    const val DISCARDED_WORKING_DRAFT: String = "DISCARDED_WORKING_DRAFT"

    /** 目标 Chapter 非法（不存在 / 不属于 target Novel / 作用域不一致）。 */
    const val INVALID_TARGET: String = "INVALID_TARGET"

    /** 当前 Canonical 已不含该历史记录的"修改后"状态 ⇒ 不可静默覆盖用户的新内容。 */
    const val REVERT_CONFLICT: String = "REVERT_CONFLICT"

    /** 仅 [CommitOperation.COMMIT] 历史可被回退（回退一条 Revert 记录需另立语义，本阶段不定义）。 */
    const val INVALID_REVERT_TARGET: String = "INVALID_REVERT_TARGET"

    /** I2 ActionPolicy 判定为 `Denied`：当前范围不允许该动作。 */
    const val ACTION_DENIED: String = "ACTION_DENIED"

    /** I2 ActionPolicy 判定为 `NeedsHuman`，但未提供（或未通过）既有 Human Gate 的批准凭据。 */
    const val HUMAN_APPROVAL_REQUIRED: String = "HUMAN_APPROVAL_REQUIRED"

    /** 调用方传入的 Project 与目标对象不一致（越界统一表现为不存在，不泄漏存在性）。 */
    const val PROJECT_SCOPE_MISMATCH: String = "PROJECT_SCOPE_MISMATCH"
}