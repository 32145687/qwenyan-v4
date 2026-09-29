package com.qianyan.application.usecase.index

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant

/*
 * I12 测试夹具（真实内存 SQLite + ApplicationContainer；数据全真实）。
 *
 * 全部使用固定 ID / 固定时间戳：索引构建与查询的结果不得依赖 UUID / 当前时间 / Map 迭代序。
 */

internal val INDEX_FIXED_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal class IndexFixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
    fun close() = handle.driver.close()
}

internal fun indexFixture(): IndexFixture {
    val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
    return IndexFixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
}

/** 一个真实 Project：Novel + 2 章 + 每章当前 Canonical 正文 + 故事基础 + 词汇候选。 */
internal class IndexWorld(
    val novelId: NovelId,
    val projectId: ProjectId,
    val chapter1: ChapterId,
    val chapter2: ChapterId,
    val draft1: DraftId,
    val draft2: DraftId,
    val vocabularyCandidateId: VocabularyCandidateId,
)

internal fun seedIndexWorld(
    f: IndexFixture,
    withFoundation: Boolean = true,
    withVocabulary: Boolean = true,
    secondChapterDraft: Boolean = true,
): IndexWorld {
    val novelId = f.app.novels.createOriginal(title = "索引之书", genre = listOf("仙侠"))
    val chapter1 = f.app.chapters.createNextChapter("入山", novelId).chapterId
    val chapter2 = f.app.chapters.createNextChapter("问道", novelId).chapterId

    val draft1 = DraftId("d-index-1")
    f.app.draftRepository.save(draft(draft1, novelId, chapter1, "第一章正文（短）。"))
    val draft2 = DraftId("d-index-2")
    if (secondChapterDraft) {
        f.app.draftRepository.save(draft(draft2, novelId, chapter2, "第二章正文（短）。"))
    }

    if (withFoundation) {
        f.app.storyFoundationRepository.upsertStoryFoundation(
            StoryFoundation(
                novelId = novelId,
                baseNovelId = BaseNovelId(novelId.value),
                version = 3L,
                genre = listOf(GenreId("xianxia")),
                createdAt = INDEX_FIXED_INSTANT,
                updatedAt = INDEX_FIXED_INSTANT,
            ),
        )
    }

    val candidateId = VocabularyCandidateId("vc-index-1")
    if (withVocabulary) {
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(novelId)
        f.app.vocabularyRepository.saveCandidate(
            VocabularyCandidate(
                candidateId = candidateId,
                vocabularyId = vocabularyId,
                novelId = novelId,
                scopeLevel = VocabularyScopeLevel.NOVEL,
                suggested = VocabularyEntry(
                    entryId = VocabularyEntryId("ve-index-1"),
                    vocabularyId = vocabularyId,
                    novelId = novelId,
                    canonical = "灵石",
                    aliases = listOf("灵玉"),
                ),
                source = VocabularyCandidateSource.AUTO_EXTRACT,
                status = VocabularyCandidateStatus.PENDING,
                createdAt = INDEX_FIXED_INSTANT,
            ),
        )
    }

    // 建立运行态（ProjectState 是 projectOf(projectId) 的前提；索引本身不读它）
    f.app.projects.selectChapter(novelId, chapter1)
    return IndexWorld(
        novelId = novelId,
        projectId = f.app.projects.projectOf(novelId).projectId,
        chapter1 = chapter1,
        chapter2 = chapter2,
        draft1 = draft1,
        draft2 = draft2,
        vocabularyCandidateId = candidateId,
    )
}

private fun draft(id: DraftId, novelId: NovelId, chapterId: ChapterId, content: String): Draft = Draft(
    draftId = id,
    novelId = novelId,
    chapterId = chapterId,
    content = content,
    format = DraftFormat.CONTROLLED_MARKDOWN,
    status = DraftStatus.WRITTEN,
    createdAt = INDEX_FIXED_INSTANT,
    updatedAt = INDEX_FIXED_INSTANT,
)

internal fun countRows(handle: QianyanDbHandle, table: String): Long =
    handle.driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM $table",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value