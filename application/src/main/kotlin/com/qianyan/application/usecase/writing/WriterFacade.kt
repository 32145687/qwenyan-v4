package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.commit.CommitUseCases
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
import com.qianyan.model.change.TextDiff
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.task.TaskType
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.WorkflowKind
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
    private val projects: com.qianyan.application.usecase.project.ProjectUseCases,
    private val novelRepository: NovelRepository,
    private val drafts: WriterUseCases,
    private val workflow: ChapterWorkflowGateway,
    private val critique: CritiqueExecutionUseCase,
    private val revision: RevisionExecutionUseCase,
    private val taskManager: TaskManagerUseCases,
    private val commits: CommitUseCases,
    private val workingDrafts: com.qianyan.application.usecase.draft.WorkingDraftUseCases,
    errorMapper: ErrorMapper,
) : WriterGateway, UseCase(errorMapper) {

    /** 只读打开上下文：章节信息 + 章节最新 Draft + durable Workflow 阶段投影（不推进任何状态）。 */
    override fun loadContext(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): WriterChapterContext {
        val chapter = guard { chapters.findById(chapterId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
        val progress = workflow.getChapterProgress(chapterId)
        // P1-02c+d：AI 待审工作产物（WorkingDraft）——按 Project 内的章节工作产物投影；
        // 它是 WorkingDraftId（非 DraftId），只投影**正文**，不伪装成 Draft。
        val pending = latestWorking(guard { projects.projectOf(novelId) }.projectId, chapterId)
        return WriterChapterContext(
            novelId = novelId,
            novelTitle = guard { novelRepository.getNovel(novelId) }?.title ?: "",
            chapterId = chapterId,
            chapterTitle = chapter.title,
            draft = drafts.latestDraft(chapterId),
            phase = progress.phase,
            waitingForUser = progress.waitingForUser,
            pendingWorkingContent = pending?.content,
            // 只有 Gate 仍 PENDING 时才投影候选/原文/Diff；已拒绝的候选不再作为待审内容展示。
            pendingProposal = pending
                ?.takeIf { progress.waitingForUser }
                ?.let { proposalOf(it) },
        )
    }

    /** 该章节**最新创建**的 AI 待审工作产物（无 / 已丢弃 → null；同时间按 id 稳定）。 */
    private fun latestWorking(projectId: com.qianyan.model.ProjectId, chapterId: ChapterId): WorkingDraft? =
        guard { workingDrafts.list(projectId) }
            .filter { it.target.chapterId == chapterId && !it.status.isTerminal }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.workingDraftId.value }))

    /** 候选审查投影：原文（基线） + 候选正文 + 既有 TextDiffer 行级 Diff。 */
    private fun proposalOf(working: WorkingDraft): WriterPendingProposal {
        val baseContent = working.base.baseDraftId
            ?.let { baseDraftId -> drafts.draft(baseDraftId)?.content }
            .orEmpty()
        return WriterPendingProposal(
            baseContent = baseContent,
            candidateContent = working.content,
            diff = TextDiffer.diff(baseContent, working.content),
        )
    }

    /** **作者手动保存正文**（P1-01f「保存即批准」）：保存后经既有 [CommitUseCases] 形成 **Canonical Commit**
     *  （同一 DB 事务：正文与 Canonical 一起成功或一起回滚）。
     *  AI 产出**不走**本入口（continue / rewrite 走既有 Workflow + Human Gate），因此不会被自动 Canonical 化。 */
    override fun saveContent(draftId: DraftId, content: String): Draft {
        commits.commitAuthorSave(draftId, content)
        return drafts.draft(draftId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Draft 不存在: ${draftId.value}"))
    }

    /**
     * AI 继续写：驱动既有章节 Workflow 前进（PLANNING → WRITING → …），返回进度投影。
     * 尚无 Workflow 时先创建骨架（与既有 ChapterWorkflowGateway 语义一致），不由 UI 决定步骤。
     */
    override fun continueWriting(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
        // P2/FD-1：只有**本次推进真正新产生**的 Draft 才打受控 Markdown 标记。
        // 先记录推进前的 Draft 身份，避免把章节上已有的 legacy Draft（format=null）当成"新 AI Draft"回溯迁移
        // ——例如章节 Workflow 已 COMPLETED、本次 advance 不产生新 Draft 的情况。
        val draftBefore = drafts.latestDraft(chapterId)?.draftId
        if (workflow.getChapterProgress(chapterId).phase == ChapterPhase.NOT_STARTED) {
            workflow.startChapter(novelId, variantId, chapterId)
        }
        val progress = workflow.advance(chapterId)
        drafts.latestDraft(chapterId)
            ?.takeIf { it.draftId != draftBefore }
            ?.let { drafts.stampControlledMarkdown(it) }
        return progress
    }

    /** 拒绝当前待决 Gate（薄委托既有章节 Workflow reject；Canonical 不变）。 */
    override fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress = workflow.reject(chapterId)

    /**
     * AI 改写：对章节最新 Draft 复用既有 Critique → Revision，但产物进入 **WorkingDraft**，
     * 再经既有章节 Workflow 进入 **CONFIRMATION → Human Gate**。
     *
     * 硬保证：本入口**不写** `ChapterDraft`、不产生 Canonical；只有人工批准后才由既有
     * Change/Commit 链形成正式正文。原稿在批准前保持不变。
     */
    override fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress {
        val current = drafts.latestDraft(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("章节 $chapterId 尚无 Draft，无法改写"))
        // 已有未决候选 / 工作流仍在进行 ⇒ 先收敛当前轮次，避免并行提案与无谓 LLM 调用。
        val progress = workflow.getChapterProgress(chapterId)
        if (progress.waitingForUser) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("chapter $chapterId 已有待确认的 AI 提案，请先批准或拒绝"),
            )
        }
        when (progress.phase) {
            ChapterPhase.PLANNING,
            ChapterPhase.WRITING,
            ChapterPhase.REVIEWING,
            ChapterPhase.REVISING,
            ChapterPhase.UPDATING_STORY,
            -> throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "chapter $chapterId 当前工作流仍在进行（${progress.phase.name}），请先继续或恢复",
                ),
            )
            else -> Unit
        }
        val projectId = guard { projects.projectOf(novelId) }.projectId
        // 先建工作产物（基线 = 当前 Draft），修订结果写回同一 WorkingDraft（不产生新 Draft 行）。
        val working = workingDrafts.create(
            projectId = projectId,
            target = WorkingDraftTarget(novelId, chapterId, variantId),
            content = current.content,
            baseDraftId = current.draftId,
        )
        val verdict = critique.execute(taskManager.create(TaskType.WRITING), current)
        val revised = revision.reviseWorking(
            taskManager.create(TaskType.WRITING),
            working.workingDraftId.value,
            current.content,
            verdict,
            novelId,
        )
        workingDrafts.replace(working.workingDraftId, revised)
        // 无实际变化 ⇒ 不建立无法提交的 Human Gate（Commit 会以 NO_CHANGES 拒绝）。
        if (revised == current.content) {
            workingDrafts.discard(working.workingDraftId)
            throw ApplicationException(
                ApplicationError.InvalidOperation("AI 改写未产生实际变化，未进入人工确认"),
            )
        }
        workflow.startChapter(novelId, variantId, chapterId, WorkflowKind.REWRITE_CHAPTER)
        return try {
            workflow.proposeRewrite(chapterId, working.workingDraftId)
        } catch (t: Throwable) {
            // 提案失败不得留下无 Gate 的孤儿候选污染后续投影。
            runCatching { workingDrafts.discard(working.workingDraftId) }
            throw t
        }
    }
}

