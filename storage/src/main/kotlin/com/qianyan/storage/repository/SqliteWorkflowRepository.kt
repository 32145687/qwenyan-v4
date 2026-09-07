package com.qianyan.storage.repository

import com.qianyan.model.DraftId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowAttemptStatus
import com.qianyan.model.workflow.WorkflowContinuation
import com.qianyan.model.workflow.WorkflowContinuationId
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepAttempt
import com.qianyan.model.workflow.WorkflowStepAttemptId
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.storage.db.QianyanDb
import kotlinx.datetime.Instant

/** [WorkflowRepository] 的 SQLDelight + SQLite JDBC 实现（P12.2）。 */
class SqliteWorkflowRepository(
    private val db: QianyanDb,
) : WorkflowRepository {

    override fun createWorkflow(w: Workflow) = db.workflowQueries.insertWorkflow(
        workflow_id = w.workflowId.value,
        novel_id = w.novelId.value,
        variant_id = w.variantId?.value,
        kind = w.kind.name,
        definition_version = w.definitionVersion,
        status = w.status.name,
        active_chapter_id = w.activeChapterId?.value,
        current_step_id = w.currentStepId?.value,
        pending_gate_id = w.pendingGateId?.value,
        created_at = w.createdAt.toEpochMilliseconds(),
        updated_at = w.updatedAt.toEpochMilliseconds(),
    )

    override fun getWorkflow(id: WorkflowId): Workflow? =
        db.workflowQueries.getWorkflowById(id.value).executeAsOneOrNull()?.let(::dbWorkflow)

    override fun getWorkflowByActiveChapter(activeChapterId: com.qianyan.model.ChapterId): Workflow? =
        db.workflowQueries.singleWorkflowByActiveChapter(activeChapterId.value).executeAsOneOrNull()?.let(::dbWorkflow)

    override fun updateWorkflow(w: Workflow) = db.workflowQueries.updateWorkflow(
        status = w.status.name,
        active_chapter_id = w.activeChapterId?.value,
        current_step_id = w.currentStepId?.value,
        pending_gate_id = w.pendingGateId?.value,
        updated_at = w.updatedAt.toEpochMilliseconds(),
        workflow_id = w.workflowId.value,
    )

    override fun createStep(s: WorkflowStep) = db.workflowQueries.insertStep(
        step_id = s.stepId.value, workflow_id = s.workflowId.value, chapter_id = s.chapterId.value,
        phase = s.phase.name, logical_step_key = s.logicalStepKey, status = s.status.name,
        current_attempt_no = s.currentAttemptNo?.toLong(), current_task_id = s.currentTaskId,
        result_reference = s.resultReference, created_at = s.createdAt.toEpochMilliseconds(),
        completed_at = s.completedAt?.toEpochMilliseconds(),
    )

    override fun getStep(id: WorkflowStepId): WorkflowStep? =
        db.workflowQueries.getStepById(id.value).executeAsOneOrNull()?.let(::dbStep)

    override fun getStepByKey(workflowId: WorkflowId, key: String): WorkflowStep? =
        db.workflowQueries.getStepByKey(workflowId.value, key).executeAsOneOrNull()?.let(::dbStep)

    override fun listSteps(workflowId: WorkflowId): List<WorkflowStep> =
        db.workflowQueries.listStepsByWorkflow(workflowId.value).executeAsList().map(::dbStep)

    override fun updateStep(s: WorkflowStep) = db.workflowQueries.updateStep(
        status = s.status.name, current_attempt_no = s.currentAttemptNo?.toLong(),
        current_task_id = s.currentTaskId, result_reference = s.resultReference,
        completed_at = s.completedAt?.toEpochMilliseconds(), step_id = s.stepId.value,
    )

    override fun createAttempt(a: WorkflowStepAttempt) = db.workflowQueries.insertAttempt(
        attempt_id = a.attemptId.value, step_id = a.stepId.value, attempt_no = a.attemptNo.toLong(),
        task_id = a.taskId, status = a.status.name,
        error_category = a.errorCategory?.name, error_message = a.errorMessage,
        started_at = a.startedAt.toEpochMilliseconds(), completed_at = a.completedAt?.toEpochMilliseconds(),
    )

    override fun getAttempt(stepId: WorkflowStepId, no: Int): WorkflowStepAttempt? =
        db.workflowQueries.getAttempt(stepId.value, no.toLong()).executeAsOneOrNull()?.let(::dbAttempt)

    override fun listAttempts(stepId: WorkflowStepId): List<WorkflowStepAttempt> =
        db.workflowQueries.listAttemptsByStep(stepId.value).executeAsList().map(::dbAttempt)

    override fun updateAttempt(a: WorkflowStepAttempt) = db.workflowQueries.updateAttempt(
        task_id = a.taskId, status = a.status.name, error_category = a.errorCategory?.name,
        error_message = a.errorMessage, completed_at = a.completedAt?.toEpochMilliseconds(), attempt_id = a.attemptId.value,
    )

    override fun createGate(g: WorkflowHumanGate) = db.workflowQueries.insertGate(
        gate_id = g.gateId.value, workflow_id = g.workflowId.value, step_id = g.stepId?.value,
        draft_id = g.draftId?.value, gate_key = g.gateKey, status = g.status.name,
        decision = g.decision.name, created_at = g.createdAt.toEpochMilliseconds(),
        resolved_at = g.resolvedAt?.toEpochMilliseconds(), resolved_by = g.resolvedBy,
    )

    override fun getGate(id: WorkflowHumanGateId): WorkflowHumanGate? =
        db.workflowQueries.getGateById(id.value).executeAsOneOrNull()?.let(::dbGate)

    override fun getGateByKey(key: String): WorkflowHumanGate? =
        db.workflowQueries.getGateByKey(key).executeAsOneOrNull()?.let(::dbGate)

    override fun updateGate(g: WorkflowHumanGate) = db.workflowQueries.updateGate(
        status = g.status.name, decision = g.decision.name,
        resolved_at = g.resolvedAt?.toEpochMilliseconds(), resolved_by = g.resolvedBy, gate_id = g.gateId.value,
    )

    override fun createContinuation(c: WorkflowContinuation) = db.workflowQueries.insertContinuation(
        continuation_id = c.continuationId.value, workflow_id = c.workflowId.value,
        source_draft_id = c.sourceDraftId.value, source_chapter_id = c.sourceChapterId?.value,
        target_chapter_id = c.targetChapterId.value, created_at = c.createdAt.toEpochMilliseconds(),
    )

    override fun getContinuation(id: WorkflowContinuationId): WorkflowContinuation? =
        db.workflowQueries.getContinuationById(id.value).executeAsOneOrNull()?.let(::dbContinuation)

    override fun getContinuation(sourceDraftId: DraftId, targetChapterId: com.qianyan.model.ChapterId): WorkflowContinuation? =
        db.workflowQueries.getContinuation(sourceDraftId.value, targetChapterId.value).executeAsOneOrNull()?.let(::dbContinuation)

    override fun getContinuationBySourceDraft(sourceDraftId: DraftId): WorkflowContinuation? =
        db.workflowQueries.singleContinuationBySourceDraft(sourceDraftId.value).executeAsOneOrNull()?.let(::dbContinuation)

    override fun getContinuationByTargetChapter(targetChapterId: com.qianyan.model.ChapterId): WorkflowContinuation? =
        db.workflowQueries.singleContinuationByTargetChapter(targetChapterId.value).executeAsOneOrNull()?.let(::dbContinuation)

    override fun inTransaction(block: () -> Unit) {
        db.transaction { block() }
    }

    /* ---------- row → domain ---------- */

    private fun dbWorkflow(r: com.qianyan.storage.db.Workflow) = Workflow(
        workflowId = WorkflowId(r.workflow_id),
        novelId = com.qianyan.model.NovelId(r.novel_id),
        variantId = r.variant_id?.let { com.qianyan.model.VariantId(it) },
        kind = WorkflowKind.valueOf(r.kind),
        definitionVersion = r.definition_version,
        status = WorkflowStatus.valueOf(r.status),
        activeChapterId = r.active_chapter_id?.let { com.qianyan.model.ChapterId(it) },
        currentStepId = r.current_step_id?.let { WorkflowStepId(it) },
        pendingGateId = r.pending_gate_id?.let { WorkflowHumanGateId(it) },
        createdAt = Instant.fromEpochMilliseconds(r.created_at),
        updatedAt = Instant.fromEpochMilliseconds(r.updated_at),
    )

    private fun dbStep(r: com.qianyan.storage.db.WorkflowStep) = WorkflowStep(
        stepId = WorkflowStepId(r.step_id),
        workflowId = WorkflowId(r.workflow_id),
        chapterId = com.qianyan.model.ChapterId(r.chapter_id),
        phase = WorkflowStepPhase.valueOf(r.phase),
        logicalStepKey = r.logical_step_key,
        status = WorkflowStepStatus.valueOf(r.status),
        currentAttemptNo = r.current_attempt_no?.toInt(),
        currentTaskId = r.current_task_id,
        resultReference = r.result_reference,
        createdAt = Instant.fromEpochMilliseconds(r.created_at),
        completedAt = r.completed_at?.let { Instant.fromEpochMilliseconds(it) },
    )

    private fun dbAttempt(r: com.qianyan.storage.db.WorkflowStepAttempt) = WorkflowStepAttempt(
        attemptId = WorkflowStepAttemptId(r.attempt_id),
        stepId = WorkflowStepId(r.step_id),
        attemptNo = r.attempt_no.toInt(),
        taskId = r.task_id,
        status = WorkflowAttemptStatus.valueOf(r.status),
        errorCategory = r.error_category?.let { com.qianyan.model.workflow.AttemptErrorCategory.valueOf(it) },
        errorMessage = r.error_message,
        startedAt = Instant.fromEpochMilliseconds(r.started_at),
        completedAt = r.completed_at?.let { Instant.fromEpochMilliseconds(it) },
    )

    private fun dbGate(r: com.qianyan.storage.db.WorkflowHumanGate) = WorkflowHumanGate(
        gateId = WorkflowHumanGateId(r.gate_id),
        workflowId = WorkflowId(r.workflow_id),
        stepId = r.step_id?.let { WorkflowStepId(it) },
        draftId = r.draft_id?.let { DraftId(it) },
        gateKey = r.gate_key,
        status = HumanGateStatus.valueOf(r.status),
        decision = HumanDecision.valueOf(r.decision),
        createdAt = Instant.fromEpochMilliseconds(r.created_at),
        resolvedAt = r.resolved_at?.let { Instant.fromEpochMilliseconds(it) },
        resolvedBy = r.resolved_by,
    )

    private fun dbContinuation(r: com.qianyan.storage.db.WorkflowContinuation) = WorkflowContinuation(
        continuationId = WorkflowContinuationId(r.continuation_id),
        workflowId = WorkflowId(r.workflow_id),
        sourceDraftId = DraftId(r.source_draft_id),
        sourceChapterId = r.source_chapter_id?.let { com.qianyan.model.ChapterId(it) },
        targetChapterId = com.qianyan.model.ChapterId(r.target_chapter_id),
        createdAt = Instant.fromEpochMilliseconds(r.created_at),
    )
}