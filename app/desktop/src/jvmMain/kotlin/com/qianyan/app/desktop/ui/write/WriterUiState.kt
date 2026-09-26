package com.qianyan.app.desktop.ui.write

import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.DraftId

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
    val revisionCount: Int = 0,
    val error: String? = null,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val isGenerating: Boolean = false,
    val isApproving: Boolean = false,
) {
    val hasDraft: Boolean get() = draftId != null

    /** true = 该 Draft 早于 P20-P2（format=null），保存不得静默迁移格式。 */
    val isLegacyFormat: Boolean get() = draftFormat == null

    val formatLabel: String get() = draftFormat ?: "legacy 纯文本（format=null）"

    val busy: Boolean get() = isSaving || isGenerating || isApproving

    /** 尚无 Draft 不可保存（不伪造草稿；需先经「继续写作」生成初稿）。 */
    val canSave: Boolean get() = isLoaded && draftId != null && !busy

    val canGenerate: Boolean get() = isLoaded && !busy

    val canRewrite: Boolean get() = isLoaded && draftId != null && !busy

    /** HITL：仅当 Workflow 确实停在人工门时可用（不绕过、不自动批准）。 */
    val canApprove: Boolean get() = isLoaded && waitingForUser && !busy
}