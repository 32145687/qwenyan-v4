package com.qianyan.application.usecase.context

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
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/*
 * I6 测试夹具（真实内存 SQLite + ApplicationContainer，与 I5 同构）。
 *
 * 全部使用**固定时间戳 / 固定 ID**：I6 测试不依赖 System.currentTimeMillis / Instant.now / UUID 决定结果（§27）。
 */

internal class ContextFixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
    fun close() = handle.driver.close()
}

internal fun contextFixture(): ContextFixture {
    val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
    return ContextFixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
}

/** 一个真实 Project：Novel + 2 章 + 草稿 + StoryFoundation + 词汇候选 + 运行态。 */
internal class SeededProject(
    val novelId: NovelId,
    val projectId: ProjectId,
    val chapter1: ChapterId,
    val chapter2: ChapterId,
    val draftId: DraftId,
    val candidateId: VocabularyCandidateId,
)

internal fun seedProject(f: ContextFixture, title: String, key: String): SeededProject {
    val now = Instant.parse("2026-01-01T00:00:00Z")
    val novelId = f.app.novels.createOriginal(title = title)
    val chapter1 = f.app.chapters.createNextChapter("$key-1", novelId).chapterId
    val chapter2 = f.app.chapters.createNextChapter("$key-2", novelId).chapterId

    val draftId = DraftId("draft-$key-2")
    f.app.draftRepository.save(
        Draft(
            draftId = draftId,
            novelId = novelId,
            chapterId = chapter2,
            content = "$key 第二章正文",
            status = DraftStatus.WRITTEN,
            createdAt = now,
            updatedAt = now,
        ),
    )

    f.app.storyFoundationRepository.upsertStoryFoundation(
        StoryFoundation(
            novelId = novelId,
            baseNovelId = BaseNovelId(novelId.value),
            genre = listOf(GenreId("genre-$key")),
            direction = StoryDirection(theme = "$key 主题", conflict = "$key 冲突"),
            audience = NarrativeProfile(pov = "第三人称"),
            policy = WritingPolicy(rules = listOf("$key 规则")),
            createdAt = now,
            updatedAt = now,
        ),
    )

    val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(novelId)
    val candidateId = VocabularyCandidateId("vc-$key")
    f.app.vocabularyRepository.saveCandidate(
        VocabularyCandidate(
            candidateId = candidateId,
            vocabularyId = vocabularyId,
            novelId = novelId,
            suggested = VocabularyEntry(
                entryId = VocabularyEntryId("ve-$key"),
                vocabularyId = vocabularyId,
                novelId = novelId,
                canonical = "$key 苏清",
                aliases = listOf("$key 苏青"),
                type = VocabularyEntryType.CHARACTER_APPELLATION,
            ),
            createdAt = now,
        ),
    )

    f.app.projects.selectChapter(novelId, chapter2)
    return SeededProject(novelId, f.app.projects.projectOf(novelId).projectId, chapter1, chapter2, draftId, candidateId)
}

/** 固定时钟的 Context Engine（确定性 / Snapshot 测试用；容器默认时钟不参与选择与版本指纹）。 */
internal fun fixedClockEngine(
    f: ContextFixture,
    instant: Instant = Instant.parse("2026-01-01T00:00:00Z"),
): ContextEngineUseCases = ContextEngineUseCases(
    projects = f.app.projects,
    chapters = f.app.chapters,
    sessions = f.app.agentSessions,
    sources = defaultContextSources(
        projects = f.app.projects,
        novels = f.app.novels,
        chapters = f.app.chapters,
        writer = f.app.writerUseCases,
        vocabularies = f.app.vocabularies,
        foundations = f.app.storyFoundationRepository,
    ),
    clock = object : Clock {
        override fun now(): Instant = instant
    },
    errorMapper = f.app.errorMapper,
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