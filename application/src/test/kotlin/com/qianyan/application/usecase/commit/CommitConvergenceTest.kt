package com.qianyan.application.usecase.commit

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelUpdateAgent
import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelUpdateUseCases
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.worldmodel.CandidateFact
import com.qianyan.model.worldmodel.CandidateFactStatus
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.repository.SqliteCommitHistoryRepository
import com.qianyan.storage.repository.WorldModelCandidateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0-3B · Step 4（D9）：Commit 成功后的状态收敛与重复调用。
 */
class CommitConvergenceTest {

    @Test
    fun `successful commit exposes identity and no post commit warning`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val approval = approvedGate(f, w, gateId = "gate-converge-success")
            val artifact = artifactFor(f, w, "收敛测试正文")

            val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)

            assertNull(result.postCommitWarning, "成功提交不应产生 postCommitWarning")
            assertEquals(artifact.artifactId, result.historyEntry.artifactId, "CommitHistory 必须保留原 Artifact 身份")
            assertEquals(result.historyEntry, f.app.commits.committedByArtifact(artifact.artifactId))
            assertEquals(result.historyEntry, f.app.commits.committedByGate(approval.gateId))
            assertEquals("收敛测试正文", f.app.canonicalRead.committedContent(w.chapter2))
            assertEquals(1, f.app.commits.history(w.projectId).size)
        } finally {
            f.close()
        }
    }

    @Test
    fun `post commit pipeline failure is reported as warning while commit stays successful`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val approval = approvedGate(f, w, gateId = "gate-converge-warning")
            val artifact = artifactFor(f, w, "后续更新失败正文")

            val failingPipeline = WorldModelUpdateUseCases(
                agent = WorldModelUpdateAgent(MockLLMGateway()),
                candidates = object : WorldModelCandidateRepository {
                    override fun listByHistory(historyId: String): List<CandidateFact> =
                        throw RuntimeException("world model store unavailable")

                    override fun save(candidate: CandidateFact) =
                        throw RuntimeException("world model store unavailable")

                    override fun getById(candidateId: String): CandidateFact? = null
                    override fun listByStatus(status: CandidateFactStatus): List<CandidateFact> = emptyList()

                    override fun updateStatus(
                        candidateId: String,
                        status: CandidateFactStatus,
                        decisionReason: String?,
                        conflictWith: String?,
                        resultingFactId: String?,
                    ) = throw RuntimeException("world model store unavailable")
                },
                storyState = f.app.storyState,
                errorMapper = f.app.errorMapper,
            )
            val commits = CommitUseCases(
                projects = f.app.projects,
                chapters = f.app.chapters,
                workingDrafts = f.app.workingDrafts,
                changes = f.app.changes,
                drafts = f.app.writerUseCases,
                history = SqliteCommitHistoryRepository(f.handle.db),
                workflows = f.app.workflowRepository,
                actionPolicy = f.app.actionPolicy,
                worldModelUpdate = failingPipeline,
                errorMapper = f.app.errorMapper,
            )

            val result = commits.commit(w.projectId, artifact.artifactId, approval)

            assertTrue(
                result.postCommitWarning.orEmpty().contains("World Model"),
                "后续更新失败必须作为非致命警告返回：${result.postCommitWarning}",
            )
            assertEquals(1, commits.history(w.projectId).size, "Commit 本身必须已持久化")
            assertEquals(result.historyEntry, commits.committedByArtifact(artifact.artifactId))
            assertEquals("后续更新失败正文", f.app.canonicalRead.committedContent(w.chapter2))
            assertNotNull(result.historyEntry.resultingDraftId)
        } finally {
            f.close()
        }
    }

    @Test
    fun `working draft cleanup failure is a warning and leaves the draft for explicit cleanup`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val approval = approvedGate(f, w, gateId = "gate-converge-cleanup")
            val artifact = artifactFor(f, w, "清理失败正文")
            val commits = CommitUseCases(
                projects = f.app.projects,
                chapters = f.app.chapters,
                workingDrafts = f.app.workingDrafts,
                changes = f.app.changes,
                drafts = f.app.writerUseCases,
                history = SqliteCommitHistoryRepository(f.handle.db),
                workflows = f.app.workflowRepository,
                actionPolicy = f.app.actionPolicy,
                discardWorkingDraft = { throw RuntimeException("discard unavailable") },
                errorMapper = f.app.errorMapper,
            )

            val result = commits.commit(w.projectId, artifact.artifactId, approval)

            assertTrue(
                result.postCommitWarning.orEmpty().contains("WorkingDraft"),
                "清理失败必须作为非致命警告返回：${result.postCommitWarning}",
            )
            assertEquals(1, commits.history(w.projectId).size, "Commit 本身必须已持久化")
            assertEquals("清理失败正文", f.app.canonicalRead.committedContent(w.chapter2))
            val draft = f.app.workingDrafts.get(artifact.workingDraftId)
            assertTrue(
                draft.status != WorkingDraftStatus.DISCARDED,
                "清理失败后候选保留，等待显式补收尾：${draft.status}",
            )

            // 重复提交同一 Artifact → 依据既有提交事实拒绝，不重复写 Canonical / History
            val error = assertFailsWith<ApplicationException> {
                commits.commit(w.projectId, artifact.artifactId, approval)
            }
            assertTrue(error.message.orEmpty().contains(CommitErrorCodes.ALREADY_COMMITTED), error.message.orEmpty())
            assertEquals(1, commits.history(w.projectId).size)

            // 显式补收尾：真实 WorkingDraft 仓库仍可将遗留候选置为终态
            f.app.workingDrafts.discard(artifact.workingDraftId)
            assertEquals(
                WorkingDraftStatus.DISCARDED,
                f.app.workingDrafts.get(artifact.workingDraftId).status,
            )
        } finally {
            f.close()
        }
    }
}
