package com.qianyan.application.usecase.workflow

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
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
    errorMapper: ErrorMapper,
) : ChapterWorkflowGateway, UseCase(errorMapper) {

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

    /** 推进当前章节 workflow（runForward 到 WAITING_HUMAN / 完成 / 未接入处），返回进度投影。 */
    override fun advance(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        orchestrator.runForward(wf.workflowId)
        return getChapterProgress(chapterId)
    }

    /**
     * 恢复并推进章节 workflow。建立在既有 durable recovery 之上（Orchestrator.runForward 按
     * resultReference-first 幂等恢复），不维护任何 Facade 内存 current state；不依赖 ChapterWritingSession。
     */
    override fun resume(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        orchestrator.runForward(wf.workflowId)
        return getChapterProgress(chapterId)
    }

    /** 只读进度投影（不推进、不改变任何 durable 状态）。 */
    override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = findWorkflowByChapter(chapterId) ?: return ChapterWorkflowProgress.blank(chapterId)
        return project(wf)
    }

    /** HITL：批准当前待确认闸门（幂等，重复 approve 不产生副作用）。随后 [advance] 完成 KU → COMPLETED。 */
    override fun approve(chapterId: ChapterId): ChapterWorkflowProgress {
        val wf = requireWorkflowByChapter(chapterId)
        val gateId = wf.pendingGateId
            ?: throw ApplicationException(ApplicationError.InvalidOperation("chapter $chapterId 无待确认闸门"))
        guard { orchestrator.approveGate(gateId) }
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

        return ChapterWorkflowProgress(
            chapterId = chapterId,
            novelId = wf.novelId,
            variantId = wf.variantId,
            phase = derivePhase(wf, steps),
            waitingForUser = waitingForUser,
            revisionCount = revisionCount,
            draftId = draftId,
        )
    }

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
    fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress
}