package com.qianyan.application.usecase.workflow

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.WorkflowRepository
import java.util.UUID
import kotlinx.datetime.Clock

/**
 * P12.2 M1-M2 · ChapterWorkflowFacade —— Android / 未来 Desktop 与 Durable Workflow 之间的**用户层 Application API**。
 *
 * **不是新的 Workflow Engine / 不是第二套状态机 / 不是 retry-revision-recovery 逻辑的复刻。**
 * 它只做两件事：
 *   1) 用户意图 → 现有 Application API 的参数转换（chapterId/novelId/variantId → workflowId/gateId）；
 *   2) 持久化 durable Workflow 状态 → 用户层进度投影（[ChapterWorkflowProgress]）。
 *
 * 所有实际推进/审批/续篇/恢复都委托给底层 [WorkflowOrchestrator] / [WorkflowService]（其内部再经
 * 既有 UseCase→Task→AgentRuntime→Repository 链路）。Facade 不复制 [Workflow]/[WorkflowStep]/[WorkflowHumanGate]
 * 到内存、不在方法内维护 currentStep/currentGate，一切以 DB durable state 为准。
 *
 * 对外一致使用 [ApplicationException]（[ApplicationError]），不泄漏 Workflow/Task/Provider/SQLDelight 细节。
 *
 * 本文件还承载两个用户层最小 DTO：[ChapterPhase]（用户可读阶段）与 [ChapterWorkflowProgress]（进度投影），
 * 以及一个用户层访问 seam [ChapterWorkflowGateway]（仅用户层方法签名，供 Android/Desktop 注入与测试替身）。
 */
