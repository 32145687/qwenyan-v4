package com.qianyan.application.usecase.workflow

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.confirmation.ConfirmationExecutionUseCase
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.task.TaskType
import com.qianyan.model.workflow.AttemptErrorCategory
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowContinuation
import com.qianyan.model.workflow.WorkflowContinuationId
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepAttempt
import com.qianyan.model.workflow.WorkflowStepAttemptId
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ProviderException
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.WorkflowRepository
import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Workflow Orchestrator（P12.2 · deterministic control plane）。
 * 循环式 runForward：读取 durable Workflow → 判当前 LogicalStep → 调既有 UseCase → 写 resultReference → 推进。
 * ResultReference-first：已有可复用结果 → 复用并 COMPLETED，绝不重调 LLM / 不产生第二个业务结果。
 *
 * 本轮已完成 phase：PLANNING → WRITING → CRITIQUE ──(PASS)──> FINALIZE → CONFIRMATION(gate→WAITING_HUMAN)
 *                                └─(NEED_REVISION)→ REVISION → CRITIQUE(新 Draft) → …（≤3 轮，RevisionGate 语义）。
 * 未实现（如实留白）：KNOWLEDGE_UPDATE→COMPLETED 之外的多章续写 / Multi-Chapter Continuation。
 *
 * Retry ≠ Revision：
 *  - Retry   → 同一 logicalStep 的 attemptNo+1；revisionCount 不增加（Attempt 表独立计数）。
 *  - Revision→ 每轮独立 REVISION Step（round 编码进 logicalStepKey），workflow 级 revisionCount = REVISION Step 数（≤3）。
 *
 * Responsible boundaries：
 *  - Orchestrator 控制流程 / revisionCount / resultReference / Recovery / 防循环；**不直接调 LLM、不直接碰业务表**：
 *    Critique/Revision/Writing/KnowledgeUpdate 都经既有 Application UseCase（内部走 TaskManager→AgentRuntime）。
 */
