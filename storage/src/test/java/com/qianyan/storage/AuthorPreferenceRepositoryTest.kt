package com.qianyan.storage

import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.AuthorProfile
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceOrigin
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.AuthorPreferenceRepository
import com.qianyan.storage.repository.SqliteAuthorPreferenceRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P16 AIL-1 · Author Intelligence 仓储测试（独立 Storage Boundary）。
 * 覆盖：Profile 单例 round-trip；Preference 的 Explicit/Inferred、确认位、置信度、暂停、过期、Global/Novel scope；
 * Evidence 写入/读取；删除。
 */
class AuthorPreferenceRepositoryTest {

    private val now: Instant = Instant.fromEpochSeconds(1789000000, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)

    private fun repo(db: com.qianyan.storage.db.QianyanDb): AuthorPreferenceRepository =
        SqliteAuthorPreferenceRepository(db)

    private fun profile() = AuthorProfile(
        profileId = AuthorProfileId("aut-1"),
        displayName = "沈千寻",
        createdAt = now,
        updatedAt = now,
    )

    private fun explicit() = AuthorPreference(
        preferenceId = AuthorPreferenceId("p-expl-1"),
        scope = PreferenceScope.GLOBAL,
        dimension = PreferenceDimension.ROMANCE,
        statement = "作者明确偏好HE结局",
        origin = PreferenceOrigin.EXPLICIT,
        confidence = Confidence.HIGH,
        confirmed = false,
        revocable = false,
        obtainedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    private fun candidate() = AuthorPreference(
        preferenceId = AuthorPreferenceId("p-cand-1"),
        scope = PreferenceScope.GLOBAL,
        dimension = PreferenceDimension.STORY_DIRECTION,
        statement = "可能的慢节奏倾向",
        origin = PreferenceOrigin.INFERRED,
        confidence = Confidence(0.6),
        confirmed = false,
        revocable = true,
        paused = false,
        expiry = Instant.fromEpochSeconds(1789000000 + 30 * 86400, 0),
        obtainedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    private fun novelPref() = explicit().copy(
        preferenceId = AuthorPreferenceId("p-novel-1"),
        scope = PreferenceScope.NOVEL,
        novelId = NovelId("n1"),
        dimension = PreferenceDimension.PACING,
        statement = "本书要求高速推进",
    )

    @Test
    fun `authorProfile insert get and single-row`() {
        val h = handle()
        val r = repo(h.db)
        assertNull(r.getAuthorProfile())
        r.upsertAuthorProfile(profile())
        assertEquals(profile(), r.getAuthorProfile())
        r.upsertAuthorProfile(profile().copy(displayName = "新名"))
        assertEquals("新名", r.getAuthorProfile()!!.displayName)
    }

    @Test
    fun `explicitPreference round-trips with origin confirmed revocable`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorPreference(explicit())
        val read = r.getAuthorPreference(AuthorPreferenceId("p-expl-1"))!!
        assertEquals(PreferenceOrigin.EXPLICIT, read.origin)
        assertTrue(read.isStable, "Explicit 天然 Stable")
        assertEquals(Confidence.HIGH, read.confidence)
        assertFalse(read.paused)
    }

    @Test
    fun `candidate round-trips with confidence and expiry`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorPreference(candidate())
        val read = r.getAuthorPreference(AuthorPreferenceId("p-cand-1"))!!
        assertTrue(read.isCandidate, "Inferred 未确认应为 Candidate")
        assertFalse(read.isStable)
        assertEquals(Confidence(0.6), read.confidence)
        assertNotNull(read.expiry)
        assertTrue(read.revocable)
    }

    @Test
    fun `novel scoped preference listed by novel`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorPreference(explicit())
        r.upsertAuthorPreference(novelPref())
        val novelList = r.listPreferences(novelId = NovelId("n1"))
        assertEquals(listOf(AuthorPreferenceId("p-novel-1")), novelList.map { it.preferenceId })
        val global = r.listPreferences(scope = PreferenceScope.GLOBAL)
        assertEquals(
            setOf(AuthorPreferenceId("p-expl-1")),
            global.map { it.preferenceId }.toSet(),
        )
    }

    @Test
    fun `preference update replaces row`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorPreference(candidate())
        val confirmed = candidate().copy(confirmed = true, expiry = null)
        r.upsertAuthorPreference(confirmed)
        val read = r.getAuthorPreference(AuthorPreferenceId("p-cand-1"))!!
        assertTrue(read.isStable, "确认后 Candidate → Stable")
        assertNull(read.expiry)
    }

    @Test
    fun `preference delete removes row`() {
        val h = handle()
        val r = repo(h.db)
        r.upsertAuthorPreference(candidate())
        assertNotNull(r.getAuthorPreference(AuthorPreferenceId("p-cand-1")))
        r.deleteAuthorPreference(AuthorPreferenceId("p-cand-1"))
        assertNull(r.getAuthorPreference(AuthorPreferenceId("p-cand-1")))
    }

    @Test
    fun `evidence insert and list by novel and all`() {
        val h = handle()
        val r = repo(h.db)
        val ev1 = AuthorEvidence(
            evidenceId = AuthorEvidenceId("e-1"),
            novelId = NovelId("n1"),
            type = AuthorEvidenceType.ADOPT,
            detail = "确认",
            source = "p15:foundation:gate",
            observedAt = now,
        )
        val ev2 = ev1.copy(evidenceId = AuthorEvidenceId("e-2"), type = AuthorEvidenceType.REJECT, detail = "拒绝")
        r.upsertAuthorEvidence(ev1)
        r.upsertAuthorEvidence(ev2)
        assertEquals(ev1, r.getAuthorEvidence(AuthorEvidenceId("e-1")))
        assertEquals(
            setOf(AuthorEvidenceId("e-1"), AuthorEvidenceId("e-2")),
            r.listEvidence().map { it.evidenceId }.toSet(),
        )
        assertEquals(2, r.listEvidence(novelId = NovelId("n1")).size)
        assertTrue(r.listEvidence(novelId = NovelId("n-x")).isEmpty())
    }

    @Test
    fun `author tables are independent of novel fk cascade`() {
        val h = handle()
        val r = repo(h.db)
        val n = NovelId("n-del")
        r.upsertAuthorPreference(explicit().copy(novelId = n, scope = PreferenceScope.NOVEL))
        r.upsertAuthorEvidence(AuthorEvidence(AuthorEvidenceId("e-1"), n, AuthorEvidenceType.ADOPT, "", "p15", now))
        // 直接删除不存在的 Novel：Author 表不应受影响（无 FK 依赖）
        h.driver.execute(null, "DELETE FROM Novel WHERE novel_id = 'n-del'", 0)
        assertNotNull(r.getAuthorEvidence(AuthorEvidenceId("e-1")), "AuthorEvidence 独立于 Novel 删除")
        assertEquals(1, r.listPreferences(novelId = n).size)
    }
}