package com.qianyan.application.usecase.commit

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.commit.CommitOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I10 · Revert（§10 / §11 / §12）。
 *
 * 核心不变量：Revert **不是删除历史**，而是产生一条新的 Canonical Commit + 新 History；
 * 当前内容已被新的修改改变 → REVERT_CONFLICT；不允许绕过 prepare / 权限直接写入。
 */
class CommitRevertTest {

    private val firstContent = "A 第二章正文（提交稿一）"
    private val secondContent = "A 第二章正文（提交稿二）"

    @Test
    fun `revert restores previous canonical content and appends a new history entry`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)

        val commit = f.app.commits.commit(w.projectId, artifactFor(f, w, firstContent).artifactId, approval)
        val historyBeforeRevert = f.app.commits.history(w.projectId)
        val countsBeforeRevert = counts(f)

        val revert = f.app.commits.revert(w.projectId, commit.historyEntry.historyId, approval)

        // Revert = 一次**新的** Canonical Commit
        assertEquals(commit.historyEntry.historyId, revert.revertedHistoryId)
        assertEquals(commit.resultingDraftId, revert.previousDraftId)

        val latest = f.app.writerUseCases.latestDraft(w.chapter2)
        assertNotNull(latest)
        assertEquals(revert.resultingDraftId, latest.draftId)
        assertEquals("A 第二章正文", latest.content, "Canonical 恢复到该历史对应的提交前内容")
        assertEquals(commit.resultingDraftId, latest.previousDraftId)

        // 原历史**保留**；新增一条 REVERT 历史
        val entries = f.app.commits.history(w.projectId)
        assertEquals(2, entries.size)
        assertEquals(historyBeforeRevert.single(), entries[0], "原历史必须原样保留（Revert 不删除历史）")
        val revertEntry = entries[1]
        assertEquals(revert.historyEntry, revertEntry)
        assertEquals(CommitOperation.REVERT, revertEntry.operation)
        assertNull(revertEntry.artifactId, "Revert 无来源 Artifact")
        assertEquals(commit.historyEntry.historyId, revertEntry.revertedHistoryId)
        assertEquals(commit.resultingDraftId, revertEntry.previousDraftId)
        assertEquals(revert.resultingDraftId, revertEntry.resultingDraftId)
        assertEquals(firstContent, revertEntry.previousContent)
        assertEquals("A 第二章正文", revertEntry.resultingContent)

        // 既有 Draft 一律保留（只增不改）
        assertNotNull(f.app.draftRepository.getById(commit.resultingDraftId))
        assertNotNull(f.app.draftRepository.getById(w.canonicalDraftId))
        assertEquals(countsBeforeRevert["ChapterDraft"]!! + 1, counts(f)["ChapterDraft"])
        assertEquals(countsBeforeRevert["CommitHistory"]!! + 1, counts(f)["CommitHistory"])
        f.close()
    }

    @Test
    fun `revert of the current head works while earlier history conflicts`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)

        val first = f.app.commits.commit(w.projectId, artifactFor(f, w, firstContent).artifactId, approval)
        val second = f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, secondContent, baseDraftId = first.resultingDraftId).artifactId,
            approval,
        )
        val draftsBefore = f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId }
        val historyBefore = f.app.commits.history(w.projectId)

        // 当前 Canonical 已不是 first 的提交结果 ⇒ 冲突（禁止静默覆盖用户的新内容）
        assertInvalid(CommitErrorCodes.REVERT_CONFLICT) {
            f.app.commits.revert(w.projectId, first.historyEntry.historyId, approval)
        }
        assertEquals(draftsBefore, f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId })
        assertEquals(historyBefore, f.app.commits.history(w.projectId))

        // 当前 head 对应的历史可以回退
        val revert = f.app.commits.revert(w.projectId, second.historyEntry.historyId, approval)
        assertEquals(firstContent, f.app.writerUseCases.latestDraft(w.chapter2)?.content)
        assertEquals(second.historyEntry.historyId, revert.revertedHistoryId)

        // 回到 first 的提交结果后，first 也可以再次被回退（历史只增不删）
        val again = f.app.commits.revert(w.projectId, first.historyEntry.historyId, approval)
        assertEquals("A 第二章正文", f.app.writerUseCases.latestDraft(w.chapter2)?.content)
        assertEquals(first.historyEntry.historyId, again.revertedHistoryId)
        assertEquals(4, f.app.commits.history(w.projectId).size, "COMMIT + REVERT + REVERT + REVERT")
        f.close()
    }

    @Test
    fun `revert rejects unknown cross project and revert history`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val commit = f.app.commits.commit(w.projectId, artifactFor(f, w, firstContent).artifactId, approval)

        assertFailsWith<ApplicationException> { f.app.commits.revert(w.projectId, CommitHistoryId("missing-history"), approval) }

        // 跨 Project：统一表现为"不存在"
        val otherProjectId = f.app.projects.projectOf(f.app.novels.createOriginal(title = "书B")).projectId
        assertFailsWith<ApplicationException> { f.app.commits.revert(otherProjectId, commit.historyEntry.historyId, approval) }

        // 回退一条 REVERT 记录：本阶段不定义该语义 ⇒ 类型化拒绝
        val revert = f.app.commits.revert(w.projectId, commit.historyEntry.historyId, approval)
        assertInvalid(CommitErrorCodes.INVALID_REVERT_TARGET) {
            f.app.commits.revert(w.projectId, revert.historyEntry.historyId, approval)
        }
        f.close()
    }

    @Test
    fun `revert requires existing human gate approval and does not touch canonical when refused`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val commit = f.app.commits.commit(w.projectId, artifactFor(f, w, firstContent).artifactId, approval)

        // 既有 Gate 未批准（PENDING）也不行（夹具先建好，再取快照）
        val pending = approvedGate(
            f,
            w,
            gateId = "gate-i10-pending",
            status = com.qianyan.model.workflow.HumanGateStatus.PENDING,
            decision = com.qianyan.model.workflow.HumanDecision.PENDING,
        )
        val countsBefore = counts(f)

        // 未提供批准凭据
        assertInvalid(CommitErrorCodes.HUMAN_APPROVAL_REQUIRED) {
            f.app.commits.revert(w.projectId, commit.historyEntry.historyId)
        }
        assertEquals(countsBefore, counts(f), "拒绝的回退不得改动任何数据")

        assertInvalid(CommitErrorCodes.HUMAN_APPROVAL_REQUIRED) {
            f.app.commits.revert(w.projectId, commit.historyEntry.historyId, pending)
        }
        assertEquals(countsBefore, counts(f))
        f.close()
    }

    @Test
    fun `prepare revert is read only and reports restore target`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val commit = f.app.commits.commit(w.projectId, artifactFor(f, w, firstContent).artifactId, approval)
        val countsBefore = counts(f)

        val proposal = f.app.commits.prepareRevert(w.projectId, commit.historyEntry.historyId)

        assertEquals(commit.historyEntry, proposal.history)
        assertEquals(commit.resultingDraftId, proposal.currentDraftId)
        assertEquals(firstContent, proposal.currentContent)
        assertEquals("A 第二章正文", proposal.restoreContent)
        assertEquals(countsBefore, counts(f), "prepareRevert 不得改动任何数据")
        f.close()
    }

    // ---- helpers ----

    private fun counts(f: WorkingDraftFixture): Map<String, Long> = listOf(
        "Chapter", "ChapterDraft", "ProjectState", "StoryFoundation",
        "AgentSession", "Activity", "ToolCallLog", "Workflow", "Task", "CommitHistory",
    ).associateWith { countRows(f.handle, it) }

    private fun assertInvalid(code: String, block: () -> Unit) {
        val e = assertFailsWith<ApplicationException> { block() }
        assertTrue(e.message!!.contains(code), "期望错误码 $code，实际：${e.message}")
    }
}