/** AI 候选审查投影（原文 + 候选 + 行级 Diff；不是 Draft，也不可直接保存）。 */
data class WriterPendingProposal(
    val baseContent: String,
    val candidateContent: String,
    val diff: TextDiff,
)

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
    /** 当前是否停在**未决** Human Gate（已拒绝/已批准后收敛中均为 false）。 */
    val waitingForUser: Boolean = false,
    /**
     * P1-02c+d：AI **待审工作产物**正文（WorkingDraft）。
     * 它不是章节 Draft（无 DraftId、不可直接保存）；作者批准（人工门）后经正式 Commit 才产生 Canonical 载体 Draft。
     * 无待审工作产物 → null。
     */
    val pendingWorkingContent: String? = null,
    /** 审批前的候选审查投影；仅当 Gate 仍 PENDING 时非空（已拒绝的候选不再作为待审内容展示）。 */
    val pendingProposal: WriterPendingProposal? = null,
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
    /** AI 改写 ⇒ WorkingDraft + Human Gate（批准前不产生 Draft / Canonical）。 */
    fun rewrite(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId): ChapterWorkflowProgress
    /** 拒绝当前待决 Gate（幂等；委托既有章节 Workflow 生产入口）。 */
    fun rejectGate(chapterId: ChapterId): ChapterWorkflowProgress
}