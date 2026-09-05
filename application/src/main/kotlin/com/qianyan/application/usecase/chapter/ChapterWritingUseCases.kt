package com.qianyan.application.usecase.chapter

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.confirmation.ConfirmationExecutionUseCase
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateOutcome
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.repository.DraftRepository
import kotlinx.datetime.Clock

/**
 * 章节写作链编排（P12.1.7，Application seam，**additive**）。
 *
 * 不重新实现任何既有业务能力：逐步骤复用真实 UseCases——
 *   Planning → [PlanningExecutionUseCase]（内部经 [ContinuationResolver]、真实 PlannerAgent / Provider）
 *   Writing → [WritingExecutionUseCase]（真实 WriterAgent / DraftParser / DraftRepository）
 *   Critique → [CritiqueExecutionUseCase]
 *   Revision → [RevisionExecutionUseCase]（含 RevisionGate ≤ 3、scope/identity 校验、lineage）
 *   Finalize → 把当前 Draft 置为 FINAL（真实 DraftRepository.save，UUID 不变，身份不变）
 *   Confirmation → [ConfirmationExecutionUseCase.confirmFinalDraft]（幂等、作用域隔离）
 *   Knowledge Update → [KnowledgeUpdateExecutionUseCase]（仅接受 CONFIRMED Final Draft，Application 仍强制门禁）
 *
 * 所有 AI 步骤都经 Task/Checkpoint（复用 [TaskManagerUseCases]），不在 UI 再造 Task 状态机。
 * 本类持有 `open` 打开一个 [ChapterWritingSession]（按 chapter 隔离的链会话；禁止跨 Novel/Variant 读取）。
 */
class ChapterWritingUseCases(
    private val taskManager: TaskManagerUseCases,
    private val planning: PlanningExecutionUseCase,
    private val writing: WritingExecutionUseCase,
    private val critique: CritiqueExecutionUseCase,
    private val revision: RevisionExecutionUseCase,
    private val confirmation: ConfirmationExecutionUseCase,
    private val knowledgeUpdate: KnowledgeUpdateExecutionUseCase,
    private val draftRepository: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 打开一个针对指定既有 Chapter 的写作链会话。 */
    fun open(chapterId: ChapterId, novelId: NovelId, variantId: VariantId?): ChapterWritingSession =
        ChapterWritingSession(
            taskManager = taskManager,
            planning = planning,
            writing = writing,
            critique = critique,
            revision = revision,
            confirmation = confirmation,
            knowledgeUpdate = knowledgeUpdate,
            draftRepository = draftRepository,
            chapterId = chapterId,
            novelId = novelId,
            variantId = variantId,
        )
}

/** 一次写作链的进行中结果快照（供 UI 展示；均为真实持久化产物引用/结果）。 */
data class ChapterChainResult(
    val chapterId: ChapterId,
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    val planTaskId: TaskId? = null,
    val plan: ChapterPlan? = null,
    val writeTaskId: TaskId? = null,
    val draft: Draft? = null,
    val critique: ValidationResult? = null,
    val revisions: List<Draft> = emptyList(),
    val finalDraft: Draft? = null,
    val confirmedDraft: Draft? = null,
    val knowledgeUpdate: KnowledgeUpdateOutcome? = null,
)

/**
 * 章节写作链会话（P12.1.7）。UI / 调用方在本会话上按序推进：
 * plan → write → critique → (revise)* → finalize → confirm → knowledgeUpdate。
 * 每步**幂等**（已完成则返回既有结果），防止重复点击产生重复 Task / Draft。
 */
