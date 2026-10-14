package com.qianyan.application.usecase.commit

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.draft.FIXED_INSTANT
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.engine.markdown.ControlledMarkdown
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.DraftId
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.commit.CommitFormatHistory
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Revert × 正文格式契约（Phase 1 · CommitHistory Format Contract）。
 *
 * 契约（v26 起冻结）：
 *  - revert 恢复载体的 `Draft.format` = **被回退历史的 `previousFormat`**（legacy ⇒ null；CM ⇒ 受控 Markdown；
 *    首个 COMMIT（previousFormat=NONE）⇒ 恢复空正文、format=null）；
 *  - `Draft.content` 逐字恢复；
 *  - `previous_format / resulting_format` 为 `UNKNOWN`（v26 前旧行）⇒ 类型化拒绝 `FORMAT_UNKNOWN`，零写入；
 *  - 各 Commit 路径（AI / 作者保存 / TXT 初始化 / Revert）都必须写入 previous/resulting 格式历史；
 *  - 普通新建 Draft 的 `stampControlledMarkdown` 行为不变（AI 产物仍被标记为 CM）。
 */
class RevertLegacyFormatTest {

    /** legacy 正文：含换行/空行/普通文本（本轮起 revert 后保持 plain text，不再被解释为 Markdown）。 */
    private val legacyBody = """
        - 第一条线索。

        # 楔子

        > 引语：「夜里有风。」

        普通段落。

        | 表 | 列 |
    """.trimIndent()

    private val legacyEdited = "作者改稿后的正文（legacy 行内保存）。"

    private val cmFirst = "# 第一章\n\n段落甲。"
    private val cmSecond = "段落乙（改稿）。"

    private fun legacyDraft(
        f: WorkingDraftFixture,
        chapterId: com.qianyan.model.ChapterId,
        id: String,
        createdAt: kotlinx.datetime.Instant = FIXED_INSTANT,
    ): DraftId {
        val draftId = DraftId(id)
        f.app.draftRepository.save(
            Draft(
                draftId = draftId,
                novelId = f.app.chapters.findById(chapterId)!!.novelId,
                chapterId = chapterId,
                content = legacyBody,
                format = null, // legacy plain text
                status = DraftStatus.WRITTEN,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        return draftId
    }

    private fun counts(f: WorkingDraftFixture): Map<String, Long> =
        listOf("ChapterDraft", "CommitHistory", "Chapter", "ProjectState").associateWith { countRows(f.handle, it) }

    private fun latestEntry(f: WorkingDraftFixture, w: com.qianyan.application.usecase.draft.SeededWorld, chapterId: com.qianyan.model.ChapterId) =
        f.app.commits.history(w.projectId).last { it.target.chapterId == chapterId }

    /* ---------------- A. Legacy 历史版本 revert ⇒ format == null ---------------- */

    @Test
    fun `legacy revert restores null format with identical content`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val legacyId = legacyDraft(f, w.chapter1, "draft-legacy-ch1")

        f.app.commits.commitAuthorSave(legacyId, legacyBody)
        f.app.commits.commitAuthorSave(legacyId, legacyEdited)
        val before = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))
        assertEquals(DraftFormat.PLAIN_TEXT, before.format, "legacy 正典基线必须是 format = null")

        val revert = f.app.commits.revert(w.projectId, latestEntry(f, w, w.chapter1).historyId, approval)

