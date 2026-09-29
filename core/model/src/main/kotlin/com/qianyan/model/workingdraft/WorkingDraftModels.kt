package com.qianyan.model.workingdraft

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I8 · Working Draft 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §17）：
 *   Canonical Story（正式事实：`ChapterDraft` CONFIRMED / Chapter / StoryFoundation / World Model）
 *        ↓
 *   **Working Draft（Agent / Skill 在一次任务中产生的、尚未提交到 Canonical 的临时创作结果）**
 *        ↓
 *   Validation（确定性结构 / 边界检查）
 *        ↓
 *   （I9 起）Diff / Change Proposal → Human Gate → Commit
 *
 * 为什么**不复用也不扩展**既有 `Draft`（`ChapterDraft` 表）：
 *  1. `Draft` 是**持久化的 canonical 路径内容**：被 `WriterGateway` / `WriterFacade` / Reader 经
 *     `latestByChapter` 读取；把 Agent 临时产物写进去会改变既有写作/阅读流程的结果（污染）；
 *  2. `Draft` 没有 session / activity 归属（§6 可追踪性要求），扩展它需要 schema 变更；
 *  3. architecture §17 记录的缺失项是**统一的"提案/工作区"抽象**（`DESIGNED`），而不是又一个正文持久化模型；
 *  4. 因此 Working Draft 是**内存工作区**（本阶段不落库、不加表、不加迁移）—— 见 §8 / §22。
 *
 * 硬边界：
 *  - Working Draft **不是** Canonical：本文件与 Use Case 均不写 `ChapterDraft` / `Chapter` / `StoryFoundation` / World Model；
 *  - **不是** Agent 生命周期：`status` 只描述"这份临时成果本身"，Agent 生命周期仍由 `AgentSession` / `Activity` 唯一负责；
 *  - **不是** Version 系统：base 只引用既有 `DraftId` / `DraftStatus` / `updatedAt` / `ChapterStatus`（不新建 revision 体系）；
 *  - **不是** Validation（校验在 `WorkingDraftValidator`）、**不是** 权限（ActionPolicy 判定）、**不是** Context（I6 构建）。
 */

/** Working Draft 唯一标识（内存工作区身份键）。 */
@JvmInline
@Serializable
value class WorkingDraftId(val value: String)

/** Working Draft 目标（当前只支持章节；Scene 等属未来扩展，I8 不建模）。 */
@Serializable
data class WorkingDraftTarget(
    val novelId: NovelId,
    val chapterId: ChapterId,
    val variantId: VariantId? = null,
)

/**
 * 基线引用：本 Working Draft 基于哪一份**既有 Canonical 内容 / 状态**产生。
 *
 * 只引用既有标识与字段（`Draft.draftId` / `DraftStatus` / `updatedAt` / `ChapterStatus`），
 * 不复制正文、不新建版本号体系（§6 / §21）。
 */
@Serializable
data class WorkingDraftBase(
    /** 基线草稿（既有 `Draft.draftId`）；新写（无基线）时为 null。 */
    val baseDraftId: DraftId? = null,
    /** 基线草稿状态（既有 `DraftStatus`；可追踪"当时是什么状态"）。 */
    val baseDraftStatus: DraftStatus? = null,
    /** 基线草稿的 `updatedAt`（可追踪"基于哪个版本"，复用既有字段而非新版本号）。 */
    val baseDraftUpdatedAt: Instant? = null,
    /** 基线章节状态（既有 `ChapterStatus`）。 */
    val baseChapterStatus: ChapterStatus? = null,
)

/**
 * Working Draft 生命周期（只表达"这份临时成果本身"，**不复用** Agent 的 CREATED/RUNNING/PAUSED/COMPLETED/FAILED）。
 *
 * `WORKING` → `VALIDATED` / `INVALID`；任意非终态 → `DISCARDED`（终态）。
 */
@Serializable
enum class WorkingDraftStatus {
    WORKING,
    VALIDATED,
    INVALID,
    DISCARDED,
    ;

    /** 终态：不可再修改 / 再校验（与既有 `AgentLogStatus` 的终态惯例一致）。 */
    val isTerminal: Boolean get() = this == DISCARDED
}

/**
 * Working Draft：Agent / Skill 在一次任务中产生、**尚未提交到 Canonical Story** 的临时创作结果。
 *
 * @param projectId Project 归属（Project 隔离的锚）。
 * @param sessionId / activityId 归属关系（可选；复用 I3 / I4 身份，不复制其状态）。
 * @param target 目标章节（`novelId` 必须与 `projectId` 解析出的 Novel 一致）。
 * @param base 基线引用（可追踪性；见 [WorkingDraftBase]）。
 * @param content 临时正文（**不落库**；Validation 的检查对象）。
 * @param status 本成果自身状态（见 [WorkingDraftStatus]）。
 */
@Serializable
data class WorkingDraft(
    val workingDraftId: WorkingDraftId,
    val projectId: ProjectId,
    val target: WorkingDraftTarget,
    val base: WorkingDraftBase = WorkingDraftBase(),
    val content: String,
    val status: WorkingDraftStatus = WorkingDraftStatus.WORKING,
    val sessionId: AgentSessionId? = null,
    val activityId: ActivityId? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Validation 结果码（稳定契约；`ValidationIssue.field` 承载本码 —— 见 `WorkingDraftValidator`）。 */
object WorkingDraftValidationCodes {

    // 状态
    const val INVALID_STATE = "INVALID_STATE"

    // 内容（确定性、无 IO）
    const val EMPTY_CONTENT = "EMPTY_CONTENT"
    const val CONTENT_TOO_LONG = "CONTENT_TOO_LONG"
    const val CONTROLLED_MARKDOWN_DEGRADED = "CONTROLLED_MARKDOWN_DEGRADED"
    const val UNCHANGED_FROM_BASE = "UNCHANGED_FROM_BASE"

    // 作用域 / 边界
    const val PROJECT_SCOPE_MISMATCH = "PROJECT_SCOPE_MISMATCH"
    const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    const val CHAPTER_SCOPE_MISMATCH = "CHAPTER_SCOPE_MISMATCH"
    const val SESSION_SCOPE_MISMATCH = "SESSION_SCOPE_MISMATCH"
    const val ACTIVITY_SCOPE_MISMATCH = "ACTIVITY_SCOPE_MISMATCH"
    const val BASE_DRAFT_NOT_FOUND = "BASE_DRAFT_NOT_FOUND"
}

/** Working Draft 的物理上限（**不是**文学质量判断，只拦截明显非法输入 —— §10 C）。 */
object WorkingDraftLimits {
    /** 正文长度上限（字符数）：超出视为极端异常输入。 */
    const val MAX_CONTENT_CHARS: Int = 200_000
}