class ChapterWritingSession internal constructor(
    private val taskManager: TaskManagerUseCases,
    private val planning: PlanningExecutionUseCase,
    private val writing: WritingExecutionUseCase,
    private val critique: CritiqueExecutionUseCase,
    private val revision: RevisionExecutionUseCase,
    private val confirmation: ConfirmationExecutionUseCase,
    private val knowledgeUpdate: KnowledgeUpdateExecutionUseCase,
    private val draftRepository: DraftRepository,
    private val chapterId: ChapterId,
    private val novelId: NovelId,
    private val variantId: VariantId?,
) {
    private val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT

    private var planTaskId: TaskId? = null
    private var plan: ChapterPlan? = null
    private var writeTaskId: TaskId? = null
    private var draft: Draft? = null
    private var critiqueResult: ValidationResult? = null
    private val revisions = mutableListOf<Draft>()
    private var finalDraft: Draft? = null
    private var confirmedDraft: Draft? = null
    private var ku: KnowledgeUpdateOutcome? = null

    /** T1 / T3 / T15：真实 Planning（绑定到本既有 Chapter；可选 continuation）。幂等。 */
    fun plan(continuation: ContinuationReference? = null): ChapterPlan {
        plan?.let { return it }
        val id = taskManager.create(TaskType.PLANNING)
        val result = planning.execute(id, writingRequest(), continuation, targetChapterId = chapterId)
        planTaskId = id
        plan = result
        return result
    }

    /** T2 / T15：真实 Writing → 现存 Draft（lineage 延续，来自 Database）。幂等。 */
    fun write(): Draft {
        draft?.let { return it }
        val current = requirePlan()
        val id = taskManager.create(TaskType.WRITING)
        val d = writing.execute(id, writingRequest(), current)
        writeTaskId = id
        draft = d
        return d
    }

    /** T3 / T4：真实 Critique。同一 WRITING Task 内保存 CRITIQUE Checkpoint。幂等。 */
    fun critique(): ValidationResult {
        critiqueResult?.let { return it }
        val id = requireWriteTask()
        val r = critique.execute(id, requireDraft())
        critiqueResult = r
        return r
    }

    /** T6 / T7：真实 Revision（RevisionGate ≤ 3 由 Application 强制）→ 新 Draft；旧 Draft 不覆盖。 */
    fun revise(): Draft {
        val id = requireWriteTask()
        val c = critique() ?: throw ApplicationException(
            ApplicationError.InvalidOperation("Revision 前必须先 Critique"),
        )
        val next = revision.execute(id, requireDraft(), c)
        // 确立本次工作稿为新 Draft，并让下一轮 Critique 重新判定
        draft = next
        revisions += next
        critiqueResult = null
        return next
    }

    /** Step Draft → FINAL（真实持久化；同一 DraftId，身份不变）。幂等。 */
    fun finalize(): Draft {
        finalDraft?.let { return it }
        val current = requireDraft() ?: throw ApplicationException(
            ApplicationError.InvalidOperation("Finalize 前必须先 Writing/Revision"),
        )
        if (current.status == DraftStatus.FINAL || current.status == DraftStatus.PENDING_CONFIRMATION || current.status == DraftStatus.CONFIRMED) {
            finalDraft = current
            return current
        }
        val finalized = current.copy(status = DraftStatus.FINAL, updatedAt = Clock.System.now())
        draftRepository.save(finalized)
        draft = finalized
        finalDraft = finalized
        return finalized
    }

    /** 真实 Confirmation：FINAL / PENDING_CONFIRMATION → CONFIRMED；幂等；scope 隔离由 ConfirmationExecutionUseCase 保证。 */
    fun confirm(): Draft {
        confirmedDraft?.let { return it }
        val fd = finalize() // 未最终定稿的 Draft 不允许确认
        val c = confirmation.confirmFinalDraft(fd.draftId, novelId, variantId)
        confirmedDraft = c
        return c
    }

    /** 真实 Knowledge Update（仅接受 CONFIRMED Final Draft；Application 仍强制 DraftConfirmationRequired）。幂等。
     *  独立 KNOWN_UPDATE Task（复用 P11.5 独立 Task 语义，避免与 WRITING 任务的 3-checkpoint budget 冲突）。 */
    fun knowledgeUpdate(): KnowledgeUpdateOutcome {
        ku?.let { return it }
        val confirmed = requireConfirmed()
        val kuTaskId = taskManager.create(TaskType.KNOWLEDGE_UPDATE)
        val outcome = knowledgeUpdate.execute(kuTaskId, confirmed)
        ku = outcome
        return outcome
    }

    /** 当前链快照（供 UI 展示真实状态）。 */
    fun current(): ChapterChainResult = ChapterChainResult(
        chapterId = chapterId,
        novelId = novelId,
        variantId = variantId,
        scope = scope,
        planTaskId = planTaskId,
        plan = plan,
        writeTaskId = writeTaskId,
        draft = draft,
        critique = critiqueResult,
        revisions = revisions.toList(),
        finalDraft = finalDraft,
        confirmedDraft = confirmedDraft,
        knowledgeUpdate = ku,
    )

    /**
     * 从数据库恢复链的真实持久化状态（重进 / Activity 重建）：
     * 按 chapterId 读取该 Novel 下的 Draft，恢复「工作稿 / FINAL / CONFIRMED」。
     * Plan / Critique / KU 结果存于对应 Task Checkpoint，属运行期上下文（本会话持有 taskId 时可经 TaskManager 恢复）。
     */
    fun refreshFromDatabase(): ChapterChainResult {
        val all = draftRepository.listByNovel(novelId).filter { it.chapterId == chapterId }
        val confirmed = all.lastOrNull { it.status == DraftStatus.CONFIRMED }
        val final = all.lastOrNull { it.status == DraftStatus.FINAL || it.status == DraftStatus.PENDING_CONFIRMATION }
        val work = all.lastOrNull { it.status == DraftStatus.WRITTEN || it.status == DraftStatus.REVISED }
        val latest = confirmed ?: final ?: work
        if (latest != null) {
            draft = latest
            finalDraft = if (latest.status == DraftStatus.FINAL || latest.status == DraftStatus.PENDING_CONFIRMATION || latest.status == DraftStatus.CONFIRMED) latest else null
            confirmedDraft = if (latest.status == DraftStatus.CONFIRMED) latest else null
        }
        return current()
    }

    private fun requirePlan(): ChapterPlan =
        plan ?: throw ApplicationException(ApplicationError.InvalidOperation("Chain 未规划（先执行 plan()）"))

    private fun requireDraft(): Draft =
        draft ?: throw ApplicationException(ApplicationError.InvalidOperation("Chain 未写作（先执行 write()）"))

    private fun requireWriteTask(): TaskId =
        writeTaskId ?: throw ApplicationException(ApplicationError.InvalidOperation("Writing Task 不存在"))

    private fun requireConfirmed(): Draft =
        confirmedDraft ?: throw ApplicationException(ApplicationError.DraftConfirmationRequired("Knowledge Update 需要 CONFIRMED Final Draft"))

    private fun writingRequest(): UserWritingRequest = UserWritingRequest(
        requestId = RequestId(java.util.UUID.randomUUID().toString()),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
        variantId = variantId,
        scope = scope,
    )
}