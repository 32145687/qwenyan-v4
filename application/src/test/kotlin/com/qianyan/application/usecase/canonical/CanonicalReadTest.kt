package com.qianyan.application.usecase.canonical

import com.qianyan.application.usecase.commit.approvedGate
import com.qianyan.application.usecase.commit.artifactFor
import com.qianyan.application.usecase.commit.commitFixture
import com.qianyan.application.usecase.commit.commitWorld
import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.model.DraftId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-01b · Canonical Read 测试（R3 / §23B）。
 *
 * 复用 I8/I9/I10 既有夹具（真实内存 SQLite + ApplicationContainer）：
 * Canonical 只经 `CommitHistory.resultingDraftId` 定位已提交正文载体，与 `latestByChapter`（Draft）严格分离。
 *
 * 时间说明：[LATER] 明确晚于夹具的固定时间戳（2026-01-01）与真实时钟，保证"未提交的新 Draft"
 * 一定是 `latestByChapter` 的返回者，从而真正检验 Canonical 与 Draft 的分离。
 */
class CanonicalReadTest {

    @Test
    fun `chapter without any commit has no canonical content even though a latest draft exists`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f) // 夹具已 seed 一份 chapter2 的既有 Draft（未提交）

            assertTrue(f.app.draftRepository.latestByChapter(w.chapter2) != null, "前置：应存在最新 Draft")
            assertNull(f.app.canonicalRead.committedDraft(w.chapter2), "未提交章节不得被当作 Canonical")
            assertNull(f.app.canonicalRead.committedContent(w.chapter2), "未提交章节不得有 Canonical 正文")
        } finally {
            f.close()
        }
    }

    @Test
    fun `committed draft is canonical while an uncommitted newer draft stays draft-only`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val committedContent = commitChapter(f, w, "第二章·已提交正文", gateId = "gate-canonical-1")

            // 已提交 → Canonical 读到提交产物
            val canonical = f.app.canonicalRead.committedDraft(w.chapter2)
            assertEquals(committedContent, canonical?.content, "Canonical 应为已提交正文")
            assertEquals(committedContent, f.app.canonicalRead.committedContent(w.chapter2))

            // 未提交的新 Draft（更新）→ 只影响 latestByChapter，不影响 Canonical
            val draftB = DraftId("draft-canonical-uncommitted")
            f.app.draftRepository.save(
                Draft(
                    draftId = draftB,
                    novelId = w.novelId,
                    chapterId = w.chapter2,
                    previousDraftId = canonical?.draftId,
                    content = "第二章·未提交草稿",
                    format = DraftFormat.CONTROLLED_MARKDOWN,
                    status = DraftStatus.WRITTEN,
                    createdAt = LATER,
                    updatedAt = LATER,
                ),
            )

            assertEquals(draftB, f.app.draftRepository.latestByChapter(w.chapter2)?.draftId, "最新 Draft 应为未提交的 B")
            assertEquals(committedContent, f.app.canonicalRead.committedContent(w.chapter2), "Canonical 不得被未提交 Draft 改变")
            assertNotEquals(draftB, canonical?.draftId, "未提交 Draft 不得成为 Canonical")
        } finally {
            f.close()
        }
    }

    @Test
    fun `canonical read follows the newest commit`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val first = commitChapter(f, w, "第二章·第一次提交", gateId = "gate-canonical-a")
            assertEquals(first, f.app.canonicalRead.committedContent(w.chapter2))

            val current = f.app.canonicalRead.committedDraft(w.chapter2)
            val second = commitChapter(
                f, w, "第二章·第二次提交",
                gateId = "gate-canonical-b",
                baseDraftId = current?.draftId,
            )

            assertEquals(second, f.app.canonicalRead.committedContent(w.chapter2), "Canonical 应随最新 Commit 更新")
            assertEquals(second, f.app.canonicalRead.committedDraft(w.chapter2)?.content)
        } finally {
            f.close()
        }
    }

    /* ---------------- helpers ---------------- */

    /** 走既有 I8 → I9 → I10 链路提交一章内容，返回该次提交产生的正文。 */
    private fun commitChapter(
        f: WorkingDraftFixture,
        w: SeededWorld,
        content: String,
        gateId: String,
        baseDraftId: DraftId? = w.canonicalDraftId,
    ): String {
        val artifact = artifactFor(f, w, content, baseDraftId = baseDraftId)
        f.app.commits.commit(w.projectId, artifact.artifactId, approvedGate(f, w, gateId = gateId))
        return content
    }

    private companion object {
        /** 明确晚于夹具固定时间戳与真实时钟。 */
        val LATER: Instant = Instant.parse("2100-01-01T00:00:00Z")
    }
}