class WorkflowOrchestrator(
    private val workflowRepository: WorkflowRepository,
    private val taskManager: TaskManagerUseCases,
    private val writing: WritingExecutionUseCase,
    private val planning: PlanningExecutionUseCase,
    private val critique: CritiqueExecutionUseCase,
    private val revision: RevisionExecutionUseCase,
    private val confirmation: ConfirmationExecutionUseCase,
    private val knowledgeUpdate: KnowledgeUpdateExecutionUseCase,
    private val draftRepository: DraftRepository,
    private val chapterRepository: ChapterRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 前进当前 LogicalStep 直到 WAITING_HUMAN / 未接入 phase / COMPLETED。幂等：已 COMPLETED / resultReference 命中不复跑。 */
    fun runForward(workflowId: WorkflowId): WorkflowRunResult {
        var guard = 0
        var last = WorkflowRunResult(status = requireWorkflow(workflowId).status, stepCompleted = false)
        var done = false
        while (guard++ < 16 && !done) {
            val wf = requireWorkflow(workflowId)
            when (wf.status) {
                WorkflowStatus.WAITING_HUMAN, WorkflowStatus.PAUSED,
                WorkflowStatus.COMPLETED, WorkflowStatus.CANCELLED, WorkflowStatus.FAILED,
                -> {
                    done = true
                }
                else -> {
                    val steps = workflowRepository.listSteps(wf.workflowId)
                    val step = wf.currentStepId?.let { workflowRepository.getStep(it) }
                        ?: steps.firstOrNull { it.status != WorkflowStepStatus.COMPLETED && it.status != WorkflowStepStatus.SKIPPED }
                    if (step == null) {
                        // 没有未完成 step：若尚无从 activeChapter 的 PLANNING 开始；否则若「最后一个已完成步骤」是
                        // 需要后继的分支步骤（CRITIQUE / REVISION / …），则补建后继并推进（覆盖 crash 于 complete/advance/建后继之间的窗口）。
                        if (steps.isEmpty()) {
                            val c = wf.activeChapterId
                            if (c == null) { done = true } else createStepIfMissing(wf.workflowId, c, WorkflowStepPhase.PLANNING)
                        } else {
                            val lastStep = steps.last()
                            if (lastStep.status == WorkflowStepStatus.COMPLETED && lastStep.phase in SUCCEEDED_PHASES) {
                                createSuccessor(lastStep)
                                advance(wf.workflowId, lastStep)
                                last = WorkflowRunResult(status = wf.status, stepCompleted = true, reused = true)
                            } else { done = true }
                        }
                    } else if (step.resultReference != null) {
                        ensureCompleted(step)
                        createSuccessor(step)
                        advance(wf.workflowId, step)
                        last = WorkflowRunResult(status = wf.status, stepCompleted = true, reused = true)
                    } else {
                        when (step.phase) {
                            WorkflowStepPhase.PLANNING -> { executePlanning(wf, step); last = WorkflowRunResult(status = wf.status, stepCompleted = true) }
                            WorkflowStepPhase.WRITING -> last = executeWriting(wf, step)
                            WorkflowStepPhase.CRITIQUE -> last = executeCritique(wf, step)
                            WorkflowStepPhase.REVISION -> last = executeRevision(wf, step)
                            WorkflowStepPhase.FINALIZE -> last = executeFinalize(wf, step)
                            WorkflowStepPhase.CONFIRMATION -> last = executeConfirmation(wf, step)
                            WorkflowStepPhase.KNOWLEDGE_UPDATE -> last = executeKnowledgeUpdate(wf, step)
                            else -> { done = true }
                        }
                    }
                }
            }
        }
        return last
    }

    /* ---------- phase executors ---------- */

    private fun executePlanning(wf: Workflow, step: WorkflowStep) {
        val taskId = taskManager.create(TaskType.PLANNING)
        // P12.2 Test E：Ch2（及后续章节）的 Planning 续篇来源来自 durable WorkflowContinuation，
        // 而非 session 内存。无续篇（第一章）→ null。
        val continuationRef = workflowRepository.getContinuationByTargetChapter(step.chapterId)?.let {
            ContinuationReference(sourceChapterId = it.sourceChapterId ?: step.chapterId, sourceDraftId = it.sourceDraftId)
        }
        val plan = planning.execute(taskId, buildRequest(wf.novelId, wf.variantId), continuationRef, targetChapterId = step.chapterId)
        finishStep(wf, step, "PLANJSON:" + encodeJson(plan))
    }

    private fun executeWriting(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val attemptNo = (workflowRepository.listAttempts(step.stepId).size) + 1
        val attempt = createAttempt(step, attemptNo)
        val chapterId = step.chapterId
        val plan = requirePlanFor(wf, step, chapterId)
        val request = buildRequest(wf.novelId, wf.variantId)
        return try {
            val taskId = taskManager.create(TaskType.WRITING)
            val draft = writing.execute(taskId, request, plan)
            workflowRepository.inTransaction {
                updateAttempt(attempt.copy(status = WorkflowAttemptStatus.COMPLETED, taskId = taskId.value, completedAt = Clock.System.now()))
                updateStep(step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = draft.draftId.value, currentAttemptNo = attemptNo, currentTaskId = taskId.value, completedAt = Clock.System.now()))
            }
            val done = step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = draft.draftId.value, currentAttemptNo = attemptNo, currentTaskId = taskId.value, completedAt = Clock.System.now())
            createSuccessor(done)
            advance(wf.workflowId, step)
            WorkflowRunResult(status = wf.status, stepCompleted = true, retried = attemptNo > 1)
        } catch (e: ApplicationException) {
            markAttemptFailed(attempt, classify(e))
            WorkflowRunResult(status = wf.status, stepCompleted = false, retried = false, failed = true)
        } catch (t: Throwable) {
            markAttemptFailed(attempt, errorMapperToCategory(t))
            WorkflowRunResult(status = wf.status, stepCompleted = false, retried = false, failed = true)
        }
    }

    /**
     * Critique 执行 + decision 分支（§6 Critique Decision）：
     *  resultReference = "CRITIQUE:<taskId>:<subjectDraftId>:<PASS|REVISION>"（decision 为**机器可判定**布尔派生，不经文本解析）。
     *  PASS → FINALIZE；NEED_REVISION → 若 revisionCount < 3 建 REVISION Step，否则 WORKFLOW FAILED（明确终止，不无限循环）。
     */
    private fun executeCritique(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val draft = requireLatestDraft(step.chapterId)
        val cTaskId = taskManager.create(TaskType.WRITING)
        val result = critique.execute(cTaskId, draft)
        val ref = critiqueRef(cTaskId, draft.draftId, result.passed)
        finishStep(wf, step, ref)
        // 若 REVISION 分支触发终止（revision 达上限），以最新状态返回。
        return WorkflowRunResult(status = requireWorkflow(wf.workflowId).status, stepCompleted = true)
    }

    /**
     * Revision 执行（§5/§7/§8/§13）。resultReference = 新 DraftId（与 WRITING 的 Draft 引用形式一致）。
     *  - 每轮独立 WRITING Task（避免复用 WRITING task 的 3-checkpoint budget 在多次修订时耗尽）。
     *  - revisionCount = 已有 REVISION Step 数（Orchestrator 持久化控制；RevisionGate 语义 ≤3 在创建后继时约束）。
     *  - 幂等：若已存在以本步骤 subject Draft 为父（previousDraftId）的 REVISED Draft（如 crash 于 Draft 落库后、Step 标记前），
     *    直接复用该结果（不再次调用 LLM / 不产生第二个修订 Draft）。
     *  - Retry ≠ Revision：attemptNo 独立计数；一次成功 Revision = workflow 层 revisionCount +1。
     */
    private fun executeRevision(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val attemptNo = (workflowRepository.listAttempts(step.stepId).size) + 1
        val attempt = createAttempt(step, attemptNo)
        val chapterId = step.chapterId
        return try {
            val critStep = triggeringCritique(wf.workflowId, chapterId)
                ?: throw ApplicationException(ApplicationError.InvalidOperation("REVISION 缺前置 CRITIQUE"))
            val ref = decodeCritique(critStep.resultReference)
                ?: throw ApplicationException(ApplicationError.RestoreFailure("CRITIQUE 缺少可判定 decision"))
            val subject = draftRepository.getById(ref.subjectDraftId)
                ?: throw ApplicationException(ApplicationError.EntityNotFound("REVISION 的 subject Draft 缺失"))

            // 幂等复用：该轮修订若已产出 Draft（crash 后恢复），复用而非新建。
            val existing = draftRepository.listByChapter(chapterId)
                .firstOrNull { it.previousDraftId == subject.draftId && it.status == DraftStatus.REVISED }

            val result = if (existing != null) existing else {
                val critiqueResult = recoverCritiqueResult(critStep)
                    ?: throw ApplicationException(ApplicationError.RestoreFailure("无法恢复 CRITIQUE 结果（ValidationResult）"))
                val taskId = taskManager.create(TaskType.WRITING)
                revision.execute(taskId, subject, critiqueResult)
            }

            val now = Clock.System.now()
            workflowRepository.inTransaction {
                updateAttempt(attempt.copy(status = WorkflowAttemptStatus.COMPLETED, completedAt = now))
                updateStep(step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = result.draftId.value, currentAttemptNo = attemptNo, completedAt = now))
            }
            val done = step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = result.draftId.value, currentAttemptNo = attemptNo, completedAt = now)
            createSuccessor(done)
            advance(wf.workflowId, step)
            WorkflowRunResult(status = wf.status, stepCompleted = true, retried = attemptNo > 1, reused = existing != null)
        } catch (e: ApplicationException) {
            markAttemptFailed(attempt, classify(e))
            WorkflowRunResult(status = wf.status, stepCompleted = false, retried = false, failed = true)
        } catch (t: Throwable) {
            markAttemptFailed(attempt, errorMapperToCategory(t))
            WorkflowRunResult(status = wf.status, stepCompleted = false, retried = false, failed = true)
        }
    }

    private fun executeFinalize(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val draft = requireLatestDraft(step.chapterId)
        val now = Clock.System.now()
        val finalized = if (draft.status == DraftStatus.FINAL || draft.status == DraftStatus.PENDING_CONFIRMATION || draft.status == DraftStatus.CONFIRMED) draft
        else draft.copy(status = DraftStatus.FINAL, updatedAt = now)
        draftRepository.save(finalized)
        finishStep(wf, step, "FINAL:" + finalized.draftId.value)
        return WorkflowRunResult(status = wf.status, stepCompleted = true)
    }

    private fun executeConfirmation(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val draft = requireLatestDraft(step.chapterId)
        if (draft.status != DraftStatus.CONFIRMED) {
            // 进入 Human Gate：建 Gate（幂等）→ WAITING_HUMAN。
            val gate = createPendingGate(wf, step, draft.draftId)
            updateWorkflow(wf.copy(status = WorkflowStatus.WAITING_HUMAN, pendingGateId = gate.gateId, currentStepId = step.stepId, updatedAt = Clock.System.now()))
            return WorkflowRunResult(status = WorkflowStatus.WAITING_HUMAN, stepCompleted = true)
        }
        finishStep(wf, step, "CONFIR:" + draft.draftId.value)
        return WorkflowRunResult(status = wf.status, stepCompleted = true)
    }

    private fun executeKnowledgeUpdate(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val confirmed = draftRepository.latestByChapter(step.chapterId)
            ?.takeIf { it.status == DraftStatus.CONFIRMED }
            ?: throw ApplicationException(ApplicationError.DraftConfirmationRequired("Knowledge Update 需要 CONFIRMED Final Draft"))
        val kuTaskId = taskManager.create(TaskType.KNOWLEDGE_UPDATE)
        knowledgeUpdate.execute(kuTaskId, confirmed)
        completeStep(step, "KU:" + kuTaskId.value)
        advance(wf.workflowId, step)
        // 单章完成：Workflow → COMPLETED。
        updateWorkflow(wf.copy(status = WorkflowStatus.COMPLETED, updatedAt = Clock.System.now()))
        return WorkflowRunResult(status = WorkflowStatus.COMPLETED, stepCompleted = true)
    }

    /** 幂等批准（DB 事务）；批准后 Draft 确认，Workflow 回到 RUNNING（下一 runForward 处理 KU）。 */
    fun approveGate(gateId: com.qianyan.model.workflow.WorkflowHumanGateId): GateOutcome {
        val gate = requireGate(gateId)
        if (gate.status == HumanGateStatus.RESOLVED) return GateOutcome.AlreadyApproved
        val wf = requireWorkflow(gate.workflowId)
        val draft = gate.draftId?.let { draftRepository.getById(it) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Gate Draft 不存在"))
        confirmation.confirmFinalDraft(draft.draftId, wf.novelId, wf.variantId)
        val resolved = resolveGatePending(gate)
        if (resolved) updateWorkflow(wf.copy(status = WorkflowStatus.RUNNING, updatedAt = Clock.System.now()))
        return GateOutcome.Approved
    }

    /**
     * P12.2 Test E · Multi-Chapter Continuation（durable wire）。
     * 前置：source Workflow 必须 COMPLETED，其 active Chapter 存在 CONFIRMED Final Draft。
     * 职责（幂等，收敛到一个 durable business result）：
     *   1) 以 sourceDraftId 为幂等键解析 target Chapter：已有 continuation → 复用其 target；否则取「下一章」
     *      （同 novel+variant 按 order 紧跟 source Chapter 的既有 Chapter；无则原子创建），并用事务写入
     *      [WorkflowContinuation]（唯一 sourceDraftId+targetChapterId）。
     *   2) 以确定性 workflowId = continue:<sourceDraftId>:<targetChapterId> 查找/创建 Chapter 2 的专属 Workflow。
     * 返回 Chapter 2 的 WorkflowId（调用方据此 runForward 驱动其 Planning/Writing…）。
     * 不复制正文：只落 sourceChapterId + sourceDraftId 引用（reference-first）。
     */
    fun continueToNextWorkflow(sourceWorkflowId: WorkflowId): WorkflowId {
        val wf1 = requireWorkflow(sourceWorkflowId)
        if (wf1.status != WorkflowStatus.COMPLETED) {
            throw ApplicationException(ApplicationError.InvalidOperation("source Workflow ${sourceWorkflowId.value} 未 COMPLETED，不能续篇"))
        }
        val srcChapter = wf1.activeChapterId
            ?: throw ApplicationException(ApplicationError.InvalidOperation("source Workflow 无 active Chapter，无法续篇"))
        val srcDraft = draftRepository.latestByChapter(srcChapter)?.takeIf { it.status == DraftStatus.CONFIRMED }
            ?: throw ApplicationException(ApplicationError.DraftConfirmationRequired("续篇需要 source Chapter 的 CONFIRMED Final Draft"))

        val targetChapterId = workflowRepository.getContinuationBySourceDraft(srcDraft.draftId)?.targetChapterId ?: run {
            val target = resolveNextChapter(wf1, srcChapter)
            workflowRepository.inTransaction {
                if (workflowRepository.getContinuationBySourceDraft(srcDraft.draftId) == null) {
                    workflowRepository.createContinuation(
                        WorkflowContinuation(
                            continuationId = WorkflowContinuationId(UUID.randomUUID().toString()),
                            workflowId = wf1.workflowId,
                            sourceDraftId = srcDraft.draftId,
                            sourceChapterId = srcChapter,
                            targetChapterId = target,
                            createdAt = Clock.System.now(),
                        ),
                    )
                }
            }
            target
        }

        // 确定性 Chapter 2 Workflow 身份 → 幂等查找/创建（不因 session 不同而重复）。
        val wf2Id = WorkflowId("continue:${srcDraft.draftId.value}:${targetChapterId.value}")
        if (workflowRepository.getWorkflow(wf2Id) == null) {
            val now = Clock.System.now()
            workflowRepository.createWorkflow(
                Workflow(wf2Id, wf1.novelId, wf1.variantId, kind = WorkflowKind.CONTINUE, status = WorkflowStatus.CREATED, activeChapterId = targetChapterId, createdAt = now, updatedAt = now),
            )
        }
        return wf2Id
    }

    /** 解析 source Chapter 的下一章：同 novel+variant、紧邻其后（order）的既有 Chapter；无则原子创建。 */
    private fun resolveNextChapter(wf: Workflow, srcChapter: ChapterId): ChapterId {
        val chapters = chapterRepository.listByNovel(wf.novelId, wf.variantId)
        val idx = chapters.indexOfFirst { it.chapterId == srcChapter }
        if (idx >= 0 && idx + 1 < chapters.size) return chapters[idx + 1].chapterId
        val now = Clock.System.now()
        val created = Chapter(
            chapterId = ChapterId(UUID.randomUUID().toString()),
            novelId = wf.novelId,
            variantId = wf.variantId,
            scope = if (wf.variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
            title = "未命名章节",
            order = 0,
            status = ChapterStatus.PLANNED,
            createdAt = now,
            updatedAt = now,
        )
        return chapterRepository.createNextChapter(created).chapterId
    }

    /* ---------- successor / decision / recovery ---------- */

    /**
     * 依据已完成 Step 的 phase+resultReference 创建（幂等）其 business 后继。
     * CRITIQUE 依据 decision 分支：PASS→FINALIZE；REVISION→（<max）REVISION:Rn 否则 Workflow FAILED（明确终止）。
     * REVISION → 对新 Draft 的 CRITIQUE:Rn。KNOOWLEDGE_UPDATE 为叶，不建后继。
     */
    private fun createSuccessor(step: WorkflowStep) {
        if (step.status != WorkflowStepStatus.COMPLETED) return
        val wfId = step.workflowId
        val ch = step.chapterId
        when (step.phase) {
            WorkflowStepPhase.PLANNING -> createStepIfMissing(wfId, ch, WorkflowStepPhase.WRITING)
            WorkflowStepPhase.WRITING -> createStepIfMissing(wfId, ch, WorkflowStepPhase.CRITIQUE)
            WorkflowStepPhase.CRITIQUE -> {
                val d = decodeCritique(step.resultReference) ?: return
                when (d.decision) {
                    CritiqueDecision.PASS -> createStepIfMissing(wfId, ch, WorkflowStepPhase.FINALIZE)
                    CritiqueDecision.REVISION -> {
                        val revCount = countRevisions(wfId, ch)
                        if (revCount >= MAX_REVISIONS) {
                            termFailed(wfId, "达到最大修订次数 $MAX_REVISIONS，修订后仍不通过，终止")
                        } else {
                            createStepIfMissingKey(wfId, ch, WorkflowStepPhase.REVISION, revisionKey(wfId, ch, revCount + 1))
                        }
                    }
                }
            }
            WorkflowStepPhase.REVISION -> createStepIfMissingKey(wfId, ch, WorkflowStepPhase.CRITIQUE, critiqueKey(wfId, ch, revisionRound(step)))
            WorkflowStepPhase.FINALIZE -> createStepIfMissing(wfId, ch, WorkflowStepPhase.CONFIRMATION)
            WorkflowStepPhase.CONFIRMATION -> createStepIfMissing(wfId, ch, WorkflowStepPhase.KNOWLEDGE_UPDATE)
            else -> {} // KNOWLEDGE_UPDATE 叶
        }
    }

    /** 完成一个 Step + 写 resultReference + 建后继 + 推进（统一；幂等）。 */
    private fun finishStep(wf: Workflow, step: WorkflowStep, resultReference: String) {
        val done = step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = resultReference, completedAt = Clock.System.now())
        completeStep(step, resultReference)
        createSuccessor(done)
        advance(wf.workflowId, step)
    }

    /** 触发本次修订的 CRITIQUE = 该 chapter 最近一个 CRITIQUE（先于本 REVISION 创建）。 */
    private fun triggeringCritique(wfId: WorkflowId, chapterId: ChapterId): WorkflowStep? =
        workflowRepository.listSteps(wfId).lastOrNull { it.phase == WorkflowStepPhase.CRITIQUE && it.chapterId == chapterId }

    /** 从 REVISION Step 的 logicalStepKey 解析 round（R1/R2/…）。 */
    private fun revisionRound(step: WorkflowStep): Int {
        val marker = "${WorkflowStepPhase.REVISION.name}:R"
        val idx = step.logicalStepKey.lastIndexOf(marker)
        return if (idx == -1) 1 else step.logicalStepKey.substring(idx + marker.length).toIntOrNull() ?: 1
    }

    /** workflow 级 revisionCount = 本章已创建的 REVISION Step 数（每次修订 +1，Metadata 持久化于 step 序列）。 */
    private fun countRevisions(wfId: WorkflowId, chapterId: ChapterId): Int =
        workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.REVISION && it.chapterId == chapterId }

    private fun createStepIfMissing(wfId: WorkflowId, chapterId: ChapterId, phase: WorkflowStepPhase) =
        createStepIfMissingKey(wfId, chapterId, phase, "${wfId.value}:${chapterId.value}:${phase.name}")

    private fun createStepIfMissingKey(wfId: WorkflowId, chapterId: ChapterId, phase: WorkflowStepPhase, key: String) {
        if (workflowRepository.getStepByKey(wfId, key) == null) {
            workflowRepository.createStep(
                WorkflowStep(
                    stepId = WorkflowStepId(UUID.randomUUID().toString()), workflowId = wfId,
                    chapterId = chapterId, phase = phase, logicalStepKey = key,
                    status = WorkflowStepStatus.PENDING, createdAt = Clock.System.now(),
                ),
            )
        }
    }

    private fun createAttempt(step: WorkflowStep, attemptNo: Int): WorkflowStepAttempt {
        val a = WorkflowStepAttempt(
            attemptId = WorkflowStepAttemptId(UUID.randomUUID().toString()),
            stepId = step.stepId, attemptNo = attemptNo,
            status = WorkflowAttemptStatus.RUNNING, startedAt = Clock.System.now(),
        )
        workflowRepository.createAttempt(a)
        updateStep(step.copy(currentAttemptNo = attemptNo))
        return a
    }

    private fun markAttemptFailed(attempt: WorkflowStepAttempt, category: AttemptErrorCategory?) {
        updateAttempt(attempt.copy(status = WorkflowAttemptStatus.FAILED, errorCategory = category, errorMessage = "attempt ${attempt.attemptNo} 失败", completedAt = Clock.System.now()))
    }

    private fun requireLatestDraft(ch: ChapterId): com.qianyan.model.writing.Draft =
        draftRepository.latestByChapter(ch) ?: throw ApplicationException(ApplicationError.InvalidOperation("chapter $ch 无 Draft"))

    private fun requirePlanFor(wf: Workflow, step: WorkflowStep, chapterId: ChapterId): ChapterPlan {
        val planStep = workflowRepository.listSteps(wf.workflowId).firstOrNull { it.phase == WorkflowStepPhase.PLANNING && it.chapterId == chapterId }
        val ref = planStep?.resultReference ?: throw ApplicationException(ApplicationError.InvalidOperation("缺少已完成 PLANNING 步骤结果"))
        if (!ref.startsWith("PLANJSON:")) throw ApplicationException(ApplicationError.InvalidOperation("PLANNING 缺 durable plan seam"))
        return decodeJson(ref.removePrefix("PLANJSON:")) ?: throw ApplicationException(ApplicationError.RestoreFailure("无法解码 plan"))
    }

    private fun completeStep(step: WorkflowStep, ref: String) {
        if (step.status != WorkflowStepStatus.COMPLETED) {
            updateStep(step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = ref, completedAt = Clock.System.now()))
        }
    }

    private fun ensureCompleted(step: WorkflowStep) {
        if (step.status != WorkflowStepStatus.COMPLETED) {
            updateStep(step.copy(status = WorkflowStepStatus.COMPLETED, completedAt = Clock.System.now(), resultReference = step.resultReference))
        }
    }

    private fun advance(wfId: WorkflowId, done: WorkflowStep) {
        val wf = requireWorkflow(wfId)
        // currentStepId 置 null，让当前步骤以「首个未完成」解析（避免指向刚完成的步骤而循环空转）。
        updateWorkflow(wf.copy(currentStepId = null, updatedAt = Clock.System.now()))
    }

    private fun termFailed(wfId: WorkflowId, message: String) {
        val wf = requireWorkflow(wfId)
        updateWorkflow(wf.copy(status = WorkflowStatus.FAILED, updatedAt = Clock.System.now()))
    }

    /* ---------- critique 编解码 ---------- */

    private enum class CritiqueDecision { PASS, REVISION }

    private fun critiqueRef(cTaskId: TaskId, subjectDraftId: DraftId, passed: Boolean): String {
        val decision = if (passed) CritiqueDecision.PASS else CritiqueDecision.REVISION
        return "CRITIQUE:${cTaskId.value}:${subjectDraftId.value}:$decision"
    }

    /** 解析 "CRITIQUE:<taskId>:<draftId>:<PASS|REVISION>"（decision 为机器可判定后缀，taskId 可含冒号仍安全）。 */
    private fun decodeCritique(ref: String?): DecodedCritique? {
        if (ref == null || !ref.startsWith("CRITIQUE:")) return null
        val body = ref.removePrefix("CRITIQUE:")
        val i = body.lastIndexOf(':')
        if (i == -1) return null
        val decision = when (body.substring(i + 1)) {
            CritiqueDecision.PASS.name -> CritiqueDecision.PASS
            CritiqueDecision.REVISION.name -> CritiqueDecision.REVISION
            else -> return null
        }
        val mid = body.substring(0, i)
        val j = mid.lastIndexOf(':')
        if (j == -1) return null
        val taskId = mid.substring(0, j)
        val draftId = mid.substring(j + 1)
        return DecodedCritique(taskId = taskId, subjectDraftId = DraftId(draftId), decision = decision)
    }

    /** 从 CRITIQUE Task 的 Checkpoint 恢复完整 [ValidationResult]（resultReference-first，不重调 LLM）。 */
    private fun recoverCritiqueResult(critStep: WorkflowStep): ValidationResult? {
        val decoded = decodeCritique(critStep.resultReference) ?: return null
        val cp = try { taskManager.restoreCheckpoint(TaskId(decoded.taskId)) } catch (e: ApplicationException) { return null }
        return critique.critiqueFrom(cp)
    }

    private data class DecodedCritique(val taskId: String, val subjectDraftId: DraftId, val decision: CritiqueDecision)

    private fun revisionKey(wfId: WorkflowId, ch: ChapterId, round: Int) = "${wfId.value}:${ch.value}:${WorkflowStepPhase.REVISION.name}:R$round"

    private fun critiqueKey(wfId: WorkflowId, ch: ChapterId, round: Int): String =
        if (round <= 0) "${wfId.value}:${ch.value}:${WorkflowStepPhase.CRITIQUE.name}"
        else "${wfId.value}:${ch.value}:${WorkflowStepPhase.CRITIQUE.name}:R$round"

    private fun updateWorkflow(wf: Workflow) = workflowRepository.updateWorkflow(wf)
    private fun updateStep(step: WorkflowStep) = workflowRepository.updateStep(step)
    private fun updateAttempt(a: WorkflowStepAttempt) = workflowRepository.updateAttempt(a)

    private fun classify(e: ApplicationException): AttemptErrorCategory? = when (e.error) {
        is ApplicationError.ProviderUnavailable -> AttemptErrorCategory.RETRYABLE
        is ApplicationError.ProviderCredentialMissing -> AttemptErrorCategory.NEEDS_HUMAN
        is ApplicationError.InvalidWritingOutput -> AttemptErrorCategory.NON_RETRYABLE
        else -> null
    }

    private fun errorMapperToCategory(t: Throwable): AttemptErrorCategory =
        if (t is ProviderException) AttemptErrorCategory.RETRYABLE else AttemptErrorCategory.NON_RETRYABLE

    private fun requireWorkflow(id: WorkflowId): Workflow =
        workflowRepository.getWorkflow(id) ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${id.value}"))

    private fun requireGate(id: com.qianyan.model.workflow.WorkflowHumanGateId): WorkflowHumanGate =
        workflowRepository.getGate(id) ?: throw ApplicationException(ApplicationError.EntityNotFound("Gate 不存在: ${id.value}"))

    private fun resolveGatePending(gate: WorkflowHumanGate): Boolean {
        var resolved = false
        workflowRepository.inTransaction {
            val g = requireGate(gate.gateId)
            if (g.status == HumanGateStatus.PENDING) {
                workflowRepository.updateGate(g.copy(status = HumanGateStatus.RESOLVED, decision = HumanDecision.APPROVED, resolvedAt = Clock.System.now(), resolvedBy = "user"))
                resolved = true
            }
        }
        return resolved
    }

    private fun createPendingGate(wf: Workflow, step: WorkflowStep, draftId: DraftId): WorkflowHumanGate {
        val existing = workflowRepository.getGateByKey("${wf.workflowId.value}:${step.logicalStepKey}:${draftId.value}")
        if (existing != null) return existing
        val now = Clock.System.now()
        val gate = WorkflowHumanGate(
            gateId = com.qianyan.model.workflow.WorkflowHumanGateId(UUID.randomUUID().toString()),
            workflowId = wf.workflowId, stepId = step.stepId, draftId = draftId,
            gateKey = "${wf.workflowId.value}:${step.logicalStepKey}:${draftId.value}",
            status = HumanGateStatus.PENDING, decision = HumanDecision.PENDING, createdAt = now,
        )
        workflowRepository.createGate(gate)
        return gate
    }

    private fun encodeJson(plan: ChapterPlan): String = Json.encodeToString(ChapterPlan.serializer(), plan)
    private fun decodeJson(s: String): ChapterPlan? = try { Json.decodeFromString(ChapterPlan.serializer(), s) } catch (e: Exception) { null }

    private fun buildRequest(novelId: NovelId, variantId: VariantId?): com.qianyan.model.context.UserWritingRequest =
        com.qianyan.model.context.UserWritingRequest(
            requestId = com.qianyan.model.RequestId(UUID.randomUUID().toString()),
            intentType = com.qianyan.model.IntentType.CONTINUE,
            target = com.qianyan.model.context.TargetRef(com.qianyan.model.context.TargetKind.CHAPTER, null),
            planningScope = com.qianyan.model.PlanningScope.CHAPTER,
            baseNovelId = com.qianyan.model.BaseNovelId(novelId.value),
            variantId = variantId,
            scope = if (variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
        )

    private companion object {
        /** 与 RevisionGate.MAX_REVISIONS 对齐：修订上限 3。 */
        const val MAX_REVISIONS = 3

        /** 需要由本 Orchestrator 补建后继的已完成 step phase（KNOWLEDGE_UPDATE 为叶，排除）。 */
        val SUCCEEDED_PHASES = setOf(
            WorkflowStepPhase.PLANNING, WorkflowStepPhase.WRITING, WorkflowStepPhase.CRITIQUE,
            WorkflowStepPhase.REVISION, WorkflowStepPhase.FINALIZE, WorkflowStepPhase.CONFIRMATION,
        )
    }
}

/** runForward 结果。 */
data class WorkflowRunResult(
    val status: WorkflowStatus,
    val stepCompleted: Boolean = false,
    val retried: Boolean = false,
    val reused: Boolean = false,
    val failed: Boolean = false,
)