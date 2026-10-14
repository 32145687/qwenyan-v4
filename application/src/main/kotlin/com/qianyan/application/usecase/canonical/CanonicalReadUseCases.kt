package com.qianyan.application.usecase.canonical

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.ChapterId
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.writing.Draft
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.CommitHistoryRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.NovelRepository

/**
 * P1-01b · Canonical Read（**正式 Canonical 正文读取入口**；R3 / §23B）。
 *
 * 读取职责分离（R3 决策）：
 * ```
 * CanonicalRead                      → 已 Commit 的 Canonical 正文（小说事实基线）
 * DraftRepository.latestByChapter    → Draft / Working Draft（编辑中的内容）
 * ```
 *
 * 事实来源：**只经既有 I10 `CommitHistory`**（`resultingDraftId`）定位已提交的正文载体，
 * 再经既有 I8 `DraftRepository.getById` 取回正文 —— **不复制正文、不新增表、不新建 Canonical 模型**。
 *
 * 硬边界：
 *  - **不使用 `latestByChapter`**：最新 Draft ≠ Canonical（R3 明确禁止把未提交 Draft 当作已发生事实）；
 *  - **不读 Working Draft / 未过 Human Gate 的 Change**：两者都不产生 CommitHistory 记录；
 *  - **只读**：不写任何状态，不创建 / 修改 Commit、Change、Draft；
 *  - 章节不存在 → `ApplicationError.EntityNotFound`；章节存在但从未提交 → `null`（两者可区分）。
 */
class CanonicalReadUseCases(
    private val chapters: ChapterRepository,
    private val novels: NovelRepository,
    private val history: CommitHistoryRepository,
    private val drafts: DraftRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 该章节**已提交**的 Canonical 正文载体（最新一次 Commit 的 `resultingDraftId`）。
     *
     * @return 从未提交 → null（不代表"无正文"，只代表"尚不是 Canonical"）。
     * @throws ApplicationError.EntityNotFound 章节或其 Novel 不存在。
     */
    fun committedDraft(chapterId: ChapterId): Draft? {
        val entry = latestCommittedEntry(chapterId) ?: return null
        return guard { drafts.getById(entry.resultingDraftId) }
    }

    /**
     * 该章节**已提交**的 Canonical 正文；从未提交 → null。
     *
     * @throws ApplicationError.EntityNotFound 章节或其 Novel 不存在。
     */
    fun committedContent(chapterId: ChapterId): String? = committedDraft(chapterId)?.content

    /* ---------------- internals ---------------- */

    /**
     * 该章节最新一条 Commit 历史。
     *
     * 复用既有 `listByProject`（**插入序** = 真实发生时序，按 rowid，不依赖时间戳精度），
     * 取其中 `target.chapterId` 匹配的最后一条 ⇒ 同时覆盖 COMMIT 与 REVERT（Revert 也产生新记录）。
     */
    private fun latestCommittedEntry(chapterId: ChapterId): CommitHistoryEntry? {
        val chapter = guard { chapters.findById(chapterId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"),
            )
        val novel = guard { novels.getNovel(chapter.novelId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("Novel 不存在: ${chapter.novelId.value}"),
            )
        return guard { history.listByProject(novel.projectId) }
            .lastOrNull { it.target.chapterId == chapterId }
    }
}
