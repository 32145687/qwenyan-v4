package com.qianyan.application.usecase.commit

import com.qianyan.application.usecase.draft.FIXED_INSTANT
import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.seedWorld
import com.qianyan.application.usecase.draft.workingDraftFixture
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.change.ChangeArtifact
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.storage.repository.SqliteCommitHistoryRepository
import app.cash.sqldelight.db.QueryResult

/*
 * I10 测试夹具（复用 I8/I9 夹具：真实内存 SQLite + ApplicationContainer）。
 *
 * 确定性说明：既有 Canonical Draft 使用固定时间戳（[FIXED_INSTANT] 2026-01-01，**早于**真实时钟），
 * 因此提交产生的新 Draft 必然成为章节最新正文（`latestByChapter` 按 created_at DESC）。
 */

internal fun commitFixture(): WorkingDraftFixture = workingDraftFixture()

internal fun commitWorld(f: WorkingDraftFixture): SeededWorld = seedWorld(f)

/** 直连同一驱动的 History 仓储（与容器内实例同表同事务；测试断言用）。 */
internal fun historyRepo(f: WorkingDraftFixture): SqliteCommitHistoryRepository =
    SqliteCommitHistoryRepository(f.handle.db)

/**
 * 既有 Human Gate 的批准凭据（I2 复用）：直接写入既有 Workflow / Step / Gate 表，
 * 只构造 **已有词汇**（CONFIRMATION step + RESOLVED + APPROVED），不新建 Gate 体系。
 *
 * D8：Gate 必须绑定 WorkflowStep；workflow.activeChapterId 与 step.chapterId 使用 [chapterId]。
 * 若调用前已存在该章节的 WorkingDraft，则同时补一条匹配的 WRITING step；
 * 若调用后再创建 Artifact，[artifactFor] 会补/更新该 WRITING step。
 */
internal fun approvedGate(
    f: WorkingDraftFixture,
    w: SeededWorld,
    gateId: String = "gate-i10-approved",
    status: HumanGateStatus = HumanGateStatus.RESOLVED,
    decision: HumanDecision = HumanDecision.APPROVED,
    chapterId: ChapterId = w.chapter2,
): CommitApproval {
    val workflowId = WorkflowId("wf-i10-$gateId")
    f.app.workflows.createWorkflow(
        Workflow(
            workflowId = workflowId,
            novelId = w.novelId,
            kind = WorkflowKind.WRITE_NOVEL,
            status = WorkflowStatus.RUNNING,
            activeChapterId = chapterId,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
        ),
    )
    val confirmStep = WorkflowStep(
        stepId = WorkflowStepId("step-$workflowId-confirmation"),
        workflowId = workflowId,
        chapterId = chapterId,
        phase = WorkflowStepPhase.CONFIRMATION,
        logicalStepKey = "$workflowId:${chapterId.value}:CONFIRMATION",
        status = WorkflowStepStatus.COMPLETED,
        createdAt = FIXED_INSTANT,
        completedAt = FIXED_INSTANT,
    )
    f.app.workflows.createStep(confirmStep)
    f.app.workflows.createGate(
        WorkflowHumanGate(
            gateId = WorkflowHumanGateId(gateId),
            workflowId = workflowId,
            stepId = confirmStep.stepId,
            gateKey = "i10:$gateId",
            status = status,
            decision = decision,
            createdAt = FIXED_INSTANT,
            resolvedAt = if (status == HumanGateStatus.RESOLVED) FIXED_INSTANT else null,
            resolvedBy = if (status == HumanGateStatus.RESOLVED) "user" else null,
        ),
    )
    latestWorkingDraftId(f, w, chapterId)?.let { bindWorkingStep(f, workflowId, chapterId, it) }
    return CommitApproval(WorkflowHumanGateId(gateId))
}

