package com.qianyan.application.usecase.commit

import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.ProjectId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.Draft
import com.qianyan.storage.repository.CommitHistoryRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.SqliteCommitHistoryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I10 · Storage Transaction 故障注入（§16 / §22「零半提交」）。
 *
 * 全部使用**真实内存 SQLite + 真实跨仓储事务**（`WorkflowRepository.inTransaction` → `QianyanDb.transaction`），
 * 只在写入路径上注入失败，验证：
 *
 *   Case A · Canonical 正文写入中途失败（已落库后报错）  ⇒ Canonical / History 全部不变
 *   Case B · History 写入失败（Canonical 已落库）        ⇒ Canonical / History 全部不变
 *   Case C · 多 Repository 写入后失败（两者均已落库）    ⇒ Canonical / History 全部不变
 *   Case D · 全部成功                                    ⇒ Canonical 已改 + History 已建
 */
class CommitTransactionTest {

    private val newContent = "A 第二章正文（提交稿）"

    @Test
    fun `case A canonical write fails mid way and nothing is half committed`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)
        val commits = commitUseCasesWith(f, drafts = WriterUseCases(FailingDraftRepository(f.app.draftRepository), f.app.errorMapper))

        assertFailsWith<IllegalStateException> { commits.commit(w.projectId, artifact.artifactId, approval) }

        assertNoHalfCommit(f, w, artifact.workingDraftId)
        f.close()
    }

    @Test
    fun `case B history write fails and draft write is rolled back`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)
        val commits = commitUseCasesWith(
            f,
            history = FailingCommitHistoryRepository(
                SqliteCommitHistoryRepository(f.handle.db),
                mode = FailMode.BEFORE_APPEND,
            ),
        )

        assertFailsWith<IllegalStateException> { commits.commit(w.projectId, artifact.artifactId, approval) }

        assertNoHalfCommit(f, w, artifact.workingDraftId)
        f.close()
    }

    @Test
    fun `case C multi repository failure rolls back every write`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)
        val commits = commitUseCasesWith(
            f,
            history = FailingCommitHistoryRepository(
                SqliteCommitHistoryRepository(f.handle.db),
                mode = FailMode.AFTER_APPEND,
            ),
        )

        assertFailsWith<IllegalStateException> { commits.commit(w.projectId, artifact.artifactId, approval) }

        // 两个仓储都已写入，随后失败 ⇒ 必须一起回滚（零半提交）
        assertNoHalfCommit(f, w, artifact.workingDraftId)
        assertEquals(0, countRows(f.handle, "CommitHistory"), "Failure 后 History 不得残留")
        f.close()
    }

    @Test
    fun `case D all writes succeed and both canonical and history change`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)

        val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)

        val latest = f.app.writerUseCases.latestDraft(w.chapter2)
        assertNotNull(latest)
        assertEquals(newContent, latest.content, "Canonical 已改")
        assertEquals(result.resultingDraftId, latest.draftId)
        assertEquals(1, historyRepo(f).listByProject(w.projectId).size, "History 已建")
        assertEquals(WorkingDraftStatus.DISCARDED, f.app.workingDrafts.get(artifact.workingDraftId).status)
        f.close()
    }

    @Test
    fun `case B revert history write fails and canonical restore is rolled back`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val commit = f.app.commits.commit(w.projectId, artifactFor(f, w, newContent).artifactId, approval)
        val draftsBefore = f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId }.toSet()

        val commits = commitUseCasesWith(
            f,
            history = FailingCommitHistoryRepository(
                SqliteCommitHistoryRepository(f.handle.db),
                mode = FailMode.BEFORE_APPEND,
            ),
        )
        assertFailsWith<IllegalStateException> {
            commits.revert(w.projectId, commit.historyEntry.historyId, approval)
        }

        // Revert 的 Canonical 新 Draft 必须被回滚，原历史不得新增
        assertEquals(draftsBefore, f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId }.toSet())
        assertEquals(newContent, f.app.writerUseCases.latestDraft(w.chapter2)?.content)
        assertEquals(1, historyRepo(f).listByProject(w.projectId).size)
        f.close()
    }

    // ---- helpers ----

    private fun commitUseCasesWith(
        f: WorkingDraftFixture,
        drafts: WriterUseCases = f.app.writerUseCases,
        history: CommitHistoryRepository = SqliteCommitHistoryRepository(f.handle.db),
    ) = CommitUseCases(
        projects = f.app.projects,
        chapters = f.app.chapters,
        workingDrafts = f.app.workingDrafts,
        changes = f.app.changes,
        drafts = drafts,
        history = history,
        workflows = f.app.workflows,
        actionPolicy = f.app.actionPolicy,
        errorMapper = f.app.errorMapper,
    )

    /** 零半提交：既有 Canonical 正文不变、无新 Draft、无 History、Working Draft 未被收尾。 */
    private fun assertNoHalfCommit(f: WorkingDraftFixture, w: SeededWorld, workingDraftId: WorkingDraftId) {
        val latest = f.app.writerUseCases.latestDraft(w.chapter2)
        assertNotNull(latest)
        assertEquals(w.canonicalDraftId, latest.draftId, "既有 Canonical 正文载体不得被改动")
        assertEquals("A 第二章正文", latest.content, "既有 Canonical 正文内容不得被改动")
        assertEquals(2, f.app.draftRepository.listByChapter(w.chapter2).size, "不得新增 Canonical Draft")
        assertEquals(0, historyRepo(f).listByProject(w.projectId).size, "不得写入 History")
        assertTrue(
            f.app.workingDrafts.get(workingDraftId).status != WorkingDraftStatus.DISCARDED,
            "失败提交不得收尾 Working Draft",
        )
    }

    private enum class FailMode { BEFORE_APPEND, AFTER_APPEND }

    /** 注入"Canonical 正文写入已落库、随后该步骤报错"的失败。 */
    private class FailingDraftRepository(
        private val delegate: DraftRepository,
    ) : DraftRepository by delegate {
        override fun save(draft: Draft) {
            delegate.save(draft)
            throw IllegalStateException("injected canonical draft write failure (${draft.draftId.value})")
        }
    }

    /** 注入 History 写入失败（BEFORE = 完全未写；AFTER = 已落库后报错）。 */
    private class FailingCommitHistoryRepository(
        private val delegate: CommitHistoryRepository,
        private val mode: FailMode,
    ) : CommitHistoryRepository {
        override fun append(entry: CommitHistoryEntry) {
            if (mode == FailMode.BEFORE_APPEND) throw IllegalStateException("injected history write failure")
            delegate.append(entry)
            throw IllegalStateException("injected history write failure after append")
        }

        override fun getById(historyId: CommitHistoryId): CommitHistoryEntry? = delegate.getById(historyId)
        override fun getByArtifact(artifactId: ChangeArtifactId): CommitHistoryEntry? = delegate.getByArtifact(artifactId)
        override fun listByProject(projectId: ProjectId): List<CommitHistoryEntry> = delegate.listByProject(projectId)
    }
}