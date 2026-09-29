package com.qianyan.model.change

import com.qianyan.model.ProjectId
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.workingdraft.WorkingDraftBase
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I9 · Diff / Change / Artifact 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §19 / §26）：
 *   Working Draft（I8 临时成果）
 *        ↓
 *   **Diff**（相对基线的实际内容变化；确定性、最小）
 *        ↓
 *   **Change**（一项可被审查的结构化变更）
 *        ↓
 *   **Change Artifact**（给 UI / Human Gate / 后续阶段的变更提案载体）
 *        ↓
 *   （I10 起）Human Gate → Atomic Commit → History / Revert
 *
 * 硬边界：
 *  - **只读 + 派生**：本层不写任何 Canonical 数据（Chapter / ChapterDraft / ProjectState / StoryFoundation /
 *    AgentSession / Activity / Workflow / Task），也不创建新的 Canonical Draft；
 *  - **不执行 Commit / History / Revert / Undo**（I10 职责）；Artifact 只是**提案载体**，不是执行命令；
 *  - **不复用也不重造 Validation**：直接携带既有 [ValidationResult]（I8 已复用同一模型）；
 *  - **不复用也不重造 Human Gate / ActionPolicy**：[ChangeArtifactStatus] 只表达由**事实**确定性派生的
 *    "能否进入后续人工确认流程"，不表达"用户是否批准"（那是 I2 Human Gate / I10 的事）；
 *  - **无第二套状态机**：Artifact 状态只描述变更审查就绪度，与 Agent / Workflow / Task 生命周期无关。
 *
 * 关于既有 Change 类原语的取舍（Audit 结论）：
 *  - `KnowledgeOperation{ADD,UPDATE,REMOVE}` + `CandidateKnowledgeChange` 属**知识/世界事实域**
 *    （target 主题 + category + confidence），无 `unchanged` / `modified` 语义，粒度是"事实变更"而非"正文文本变更"；
 *  - `FoundationProposal` / `FoundationDecisionField` 属**故事基础域**（字段级 GENRE/DIRECTION/AUDIENCE/POLICY）；
 *  - 二者都绑定各自域的 Gate 流程，直接复用于"章节正文 Diff"会造成语义错配，故 I9 建立独立的最小
 *    [ChangeKind] / [DiffLineKind]（仅表达文本层变化性质）。
 */

/** 一个 Change 的整体性质（由 Diff 形状确定性派生；[TextDiff.op] 与 [Change.kind] 共用同一取值）。 */
@Serializable
enum class ChangeKind {
    /** 无任何变化（内容等价：含换行归一化后相同、或双方均为空白）。 */
    UNCHANGED,

    /** 只有新增（基线为空，或在保留内容中间纯插入）。 */
    ADDED,

    /** 只有删除（当前为空，或纯删除）。 */
    REMOVED,

    /** 既有删除又有新增（原位修改 / 重写）。 */
    MODIFIED,
}

/** Diff hunk 的行级操作（不含整体语义 MODIFIED —— 该语义由 [ChangeKind] 在整体层表达）。 */
@Serializable
enum class DiffLineKind {
    UNCHANGED,
    ADDED,
    REMOVED,
}

/**
 * 一段连续的同操作行（Diff 的最小可审查单元）。
 *
 * @param lines 该段原文（已按 LF 归一化；不含行号）。
 * @param baseStartLine 基线侧 1-based 起始行；[DiffLineKind.ADDED] 时为 null。
 * @param workingStartLine 当前侧 1-based 起始行；[DiffLineKind.REMOVED] 时为 null。
 */
@Serializable
data class DiffHunk(
    val kind: DiffLineKind,
    val lines: List<String>,
    val baseStartLine: Int? = null,
    val workingStartLine: Int? = null,
)

/**
 * 文本 Diff（确定性；由 `TextDiffer` 产出）。
 *
 * hunk 顺序固定为：不变前缀 → 删除段 → 新增段 → 不变后缀（同位置不存在时省略）。
 */
@Serializable
data class TextDiff(
    /** 整体性质（由 hunk 形状派生）。 */
    val op: ChangeKind,
    val hunks: List<DiffHunk> = emptyList(),
    val unchangedLines: Int = 0,
    val addedLines: Int = 0,
    val removedLines: Int = 0,
) {
    /** 是否存在实际变化。 */
    val hasChanges: Boolean get() = op != ChangeKind.UNCHANGED
}

/**
 * Change：一项**可被审查**的结构化变更（Value Object；身份由其所在的 [ChangeArtifact] 承载）。
 *
 * 不复制内容本体：只携带 Working Draft 身份 / target / 基线引用 / Diff / Validation / 审计状态。
 */
