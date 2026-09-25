package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.storage.repository.NovelRepository

/**
 * P20-P3 · WriterFacade —— Android Writer 与既有 Application 能力之间的**用户层 Application API**。
 *
 * **不是第二套写作 Pipeline**，也不复刻任何业务规则；只做两件事：
 *   1) 用户意图 → 既有 Application API 的参数转换（novelId/variantId/chapterId → 既有 UseCase 调用）；
 *   2) 持久化状态 → 用户层上下文投影（[WriterChapterContext]）。
 *
 * 委托关系（全部复用既有能力）：
 *  - Draft 读 / 保存 → [WriterUseCases]（→ DraftRepository）；
 *  - **AI 继续写** → 既有 [ChapterWorkflowGateway]（startChapter / advance 驱动 durable Workflow →
 *    Planning → Writing，DecisionPolicy 由 Application orchestration 注入，Writer 只消费）；
 *  - **AI 改写** → 既有 [CritiqueExecutionUseCase] + [RevisionExecutionUseCase]（与 WorkflowOrchestrator
 *    的 CRITIQUE / REVISION 步骤**同一对** UseCase，不新增业务规则 / Gate）。
 *
 * 明确不做：UI 不接触 Repository / Provider / Agent（本 Facade 是其唯一出口）；不 Decision（无 DecisionModel）；
 * 不新建数据库表 / 不改 Markdown Contract。
 */
class WriterFacade(
    private val chapters: ChapterUseCases,
    private val novelRepository: NovelRepository,
    private val drafts: WriterUseCases,
    private val workflow: ChapterWorkflowGateway,
    private val critique: CritiqueExecutionUseCase,
    private val revision: RevisionExecutionUseCase,
    private val taskManager: TaskManagerUseCases,
    errorMapper: ErrorMapper,
) : WriterGateway, UseCase(errorMapper) {

    /** 只读打开上下文：章节信息 + 章节最新 Draft + durable Workflow 阶段投影（不推进任何状态）。 */
    override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext {
        val chapter = guard { chapters.findById(chapterId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
        return WriterChapterContext(
            novelId = novelId,
            novelTitle = guard { novelRepository.getNovel(novelId) }?.title ?: "",
            chapterId = chapterId,
            chapterTitle = chapter.title,
            draft = drafts.latestDraft(chapterId),
            phase = workflow.getChapterProgress(chapterId).phase,
        )
    }

    /** 保存用户编辑后的正文（身份 / lineage / status / format 不变）。 */
    override fun saveContent(draftId: DraftId, content: String): Draft = drafts.saveContent(draftId, content)

    /**
     * AI 继续写：驱动既有章节 Workflow 前进（PLANNING → WRITING → …），返回进度投影。
     * 尚无 Workflow 时先创建骨架（与既有 ChapterWorkflowGateway 语义一致），不由 UI 决定步骤。
     */
    override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
        if (workflow.getChapterProgress(chapterId).phase == ChapterPhase.NOT_STARTED) {
            workflow.startChapter(novelId, variantId, chapterId)
        }
        val progress = workflow.advance(chapterId)
        // P2/FD-1：本次新产生的 Draft 标记为受控 Markdown v1（已有 format 的 Draft 不动）。
        drafts.latestDraft(chapterId)?.let { drafts.stampControlledMarkdown(it) }
        return progress
    }

    /**
     * AI 改写：对章节最新 Draft 复用既有 Critique → Revision（新 draftId、status=REVISED，原稿不破坏）。
     * 无 Draft → [ApplicationError.EntityNotFound]（不伪造草稿）。
     */
    override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): Draft {
        val current = drafts.latestDraft(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("章节 $chapterId 尚无 Draft，无法改写"))
        val verdict = critique.execute(taskManager.create(TaskType.WRITING), current)
        val revised = revision.execute(taskManager.create(TaskType.WRITING), current, verdict)
        return drafts.stampControlledMarkdown(revised)
    }
}

/** Writer 用户层上下文（章节信息 + 最新 Draft + 阶段投影；不含 Repository / Agent / Workflow 内部对象）。 */
data class WriterChapterContext(
    val novelId: NovelId,
    val novelTitle: String,
    val chapterId: ChapterId,
    val chapterTitle: String,
    /** 章节最新 Draft；尚无草稿 → null（UI 不伪造，需先由 AI 生成初稿）。 */
    val draft: Draft?,
    /** durable Workflow 阶段投影（[ChapterPhase]；无 Workflow → NOT_STARTED）。 */
    val phase: ChapterPhase,
)

/**
 * 用户层 Writer 访问 seam（P20-P3）：Android 只依赖此接口 + [WriterChapterContext] / 既有
 * [ChapterWorkflowProgress]，不接触 Repository / Provider / Agent / Workflow 内部。
 * 唯一实现即 [WriterFacade]；测试提供替身。
 */
interface WriterGateway {
    fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext
    fun saveContent(draftId: DraftId, content: String): Draft
    fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress
    fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): Draft
}