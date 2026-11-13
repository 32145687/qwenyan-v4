package com.qianyan.app.desktop.ui.write

import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.writing.WriterPendingProposal
import com.qianyan.model.DraftId
import com.qianyan.model.workflow.HumanDecision

/**
 * Desktop Writer 任务状态（P20-PC2）。
 *
 * **不是第二套状态机**：本枚举是 durable Workflow 的 [ChapterPhase] → 用户可读文案的纯投影，
 * 唯一的事实来源仍是 Workflow / Task / Checkpoint（FD-2）。
 */
enum class WriterTaskStatus(val label: String) {
    IDLE("准备"),
    PLANNING("规划中"),
    WRITING("写作中"),
    CRITIQUE("审校中"),
    REVISION("修订中"),
    WAITING_CONFIRMATION("等待人工确认"),
    UPDATING_STORY("更新故事状态"),
    COMPLETED("完成"),
    FAILED("失败"),
    ;

    companion object {
        /** [ChapterPhase] → Desktop 状态（纯映射，无业务判断）。 */
        fun of(phase: ChapterPhase): WriterTaskStatus = when (phase) {
            ChapterPhase.NOT_STARTED -> IDLE
            ChapterPhase.PLANNING -> PLANNING
            ChapterPhase.WRITING -> WRITING
            ChapterPhase.REVIEWING -> CRITIQUE
            ChapterPhase.REVISING -> REVISION
            ChapterPhase.WAITING_CONFIRMATION -> WAITING_CONFIRMATION
            ChapterPhase.UPDATING_STORY -> UPDATING_STORY
            ChapterPhase.COMPLETED -> COMPLETED
            ChapterPhase.FAILED -> FAILED
        }
    }
}

/** T-O82-F：失败来源动作（「重试」按同一动作重新发起，不猜测用户意图）。 */
enum class WriterFailureAction { CONTINUE_WRITING, REWRITE }

/** T-O108（A-32）：批准后后台收尾的用户可见阶段（纯投影，不是新状态机）。 */
enum class WriterPostCommitState { NONE, PENDING, FAILED }

/**
 * Desktop Writer UI 状态（P20-PC2）。
 *
 * 内容全部来自 [com.qianyan.application.usecase.writing.WriterGateway] 的
 * [com.qianyan.application.usecase.writing.WriterChapterContext] 与
 * [com.qianyan.application.usecase.workflow.ChapterWorkflowGateway] 的进度投影；
 * 不含 Repository / SQL / Provider / Agent / Workflow 内部对象。
 *
 * [draftContent] 是唯一的本地编辑缓冲（未保存修改由 [dirty] 标记）。
 */
