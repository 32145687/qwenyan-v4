package com.qianyan.application.usecase.commit

import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workingdraft.WorkingDraftTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * O-56 · 同毫秒工作产物不得影响 Gate 绑定。
 *
 * 历史抖动：`approvedGate` 以“最新 WorkingDraft”猜测绑定，而 WorkingDraft 时间戳截断到毫秒、
 * `list()` 按 workingDraftId 排序；同毫秒存在两份产物时会绑定错行，Commit 抛 `GATE_TARGET_MISMATCH`
 * （影响 CanonicalReadTest / ReadingUseCasesCanonicalTest）。
 *
 * 修复后：调用方显式传 Artifact 的 workingDraftId，绑定与时间/排序无关。
 */
class CommitGateSameMillisecondTest {

    @Test
    fun `gate binds the artifact working draft even when another draft shares the millisecond`() {
        repeat(10) { attempt ->
            val f = commitFixture()
            try {
                val w = commitWorld(f)
                val target = WorkingDraftTarget(w.novelId, w.chapter2)
                val other = f.app.workingDrafts.create(
                    w.projectId, target, "另一份同毫秒产物", w.sessionId, w.activityId, w.canonicalDraftId,
                )
                val intended = f.app.workingDrafts.create(
                    w.projectId, target, "待提交产物", w.sessionId, w.activityId, w.canonicalDraftId,
                )
                val artifact = f.app.changes.prepare(intended.workingDraftId)
                val gate = approvedGate(
                    f, w, gateId = "gate-same-ms-$attempt", workingDraftId = artifact.workingDraftId,
                )

                f.app.commits.commit(w.projectId, artifact.artifactId, gate)

                val wf = f.app.workflows.getWorkflow(WorkflowId("wf-i10-gate-same-ms-$attempt"))!!
                val ref = f.app.workflows.listSteps(wf.workflowId)
                    .lastOrNull { it.phase == WorkflowStepPhase.WRITING }
                    ?.resultReference
                assertEquals(
                    WritingSnapshot.WORKING_REF_PREFIX + intended.workingDraftId.value,
                    ref,
                    "Gate 必须绑定 Artifact 的工作产物；另一份产物（${other.workingDraftId.value}）不得影响选择",
                )
            } finally {
                f.close()
            }
        }
    }
}
