package com.qianyan.application.usecase.workflow

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.change.ChangeUseCases
import com.qianyan.application.usecase.canonical.CanonicalReadUseCases
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.application.usecase.draft.WorkingDraftUseCases
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.application.usecase.writing.WritingExecutionUseCase
import com.qianyan.application.usecase.writing.critique.CritiqueExecutionUseCase
import com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlanningSnapshot
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.decision.DecisionPolicy
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
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.provider.ProviderException
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.WorkflowRepository
import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
    private val knowledgeUpdate: KnowledgeUpdateExecutionUseCase,
    private val draftRepository: DraftRepository,
    private val chapterRepository: ChapterRepository,
    private val commits: CommitUseCases,
    private val workingDrafts: WorkingDraftUseCases,
    private val changes: ChangeUseCases,
    private val canonicalRead: CanonicalReadUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** P12.4-M08 单 step 最大尝试次数（attemptNo 计数）。达到上限后终止为 Workflow.FAILED，禁止无条件重跑。 */
    private val maxStepAttempts = 5

    /** 前进当前 LogicalStep 直到 WAITING_HUMAN / 未接入 phase / COMPLETED。幂等：已 COMPLETED / resultReference 命中不复跑。 */
    /**
     * 前进当前 LogicalStep 直到 WAITING_HUMAN / 未接入 phase / COMPLETED。幂等：已 COMPLETED / resultReference 命中不复跑。
     *
     * @param allowReentryAfterReject P1-02g：**显式作者请求**（如 `ChapterWorkflowGateway.advance`）才置 true ——
     *   被拒绝的 Gate 允许在同一 Workflow 上开启**新的决议轮次**（不重新生成内容）。
     *   只读查询与恢复路径（`resume`）必须保持 false：不得因刷新/恢复自动创建新 Gate。
     */
    fun runForward(workflowId: WorkflowId, allowReentryAfterReject: Boolean = false): WorkflowRunResult {
        var guard = 0
        var last = WorkflowRunResult(status = requireWorkflow(workflowId).status, stepCompleted = false)
        var done = false
        while (guard++ < 16 && !done) {
            val wf = requireWorkflow(workflowId)
            when (wf.status) {
                WorkflowStatus.WAITING_HUMAN -> {
                    // P1-02g：旧 Gate 被拒绝 + 显式请求 ⇒ 开启新决议轮次（否则保持等待）
                    if (allowReentryAfterReject && reopenRejectedRound(wf)) continue
                    done = true
                }
                WorkflowStatus.PAUSED,
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
                            if (c == null) { done = true } else createStepIfMissing(wf.workflowId, c, WorkflowStepPhase.PLANNING, 0)
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
                        // P12.4-M08：失败 step 的 retry 策略——Retryable 受 bounded 上限约束（可自动重试），
                        // NonRetryable 立即终止为 Workflow.FAILED；不再在单次 runForward 内无条件 16 次重跑。
                        if (last.failed && !shouldRetryStep(wf, step)) {
                            done = true
                            last = WorkflowRunResult(status = WorkflowStatus.FAILED, stepCompleted = false, failed = true)
                        }
                    }
                }
            }
        }
        return last
    }

    /**
     * **AI 改写提案 → 既有章节 Workflow 的人工门**（P0-2.1）。
     *
     * 复用既有 Step / Gate / 批准收敛链路，不新开 Workflow、不重跑 LLM：
     *  1. 已有 PENDING Gate → 拒绝（必须先决议，防止并行提案）；
     *  2. 旧 CONFIRMATION 若已批准但尚未收敛 → 仅补完成该 Step 与既有后继（不重跑 LLM）；
     *     其余未决 / 已拒绝 CONFIRMATION → `SKIPPED` 关闭旧轮（旧 Gate 决议不改写）；
     *  3. 其他未完成 Step（WRITING / CRITIQUE / REVISION …）→ 明确拒绝，避免与在途轮次混写；
     *  4. 追加一个携带 `WORKING:<id>` 的已完成 REVISION Step + 新 CONFIRMATION Step + 新 PENDING Gate。
     *
     * 批准仍走 [approveGate]（Change → Commit）；拒绝走 `WorkflowService.rejectGate`。
     */
    fun proposeRewrite(workflowId: WorkflowId, workingDraftId: WorkingDraftId): WorkflowRunResult {
        val working = workingDrafts.get(workingDraftId)
        val chapterId = working.target.chapterId
        val wf = requireWorkflow(workflowId)
        if (wf.activeChapterId != chapterId) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Working Draft 目标章节 ${chapterId.value} 与 Workflow ${workflowId.value} 的 active Chapter 不一致",
                ),
            )
        }
        if (working.target.novelId != wf.novelId || working.target.variantId != wf.variantId) {
            throw ApplicationException(ApplicationError.InvalidOperation("Working Draft 作用域与 Workflow 不一致，不能进入人工门"))
        }
        val pendingGate = wf.pendingGateId?.let { workflowRepository.getGate(it) }
        if (pendingGate?.status == HumanGateStatus.PENDING) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("chapter ${chapterId.value} 已有待确认的 AI 提案，请先批准或拒绝"),
            )
        }
        val unresolved = workflowRepository.listSteps(workflowId).filter {
            it.chapterId == chapterId &&
                it.status != WorkflowStepStatus.COMPLETED &&
                it.status != WorkflowStepStatus.SKIPPED
        }
        val inFlight = unresolved.filter { it.phase != WorkflowStepPhase.CONFIRMATION }
        if (inFlight.isNotEmpty()) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "chapter ${chapterId.value} 当前工作流尚有未完成步骤（${inFlight.joinToString { it.phase.name }}），请先继续或恢复",
                ),
            )
        }

        val validation = workingDrafts.validate(workingDraftId)
        if (!validation.passed) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "AI 候选未通过确定性校验（${validation.issues.joinToString { it.field }}），不能进入人工确认",
                ),
            )
        }
        val now = Clock.System.now()
        workflowRepository.inTransaction {
            // 已批准的旧轮：提交事实已成立，仅补齐 Step 收敛与既有后继（绝不重跑 LLM）。
            val approvedStepId = pendingGate
                ?.takeIf { it.status == HumanGateStatus.RESOLVED && it.decision == HumanDecision.APPROVED }
                ?.stepId
            if (approvedStepId != null) {
                val step = workflowRepository.getStep(approvedStepId)
                if (step != null && step.status != WorkflowStepStatus.COMPLETED && step.status != WorkflowStepStatus.SKIPPED) {
                    val done = step.copy(status = WorkflowStepStatus.COMPLETED, completedAt = now)
                    workflowRepository.updateStep(done)
                    createSuccessor(done)
                }
            }
            // 被拒绝 / 未决的旧 CONFIRMATION：关闭旧轮（旧 Gate 行保持原决议）。
            unresolved
                .filter { it.phase == WorkflowStepPhase.CONFIRMATION && it.stepId != approvedStepId }
                .forEach { updateStep(it.copy(status = WorkflowStepStatus.SKIPPED, completedAt = now)) }

            val revisionNo = totalRevisionCount(workflowId, chapterId) + 1
            val revision = WorkflowStep(
                stepId = WorkflowStepId(UUID.randomUUID().toString()),
                workflowId = workflowId,
                chapterId = chapterId,
                phase = WorkflowStepPhase.REVISION,
                logicalStepKey = revisionKey(workflowId, chapterId, revisionNo),
                status = WorkflowStepStatus.COMPLETED,
                resultReference = workingRef(workingDraftId),
                roundNo = 0,
                createdAt = now,
                completedAt = now,
            )
            workflowRepository.createStep(revision)

            val suffix = nextRoundSuffix(workflowId, chapterId)
            val confirmation = WorkflowStep(
                stepId = WorkflowStepId(UUID.randomUUID().toString()),
                workflowId = workflowId,
                chapterId = chapterId,
                phase = WorkflowStepPhase.CONFIRMATION,
                logicalStepKey = phaseKey(workflowId, chapterId, WorkflowStepPhase.CONFIRMATION, suffix),
                status = WorkflowStepStatus.PENDING,
                roundNo = suffix,
                // 同毫秒内保证确认 Step 晚于工作引用 Step 出现（listSteps 按 created_at 排序）。
                createdAt = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 1),
            )
            workflowRepository.createStep(confirmation)

            val current = requireWorkflow(workflowId)
            val gate = createPendingGate(current, confirmation, workingRef(workingDraftId))
            updateWorkflow(
                current.copy(
                    status = WorkflowStatus.WAITING_HUMAN,
                    pendingGateId = gate.gateId,
                    currentStepId = confirmation.stepId,
                    updatedAt = now,
                ),
            )
        }
        return WorkflowRunResult(status = WorkflowStatus.WAITING_HUMAN, stepCompleted = true)
    }

    /* ---------- 被拒绝 Gate 的重新进入（P1-02g） ---------- */

    /**
     * **重新进入（同一 Workflow 上的新决议轮次）**——仅由显式作者请求触发（见 [runForward] 参数）。
     *
     * 领域语义：**拒绝只针对当前这一轮提案 / Gate**，不构成该章节未来所有尝试的永久拒绝。
     * 最小实现（不新建 Workflow、不重建内容、不改写历史）：
     *  1. 前置：`WAITING_HUMAN` 且 `pendingGateId` 指向一个 `RESOLVED + REJECTED` 的 Gate（其它状态一律不动）；
     *  2. 把该被拒轮次的 CONFIRMATION Step 置 `SKIPPED`（关闭该轮，使其不再参与"首个未完成 Step"扫描）；
     *     旧 Gate 行**保持原样**（决议不可改写、不删除）；
     *  3. 为同一章节建立**新一轮 CONFIRMATION Step**（key 带 `:R<round>`）——Gate 身份由 `gateKey` 派生，
     *     因此新轮次必然得到**新的 Gate**，不复用旧的 REJECTED Gate；
     *  4. `WAITING_HUMAN → RUNNING` 并清空 `pendingGateId`：由 CONFIRMATION 阶段按既有逻辑建立新 PENDING Gate；
     *  5. **不重新生成内容**：新轮次经 `requireChapterWorking` 复用既有工作产物（引用失效 → 明确失败，不重跑 LLM）。
     *
     * @return true = 已开启新轮次（调用方继续推进）；false = 无需/不可重开（保持原状，幂等）。
     */
    private fun reopenRejectedRound(wf: Workflow): Boolean {
        val gate = wf.pendingGateId?.let { workflowRepository.getGate(it) } ?: return false
        if (gate.status != HumanGateStatus.RESOLVED || gate.decision != HumanDecision.REJECTED) return false
        val chapterId = wf.activeChapterId ?: return false
        val now = Clock.System.now()
        val confirmations = workflowRepository.listSteps(wf.workflowId)
            .filter { it.chapterId == chapterId && it.phase == WorkflowStepPhase.CONFIRMATION }
        // 关闭被拒轮次（SKIPPED：不再被"首个未完成 Step"选中；不删除、不改写其 Gate 决议）
        confirmations
            .filter { it.status != WorkflowStepStatus.COMPLETED && it.status != WorkflowStepStatus.SKIPPED }
            .forEach { updateStep(it.copy(status = WorkflowStepStatus.SKIPPED, completedAt = now)) }
        // P1-02j.2 统一编号：与 `abandonRound` 共用唯一后缀分配器 `nextRoundSuffix`（杜绝 `:R<n>` 冲突，
        // 不复用其旧的 `confirmations.size+1` 推导）。**创作轮次不变**：重开只是同一轮次的 Gate 决议轮，
        // `roundNo` 沿用被拒 CONFIRMATION Step 的创作轮次（`null` → 0）。
        val creativeRound = gate.stepId?.let { workflowRepository.getStep(it)?.roundNo } ?: 0
        val suffix = nextRoundSuffix(wf.workflowId, chapterId)
        createStepIfMissingKey(
            wf.workflowId,
            chapterId,
            WorkflowStepPhase.CONFIRMATION,
            phaseKey(wf.workflowId, chapterId, WorkflowStepPhase.CONFIRMATION, suffix),
            creativeRound,
        )
        updateWorkflow(
            wf.copy(status = WorkflowStatus.RUNNING, pendingGateId = null, currentStepId = null, updatedAt = now),
        )
        return true
    }

    /* ---------- 失效轮次的显式作废（P1-02j A1） ---------- */

    /**
     * **显式作废一个"失效轮次"并开启新一轮**（P1-02j A1）。
     *
     * 适用：Gate 已被**拒绝**，但其工作产物**已失效**（内存工作区丢失 / 无工作引用）—— 该轮**无合法前进出口**
     * （既不能批准旧正文，也无法重放 CONFIRMATION）。**非自动**：仅当上层显式调用才执行 ——
     * `resume` / `getChapterProgress` 等只读路径**绝不**触发（不因检测到 `RestoreFailure` 就静默作废 / 重跑 LLM）。
     *
     * 语义（不伪造、不重跑 LLM、不改写历史）：
     *  - 旧 Gate 保持 `RESOLVED + REJECTED`；旧 Step / Attempt 历史**保留**（仅把该轮**未终态的 CONFIRMATION**
     *    置 `SKIPPED` 关闭该轮）；
     *  - 同一 Workflow 上开启**新一轮**：全新 `logical_step_key`（`…:<phase>:R<round>`）覆盖
     *    WRITING / CRITIQUE / FINALIZE / CONFIRMATION（不复用旧 Step 身份 / 旧 Gate；**不用** `attemptNo` 表达轮次）；
     *  - **仅当计划 seam 仍可解码**，才把新一轮限制为 WRITING（不重跑 PLANNING、不重跑 LLM）；否则显式失败；
     *  - 清空 `pendingGateId` / `currentStepId`，`WAITING_HUMAN → RUNNING`（脱离语义不符的"等待人工"）。
     *
     * 前置（任一不满足 → 类型化失败，**不改动任何状态**）：
     *  - Gate 必须 `RESOLVED + REJECTED`（`PENDING` 未决 / `APPROVED` 已批准一律拒绝 —— 后者走批准收敛，绝不作废）；
     *  - Workflow 必须 `WAITING_HUMAN` 且 `pendingGateId` 指向该 Gate；
     *  - 该轮工作产物必须**确实失效**（仍可解析 ⇒ 属可恢复轮次，应走 `advance` 重新进入）。
     *
     * @return [AbandonRoundOutcome.Abandoned]（新一轮编号）；仅建立新一轮，**不**驱动 LLM（由后续显式 advance 触发）。
     */
    fun abandonRound(gateId: com.qianyan.model.workflow.WorkflowHumanGateId): AbandonRoundOutcome {
        val gate = requireGate(gateId)
        // 1) 决议校验：只有"被拒绝且失效"的轮次才可作废；APPROVED 走批准收敛，绝不改写为作废。
        if (gate.status != HumanGateStatus.RESOLVED || gate.decision != HumanDecision.REJECTED) {
            val hint = if (gate.decision == HumanDecision.APPROVED) "；已批准请走批准收敛（approveGate）" else ""
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Gate ${gateId.value} 非 RESOLVED+REJECTED（当前 ${gate.status}/${gate.decision}），不能作废$hint",
                ),
            )
        }
        val step = gate.stepId?.let { workflowRepository.getStep(it) }
            ?: throw ApplicationException(ApplicationError.InvalidOperation("Gate ${gateId.value} 未绑定 Workflow Step"))
        val wf = workflowRepository.getWorkflow(step.workflowId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${step.workflowId.value}"))
        if (wf.status != WorkflowStatus.WAITING_HUMAN || wf.pendingGateId != gate.gateId) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "Workflow ${wf.workflowId.value} 当前不在该 Gate 的待决位置（status=${wf.status.name}），不能作废",
                ),
            )
        }
        // 2) 只作废**失效**轮次：工作产物仍可解析 ⇒ 属可恢复轮次，不作废（请走重新进入 / 批准）。
        if (resolveChapterWorkingOrNull(wf, step) != null) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "chapter ${step.chapterId.value} 该轮工作产物仍可用，属可恢复轮次，不能作废（请使用重新进入 / 批准）",
                ),
            )
        }
        // 3) 仅当计划 seam 可解码，才允许新一轮只重跑 WRITING（不重跑 PLANNING / 不重跑 LLM）。
        requirePlanContextFor(wf, step, step.chapterId)

        val chapterId = step.chapterId
        val now = Clock.System.now()
        val round = nextRoundSuffix(wf.workflowId, chapterId)
        workflowRepository.inTransaction {
            // 关闭失效轮次：该章未终态的 CONFIRMATION → SKIPPED（旧 Gate / Step / Attempt 一律保留、不改写决议）。
            workflowRepository.listSteps(wf.workflowId)
                .filter { it.chapterId == chapterId && it.phase == WorkflowStepPhase.CONFIRMATION }
                .filter { it.status != WorkflowStepStatus.COMPLETED && it.status != WorkflowStepStatus.SKIPPED }
                .forEach { updateStep(it.copy(status = WorkflowStepStatus.SKIPPED, completedAt = now)) }
            // 新一轮只**起一个 WRITING Step**（创作轮次 = round）；其后继 CRITIQUE / FINALIZE / CONFIRMATION
            // 由 `createSuccessor` 按该 Step 的 roundNo **动态建立**（⇒ 与 REVISION 分支的先后由 created_at 自然保证）。
            createStepIfMissingKey(
                wf.workflowId, chapterId, WorkflowStepPhase.WRITING,
                phaseKey(wf.workflowId, chapterId, WorkflowStepPhase.WRITING, round), round,
            )
            updateWorkflow(wf.copy(status = WorkflowStatus.RUNNING, pendingGateId = null, currentStepId = null, updatedAt = now))
        }
        return AbandonRoundOutcome.Abandoned(wf.workflowId, round)
    }

    /**
     * 后缀分配器（P1-02j.2 统一编号；P1-02j.2-G 命名空间分离）：`abandonRound` 与 `reopenRejectedRound`
     * 共用，返回严格大于既有所有 `:R<n>` 与 `:W<n>` 后缀的值（⇒ 新 `:W<n>` 永不冲突），并**预留**
     * revision 命名空间（≤ [MAX_REVISIONS]）以避免与轮内动态 key 相邻。
     *
     * 注意：本分配器只负责**键唯一性**；**创作轮次身份**由 [WorkflowStep.roundNo] 承载（仅 abandon 推进）。
     */
    private fun nextRoundSuffix(wfId: WorkflowId, chapterId: ChapterId): Int {
        val steps = workflowRepository.listSteps(wfId)
        val maxSuffix = steps.mapNotNull { ROUND_SUFFIX.find(it.logicalStepKey)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        val revCount = steps.count { it.chapterId == chapterId && it.phase == WorkflowStepPhase.REVISION }
        return maxOf(maxSuffix, revCount + MAX_REVISIONS) + 1
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
        // P20-P5-fix（FD-4）：从本 PLANNING Task 的 Checkpoint 取**已决定的同一份** DecisionPolicy（只解码，不重算），
        // 随 Plan 一并持久化到 step.resultReference，供 Writing 恢复（Resume 不重新 Decision）。
        val policies = planning.decisionPoliciesFrom(taskManager.restoreCheckpoint(taskId)).orEmpty()
        finishStep(wf, step, encodePlanRef(plan, policies))
    }

    private fun executeWriting(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val attemptNo = (workflowRepository.listAttempts(step.stepId).size) + 1
        val attempt = createAttempt(step, attemptNo)
        val chapterId = step.chapterId
        val restored = requirePlanContextFor(wf, step, chapterId)
        val plan = restored.plan
        val request = buildRequest(wf.novelId, wf.variantId)
        // 与 executePlanning 同源：续篇来源来自 durable WorkflowContinuation（首章 → null）。
        val continuationRef = workflowRepository.getContinuationByTargetChapter(chapterId)?.let {
            ContinuationReference(sourceChapterId = it.sourceChapterId ?: chapterId, sourceDraftId = it.sourceDraftId)
        }
        return try {
            val taskId = taskManager.create(TaskType.WRITING)
            // P20-P5-fix（FD-4）：Writing 使用与 Planning **同一份** DecisionPolicy（旧 PLANJSON 无政策 → 空）；
            // Writer 不拥有 DecisionModel，绝不重新 Decision。
            // P1-02c+d：AI 产出进 WorkingDraft（工作区），**不写章节 Draft 表**；resultReference 为类型化工作引用。
            val working = writing.executeIntoWorkingDraft(
                taskId, request, plan, restored.policies.orEmpty(), continuationRef,
            )
            val workingRef = workingRef(working.workingDraftId)
            workflowRepository.inTransaction {
                updateAttempt(attempt.copy(status = WorkflowAttemptStatus.COMPLETED, taskId = taskId.value, completedAt = Clock.System.now()))
                updateStep(step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = workingRef, currentAttemptNo = attemptNo, currentTaskId = taskId.value, completedAt = Clock.System.now()))
            }
            val done = step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = workingRef, currentAttemptNo = attemptNo, currentTaskId = taskId.value, completedAt = Clock.System.now())
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
     *  resultReference = "CRITIQUE:<taskId>:WORKING:<workingDraftId>:<PASS|REVISION>"（decision 为**机器可判定**布尔派生）。
     *  PASS → FINALIZE；NEED_REVISION → 若 revisionCount < 3 建 REVISION Step，否则 WORKFLOW FAILED（明确终止，不无限循环）。
     *
     *  P1-02c+d：评审对象是**该章节当前 AI 工作产物**（WorkingDraft），按 resultReference 解析；
     *  缺失工作产物 → 明确错误（**不**回退到 `latestByChapter` 的 Draft）。
     */
    private fun executeCritique(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val subject = requireChapterWorking(wf, step)
        val cTaskId = taskManager.create(TaskType.WRITING)
        val result = critique.execute(cTaskId, subject.content)
        val ref = critiqueRef(cTaskId, workingRef(subject.workingDraftId), result.passed)
        finishStep(wf, step, ref)
        // 若 REVISION 分支触发终止（revision 达上限），以最新状态返回。
        return WorkflowRunResult(status = requireWorkflow(wf.workflowId).status, stepCompleted = true)
    }

    /**
     * Revision 执行（§5/§7/§8/§13）。resultReference = 同一工作产物引用（工作区正文被替换，身份不变）。
     *  - 每轮独立 WRITING Task（避免复用 WRITING task 的 3-checkpoint budget 在多次修订时耗尽）。
     *  - revisionCount = 已有 REVISION Step 数（Orchestrator 持久化控制；RevisionGate 语义 ≤3 在创建后继时约束）。
     *  - **P1-02c+d**：修订产物写回 WorkingDraft（`replace`，内存工作区），**不产生 / 不持久化章节 Draft**；
     *    幂等由 Step 级 resultReference 保证（已 COMPLETED 的 Step 不会再次执行）。
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
            val subject = resolveWorking(ref.subjectRef)
                ?: throw ApplicationException(
                    ApplicationError.RestoreFailure("REVISION 的 subject 工作产物不可用（引用失效）: ${ref.subjectRef}"),
                )
            val critiqueResult = recoverCritiqueResult(critStep)
                ?: throw ApplicationException(ApplicationError.RestoreFailure("无法恢复 CRITIQUE 结果（ValidationResult）"))

            val taskId = taskManager.create(TaskType.WRITING)
            val revisedContent = revision.reviseWorking(taskId, subject.workingDraftId.value, subject.content, critiqueResult, wf.novelId)
            val updated = workingDrafts.replace(subject.workingDraftId, revisedContent)
            val resultRef = workingRef(updated.workingDraftId)

            val now = Clock.System.now()
            workflowRepository.inTransaction {
                updateAttempt(attempt.copy(status = WorkflowAttemptStatus.COMPLETED, taskId = taskId.value, completedAt = now))
                updateStep(step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = resultRef, currentAttemptNo = attemptNo, currentTaskId = taskId.value, completedAt = now))
            }
            val done = step.copy(status = WorkflowStepStatus.COMPLETED, resultReference = resultRef, currentAttemptNo = attemptNo, currentTaskId = taskId.value, completedAt = now)
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
     * FINALIZE（P1-02c+d）：对当前工作产物执行确定性终稿整理（`validate`：WORKING → VALIDATED）。
     * **不写章节 Draft 表**（AI 终稿仍属工作区，只有人工门批准后的正式 Commit 才产生 Canonical 载体）。
     */
    private fun executeFinalize(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val subject = requireChapterWorking(wf, step)
        workingDrafts.validate(subject.workingDraftId)
        finishStep(wf, step, "FINAL:" + workingRef(subject.workingDraftId))
        return WorkflowRunResult(status = wf.status, stepCompleted = true)
    }

    /**
     * CONFIRMATION（P1-02c+d）：进入 Human Gate 前必须**准确关联当前待审核的工作产物**。
     *
     * Gate 的绑定方式（不改 schema）：`stepId` 指向 CONFIRMATION Step，`step.resultReference`
     * 为该 Step 的**类型化工作引用**（`WORKING:<workingDraftId>`）；`WorkflowHumanGate.draftId` 保持 `null`
     * ——**不把 WorkingDraftId 冒充 DraftId**（`draftId` 字段语义仍专属于绑定 Draft 的 Gate）。
     * 幂等：该 Gate 已被批准（RESOLVED + APPROVED）→ 完成本 Step（不重复建 Gate）。
     */
    private fun executeConfirmation(wf: Workflow, step: WorkflowStep): WorkflowRunResult {
        val subject = requireChapterWorking(wf, step)
        val ref = workingRef(subject.workingDraftId)
        val existing = workflowRepository.getGateByKey(gateKey(wf, step, ref))
        if (existing != null && existing.status == HumanGateStatus.RESOLVED && existing.decision == HumanDecision.APPROVED) {
            finishStep(wf, step, "CONFIR:" + ref)
            return WorkflowRunResult(status = wf.status, stepCompleted = true)
        }
        // 进入 Human Gate：建 Gate（幂等）→ WAITING_HUMAN。
        val gate = createPendingGate(wf, step, ref)
        updateWorkflow(wf.copy(status = WorkflowStatus.WAITING_HUMAN, pendingGateId = gate.gateId, currentStepId = step.stepId, updatedAt = Clock.System.now()))
        return WorkflowRunResult(status = WorkflowStatus.WAITING_HUMAN, stepCompleted = true)
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

    /**
     * 幂等批准（P1-02c+d）：批准 = **人工明确批准当前 AI 工作产物** ⇒ 经 I9 Change Artifact 交由
     * I10 [CommitUseCases] 唯一提交入口形成 **Canonical Commit**（凭据 = 本 Gate 的 `RESOLVED + APPROVED`
     * 记录，`COMMIT_CANONICAL` 在 I2 恒为 NeedsHuman —— 不新建 Gate、不弱化策略、不直接写 CommitHistory）。
     *
     * 事务/失败行为：提交由 [CommitUseCases.commit] 内部单事务完成（含事务内 stale 重检）；
     * 提交成功后才把 Workflow 置回 RUNNING。若提交失败，Gate 已 RESOLVED —— 重试（重复批准）会幂等补齐
     * （工作产物未 DISCARDED ⇒ 重新走 Change/Commit；已提交 ⇒ no-op）。
     */
    /**
     * 幂等批准：已批准 ⇒ 收敛；已拒绝 / 请求修订 ⇒ **类型化拒绝**（绝不把旧决议改写为批准）。
     */
    fun approveGate(gateId: com.qianyan.model.workflow.WorkflowHumanGateId): GateOutcome {
        val gate = requireGate(gateId)
        if (gate.status == HumanGateStatus.RESOLVED) {
            // 已批准：收敛未完成的后续步骤（幂等）；已拒绝的 Gate 不可被改写为批准。
            when (gate.decision) {
                HumanDecision.APPROVED -> {
                    convergeApprovedGate(gate)
                    return GateOutcome.AlreadyApproved
                }
                HumanDecision.REJECTED, HumanDecision.REQUEST_REVISION -> throw ApplicationException(
                    ApplicationError.InvalidOperation(
                        "Gate ${gate.gateId.value} 已 ${gate.decision.name}，不能批准；如需新轮次请显式推进 Workflow",
                    ),
                )
                else -> throw ApplicationException(
                    ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 决议状态异常"),
                )
            }
        }
        // P1-01：独立 Human Gate（workflowId=null）不参与 WAITING_HUMAN / pendingGateId 流转，本入口只处理 Workflow 绑定 Gate。
        val gateWorkflowId = gate.workflowId
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 是独立 Human Gate（未绑定 Workflow），不能经 WorkflowOrchestrator 批准"),
            )
        requireWorkflow(gateWorkflowId)
        // P1-02i-fix：**先判定本次批准可否收敛**（工作产物可用 + Artifact 可生成 / 或已提交事实已存在），
        // 再落批准 —— 否则"批准已记录、提交却失败"会留下虚假批准（P1-02i 定位）。
        val target = resolveApprovalTarget(gate)
        val resolved = resolveGatePending(gate)
        if (resolved) commitApproval(requireGate(gateId), target)
        return GateOutcome.Approved
    }

    /** 本次批准的可收敛目标（只读判定 + 确定性派生；判定失败 → 类型化错误，且**不写任何业务状态**）。 */
    private sealed interface ApprovalTarget {
        /** 正式提交事实已存在（Canonical 已等于该工作产物正文）⇒ 复用该载体，不重复提交。 */
        data class Committed(val carrierId: DraftId) : ApprovalTarget

        /** 已生成 I9 Artifact（通过 Validation）⇒ 待提交。 */
        data class Ready(val artifact: com.qianyan.model.change.ChangeArtifact) : ApprovalTarget
    }

    /**
     * 解析"本次批准要收敛到什么"（P1-02i-fix + P1-02j B1）。
     *
     * **首要判据（B1）** = 持久的 Gate ↔ Commit 关联（[CommitUseCases.committedByGate]）——跨进程重启可判定，
     * 不用"正文相等"启发式（逐字相同的多轮次会误判）。
     *
     * 其余：
     * - 工作产物引用失效（含跨重启内存工作区丢失）→ `RestoreFailure`（不得回退 `latestByChapter`、不得重跑 LLM）；
     * - 工作产物已 `DISCARDED` / Validation 未通过 → `DISCARDED_WORKING_DRAFT` / `INVALID_ARTIFACT` 类型化拒绝。
     *
     * 该方法**不改动任何业务状态**（仅生成内存 Artifact），因此可在"置 Gate 为 APPROVED"之前调用。
     */
    private fun resolveApprovalTarget(gate: WorkflowHumanGate): ApprovalTarget {
        // 1) B1：**首要**判据 —— 该 Gate 是否已存在持久的正式提交事实（跨重启可判定，非启发式）。
        commits.committedByGate(gate.gateId)?.let { return ApprovalTarget.Committed(it.resultingDraftId) }

        val step = gate.stepId?.let { workflowRepository.getStep(it) }
            ?: throw ApplicationException(ApplicationError.InvalidOperation("Gate ${gate.gateId.value} 未绑定 Workflow Step"))
        val wf = workflowRepository.getWorkflow(step.workflowId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${step.workflowId.value}"))
        // 2) 待审对象 = 该章节当前工作产物；失效（含跨重启丢失）→ 明确错误（无 Gate↔Commit 关联时不得凭正文相等推断）。
        val working = resolveChapterWorkingOrNull(wf, step)
            ?: throw ApplicationException(
                ApplicationError.RestoreFailure(
                    "该轮 AI 工作产物已失效且无 Gate↔Commit 关联（gate=${gate.gateId.value}）：" +
                        "无法判定是否已提交（当前 Canonical 载体=${canonicalRead.committedDraft(step.chapterId)?.draftId?.value ?: "无"}）；" +
                        "不得回退 latestByChapter / 不重跑 LLM；如需继续请显式作废该轮后重新生成",
                ),
            )
        if (working.status.isTerminal) {
            throw invalid(
                CommitErrorCodes.DISCARDED_WORKING_DRAFT,
                "工作产物已处于终态（${working.status.name}），不可提交: ${working.workingDraftId.value}",
            )
        }
        workingDrafts.validate(working.workingDraftId)
        // P0-3B D2：优先复用候选已有的 Artifact 身份（同一 WorkingDraft + 同一内容指纹）。
        return ApprovalTarget.Ready(changes.prepareReusing(working.workingDraftId))
    }

    /** 已批准 Gate 的自愈收敛：重新判定目标（可能明确失败），成功则幂等收敛。 */
    private fun convergeApprovedGate(gate: WorkflowHumanGate) {
        val step = gate.stepId?.let { workflowRepository.getStep(it) } ?: return
        val wf = workflowRepository.getWorkflow(step.workflowId) ?: return
        // 已收敛（已推进 / 已被其它 Gate 接管）→ no-op（不得在成功之后抛错）
        if (wf.status != WorkflowStatus.WAITING_HUMAN || wf.pendingGateId != gate.gateId) return
        commitApproval(gate, resolveApprovalTarget(gate))
    }

    /**
     * 批准后的**幂等收敛**（P1-02f + P1-02i-fix）：把"Gate 已批准"推进到
     * "提交事实已确立 + 载体 CONFIRMED + Workflow RUNNING"。
     *
     * 覆盖中断窗口：`resolveGatePending` 与 `commit` 之间（P1-02f A）、`commit` 与状态更新之间（P1-02f B）。
     * 幂等边界：仅当 Workflow 处于 `WAITING_HUMAN` 且 `pendingGateId` 为本 Gate（= 批准后尚未推进）才执行。
     */
    private fun commitApproval(gate: WorkflowHumanGate, target: ApprovalTarget) {
        val step = gate.stepId?.let { workflowRepository.getStep(it) } ?: return
        // 1) 载体：已提交 ⇒ 复用正式记录；未提交 ⇒ 经既有 I9/I10 流程提交（保留 stale/并发/事务内重检）
        val carrierId = when (target) {
            is ApprovalTarget.Committed -> target.carrierId
            is ApprovalTarget.Ready ->
                commits.commit(target.artifact.projectId, target.artifact.artifactId, CommitApproval(gate.gateId)).resultingDraftId
        }

        // 2) 载体状态补齐（幂等；只补 CONFIRMED，不改内容 / format）
        draftRepository.getById(carrierId)
            ?.takeIf { it.status != DraftStatus.CONFIRMED }
            ?.let { carrier ->
                draftRepository.save(carrier.copy(status = DraftStatus.CONFIRMED, updatedAt = Clock.System.now()))
            }

        // 3) Workflow 推进：批准+提交事实已确立 ⇒ WAITING_HUMAN → RUNNING（下一 runForward 完成 CONFIRMATION→KU）
        val wf = workflowRepository.getWorkflow(step.workflowId) ?: return
        if (wf.status == WorkflowStatus.WAITING_HUMAN && wf.pendingGateId == gate.gateId) {
            updateWorkflow(wf.copy(status = WorkflowStatus.RUNNING, updatedAt = Clock.System.now()))
        }
    }

    /**
     * 批准后的**幂等收敛**（P1-02f）：把"Gate 已批准"推进到"提交事实已确立 + 载体 CONFIRMED + Workflow RUNNING"。
     *
     * 可重入、可重试，覆盖两个中断窗口（P1-02e 定位）：
     *  - **A** `resolveGatePending` 与 `commit` 之间中断（Gate 已批准、尚未提交）；
     *  - **B** `commit` 成功与「载体 CONFIRMED / Workflow RUNNING」之间中断。
     *
     * 判定**只依据正式持久化事实**，不依赖内存状态推断是否已提交：
     *  - 「是否已提交」= `CanonicalReadUseCases.committedDraft`（`CommitHistory` → 载体）的正文与该工作产物正文一致；
     *  - 已提交 ⇒ **复用该正式记录**定位载体（`resultingDraftId`），**不重复提交**；
     *  - 未提交 ⇒ 经既有 I9 `ChangeUseCases.prepare` + I10 `CommitUseCases.commit`（保留 stale/并发/事务内重检）。
     *
     * 幂等边界：仅当 Workflow 处于 `WAITING_HUMAN` 且 `pendingGateId` 为本 Gate 时执行（即"批准后尚未推进"）；
     * 其余状态（RUNNING/COMPLETED/…）为**已收敛**，直接 no-op —— 因此重复调用不会重复提交、不会改写已完成状态，
     * 也不会把后续作者的提交误当作本次批准的载体。
     *
     * 引用失效（内存工作区不含该工作产物）→ **明确错误**：不回退 `latestByChapter`、不重跑 Writer/Critique/Revision。
     */

    /**
     * P12.2 Test E · Multi-Chapter Continuation（durable wire）。
     * 前置：source Workflow 必须 COMPLETED，其 active Chapter 存在已提交 CONFIRMED Canonical carrier。
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
        // L0=A：续篇来源必须来自已提交 Canonical（不是 latestByChapter）；正文只取 Canonical carrier。
        val srcDraft = guard { canonicalRead.committedDraft(srcChapter) }
            ?: throw ApplicationException(
                ApplicationError.DraftConfirmationRequired("续篇需要 source Chapter 的已提交 Canonical Final Draft"),
            )
        if (srcDraft.status != DraftStatus.CONFIRMED) {
            throw ApplicationException(
                ApplicationError.DraftConfirmationRequired(
                    "续篇需要 source Chapter 的 CONFIRMED Canonical carrier（当前 status=${srcDraft.status}）",
                ),
            )
        }

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
        val round = step.roundNo ?: 0
        when (step.phase) {
            WorkflowStepPhase.PLANNING -> createStepIfMissing(wfId, ch, WorkflowStepPhase.WRITING, round)
            WorkflowStepPhase.WRITING -> createStepIfMissing(wfId, ch, WorkflowStepPhase.CRITIQUE, round)
            WorkflowStepPhase.CRITIQUE -> {
                val d = decodeCritique(step.resultReference) ?: return
                when (d.decision) {
                    CritiqueDecision.PASS -> createStepIfMissing(wfId, ch, WorkflowStepPhase.FINALIZE, round)
                    CritiqueDecision.REVISION -> {
                        // P1-02j.2：预算按**创作轮次**判定；key 用**跨轮累计序号**保证唯一。
                        if (countRevisions(wfId, ch, round) >= MAX_REVISIONS) {
                            termFailed(wfId, "第 $round 轮达到最大修订次数 $MAX_REVISIONS，修订后仍不通过，终止")
                        } else {
                            createStepIfMissingKey(
                                wfId, ch, WorkflowStepPhase.REVISION,
                                revisionKey(wfId, ch, totalRevisionCount(wfId, ch) + 1), round,
                            )
                        }
                    }
                }
            }
            WorkflowStepPhase.REVISION ->
                createStepIfMissingKey(wfId, ch, WorkflowStepPhase.CRITIQUE, critiqueKey(wfId, ch, revisionRound(step)), round)
            WorkflowStepPhase.FINALIZE -> createStepIfMissing(wfId, ch, WorkflowStepPhase.CONFIRMATION, round)
            WorkflowStepPhase.CONFIRMATION -> createStepIfMissing(wfId, ch, WorkflowStepPhase.KNOWLEDGE_UPDATE, round)
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

    /**
     * **指定创作轮次**内该章的 REVISION Step 数（P1-02j.2 轮次级修订预算）。
     * `roundNo == null`（历史记录）按 `0` 解释。
     */
    private fun countRevisions(wfId: WorkflowId, chapterId: ChapterId, roundNo: Int): Int =
        workflowRepository.listSteps(wfId).count {
            it.phase == WorkflowStepPhase.REVISION && it.chapterId == chapterId && (it.roundNo ?: 0) == roundNo
        }

    /**
     * 该章**全部创作轮次**的 REVISION Step 数（跨轮累计）。
     * 仅用于 **revision key 的唯一分配**与 `nextSuffix` 的命名空间预留；**不**用于预算判定。
     */
    private fun totalRevisionCount(wfId: WorkflowId, chapterId: ChapterId): Int =
        workflowRepository.listSteps(wfId).count { it.phase == WorkflowStepPhase.REVISION && it.chapterId == chapterId }

    /**
     * phase 的 `logical_step_key`：**初始轮**（keyRound ≤ 0）无后缀（`wf:ch:PHASE`，兼容既有数据）；
     * **后续创作/决议轮**（keyRound > 0）带 **`:W<n>`** 后缀。
     *
     * P1-02j.2-G：固定阶段的轮次后缀使用**独立命名空间 `:W<n>`**，与 REVISION 序号的
     * `:R<n>`（`revisionKey`/`critiqueKey`，跨轮累计）**严格分离** —— 否则前一轮的固定后缀
     * （如 `CRITIQUE:R7`）会与后续轮次的 revision 后继 critique key（`CRITIQUE:R7`）相撞，
     * 使 `createStepIfMissingKey` 静默 no-op、新轮缺 Step 而停滞。
     */
    private fun phaseKey(wfId: WorkflowId, chapterId: ChapterId, phase: WorkflowStepPhase, keyRound: Int): String =
        if (keyRound <= 0) "${wfId.value}:${chapterId.value}:${phase.name}"
        else "${wfId.value}:${chapterId.value}:${phase.name}:W$keyRound"

    private fun createStepIfMissing(wfId: WorkflowId, chapterId: ChapterId, phase: WorkflowStepPhase, roundNo: Int) =
        createStepIfMissingKey(wfId, chapterId, phase, phaseKey(wfId, chapterId, phase, roundNo), roundNo)

    private fun createStepIfMissingKey(
        wfId: WorkflowId,
        chapterId: ChapterId,
        phase: WorkflowStepPhase,
        key: String,
        roundNo: Int,
    ) {
        if (workflowRepository.getStepByKey(wfId, key) == null) {
            workflowRepository.createStep(
                WorkflowStep(
                    stepId = WorkflowStepId(UUID.randomUUID().toString()), workflowId = wfId,
                    chapterId = chapterId, phase = phase, logicalStepKey = key,
                    status = WorkflowStepStatus.PENDING, roundNo = roundNo,
                    createdAt = Clock.System.now(),
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

    /* ---------- AI 工作产物引用（P1-02c+d） ---------- */

    /**
     * 类型化工作引用：`WORKING:<workingDraftId>`。
     * 与 DraftId **严格区分**（`WorkflowStep.resultReference` 不再假设"一定是 DraftId"）；
     * 恢复时按类型解析（见 [WorkflowService.validateResultReference]）。
     */
    private fun workingRef(workingDraftId: WorkingDraftId): String = WORKING_REF_PREFIX + workingDraftId.value

    private fun isWorkingRef(ref: String): Boolean = ref.startsWith(WORKING_REF_PREFIX)

    /** 解析工作引用 → 当前工作区对象；不存在（含进程重启导致的内存工作区丢失）→ null。 */
    private fun resolveWorking(ref: String): WorkingDraft? {
        if (!isWorkingRef(ref)) return null
        val id = WorkingDraftId(ref.removePrefix(WORKING_REF_PREFIX))
        return try {
            workingDrafts.get(id)
        } catch (e: ApplicationException) {
            null
        }
    }

    /**
     * 该章节**当前** AI 工作产物：按最近一个带工作引用的 WRITING / REVISION Step 解析。
     * 缺失引用或引用失效 → 明确错误（**不**回退到 `latestByChapter` 的 Draft、不伪造引用）。
     */
    private fun requireChapterWorking(wf: Workflow, step: WorkflowStep): WorkingDraft {
        val ref = chapterWorkingRef(wf, step)
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("chapter ${step.chapterId.value} 当前无 AI 工作产物（缺 WRITING/REVISION 结果引用）"),
            )
        return resolveWorking(ref)
            ?: throw ApplicationException(
                ApplicationError.RestoreFailure("AI 工作产物引用失效（内存工作区不含 $ref）；不得回退到章节最新 Draft"),
            )
    }

    /** 该章节当前工作产物的**无错误**解析：无引用或引用失效 → null（供恢复判定区分"失效轮次"）。 */
    private fun resolveChapterWorkingOrNull(wf: Workflow, step: WorkflowStep): WorkingDraft? =
        chapterWorkingRef(wf, step)?.let { resolveWorking(it) }

    /** 该章节最近一个带类型化工作引用的 WRITING / REVISION Step 的 resultReference；无 → null。 */
    private fun chapterWorkingRef(wf: Workflow, step: WorkflowStep): String? =
        workflowRepository.listSteps(wf.workflowId)
            .lastOrNull {
                it.chapterId == step.chapterId &&
                    it.phase in WORKING_PHASES &&
                    it.resultReference?.let { r -> isWorkingRef(r) } == true
            }
            ?.resultReference

    /** P20-P5-fix：把 Plan + 本任务 DecisionPolicy 编码为新的 durable plan seam（复用 PlanningSnapshot codec）。 */
    private fun encodePlanRef(plan: ChapterPlan, policies: List<DecisionPolicy>): String =
        PLAN_REF_V2 + PlanningSnapshot.encode(plan, policies).toString()

    /**
     * P20-P5-fix（FD-4）：恢复 PLANNING 步骤持久化的 Plan + DecisionPolicy（**只解码，不重算**）。
     *  - 新 seam `PLANJSON2:<PlanningSnapshot>`：plan + 可选 decisionPolicies 一并恢复（复用 PlanningSnapshot /
     *    DecisionPolicySnapshot，不复制新 codec）；
     *  - 旧 seam `PLANJSON:<ChapterPlan json>`（P12.2 起已落库的数据）：继续可读，policies = null
     *    （该 Checkpoint 早于 P5，无政策快照）→ 调用方按空政策处理，**不得**偷偷重新 Decision；
     *  - 两者都不是 → 既有 [ApplicationError.InvalidOperation]（缺 durable plan seam，语义不变）。
     */
    private fun requirePlanContextFor(wf: Workflow, step: WorkflowStep, chapterId: ChapterId): RestoredPlanContext {
        val planStep = workflowRepository.listSteps(wf.workflowId).firstOrNull { it.phase == WorkflowStepPhase.PLANNING && it.chapterId == chapterId }
        val ref = planStep?.resultReference ?: throw ApplicationException(ApplicationError.InvalidOperation("缺少已完成 PLANNING 步骤结果"))
        if (ref.startsWith(PLAN_REF_V2)) {
            val snapshot = try { Json.parseToJsonElement(ref.removePrefix(PLAN_REF_V2)).jsonObject } catch (e: Exception) { null }
                ?: throw ApplicationException(ApplicationError.RestoreFailure("无法解码 plan payload"))
            val plan = PlanningSnapshot.decode(snapshot)
                ?: throw ApplicationException(ApplicationError.RestoreFailure("无法解码 plan"))
            return RestoredPlanContext(plan, PlanningSnapshot.decodePolicies(snapshot))
        }
        if (ref.startsWith(PLAN_REF_LEGACY)) {
            val legacyPlan = decodeJson(ref.removePrefix(PLAN_REF_LEGACY))
                ?: throw ApplicationException(ApplicationError.RestoreFailure("无法解码 plan"))
            return RestoredPlanContext(legacyPlan, null)
        }
        throw ApplicationException(ApplicationError.InvalidOperation("PLANNING 缺 durable plan seam"))
    }

    /** PLANNING 步骤恢复出的 Plan 与其 DecisionPolicy 快照（policies = null 表示旧 seam 无政策）。 */
    private data class RestoredPlanContext(val plan: ChapterPlan, val policies: List<DecisionPolicy>?)

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

    /**
     * P12.4-M08 重试策略决策。读取当前 step 最近一次 attempt 的错误类别与已用 attempt 数：
     *  - NonRetryable：不可重试 → 立即将 step 置 FAILED 并将 workflow 置 FAILED，返回 false（终止）。
     *  - Retryable 但已达 step 重试上限 [MAX_STEP_ATTEMPTS]：继续重试无意义 → 同样 FAILED，返回 false。
     *  - Retryable 且未达上限：保留 step 为可重试（attempt 已按其类别标记 FAILED），返回 true（允许下一次循环重试）。
     */
    private fun shouldRetryStep(wf: Workflow, step: WorkflowStep): Boolean {
        val attempts = workflowRepository.listAttempts(step.stepId)
        val lastCategory = attempts.lastOrNull()?.errorCategory ?: AttemptErrorCategory.NON_RETRYABLE
        val terminal = lastCategory == AttemptErrorCategory.NON_RETRYABLE || attempts.size >= maxStepAttempts
        if (terminal) {
            updateStep(step.copy(status = WorkflowStepStatus.FAILED, completedAt = Clock.System.now()))
            termFailed(wf.workflowId, "step ${step.phase} 属 ${lastCategory} 且尝试 ${attempts.size} 次，终止")
            return false
        }
        return true
    }

    /* ---------- critique 编解码 ---------- */

    private enum class CritiqueDecision { PASS, REVISION }

    private fun critiqueRef(cTaskId: TaskId, subjectRef: String, passed: Boolean): String {
        val decision = if (passed) CritiqueDecision.PASS else CritiqueDecision.REVISION
        return "CRITIQUE:${cTaskId.value}:$subjectRef:$decision"
    }

    /**
     * 解析 `CRITIQUE:<taskId>:<subjectRef>:<PASS|REVISION>`（decision 为机器可判定后缀）。
     * P1-02c+d：`subjectRef` 是**类型化工作引用**（`WORKING:<id>`）——按**已知前缀**定位，避免
     * 前缀内的冒号被误当作分隔符；未找到已知前缀时回退到既有 DraftId 切分（历史兼容）。
     */
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
        val markerIdx = mid.indexOf(WORKING_REF_PREFIX)
        if (markerIdx > 0) {
            return DecodedCritique(
                taskId = mid.substring(0, markerIdx).trimEnd(':'),
                subjectRef = mid.substring(markerIdx),
                decision = decision,
            )
        }
        val j = mid.lastIndexOf(':')
        if (j == -1) return null
        return DecodedCritique(
            taskId = mid.substring(0, j),
            subjectRef = mid.substring(j + 1),
            decision = decision,
        )
    }

    /** 从 CRITIQUE Task 的 Checkpoint 恢复完整 [ValidationResult]（resultReference-first，不重调 LLM）。 */
    private fun recoverCritiqueResult(critStep: WorkflowStep): ValidationResult? {
        val decoded = decodeCritique(critStep.resultReference) ?: return null
        val cp = try { taskManager.restoreCheckpoint(TaskId(decoded.taskId)) } catch (e: ApplicationException) { return null }
        return critique.critiqueFrom(cp)
    }

    private data class DecodedCritique(val taskId: String, val subjectRef: String, val decision: CritiqueDecision)

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

    /** 与 [com.qianyan.application.usecase.commit.CommitUseCases] 同形的类型化拒绝（错误码承载在消息中）。 */
    private fun invalid(code: String, detail: String): ApplicationException =
        ApplicationException(ApplicationError.InvalidOperation("[$code] $detail"))

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

    /**
     * 建/复用待决 Human Gate（幂等，按 `gateKey`）。
     *
     * P1-02c+d：AI 工作产物 Gate 用**类型化工作引用**（`WORKING:<id>`）作为 key 的一部分，
     * `draftId` 保持 `null` —— Gate 与待审对象的关联由 `stepId` + `step.resultReference` 承载，
     * **不把 WorkingDraftId 冒充 DraftId**（`draftId` 语义仍专属于绑定 Draft 的 Gate，如 P1-01 独立 Gate）。
     */
    private fun createPendingGate(wf: Workflow, step: WorkflowStep, workingRef: String): WorkflowHumanGate {
        val key = gateKey(wf, step, workingRef)
        val existing = workflowRepository.getGateByKey(key)
        if (existing != null) return existing
        val now = Clock.System.now()
        val gate = WorkflowHumanGate(
            gateId = com.qianyan.model.workflow.WorkflowHumanGateId(UUID.randomUUID().toString()),
            workflowId = wf.workflowId, stepId = step.stepId, draftId = null,
            gateKey = key,
            status = HumanGateStatus.PENDING, decision = HumanDecision.PENDING, createdAt = now,
        )
        workflowRepository.createGate(gate)
        return gate
    }

    private fun gateKey(wf: Workflow, step: WorkflowStep, workingRef: String): String =
        "${wf.workflowId.value}:${step.logicalStepKey}:$workingRef"

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
        /** 旧 durable plan seam（P12.2）：`PLANJSON:<裸 ChapterPlan json>`；仅保留读取兼容，不再写入。 */
        const val PLAN_REF_LEGACY = "PLANJSON:"

        /** 新 durable plan seam（P20-P5-fix）：`PLANJSON2:<PlanningSnapshot json>`（plan + 可选 decisionPolicies）。 */
        const val PLAN_REF_V2 = "PLANJSON2:"

        /** P1-02c+d：AI 工作产物引用的类型前缀（单一来源见 [com.qianyan.application.usecase.writing.WritingSnapshot]）。 */
        const val WORKING_REF_PREFIX = com.qianyan.application.usecase.writing.WritingSnapshot.WORKING_REF_PREFIX

        /** 与 RevisionGate.MAX_REVISIONS 对齐：修订上限 3。 */
        const val MAX_REVISIONS = 3

        /** 产生 / 更新 AI 工作产物的 phase（用于按章节解析"当前工作产物"）。 */
        val WORKING_PHASES = setOf(WorkflowStepPhase.WRITING, WorkflowStepPhase.REVISION)

        /** 需要由本 Orchestrator 补建后继的已完成 step phase（KNOWLEDGE_UPDATE 为叶，排除）。 */
        val SUCCEEDED_PHASES = setOf(
            WorkflowStepPhase.PLANNING, WorkflowStepPhase.WRITING, WorkflowStepPhase.CRITIQUE,
            WorkflowStepPhase.REVISION, WorkflowStepPhase.FINALIZE, WorkflowStepPhase.CONFIRMATION,
        )

        /**
         * `logical_step_key` 末尾的轮次/修订后缀（用于分配器避让命名空间）：
         *  - `:R<n>` = REVISION 序号（跨轮累计；`revisionKey`/`critiqueKey`）；
         *  - `:W<n>` = 后续创作/决议轮的**固定阶段**后缀（`phaseKey`，P1-02j.2-G 起）。
         * 两者**命名空间分离**；分配器取二者的最大值，保证新 `:W<n>` 不与任何既有后缀冲突。
         */
        val ROUND_SUFFIX = Regex(":[RW](\\d+)$")
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