data class WriterUiState(
    val novelTitle: String = "",
    val chapterTitle: String = "",
    val chapterOrder: Int = 0,
    val draftId: DraftId? = null,
    /** Draft 生命周期（DraftStatus.name，真实字段，不由 UI 推导）。 */
    val draftStatus: String? = null,
    /** P2/FD-1 正文格式：null = legacy 纯文本；`markdown:controlled:v1` = 受控 Markdown v1。 */
    val draftFormat: String? = null,
    val draftContent: String = "",
    val dirty: Boolean = false,
    val taskStatus: WriterTaskStatus = WriterTaskStatus.IDLE,
    /** durable Workflow 人工门：等待用户确认（HITL，不自动通过）。 */
    val waitingForUser: Boolean = false,
    /** O-90：当前/最近一次 Gate 决议（PENDING/APPROVED/REJECTED/REQUEST_REVISION；无 Gate → null）。 */
    val pendingGateDecision: HumanDecision? = null,
    /** AI 候选审查投影（原文 / 候选 / Diff）；仅在 Gate PENDING 时非空。 */
    val pendingProposal: WriterPendingProposal? = null,
    val revisionCount: Int = 0,
    val error: String? = null,
    /**
     * T-O82-F：**最近一次写作 / 改写失败**的可见摘要（≤200 字，单行化）。
     * 成功或停在人工门时一律清空（不残留上次失败）。
     */
    val failure: String? = null,
    /** 失败来源动作；「重试」据此重新触发同一动作。 */
    val failureAction: WriterFailureAction? = null,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val isGenerating: Boolean = false,
    val isApproving: Boolean = false,
    val isRejecting: Boolean = false,
    /**
     * Gate 决议成功后的短时防抖锁：阻止同一次双击的第二击穿透到「继续写 / 让 Qianyan 修改」。
     * 只锁生成类动作，不影响保存 / 只读。
     */
    val decisionLocked: Boolean = false,
    /** T-O108：后台收尾（WMU → KU）阶段；决议后 UI 立即恢复，此字段驱动非阻塞提示与重试入口。 */
    val postCommitState: WriterPostCommitState = WriterPostCommitState.NONE,
) {
    val hasDraft: Boolean get() = draftId != null

    /** true = 该 Draft 早于 P20-P2（format=null），保存不得静默迁移格式。 */
    val isLegacyFormat: Boolean get() = draftFormat == null

    val formatLabel: String get() = draftFormat ?: "legacy 纯文本（format=null）"

    val busy: Boolean get() = isSaving || isGenerating || isApproving || isRejecting

    /**
     * 可保存：已有 Draft，或空章节但正文非空（O-84 作者首稿）。
     * **待审期间禁止普通保存**：未批准的 AI 候选不得经「保存」绕过 Human Gate。
     */
    val canSave: Boolean get() = isLoaded && !busy && !waitingForUser && (draftId != null || draftContent.isNotBlank())

    /** 决议防抖窗口内禁用生成类动作；保存不受影响。 */
    val canGenerate: Boolean get() = isLoaded && !busy && !decisionLocked

    /** 待审期间不再发起新的改写（必须先批准或拒绝当前候选）；决议防抖窗口内同样禁用。 */
    val canRewrite: Boolean get() = isLoaded && draftId != null && !busy && !waitingForUser && !decisionLocked

    /**
     * 候选已失效：Workflow 仍停在人工门，但待审工作产物不存在（WorkingDraft 只存在进程内存，
     * 重启后必然丢失）。此时批准没有决议对象；拒绝只结束当前 Gate、不依赖候选，仍可用。
     */
    val candidateLost: Boolean get() = isLoaded && waitingForUser && pendingProposal == null

    /** HITL：仅当 Workflow 确实停在人工门**且候选仍在**时可用（不绕过、不自动批准）。 */
    val canApprove: Boolean get() = isLoaded && waitingForUser && pendingProposal != null && !busy

    /** HITL：拒绝当前待决 Gate（幂等；Canonical 不变）；不依赖候选产物，失效候选也可关闭 Gate。 */
    val canReject: Boolean get() = isLoaded && waitingForUser && !busy

    /**
     * O-90：上一轮候选已拒绝——Gate 已关闭（不在待审）且最近一次决议为 REJECTED。
     * 此时界面显示「上一轮已拒绝」而非误导性的「等待人工确认」，并允许再次发起改写。
     */
    val rejectedRound: Boolean
        get() = isLoaded && !waitingForUser && pendingGateDecision == HumanDecision.REJECTED
}

/** 候选工作产物丢失（典型：进程重启）时的唯一用户文案（正文页 / 控制器错误提示共用）。 */
internal const val CANDIDATE_LOST_MESSAGE: String = "候选已失效（可能因进程重启）：请重新发起任务。"

/**
 * O-108（方案 D）：批准整链进行中的可见阶段文案。
 *
 * 批准调用内联 KNOWLEDGE_UPDATE（同步 LLM HTTP），实测可达数十秒；本函数只做文案投影，
 * 不引入状态机 / 枚举，也不改变 approve 时序（决议与收尾仍在同一次调用内完成）。
 */
internal fun approvalProgressLabel(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val elapsed = if (safe < 60) "${safe}s" else "${safe / 60}m${safe % 60}s"
    return "已通过 · 正在更新故事状态（$elapsed）…"
}