package com.qianyan.application.usecase.commit

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.model.DraftId
import com.qianyan.model.VariantScope
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.commit.CommitOperation
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I10 · Atomic Commit（§3 / §5 / §6 / §7 / §20 / §21）。
 *
 * 覆盖：valid commit、首次写入（无基线）、missing artifact、project mismatch、NO_CHANGES、INVALID、
 * DISCARDED WorkingDraft、invalid target、stale（内容漂移 / 新 Draft 出现）、duplicate commit、
 * Canonical 隔离、Artifact 冻结、WorkingDraft 终态收尾、history 作用域与顺序、prepareCommit 只读。
 */
class CommitUseCasesTest {

    private val originalCanonicalContent = "A 第二章正文"
    private val originalCanonicalCreatedAt = Instant.parse("2026-01-01T00:01:00Z")
    private val newContent = "A 第二章正文（提交稿）"

    @Test
    fun `valid artifact commits into canonical draft plus history atomically`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)
        val before = canonicalCounts(f)

        val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)

        // 结果（§7）
        assertEquals(w.canonicalDraftId, result.previousDraftId, "previous canonical reference = 提交前正文载体")
        assertEquals(w.projectId, result.projectId)
        assertEquals(artifact.change.target, result.target)
        assertEquals(newContent, result.historyEntry.resultingContent)

        // Canonical 正文载体：**新增** Draft（版本链），既有 Draft 原样保留
        val latest = f.app.writerUseCases.latestDraft(w.chapter2)
        assertNotNull(latest)
        assertEquals(result.resultingDraftId, latest.draftId, "提交结果必须成为章节最新 Canonical 正文")
        assertEquals(newContent, latest.content)
        assertEquals(w.canonicalDraftId, latest.previousDraftId, "复用既有 Draft.previousDraftId 表达版本链")
        assertEquals(DraftStatus.WRITTEN, latest.status)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, latest.format, "新建正文按 FD-1 标记受控 Markdown")
        assertEquals(w.novelId, latest.novelId)
        assertEquals(w.chapter2, latest.chapterId)
        assertEquals(VariantScope.ORIGINAL, latest.scope)

        val old = f.app.draftRepository.getById(w.canonicalDraftId)
        assertNotNull(old, "既有 Canonical Draft 不得被提交删除 / 覆盖")
        assertEquals(originalCanonicalContent, old.content)
        assertEquals(DraftStatus.REVISED, old.status)
        assertEquals(originalCanonicalCreatedAt, old.createdAt)

        // History（§8 / §9）
        val entries = f.app.commits.history(w.projectId)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(result.historyEntry, entry)
        assertEquals(CommitOperation.COMMIT, entry.operation)
        assertEquals(artifact.artifactId, entry.artifactId)
        assertEquals(w.projectId, entry.projectId)
        assertEquals(artifact.change.target, entry.target)
        assertEquals(w.canonicalDraftId, entry.previousDraftId)
        assertEquals(result.resultingDraftId, entry.resultingDraftId)
        assertEquals(originalCanonicalContent, entry.previousContent, "记录「修改前」")
        assertEquals(newContent, entry.resultingContent, "记录「修改后」")
        assertNull(entry.revertedHistoryId)
        assertEquals(result.createdAt, entry.createdAt)

        // Artifact 冻结（§19）：Commit 不得修改 Artifact 本身
        assertEquals(artifact, f.app.changes.get(artifact.artifactId))

        // WorkingDraft 终态（§18：经 I8 既有能力）
        assertEquals(WorkingDraftStatus.DISCARDED, f.app.workingDrafts.get(artifact.workingDraftId).status)

        // Canonical 隔离（§17）：只应有 ChapterDraft + CommitHistory 变化
        assertOnlyCanonicalWrites(f, before)
        f.close()
    }

    @Test
    fun `commit writes first canonical draft when chapter has no base draft`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, "B 第一章初稿", baseDraftId = null, chapterId = w.chapter1)

        val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)

        assertNull(result.previousDraftId, "无基线 ⇒ 没有 previous canonical reference")
        val latest = f.app.writerUseCases.latestDraft(w.chapter1)
        assertNotNull(latest)
        assertEquals("B 第一章初稿", latest.content)
        assertNull(latest.previousDraftId)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, latest.format)

        val entry = f.app.commits.history(w.projectId).single()
        assertNull(entry.previousDraftId)
        assertEquals("", entry.previousContent)
        assertEquals("B 第一章初稿", entry.resultingContent)
        f.close()
    }

    @Test
    fun `commit rejects unknown artifact and cross project artifact`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val artifact = artifactFor(f, w, newContent)

        assertFailsWith<ApplicationException> {
            f.app.commits.commit(w.projectId, ChangeArtifactId("missing-artifact"))
        }

        // Project B 的 Commit 不能操作 Project A 的 Artifact（统一表现为"不存在"，不泄漏存在性）
        val otherNovelId = f.app.novels.createOriginal(title = "书B")
        val otherProjectId = f.app.projects.projectOf(otherNovelId).projectId
        assertFailsWith<ApplicationException> { f.app.commits.commit(otherProjectId, artifact.artifactId) }
        assertEquals(0, f.app.commits.history(otherProjectId).size)
        assertEquals(2, f.app.draftRepository.listByChapter(w.chapter2).size, "拒绝后不得写入 Canonical")
        f.close()
    }

    @Test
    fun `commit rejects no changes artifact`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, originalCanonicalContent) // 与基线完全相同
        val before = canonicalCounts(f)

        assertInvalid(CommitErrorCodes.NO_CHANGES) { f.app.commits.commit(w.projectId, artifact.artifactId, approval) }

        assertEquals(before, canonicalCounts(f), "拒绝提交不得改动任何数据")
        f.close()
    }

    @Test
    fun `commit rejects invalid artifact`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, "") // Validation ERROR（EMPTY_CONTENT）
        val before = canonicalCounts(f)

        assertInvalid(CommitErrorCodes.INVALID_ARTIFACT) { f.app.commits.commit(w.projectId, artifact.artifactId, approval) }

        assertEquals(before, canonicalCounts(f))
        f.close()
    }

    @Test
    fun `commit rejects discarded working draft`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)
        f.app.workingDrafts.discard(artifact.workingDraftId)

        assertInvalid(CommitErrorCodes.DISCARDED_WORKING_DRAFT) {
            f.app.commits.commit(w.projectId, artifact.artifactId, approval)
        }
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `commit rejects artifact whose target is no longer legal`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)

        // Artifact 生成后 target 章节被移动到另一部 Novel（target 不再合法）
        val otherNovelId = f.app.novels.createOriginal(title = "书B")
        val chapter = f.app.chapters.findById(w.chapter2)
        assertNotNull(chapter)
        f.app.chapterRepository.save(chapter.copy(novelId = otherNovelId))

        assertInvalid(CommitErrorCodes.INVALID_TARGET) { f.app.commits.commit(w.projectId, artifact.artifactId, approval) }
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `commit rejects stale artifact when canonical content changed after artifact`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)

        // Artifact 生成后 Canonical 被其他操作修改（基线内容漂移）
        f.app.writerUseCases.saveContent(w.canonicalDraftId, "A 第二章正文（被他人改写）")

        assertInvalid(CommitErrorCodes.STALE_ARTIFACT) { f.app.commits.commit(w.projectId, artifact.artifactId, approval) }
        assertEquals(0, f.app.commits.history(w.projectId).size)
        assertEquals("A 第二章正文（被他人改写）", f.app.draftRepository.getById(w.canonicalDraftId)?.content)
        f.close()
    }

    @Test
    fun `commit rejects stale artifact when a newer canonical draft appeared`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)

        // Artifact 生成后章节出现了更新的 Canonical Draft（基线身份不再一致）
        f.app.draftRepository.save(
            Draft(
                draftId = DraftId("draft-A-newer"),
                novelId = w.novelId,
                chapterId = w.chapter2,
                previousDraftId = w.canonicalDraftId,
                content = "A 第二章正文（后来又改了）",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.REVISED,
                createdAt = Instant.parse("2026-06-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-01T00:00:00Z"),
            ),
        )

        assertInvalid(CommitErrorCodes.STALE_ARTIFACT) { f.app.commits.commit(w.projectId, artifact.artifactId, approval) }
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `same artifact cannot be committed twice`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)

        val first = f.app.commits.commit(w.projectId, artifact.artifactId, approval)
        val draftsAfterFirst = f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId }

        assertInvalid(CommitErrorCodes.ALREADY_COMMITTED) { f.app.commits.commit(w.projectId, artifact.artifactId, approval) }

        // 不产生重复 Canonical Draft / 重复 History
        assertEquals(draftsAfterFirst, f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId })
        assertEquals(1, f.app.commits.history(w.projectId).size)
        assertEquals(first.historyEntry, f.app.commits.history(w.projectId).single())
        f.close()
    }

    @Test
    fun `prepare commit is read only`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val artifact = artifactFor(f, w, newContent)
        val before = canonicalCounts(f)

        val proposal = f.app.commits.prepareCommit(w.projectId, artifact.artifactId)

        assertEquals(artifact.artifactId, proposal.artifactId)
        assertEquals(w.canonicalDraftId, proposal.previousDraftId)
        assertEquals(originalCanonicalContent, proposal.previousContent)
        assertEquals(newContent, proposal.resultingContent)
        assertEquals(before, canonicalCounts(f), "prepareCommit 不得改动任何数据")
        assertEquals(0, f.app.commits.history(w.projectId).size)
        f.close()
    }

    @Test
    fun `history is scoped to project and order is deterministic`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)

        val first = f.app.commits.commit(w.projectId, artifactFor(f, w, "第一次提交正文").artifactId, approval)
        // 第二次提交必须基于**第一次提交后的** Canonical 正文载体
        val second = f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, "第二次提交正文", baseDraftId = first.resultingDraftId).artifactId,
            approval,
        )

        val entries = f.app.commits.history(w.projectId)
        assertEquals(
            listOf(first.historyEntry.historyId, second.historyEntry.historyId),
            entries.map { it.historyId },
            "历史顺序 = 发生时序（不依赖时间戳精度）",
        )
        assertEquals(first.resultingDraftId, second.historyEntry.previousDraftId)
        assertEquals("第一次提交正文", second.historyEntry.previousContent)

        // 另一 Project 看不到任何历史；越界读取统一表现为"不存在"
        val otherNovelId = f.app.novels.createOriginal(title = "书B")
        val otherProjectId = f.app.projects.projectOf(otherNovelId).projectId
        assertEquals(0, f.app.commits.history(otherProjectId).size)
        assertFailsWith<ApplicationException> { f.app.commits.historyEntry(otherProjectId, first.historyEntry.historyId) }
        f.close()
    }

    // ---- helpers ----

    private fun canonicalCounts(f: WorkingDraftFixture): Map<String, Long> = listOf(
        "Chapter", "ChapterDraft", "ProjectState", "StoryFoundation",
        "AgentSession", "Activity", "ToolCallLog", "Workflow", "Task", "CommitHistory",
    ).associateWith { countRows(f.handle, it) }

    /** 只允许 ChapterDraft / CommitHistory 增长；其余 Canonical 与记录表必须完全不变。 */
    private fun assertOnlyCanonicalWrites(f: WorkingDraftFixture, before: Map<String, Long>) {
        listOf(
            "Chapter", "ProjectState", "StoryFoundation",
            "AgentSession", "Activity", "ToolCallLog", "Workflow", "Task",
        ).forEach { table ->
            assertEquals(before[table], countRows(f.handle, table), "$table 行数不得变化（Commit 不改动它）")
        }
        assertEquals(before["ChapterDraft"]!! + 1, countRows(f.handle, "ChapterDraft"), "应新增 1 个 Canonical Draft")
        assertEquals(before["CommitHistory"]!! + 1, countRows(f.handle, "CommitHistory"), "应新增 1 条 History")
    }

    private fun assertInvalid(code: String, block: () -> Unit) {
        val e = assertFailsWith<ApplicationException> { block() }
        assertTrue(e.message!!.contains(code), "期望错误码 $code，实际：${e.message}")
    }
}