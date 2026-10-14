package com.qianyan.application.usecase.commit

import com.qianyan.model.commit.CommitOperation
import com.qianyan.model.workflow.WorkflowHumanGateId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-02j B1 · Commit ↔ Human Gate 的**持久关联**（`CommitHistory.gate_id`）。
 *
 * 语义：
 *  - 人工门批准产生的提交 → 记录**批准它的那个** Gate（来自真实批准上下文，不靠正文相等 / 最近 Gate 推测）；
 *  - 作者手改保存 / REVERT → `gate_id = NULL`（不伪造 Gate 关联）；
 *  - 可经 `committedByGate` 反查；
 *  - 逐字相同的两轮正文，各自关联各自的 Gate（关联判据取代"正文相等"启发式）。
 */
class CommitGateLinkTest {

    @Test
    fun `gate-approved commit records the gate id and is reverse queryable`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val approval = approvedGate(f, w, gateId = "gate-link-1")
            val artifact = artifactFor(f, w, "A 第二章正文（提交稿）")

            val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)

            assertEquals(approval.gateId, result.historyEntry.gateId, "提交必须记录批准它的 Gate")
            assertEquals(CommitOperation.COMMIT, result.historyEntry.operation)
            assertEquals(result.historyEntry, f.app.commits.committedByGate(approval.gateId), "应可经 Gate 反查提交")
            // 仓储层：关联已持久化（非内存推断）
            assertEquals(approval.gateId, historyRepo(f).getByGate(approval.gateId)?.gateId)
            assertEquals(result.resultingDraftId, historyRepo(f).getByGate(approval.gateId)?.resultingDraftId)
            // 未关联任何提交的 Gate → null（不伪造）
            assertNull(f.app.commits.committedByGate(WorkflowHumanGateId("gate-absent")))
        } finally {
            f.close()
        }
    }

    @Test
    fun `revert and author save carry no gate link`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val approval = approvedGate(f, w, gateId = "gate-link-2")
            val commit = f.app.commits.commit(w.projectId, artifactFor(f, w, "提交稿").artifactId, approval)

            // REVERT：即便复用了审批凭据，也不得把它当成"一次 Gate 批准的提交"
            val revert = f.app.commits.revert(w.projectId, commit.historyEntry.historyId, approval)
            assertEquals(CommitOperation.REVERT, revert.historyEntry.operation)
            assertNull(revert.historyEntry.gateId, "REVERT 记录不得关联 Gate")

            // 作者手改保存（保存即批准）：无 Change Artifact / 无 Gate
            val authorSave = f.app.commits.commitAuthorSave(w.canonicalDraftId, "A 第二章正文（作者改稿）")
            assertEquals(CommitOperation.COMMIT, authorSave.operation)
            assertNull(authorSave.gateId, "作者手改保存不得关联 Gate")

            // 反查仍命中原 COMMIT（REVERT 未污染关联）
            assertEquals(commit.historyEntry, f.app.commits.committedByGate(approval.gateId))
        } finally {
            f.close()
        }
    }

    @Test
    fun `identical content across rounds links to each round's own gate`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val g1 = approvedGate(f, w, gateId = "gate-round-1")
            val c1 = f.app.commits.commit(w.projectId, artifactFor(f, w, "同一正文").artifactId, g1)

            // 回退到提交前（正文回到原始 Canonical），使"再次提交同一正文"成为可能
            val gRevert = approvedGate(f, w, gateId = "gate-revert")
            val rev = f.app.commits.revert(w.projectId, c1.historyEntry.historyId, gRevert)

            // 第二轮：逐字相同的正文，但由**另一个** Gate 批准
            val g2 = approvedGate(f, w, gateId = "gate-round-2")
            val c2 = f.app.commits.commit(w.projectId, artifactFor(f, w, "同一正文", baseDraftId = rev.resultingDraftId).artifactId, g2)

            assertEquals("同一正文", c1.historyEntry.resultingContent)
            assertEquals(c1.historyEntry.resultingContent, c2.historyEntry.resultingContent, "两轮正文逐字相同")
            assertTrue(c1.resultingDraftId != c2.resultingDraftId, "两轮 Canonical 载体不同")

            assertEquals(g1.gateId, c1.historyEntry.gateId)
            assertEquals(g2.gateId, c2.historyEntry.gateId)
            // 关联判据（而非正文相等）把两轮分别指向各自的载体
            assertEquals(c1.resultingDraftId, f.app.commits.committedByGate(g1.gateId)?.resultingDraftId, "G1 反查到第一轮载体")
            assertEquals(c2.resultingDraftId, f.app.commits.committedByGate(g2.gateId)?.resultingDraftId, "G2 反查到第二轮载体")
        } finally {
            f.close()
        }
    }
}