/** D8 负向夹具：只有 workflow + gate，无 stepId；任何正式 Commit 必须拒绝。 */
internal fun steplessApprovedGate(
    f: WorkingDraftFixture,
    w: SeededWorld,
    gateId: String = "gate-i10-stepless",
): CommitApproval {
    val workflowId = WorkflowId("wf-i10-stepless-$gateId")
    f.app.workflows.createWorkflow(
        Workflow(
            workflowId = workflowId,
            novelId = w.novelId,
            kind = WorkflowKind.WRITE_NOVEL,
            status = WorkflowStatus.RUNNING,
            activeChapterId = w.chapter2,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
        ),
    )
    f.app.workflows.createGate(
        WorkflowHumanGate(
            gateId = WorkflowHumanGateId(gateId),
            workflowId = workflowId,
            gateKey = "i10-stepless:$gateId",
            status = HumanGateStatus.RESOLVED,
            decision = HumanDecision.APPROVED,
            createdAt = FIXED_INSTANT,
            resolvedAt = FIXED_INSTANT,
            resolvedBy = "user",
        ),
    )
    return CommitApproval(WorkflowHumanGateId(gateId))
}

private const val WORKING_REF_PREFIX: String =
    com.qianyan.application.usecase.writing.WritingSnapshot.WORKING_REF_PREFIX

private fun latestWorkingDraftId(f: WorkingDraftFixture, w: SeededWorld, chapterId: ChapterId): WorkingDraftId? {
    val candidates = f.app.workingDrafts.list(w.projectId).filter { it.target.chapterId == chapterId }
    val latest = candidates.maxByOrNull { it.createdAt } ?: return null
    // 同毫秒创建时，list 的插入序仍可区分；取该时间戳下最后一个。
    return candidates.lastOrNull { it.createdAt == latest.createdAt }?.workingDraftId
}

/** D8：把 Gate 所在 Workflow 的 WRITING step 指到待提交的 WorkingDraft。 */
private fun bindWorkingStep(
    f: WorkingDraftFixture,
    workflowId: WorkflowId,
    chapterId: ChapterId,
    workingDraftId: WorkingDraftId,
) {
    val reference = WORKING_REF_PREFIX + workingDraftId.value
    val existing = f.app.workflows.listSteps(workflowId).firstOrNull {
        it.chapterId == chapterId &&
            it.phase == WorkflowStepPhase.WRITING &&
            it.resultReference?.startsWith(WORKING_REF_PREFIX) == true
    }
    if (existing != null) {
        if (existing.resultReference != reference) {
            f.app.workflows.updateStep(
                existing.copy(
                    resultReference = reference,
                    status = WorkflowStepStatus.COMPLETED,
                    completedAt = FIXED_INSTANT,
                ),
            )
        }
        return
    }
    f.app.workflows.createStep(
        WorkflowStep(
            stepId = WorkflowStepId("step-$workflowId-working-${chapterId.value}"),
            workflowId = workflowId,
            chapterId = chapterId,
            phase = WorkflowStepPhase.WRITING,
            logicalStepKey = "$workflowId:${chapterId.value}:WRITING",
            status = WorkflowStepStatus.COMPLETED,
            resultReference = reference,
            createdAt = FIXED_INSTANT,
            completedAt = FIXED_INSTANT,
        ),
    )
}

/** 该章节的全部 Workflow（测试内可同时存在多个批准 Gate）；用于把工作产物绑到每一个。 */
private fun workflowIdsForChapter(f: WorkingDraftFixture, chapterId: ChapterId): List<WorkflowId> =
    f.handle.driver.executeQuery(
        null,
        "SELECT workflow_id FROM Workflow WHERE active_chapter_id = ?",
        { cursor ->
            val ids = mutableListOf<WorkflowId>()
            while (cursor.next().value) {
                cursor.getString(0)?.let { ids += WorkflowId(it) }
            }
            QueryResult.Value(ids)
        },
        1,
        { bindString(0, chapterId.value) },
    ).value

/** 走完整 I8 → I9 链路产出 Change Artifact（默认基于章节当前 Canonical 正文载体）。 */
internal fun artifactFor(
    f: WorkingDraftFixture,
    w: SeededWorld,
    content: String,
    baseDraftId: DraftId? = w.canonicalDraftId,
    chapterId: ChapterId = w.chapter2,
): ChangeArtifact {
    val draft = f.app.workingDrafts.create(
        projectId = w.projectId,
        target = WorkingDraftTarget(w.novelId, chapterId),
        content = content,
        sessionId = w.sessionId,
        activityId = w.activityId,
        baseDraftId = baseDraftId,
    )
    val artifact = f.app.changes.prepare(draft.workingDraftId)
    workflowIdsForChapter(f, chapterId).forEach { workflowId ->
        bindWorkingStep(f, workflowId, chapterId, draft.workingDraftId)
    }
    return artifact
}