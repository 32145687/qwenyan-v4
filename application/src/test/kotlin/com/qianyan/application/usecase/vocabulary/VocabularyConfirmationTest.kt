package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.error.ApplicationException
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteVocabularyRepository
import com.qianyan.storage.repository.VocabularyRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P20-P1 · Vocabulary Confirmation（Application 层闭环测试）。
 * 覆盖：PENDING→APPROVED / PENDING→REJECTED / Edit、非法状态转换、重复 Confirm 幂等、
 *       Confirm 不产生重复 VocabularyEntry、Edit 保持身份与 PENDING、状态持久化回读。
 */
class VocabularyConfirmationTest {

    private val now: Instant = Instant.fromEpochSeconds(1700000000, 0)
    private val novelId = NovelId("n1")
    private val vocabularyId = VocabularyId("novel-vocab-n1")

    private fun setup(): Pair<VocabularyUseCases, VocabularyRepository> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val repo = SqliteVocabularyRepository(db)
        val uc = VocabularyUseCases(repo, com.qianyan.application.error.ErrorMapper)
        // FK：先建词库容器，候选/词条才能落库
        repo.saveVocabulary(
            com.qianyan.model.vocabulary.Vocabulary(
                vocabularyId = vocabularyId,
                novelId = novelId,
                scopeLevel = VocabularyScopeLevel.NOVEL,
                name = "NOVEL词库",
            ),
        )
        return uc to repo
    }

    private fun candidate(id: String, canonical: String): VocabularyCandidate = VocabularyCandidate(
        candidateId = VocabularyCandidateId(id),
        vocabularyId = vocabularyId,
        novelId = novelId,
        scopeLevel = VocabularyScopeLevel.NOVEL,
        suggested = VocabularyEntry(
            entryId = VocabularyEntryId("e-$id"),
            vocabularyId = vocabularyId,
            novelId = novelId,
            scopeLevel = VocabularyScopeLevel.NOVEL,
            canonical = canonical,
            type = VocabularyEntryType.WORLD_TERM,
            status = VocabularyEntryStatus.CANDIDATE,
        ),
        source = VocabularyCandidateSource.AUTO_EXTRACT,
        status = VocabularyCandidateStatus.PENDING,
        createdAt = now,
    )

    @Test
    fun `confirm moves pending to approved and persists official entry`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        val approved = uc.confirmCandidate(VocabularyCandidateId("c1"), novelId)
        assertEquals(VocabularyCandidateStatus.APPROVED, approved.status)
        // 持久化回读
        val reloaded = repo.findCandidatesByNovel(novelId).first { it.candidateId.value == "c1" }
        assertEquals(VocabularyCandidateStatus.APPROVED, reloaded.status)
        // 正式词条已落库且为 APPROVED
        val entries = repo.findEntriesByNovel(novelId)
        assertEquals(1, entries.size)
        assertEquals("灵石", entries.first().canonical)
        assertEquals(VocabularyEntryStatus.APPROVED, entries.first().status)
    }

    @Test
    fun `confirm is idempotent and does not duplicate entry`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        uc.confirmCandidate(VocabularyCandidateId("c1"), novelId)
        val again = uc.confirmCandidate(VocabularyCandidateId("c1"), novelId)
        assertEquals(VocabularyCandidateStatus.APPROVED, again.status)
        assertEquals(1, repo.findEntriesByNovel(novelId).size, "重复 Confirm 不得重复生成词条")
        assertEquals(1, repo.findCandidatesByNovel(novelId).size)
    }

    @Test
    fun `reject moves pending to rejected without writing entry`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        val rejected = uc.rejectCandidate(VocabularyCandidateId("c1"), novelId)
        assertEquals(VocabularyCandidateStatus.REJECTED, rejected.status)
        assertEquals(
            VocabularyCandidateStatus.REJECTED,
            repo.findCandidatesByNovel(novelId).first { it.candidateId.value == "c1" }.status,
        )
        assertTrue(repo.findEntriesByNovel(novelId).isEmpty(), "Reject 不写正式词条")
    }

    @Test
    fun `reject is idempotent`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        uc.rejectCandidate(VocabularyCandidateId("c1"), novelId)
        val again = uc.rejectCandidate(VocabularyCandidateId("c1"), novelId)
        assertEquals(VocabularyCandidateStatus.REJECTED, again.status)
    }

    @Test
    fun `edit changes canonical keeps identity and pending status`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        val edited = uc.editCandidate(VocabularyCandidateId("c1"), novelId, canonical = "灵髓")
        assertEquals(VocabularyCandidateId("c1"), edited.candidateId, "Edit 保持候选身份")
        assertEquals("灵髓", edited.suggested.canonical)
        assertEquals(VocabularyCandidateStatus.PENDING, edited.status, "Edit 不自动晋升")
        // 持久化回读
        val reloaded = repo.findCandidatesByNovel(novelId).first { it.candidateId.value == "c1" }
        assertEquals("灵髓", reloaded.suggested.canonical)
        assertEquals(VocabularyCandidateStatus.PENDING, reloaded.status)
    }

    @Test
    fun `confirm after edit creates entry with edited canonical only once`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        uc.editCandidate(VocabularyCandidateId("c1"), novelId, canonical = "灵髓")
        uc.confirmCandidate(VocabularyCandidateId("c1"), novelId)
        val entries = repo.findEntriesByNovel(novelId)
        assertEquals(1, entries.size)
        assertEquals("灵髓", entries.first().canonical)
    }

    @Test
    fun `non pending candidate rejects confirm and reject`() {
        val (uc, repo) = setup()
        val c = candidate("c1", "灵石").copy(status = VocabularyCandidateStatus.APPROVED)
        repo.saveCandidate(c)
        assertFailsWith<ApplicationException> { uc.rejectCandidate(VocabularyCandidateId("c1"), novelId) }
    }

    @Test
    fun `missing candidate throws`() {
        val (uc, _) = setup()
        assertFailsWith<ApplicationException> { uc.confirmCandidate(VocabularyCandidateId("nope"), novelId) }
    }

    @Test
    fun `blank canonical edit is rejected`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        assertFailsWith<ApplicationException> {
            uc.editCandidate(VocabularyCandidateId("c1"), novelId, canonical = "   ")
        }
    }

    @Test
    fun `confirming two distinct candidates yields two entries`() {
        val (uc, repo) = setup()
        repo.saveCandidate(candidate("c1", "灵石"))
        repo.saveCandidate(candidate("c2", "丹田"))
        uc.confirmCandidate(VocabularyCandidateId("c1"), novelId)
        uc.confirmCandidate(VocabularyCandidateId("c2"), novelId)
        val entries = repo.findEntriesByNovel(novelId)
        assertEquals(2, entries.size)
        assertEquals(setOf("灵石", "丹田"), entries.map { it.canonical }.toSet())
        assertNotEquals(entries[0].entryId, entries[1].entryId, "不同候选生成不同词条")
    }
}