class ChapterWorkflowFacade(
    private val workflowRepository: WorkflowRepository,
    private val draftRepository: DraftRepository,
    private val orchestrator: WorkflowOrchestrator,
    private val workflowService: WorkflowService,
    private val postCommitFinalizerProvider: () -> PostCommitFinalizer,
    errorMapper: ErrorMapper,
) : ChapterWorkflowGateway, UseCase(errorMapper) {

    /** 延迟解析（容器初始化期间 facade 可能先于 finalizer 被引用）。 */
    private val postCommitFinalizer: PostCommitFinalizer get() = postCommitFinalizerProvider()

    /* ---------- 用户意图 → 现有 Application API（薄转换） ---------- */

    /**
     * 启动一个章节 Workflow（幂等：该 chapter 已有 Workflow 则直接返回进度）。
     * 仅创建 durability 骨架（Workflow 行）；不自动推进（后续调 [advance]/[resume]）。
     */
    override fun startChapter(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId, kind: WorkflowKind): ChapterWorkflowProgress {
        if (findWorkflowByChapter(chapterId) == null) {
            val now = Clock.System.now()
            guard {
                workflowRepository.createWorkflow(
                    Workflow(
                        workflowId = WorkflowId(UUID.randomUUID().toString()),
                        novelId = novelId, variantId = variantId, kind = kind,
                        status = WorkflowStatus.CREATED, activeChapterId = chapterId,
                        createdAt = now, updatedAt = now,
                    ),
                )
            }
        }
        return getChapterProgress(chapterId)
    }

    /**
     * 推进当前章节 workflow（runForward 到 WAITING_HUMAN / 完成 / 未接入处），返回进度投影。
     *
     * P1-02g：本入口语义为**显式作者请求**（继续创作 / 推进任务）⇒ 若当前待决 Gate 已被**拒绝**，
     * 允许在同一 Workflow 上开启新的决议轮次（新 Gate，旧 Gate 保持 REJECTED；不重新生成内容）。
     * 与 [resume] 的区别：恢复路径**不得**因刷新 / 恢复而自动创建新 Gate。
     */
    override fun advance(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        guard { orchestrator.runForward(wf.workflowId, allowReentryAfterReject = true) }
        return projectAfterRun(wf, chapterId)
    }

    /**
     * 恢复并推进章节 workflow。建立在既有 durable recovery 之上（Orchestrator.runForward 按
     * resultReference-first 幂等恢复），不维护任何 Facade 内存 current state；不依赖 ChapterWritingSession。
     */
    override fun resume(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        guard { orchestrator.runForward(wf.workflowId) }
        return projectAfterRun(wf, chapterId)
    }

    /** 只读进度投影（不推进、不改变任何 durable 状态）。 */
    override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = findWorkflowByChapter(chapterId) ?: return ChapterWorkflowProgress.blank(chapterId)
        return project(wf)
    }

    /** HITL：批准当前待确认闸门（幂等，重复 approve 不产生副作用）。审批后**同一次调用内**显式推进到收尾。 */
    override fun approve(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        val gateId = wf.pendingGateId
        if (gateId == null) {
            // O-52：批准已收尾（pendingGateId 已清空）⇒ 幂等 no-op，覆盖同一次双击的第二击 / 重复恢复。
            if (wf.status == WorkflowStatus.RUNNING || wf.status == WorkflowStatus.COMPLETED) {
                guard { orchestrator.runForward(wf.workflowId) }
                return getChapterProgress(chapterId)
            }
            throw ApplicationException(ApplicationError.InvalidOperation("chapter $chapterId 无待确认闸门"))
        }
        guard { orchestrator.approveGate(gateId) }
        // O-52：批准 = 决议 + 提交事实；显式推进完成 CONFIRMATION（清 pendingGateId）→ KNOWLEDGE_UPDATE → COMPLETED。
        guard { orchestrator.runForward(wf.workflowId) }
        return getChapterProgress(chapterId)
    }

    /**
     * T-O108（A-32）· **批准快路径**：决议 + Canonical 提交 + 投递后台收尾，**不**执行 runForward。
     *
     * 调用返回时：Gate 已 RESOLVED、CommitHistory 已落、后台任务已入队 —— UI 可立即恢复按钮行与新正文；
     * WMU 与 KNOWLEDGE_UPDATE 由 [PostCommitFinalizer] 在后台完成（失败可见、可手动重试）。
     */
    override fun approveDecision(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        val gateId = wf.pendingGateId
        if (gateId == null) {
            if (wf.status != WorkflowStatus.RUNNING && wf.status != WorkflowStatus.COMPLETED) {
                throw ApplicationException(ApplicationError.InvalidOperation("chapter $chapterId 无待确认闸门"))
            }
        } else {
            guard { orchestrator.approveDecisionOnly(gateId) }
        }
        ensurePostCommitJob(chapterId)
        return getChapterProgress(chapterId)
    }

    /** 幂等投递后台收尾（不推进、不提交）：仅 RUNNING 且仍有 pendingGateId（= 已批准未收尾）时入队。 */
    override fun ensurePostCommitJob(chapterId: ChapterId) {
        val wf = findWorkflowByChapter(chapterId) ?: return
        if (wf.status != WorkflowStatus.RUNNING) return
        val gateId = wf.pendingGateId ?: return
        postCommitFinalizer.enqueue(
            historyId = null,
            gateId = gateId.value,
            workflowId = wf.workflowId.value,
            chapterId = chapterId.value,
        )
    }

    /** 异步启动该章节的后台收尾（不阻塞调用方；调用方须在 IO 线程/协程调用本方法）。 */
    override fun runPostCommit(chapterId: ChapterId) {
        ensurePostCommitJob(chapterId)
        postCommitFinalizer.runPending(chapterId.value)
    }

    /** 手动重试：按该章节最近一次收尾负载新建后台任务并立即执行（失败会再次可见，不静默）。 */
    override fun retryPostCommit(chapterId: ChapterId): ChapterWorkflowProgress {
        postCommitFinalizer.retryChapter(chapterId.value)
        postCommitFinalizer.runPending(chapterId.value)
        return getChapterProgress(chapterId)
    }

    override fun postCommitState(chapterId: ChapterId): PostCommitStage =
        postCommitFinalizer.stateOf(chapterId.value)

    /**
     * HITL：拒绝当前待确认闸门（幂等）。复用既有 [WorkflowService.rejectGate]：
     * PENDING → RESOLVED+REJECTED；Canonical 不变；旧 Gate 永不复用。
     * 已批准 → 类型化拒绝（批准只能走 [approve] 收敛，绝不改写为拒绝）。
     * 拒绝后 Workflow 保持 `WAITING_HUMAN` 且 `pendingGateId` 指向已决议 Gate；
     * 仅显式 [advance] 才会由既有 `reopenRejectedRound` 开启**新**决议轮次。
     */
    override fun reject(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        val gateId = wf.pendingGateId
            ?: throw ApplicationException(ApplicationError.InvalidOperation("chapter $chapterId 无待确认闸门"))
        val gate = guard { workflowRepository.getGate(gateId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Gate 不存在: ${gateId.value}"))
        if (gate.status == HumanGateStatus.RESOLVED) {
            if (gate.decision != HumanDecision.REJECTED) {
                throw ApplicationException(
                    ApplicationError.InvalidOperation("Gate ${gateId.value} 已 ${gate.decision.name}，不能拒绝"),
                )
            }
            return getChapterProgress(chapterId)
        }
        guard { workflowService.rejectGate(gateId, HumanDecision.REJECTED) }
        return getChapterProgress(chapterId)
    }

    /**
     * AI 改写提案进入既有章节 Workflow 的人工门（不新开 Workflow / 不重跑 Writing）。
     * 具体 Step/Gate 追加由 [WorkflowOrchestrator.proposeRewrite] 承担。
     */
    override fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        guard { orchestrator.proposeRewrite(wf.workflowId, workingDraftId) }
        return getChapterProgress(chapterId)
    }

    /**
     * **显式作废失效轮次**（P1-02j A1）：当前 Gate 已被拒绝、但其工作产物已失效时，关闭该轮并在同一
     * Workflow 上开启新一轮（只建立新一轮骨架，**不**重跑 LLM —— 由后续显式 [advance] 驱动生成）。
     *
     * 非自动：本方法是唯一的作废入口；[resume] / [getChapterProgress] 等只读路径**绝不**触发作废。
     * 前置不满足（未拒绝 / 已批准 / 产物仍可用 / 计划不可验证）→ 类型化失败，不改动任何状态。
     */
    override fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        val gateId = wf.pendingGateId
            ?: throw ApplicationException(ApplicationError.InvalidOperation("chapter $chapterId 无待决闸门，无法作废"))
        guard { orchestrator.abandonRound(gateId) }
        return getChapterProgress(chapterId)
    }

    /** 续篇：由已 COMPLETED 的 source 章节创建/复用下一章的 Workflow，返回**下一章**的用户层进度。幂等。 */
    override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        val targetWf = guard { orchestrator.continueToNextWorkflow(wf.workflowId) }
        return project(requireWorkflow(targetWf))
    }

    /* ---------- 用户层进度投影（只读；不推进状态） ---------- */

    private fun project(wf: Workflow): ChapterWorkflowProgress {
        val chapterId = wf.activeChapterId ?: return blankFrom(wf)
        val steps = guard { workflowRepository.listSteps(wf.workflowId) }
        val revisionCount = steps.count { it.phase == WorkflowStepPhase.REVISION }
        val gate = wf.pendingGateId?.let { guard { workflowRepository.getGate(it) } }
        val waitingForUser = gate?.status == HumanGateStatus.PENDING
        val draftId = guard { draftRepository.latestByChapter(chapterId) }?.draftId
        // P0-3B D4：当前审批轮次绑定的候选工作产物引用。
        // 绑定由 Workflow 的最近一个 WRITING/REVISION Step 的 `WORKING:<id>` 承载（CONFIRMATION Step 本身不写 resultReference）。
        val pendingWorkingRef = gate?.let {
            steps.lastOrNull { step ->
                step.chapterId == chapterId &&
                    (step.phase == WorkflowStepPhase.WRITING || step.phase == WorkflowStepPhase.REVISION) &&
                    step.resultReference?.startsWith(WritingSnapshot.WORKING_REF_PREFIX) == true
            }?.resultReference
        }
        // P0-3B D6：当前 Gate 的决议（PENDING/APPROVED/REJECTED/REQUEST_REVISION；无 Gate → null）。
        val pendingGateDecision = gate?.decision
        // T-O82b-F：FAILED/CANCELLED 时暴露**最近一个 failed attempt** 的真实错误摘要（只读投影）。
        val failureMessage = if (wf.status == WorkflowStatus.FAILED || wf.status == WorkflowStatus.CANCELLED) {
            lastFailedAttemptMessage(steps)
        } else {
            null
        }

        return ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = wf.novelId,
            variantId = wf.variantId,
            phase = derivePhase(wf, steps),
            waitingForUser = waitingForUser,
            revisionCount = revisionCount,
            draftId = draftId,
            pendingWorkingRef = pendingWorkingRef,
            pendingGateDecision = pendingGateDecision,
            failureMessage = failureMessage,
        )
    }

    /**
     * T-O82b-F：推进后的投影。成功 / 等待人工门时与 [getChapterProgress] 等价；
     * FAILED/CANCELLED 时 Orchestrator 会清空 `activeChapterId`（释放章节占位），
     * 按 chapter 反查只能得到 blank —— 这里用**本次调用已知的 chapterId** 补回，保证失败原因可见。
     */
    private fun projectAfterRun(before: Workflow, chapterId: ChapterId): ChapterWorkflowProgress {
        val current = requireWorkflow(before.workflowId)
        val restored = if (current.activeChapterId == null) current.copy(activeChapterId = chapterId) else current
        return project(restored)
    }

    /**
     * 取该 Workflow **最近一个 failed attempt** 的 `error_message`（Step 倒序 → Attempt 倒序），
     * 单行化 + trim + 截断 ≤[FAILURE_MESSAGE_LIMIT]；无 attempt / 无 message → null。
     * 只读，不改状态机 / 重试策略。
     */
    private fun lastFailedAttemptMessage(steps: List<WorkflowStep>): String? =
        steps.asReversed().asSequence()
            .flatMap { step -> guard { workflowRepository.listAttempts(step.stepId) }.asReversed().asSequence() }
            .firstOrNull { it.status == WorkflowAttemptStatus.FAILED && !it.errorMessage.isNullOrBlank() }
            ?.errorMessage
            ?.let(::normalizeAttemptMessage)

    private fun normalizeAttemptMessage(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').trim().take(FAILURE_MESSAGE_LIMIT)

    /** 用户层阶段：仅把 durable Workflow/Step 状态投影为可理解阶段（不在此做任何状态迁移）。 */
    private fun derivePhase(wf: Workflow, steps: List<com.qianyan.model.workflow.WorkflowStep>): ChapterPhase = when (wf.status) {
        WorkflowStatus.COMPLETED -> ChapterPhase.COMPLETED
        WorkflowStatus.FAILED -> ChapterPhase.FAILED
        WorkflowStatus.CANCELLED -> ChapterPhase.FAILED
        WorkflowStatus.WAITING_HUMAN -> ChapterPhase.WAITING_CONFIRMATION
        WorkflowStatus.PAUSED,
        WorkflowStatus.CREATED,
        WorkflowStatus.RUNNING,
        -> {
            val current = steps.firstOrNull { it.status != WorkflowStepStatus.COMPLETED && it.status != WorkflowStepStatus.SKIPPED }
            when (current?.phase) {
                WorkflowStepPhase.PLANNING -> ChapterPhase.PLANNING
                WorkflowStepPhase.WRITING -> ChapterPhase.WRITING
                WorkflowStepPhase.CRITIQUE, WorkflowStepPhase.FINALIZE -> ChapterPhase.REVIEWING
                WorkflowStepPhase.REVISION -> ChapterPhase.REVISING
                WorkflowStepPhase.CONFIRMATION -> ChapterPhase.WAITING_CONFIRMATION
                WorkflowStepPhase.KNOWLEDGE_UPDATE -> ChapterPhase.UPDATING_STORY
                null -> ChapterPhase.NOT_STARTED
                else -> ChapterPhase.NOT_STARTED
            }
        }
    }

    private fun findWorkflowByChapter(chapterId: ChapterId): Workflow? =
        guard { workflowRepository.getWorkflowByActiveChapter(chapterId) }

    private fun requireWorkflowByChapter(chapterId: ChapterId): Workflow =
        findWorkflowByChapter(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("chapter $chapterId 尚无 Workflow，请先 startChapter"))

    private fun requireWorkflow(id: WorkflowId): Workflow =
        guard { workflowRepository.getWorkflow(id) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Workflow 不存在: ${id.value}"))

    private fun blankFrom(wf: Workflow): ChapterWorkflowProgress = ChapterWorkflowProgress(
        chapterId = wf.activeChapterId ?: ChapterId(""),
        novelId = wf.novelId,
        variantId = wf.variantId,
        phase = ChapterPhase.NOT_STARTED,
        waitingForUser = false,
        revisionCount = 0,
        draftId = null,
    )

    private companion object {
        /** T-O82b-F：失败摘要上限（attempt 原文可能很长，UI 只展示这一段）。 */
        const val FAILURE_MESSAGE_LIMIT: Int = 200
    }
}

/** 用户可读章节阶段（Facade 投影；外部为 Android/Desktop 消费，非 WorkflowStepPhase）。 */
enum class ChapterPhase {
    NOT_STARTED, PLANNING, WRITING, REVIEWING, REVISING, WAITING_CONFIRMATION, UPDATING_STORY, COMPLETED, FAILED,
}

/** 用户层章节进度（生命周期由 [ChapterWorkflowFacade] 从 durable Workflow 投影）。非 Workflow 内部对象。 */
data class ChapterWorkflowProgress(
    val chapterId: ChapterId,
    val novelId: NovelId,
    val variantId: VariantId?,
    val phase: ChapterPhase,
    val waitingForUser: Boolean,
    val revisionCount: Int,
    val draftId: DraftId?,
    /**
     * P0-3B D4 · 当前待决 Gate 绑定的**工作产物引用**（`WORKING:<workingDraftId>`；无待决 Gate / 非 AI 候选 → null）。
     * 供幂等桥接判定"待确认审批是否属于本候选"；原始 resultReference 透传，不解析语义。
     */
    val pendingWorkingRef: String? = null,
    /**
     * P0-3B D6 · 当前 Gate 的决议（无 Gate → null）。
     * 供 Agent 侧收敛判断"正式 Gate 是否已拒绝/已批准"；不改变 Gate 状态。
     */
    val pendingGateDecision: HumanDecision? = null,
    /**
     * T-O82b-F · FAILED/CANCELLED 时的**失败原因摘要**（该 Workflow 最近一个 failed attempt 的
     * `error_message`，单行化 + trim + ≤200 字）；成功 / 进行中 / 无 attempt 文本 → null。
     */
    val failureMessage: String? = null,
) {
    companion object {
        fun blank(chapterId: ChapterId): ChapterWorkflowProgress = ChapterWorkflowProgress(
            chapterId = chapterId, novelId = NovelId(""), variantId = null,
            phase = ChapterPhase.NOT_STARTED, waitingForUser = false, revisionCount = 0, draftId = null,
        )
    }
}

/**
 * 用户层 Chapter 工作流访问 seam（P12.2 M3）：Android/未来 Desktop 只依赖此接口 + 用户层
 * [ChapterWorkflowProgress]/[ChapterPhase]，不接触 Workflow/Step/Attempt/Gate/Continuation 内部。
 * 唯一实现即 [ChapterWorkflowFacade]；测试可为它提供替身。
 */
interface ChapterWorkflowGateway {
    fun startChapter(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId, kind: WorkflowKind = WorkflowKind.WRITE_NOVEL): ChapterWorkflowProgress
    fun advance(chapterId: ChapterId): ChapterWorkflowProgress
    fun resume(chapterId: ChapterId): ChapterWorkflowProgress
    fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress
    fun approve(chapterId: ChapterId): ChapterWorkflowProgress
    /**
     * T-O108（A-32）：批准快路径（决议 + 提交 + 投递后台收尾，不 runForward）。
     * 默认实现仅供既有测试替身兼容（委托整链 [approve]）；生产实现由 [ChapterWorkflowFacade] 覆盖。
     */
    fun approveDecision(chapterId: ChapterId): ChapterWorkflowProgress = approve(chapterId)
    /** 幂等投递后台收尾（补偿 / 启动恢复使用）。 */
    fun ensurePostCommitJob(chapterId: ChapterId) {}
    /** 异步启动后台收尾（ui 调用后立即返回）。 */
    fun runPostCommit(chapterId: ChapterId) { ensurePostCommitJob(chapterId) }
    /** 手动重试后台收尾。 */
    fun retryPostCommit(chapterId: ChapterId): ChapterWorkflowProgress = approveDecision(chapterId)
    /** 后台收尾阶段投影（NONE / PENDING / FAILED）。 */
    fun postCommitState(chapterId: ChapterId): PostCommitStage = PostCommitStage.NONE
    /** P0-2.1：拒绝当前待决 Gate（幂等；旧 Gate 复用禁止；Canonical 不变）。 */
    fun reject(chapterId: ChapterId): ChapterWorkflowProgress
    /** P0-2.1：把 AI 改写产生的 WorkingDraft 送入既有章节 Workflow 的人工门。 */
    fun proposeRewrite(chapterId: ChapterId, workingDraftId: WorkingDraftId): ChapterWorkflowProgress
    /** P1-02j A1：显式作废失效轮次并在同一 Workflow 上开启新一轮（非自动；只读路径绝不触发）。 */
    fun abandonRound(chapterId: ChapterId): ChapterWorkflowProgress
    fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress
}