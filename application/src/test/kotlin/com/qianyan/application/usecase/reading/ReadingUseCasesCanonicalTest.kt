package com.qianyan.application.usecase.reading

import com.qianyan.application.usecase.commit.approvedGate
import com.qianyan.application.usecase.commit.artifactFor
import com.qianyan.application.usecase.commit.commitFixture
import com.qianyan.application.usecase.commit.commitWorld
import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.DraftId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P1-01c · Reader 接入 Canonical Read（R3 / §23B）。
 *
 * 复用 I8/I9/I10 既有夹具（真实内存 SQLite + ApplicationContainer）：
 * Reader（[ReadingUseCases.openChapter]，Android 与 Desktop 共用）只读**已提交的 Canonical 正文**，
 * 与 `latestByChapter`（Draft）严格分离，且**不存在** Canonical → Draft 的 fallback。
 */
class ReadingUseCasesCanonicalTest {

    @Test
    fun `reader reads committed canonical content and ignores a newer uncommitted draft`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val content = "第二章·已提交正文"
            commit(f, w, content, gateId = "gate-reader-1")

            // 更新的未提交 Draft（晚于真实时钟）⇒ latestByChapter 会选中它
            f.app.draftRepository.save(
                Draft(
                    draftId = DraftId("draft-reader-uncommitted"),
                    novelId = w.novelId,
                    chapterId = w.chapter2,
                    content = "第二章·未提交草稿",
                    format = DraftFormat.CONTROLLED_MARKDOWN,
                    status = DraftStatus.WRITTEN,
                    createdAt = LATER,
                    updatedAt = LATER,
                ),
            )
            assertEquals(
                DraftId("draft-reader-uncommitted"),
                f.app.draftRepository.latestByChapter(w.chapter2)?.draftId,
                "前置：最新 Draft 应为未提交草稿",
            )

            val view = f.app.reading.openChapter(w.novelId, null, w.chapter2)

            assertTrue(view.hasDraft, "存在已提交 Canonical 正文")
            assertEquals(listOf(MarkdownBlock.Paragraph(content)), view.blocks, "Reader 必须读 Canonical，而非未提交 Draft")
            assertNotEquals(
                listOf(MarkdownBlock.Paragraph("第二章·未提交草稿")),
                view.blocks,
                "未提交 Draft 不得进入 Reader",
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun `reader follows the newest commit`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val first = "第二章·第一次提交"
            commit(f, w, first, gateId = "gate-reader-a")
            assertEquals(listOf(MarkdownBlock.Paragraph(first)), f.app.reading.openChapter(w.novelId, null, w.chapter2).blocks)

            val base = f.app.canonicalRead.committedDraft(w.chapter2)
            val second = "第二章·第二次提交"
            commit(f, w, second, gateId = "gate-reader-b", baseDraftId = base?.draftId)

            assertEquals(
                listOf(MarkdownBlock.Paragraph(second)),
                f.app.reading.openChapter(w.novelId, null, w.chapter2).blocks,
                "Commit 后 Reader 应读到新的 Canonical 正文",
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun `reader does not fall back to draft when the chapter has no canonical content`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f) // 夹具已 seed 一份 chapter2 的 Draft（未提交）

            assertTrue(f.app.draftRepository.latestByChapter(w.chapter2) != null, "前置：存在未提交 Draft")
            assertTrue(f.app.canonicalRead.committedDraft(w.chapter2) == null, "前置：尚无 Canonical")

            val view = f.app.reading.openChapter(w.novelId, null, w.chapter2)

            assertFalse(view.hasDraft, "无 Canonical → 不应报告有正文")
            assertTrue(view.blocks.isEmpty(), "无 Canonical → 空块（不得 fallback 到最新 Draft）")
        } finally {
            f.close()
        }
    }

    /* ---------------- helpers ---------------- */

    private fun commit(
        f: WorkingDraftFixture,
        w: SeededWorld,
        content: String,
        gateId: String,
        baseDraftId: DraftId? = w.canonicalDraftId,
    ) {
        val artifact = artifactFor(f, w, content, baseDraftId = baseDraftId)
        f.app.commits.commit(w.projectId, artifact.artifactId, approvedGate(f, w, gateId = gateId))
    }

    private companion object {
        /** 明确晚于夹具固定时间戳与真实时钟。 */
        val LATER: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
