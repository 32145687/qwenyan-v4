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
import com.qianyan.storage.repository.SqliteCommitHistoryRepository

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
 * 既有 Human Gate 的批准凭据（I2 复用）：直接写入既有 Workflow / WorkflowHumanGate 表，
 * 只构造 **已有词汇**（RESOLVED + APPROVED），不新建 Gate 体系。
 */
internal fun approvedGate(
    f: WorkingDraftFixture,
    w: SeededWorld,
    gateId: String = "gate-i10-approved",
    status: HumanGateStatus = HumanGateStatus.RESOLVED,
    decision: HumanDecision = HumanDecision.APPROVED,
): CommitApproval {
    val workflowId = WorkflowId("wf-i10-$gateId")
    f.app.workflows.createWorkflow(
        Workflow(
            workflowId = workflowId,
            novelId = w.novelId,
            kind = WorkflowKind.WRITE_NOVEL,
            status = WorkflowStatus.RUNNING,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
        ),
    )
    f.app.workflows.createGate(
        WorkflowHumanGate(
            gateId = WorkflowHumanGateId(gateId),
            workflowId = workflowId,
            gateKey = "i10:$gateId",
            status = status,
            decision = decision,
            createdAt = FIXED_INSTANT,
            resolvedAt = if (status == HumanGateStatus.RESOLVED) FIXED_INSTANT else null,
            resolvedBy = if (status == HumanGateStatus.RESOLVED) "user" else null,
        ),
    )
    return CommitApproval(WorkflowHumanGateId(gateId))
}

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
    return f.app.changes.prepare(draft.workingDraftId)
}