        val after = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))
        assertEquals(legacyBody, after.content, "revert 恢复的正文必须与被恢复版本逐字一致")
        assertEquals(DraftFormat.PLAIN_TEXT, after.format, "契约：恢复载体继承被恢复版本的 legacy 格式（不得升级为 CM）")
        assertEquals(DraftFormat.PLAIN_TEXT, revert.historyEntry.resultingFormat.let { CommitFormatHistory.toDraftFormat(it) })
        // 原文形态原样保留（不被按 Markdown 重新解释/改写）
        assertTrue(after.content.contains("# 楔子") && after.content.contains("| 表 | 列 |"))
        f.close()
    }

    @Test
    fun `legacy revert keeps reader on the plain text path`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val legacyId = legacyDraft(f, w.chapter1, "draft-legacy-ch1")

        f.app.commits.commitAuthorSave(legacyId, legacyBody)
        f.app.commits.commitAuthorSave(legacyId, legacyEdited)
        val beforeReader = f.app.reading.openChapter(w.novelId, null, w.chapter1)
        assertEquals(DraftFormat.PLAIN_TEXT, beforeReader.format)
        assertEquals(1, beforeReader.blocks.size)
        assertEquals(legacyEdited, (beforeReader.blocks[0] as MarkdownBlock.Paragraph).text)

        f.app.commits.revert(w.projectId, latestEntry(f, w, w.chapter1).historyId, approval)

        val afterReader = f.app.reading.openChapter(w.novelId, null, w.chapter1)
        assertEquals(DraftFormat.PLAIN_TEXT, afterReader.format, "恢复结果为 legacy ⇒ Reader 走 plain text 路径")
        assertEquals(1, afterReader.blocks.size, "legacy 不得被解析为 Markdown 块结构")
        assertEquals(legacyBody, (afterReader.blocks[0] as MarkdownBlock.Paragraph).text)
        f.close()
    }

    /* ---------------- B. Controlled Markdown 历史版本 revert ⇒ 格式保持 ---------------- */

    @Test
    fun `controlled markdown revert keeps content and format unchanged and stays contract compliant`() {
        val f = commitFixture()
        val w = commitWorld(f) // chapter2 的既有 Canonical Draft 为 CONTROLLED_MARKDOWN
        val approval = approvedGate(f, w)

        f.app.commits.commitAuthorSave(w.canonicalDraftId, cmFirst)
        f.app.commits.commitAuthorSave(w.canonicalDraftId, cmSecond)
        val before = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter2))
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, before.format)

        f.app.commits.revert(w.projectId, latestEntry(f, w, w.chapter2).historyId, approval)

        val after = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter2))
        assertEquals(cmFirst, after.content, "正文恢复为上一版")
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, after.format, "CM 历史版本回退后格式保持不变")

        val reader = f.app.reading.openChapter(w.novelId, null, w.chapter2)
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, reader.format)
        assertEquals(ControlledMarkdown.parse(cmFirst).document.blocks, reader.blocks, "Reader 与 P2 解析一致")
        assertEquals(0, ControlledMarkdown.parse(cmFirst).degradedCount)
        f.close()
    }

    /* ---------------- C. 边界：失败零写入 / 首个 COMMIT / 未知格式 ---------------- */

    @Test
    fun `revert failures leave canonical content format and row counts untouched`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val legacyId = legacyDraft(f, w.chapter1, "draft-legacy-ch1")

        f.app.commits.commitAuthorSave(legacyId, legacyBody)
        f.app.commits.commitAuthorSave(legacyId, legacyEdited)
        val latestBefore = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))
        val countsBefore = counts(f)

        // C1：目标历史不存在
        assertFailsWith<ApplicationException> {
            f.app.commits.revert(w.projectId, CommitHistoryId("missing-history"), approval)
        }
        // C2：冲突（当前内容已不是该历史的提交结果）
        val firstHistory = f.app.commits.history(w.projectId).first { it.target.chapterId == w.chapter1 }
        val conflict = assertFailsWith<ApplicationException> {
            f.app.commits.revert(w.projectId, firstHistory.historyId, approval)
        }
        assertTrue(conflict.message!!.contains("REVERT_CONFLICT"), "冲突必须类型化为 REVERT_CONFLICT：${conflict.message}")

        // 失败路径：当前 Draft 的 content / format 与行数均不被改动（无部分写入）
        val latestAfter = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))
        assertEquals(latestBefore.draftId, latestAfter.draftId)
        assertEquals(latestBefore.content, latestAfter.content)
        assertEquals(DraftFormat.PLAIN_TEXT, latestAfter.format, "失败的 revert 不得改动 format")
        assertEquals(countsBefore, counts(f), "失败的 revert 不得产生任何写入")
        f.close()
    }

    @Test
    fun `reverting the very first commit restores empty content with legacy format`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val legacyId = legacyDraft(f, w.chapter1, "draft-legacy-ch1")

        val first = f.app.commits.commitAuthorSave(legacyId, legacyBody)
        assertEquals(CommitFormatHistory.NONE, first.previousFormat, "首个 COMMIT 的 previous 侧 = NONE")
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, first.resultingFormat, "legacy 初始化的 resulting 侧 = KNOWN_LEGACY")

        f.app.commits.revert(w.projectId, first.historyId, approval)

        val after = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))
        assertEquals("", after.content, "首个 commit 的 revert 恢复到空正文")
        assertEquals(DraftFormat.PLAIN_TEXT, after.format, "空正文按 legacy 承载（冻结决策 D）")

        val reader = f.app.reading.openChapter(w.novelId, null, w.chapter1)
        assertTrue(reader.hasDraft, "仍存在已提交的 Canonical 正文载体")
        // legacy（format=null）路径对空正文的行为：单个空 Paragraph 块（既有契约；空块仅用于"无 Canonical"）
        assertEquals(1, reader.blocks.size)
        assertEquals("", (reader.blocks[0] as MarkdownBlock.Paragraph).text)
        f.close()
    }

    @Test
    fun `unknown previous format is refused with zero writes`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val legacyId = legacyDraft(f, w.chapter1, "draft-legacy-ch1")

        f.app.commits.commitAuthorSave(legacyId, legacyBody)
        f.app.commits.commitAuthorSave(legacyId, legacyEdited)

        // 模拟 v26 之前的旧行：previousFormat / resultingFormat = UNKNOWN（存储 NULL）
        historyRepo(f).append(
            f.app.commits.history(w.projectId).last { it.target.chapterId == w.chapter1 }.copy(
                historyId = CommitHistoryId("legacy-old-row"),
                previousFormat = CommitFormatHistory.UNKNOWN,
                resultingFormat = CommitFormatHistory.UNKNOWN,
            ),
        )
        val countsBefore = counts(f)
        val latestBefore = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))

        val ex = assertFailsWith<ApplicationException> {
            f.app.commits.revert(w.projectId, CommitHistoryId("legacy-old-row"), approval)
        }
        assertTrue(ex.message!!.contains("FORMAT_UNKNOWN"), "未知格式必须类型化拒绝：${ex.message}")

        val latestAfter = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter1))
        assertEquals(latestBefore.draftId, latestAfter.draftId)
        assertEquals(latestBefore.content, latestAfter.content)
        assertEquals(DraftFormat.PLAIN_TEXT, latestAfter.format, "未知格式拒绝不得改动 format")
        assertEquals(countsBefore, counts(f), "未知格式拒绝不得产生任何写入（无新 Draft / 无 REVERT History）")
        f.close()
    }

    /* ---------------- D. 各路径的格式历史记录语义 ---------------- */

    @Test
    fun `ai commit records cross format history and revert restores the legacy baseline`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)

        // 基线：把 chapter2 的 Canonical 换成 legacy 行（模拟 TXT 导入初始化的产物；时间戳晚于既有 CM 载体）
        val legacyId = legacyDraft(
            f, w.chapter2, "draft-legacy-ch2",
            createdAt = kotlinx.datetime.Instant.parse("2026-01-01T00:02:00Z"),
        )
        f.app.commits.commitAuthorSave(legacyId, legacyBody) // previous=NONE, resulting=KNOWN_LEGACY

        // AI / Artifact Commit：legacy 基线 → 新 Draft（stamp ⇒ CM）
        val aiCommit = f.app.commits.commit(w.projectId, artifactFor(f, w, cmSecond, baseDraftId = legacyId).artifactId, approval)
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, aiCommit.historyEntry.previousFormat, "被替换的 legacy 基线必须记录为 KNOWN_LEGACY")
        assertEquals(CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN, aiCommit.historyEntry.resultingFormat, "AI 新 Draft 被 stamp 为 CM")
        assertEquals(DraftFormat.CONTROLLED_MARKDOWN, f.app.draftRepository.getById(aiCommit.resultingDraftId)?.format)

        // revert：恢复 legacy 基线 ⇒ 新载体 format = null
        f.app.commits.revert(w.projectId, aiCommit.historyEntry.historyId, approval)
        val restored = assertNotNull(f.app.draftRepository.latestByChapter(w.chapter2))
        assertEquals(legacyBody, restored.content)
        assertEquals(DraftFormat.PLAIN_TEXT, restored.format, "跨格式 revert：恢复载体继承被恢复版本的 legacy 格式")

        // REVERT 条目自身：previous = 被替换的 CM 版本；resulting = 恢复的 legacy 版本
        val revertEntry = f.app.commits.history(w.projectId).last { it.target.chapterId == w.chapter2 }
        assertEquals(CommitFormatHistory.KNOWN_CONTROLLED_MARKDOWN, revertEntry.previousFormat)
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, revertEntry.resultingFormat)
        f.close()
    }

    @Test
    fun `author save on the same row records both sides from the row format`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val legacyId = legacyDraft(f, w.chapter1, "draft-legacy-ch1")

        val first = f.app.commits.commitAuthorSave(legacyId, legacyBody)
        assertEquals(CommitFormatHistory.NONE, first.previousFormat)
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, first.resultingFormat)
        assertEquals(DraftFormat.PLAIN_TEXT, f.app.draftRepository.getById(legacyId)?.format, "写入 History 不得改变 Draft.format")

        val second = f.app.commits.commitAuthorSave(legacyId, legacyEdited)
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, second.previousFormat, "同 row 上一版的格式 = 本行 format（不可变）")
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, second.resultingFormat)
        assertEquals(DraftFormat.PLAIN_TEXT, f.app.draftRepository.getById(legacyId)?.format)
        f.close()
    }

    @Test
    fun `txt canonical initialization records none and legacy format history`() {
        val f = commitFixture()

        val out = f.app.txts.importTxtAsOriginal(
            com.qianyan.engine.txt.TxtSource("第一章\n\n正文。".toByteArray(), "a.txt"),
            title = "TXT 初始化书",
        )

        val entry = f.app.commits.history(f.app.projects.projectOf(out.novelId).projectId).single()
        assertEquals(CommitFormatHistory.NONE, entry.previousFormat, "TXT 初始化首条历史的 previous 侧 = NONE（无前一版本）")
        assertEquals(CommitFormatHistory.KNOWN_LEGACY, entry.resultingFormat, "legacy 初始化的 resulting 侧 = KNOWN_LEGACY")
        val chapterId = f.app.chapters.listByNovel(out.novelId, null).first().chapterId
        assertEquals(DraftFormat.PLAIN_TEXT, f.app.draftRepository.latestByChapter(chapterId)?.format)
        f.close()
    }
}