@Serializable
data class Change(
    val projectId: ProjectId,
    val workingDraftId: WorkingDraftId,
    /** 目标章节（复用 I8 target）。 */
    val target: WorkingDraftTarget,
    /** 基线引用（复用 I8 base reference；不新建版本体系）。 */
    val base: WorkingDraftBase = WorkingDraftBase(),
    /** 相对基线的文本变化。 */
    val diff: TextDiff,
    /** 生成本 Change 时**从当前 Working Draft 重新执行的确定性校验结果**（复用既有模型，永不使用过期快照）。 */
    val validation: ValidationResult,
    /** 生成时的 Working Draft 自身状态（审计用；不复制其生命周期）。 */
    val workingDraftStatus: WorkingDraftStatus = WorkingDraftStatus.WORKING,
) {
    /** 变更性质（= [TextDiff.op]，不重复存储）。 */
    val kind: ChangeKind get() = diff.op

    /** 确定性可读摘要（派生；不存储）。 */
    val summary: String get() = when (diff.op) {
        ChangeKind.UNCHANGED -> "与基线内容完全相同（无变化）"
        ChangeKind.ADDED -> "新增 ${diff.addedLines} 行（基线无对应内容）"
        ChangeKind.REMOVED -> "删除 ${diff.removedLines} 行（当前无内容）"
        ChangeKind.MODIFIED -> "修改：新增 ${diff.addedLines} 行 / 删除 ${diff.removedLines} 行（保留 ${diff.unchangedLines} 行）"
    }
}

/** Change Artifact 唯一标识（内存审查载体身份键）。 */
@JvmInline
@Serializable
value class ChangeArtifactId(val value: String)

/**
 * Artifact 审查就绪状态（**只表达变更审查就绪度**；由事实确定性派生）。
 *
 * 有意**不含** `REVIEWED` / `REJECTED`：那是 Human Gate 的人工决议（I2 / I10 拥有），
 * I9 不得把"用户是否批准"变成自己的判断。
 */
@Serializable
enum class ChangeArtifactStatus {
    /** 有实际变化且 Validation 无 ERROR ⇒ 可进入后续人工确认流程。 */
    READY,

    /** 与基线完全相同；没有可审查的变化。 */
    NO_CHANGES,

    /** Validation 存在 ERROR（结构 / 边界不满足）⇒ 不可进入后续流程。 */
    INVALID,
}

/**
 * Change Artifact：给 UI / Human Gate / 后续 Commit **使用的变更提案载体**。
 *
 * 回答：改了什么（[Change.diff] / [Change.summary]）、改的是哪里（[Change.target]）、
 * 基于哪个既有内容（[Change.base]）、当前 Working Draft 是什么（[Change.workingDraftId]）、
 * 是否通过 Validation（[Change.validation]）、能否进入后续人工确认流程（[status]）。
 *
 * **不得直接修改 Canonical Story**；本对象是数据，不是命令。
 */
@Serializable
data class ChangeArtifact(
    val artifactId: ChangeArtifactId,
    val change: Change,
    val status: ChangeArtifactStatus,
    /** 输入指纹（确定性；相同输入 ⇒ 相同值）—— 用于"同输入同 Artifact"与"是否已被更新取代"判定。 */
    val fingerprint: Long,
    val createdAt: Instant,
) {
    val projectId: ProjectId get() = change.projectId
    val workingDraftId: WorkingDraftId get() = change.workingDraftId

    /** 是否可进入后续人工确认流程（= [ChangeArtifactStatus.READY]；**不是**决议）。 */
    val readyForReview: Boolean get() = status == ChangeArtifactStatus.READY
}

/** Change Artifact 状态的确定性派生（唯一来源；避免状态与事实不一致）。 */
object ChangeArtifacts {

    fun statusOf(change: Change): ChangeArtifactStatus = when {
        !change.diff.hasChanges -> ChangeArtifactStatus.NO_CHANGES
        !change.validation.passed -> ChangeArtifactStatus.INVALID
        else -> ChangeArtifactStatus.READY
    }
}

/**
 * 确定性输入指纹（FNV-1a；与既有 `ChapterContextProjector.inputVersion` / `ContextPackVersion` 同一范式）。
 *
 * 覆盖所有影响 Artifact 内容的输入（Project / Working Draft / target / 基线 / Diff / Validation / Draft 状态），
 * 不覆盖 `artifactId` / `createdAt`（它们是身份与时间，不参与内容等价性）。
 */
object ChangeFingerprint {

    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L
    private const val FNV_PRIME_64 = 1099511628211L

    fun of(change: Change): Long {
        val fp = buildString {
            append(change.projectId.value).append('|')
            append(change.workingDraftId.value).append('|')
            append(change.target.novelId.value).append('|')
            append(change.target.chapterId.value).append('|')
            append(change.target.variantId?.value).append('|')
            append(change.base.baseDraftId?.value).append('|')
            append(change.base.baseDraftStatus?.name).append('|')
            append(change.base.baseDraftUpdatedAt?.toString()).append('|')
            append(change.base.baseChapterStatus?.name).append('|')
            append(change.diff.op.name).append('|')
            change.diff.hunks.forEach { hunk ->
                append(hunk.kind.name).append('#')
                    .append(hunk.baseStartLine).append('#')
                    .append(hunk.workingStartLine).append('#')
                    .append(hunk.lines.joinToString("\u0001")).append(';')
            }
            append('|')
            append(change.diff.unchangedLines).append('#')
                .append(change.diff.addedLines).append('#')
                .append(change.diff.removedLines).append('|')
            append(change.validation.passed).append('|')
            change.validation.issues.forEach { issue ->
                append(issue.field).append('#').append(issue.severity.name).append('#').append(issue.message).append(';')
            }
            append('|')
            append(change.workingDraftStatus.name)
        }
        return fnv1a64(fp)
    }

    private fun fnv1a64(s: String): Long {
        var h = FNV_OFFSET_BASIS_64
        for (c in s) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME_64
        }
        return h
    }
}