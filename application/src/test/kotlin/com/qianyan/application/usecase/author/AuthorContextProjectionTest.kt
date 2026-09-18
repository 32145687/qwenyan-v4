package com.qianyan.application.usecase.author

import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceOrigin
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AuthorPreferenceRepository
import com.qianyan.storage.repository.SqliteAuthorPreferenceRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P16 AIL-1 · AuthorContext Projection 测试：只投影稳定且激活偏好；Novel Override 最小合并。
 */
class AuthorContextProjectionTest {

    private val now: Instant = Clock.System.now()

    private fun repoAndProjection(): Pair<AuthorPreferenceRepository, AuthorContextProjection> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val repo = SqliteAuthorPreferenceRepository(db)
        return repo to AuthorContextProjection(repo, null, ErrorMapper)
    }

    private fun stable(dim: PreferenceDimension, stmt: String, id: String) = AuthorPreference(
        preferenceId = AuthorPreferenceId(id),
        scope = PreferenceScope.GLOBAL,
        dimension = dim,
        statement = stmt,
        origin = PreferenceOrigin.INFERRED,
        confidence = Confidence(0.7),
        confirmed = true,
        revocable = true,
        obtainedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    private fun candidate() = stable(PreferenceDimension.PACING, "候选慢节奏", "cand").copy(
        confirmed = false,
        origin = PreferenceOrigin.INFERRED,
        revocable = true,
    )

    private fun paused() = stable(PreferenceDimension.TONE, "暂停的偏好", "paused").copy(paused = true)

    private fun expired() = stable(PreferenceDimension.CONFLICT, "过期的偏好", "expired").copy(
        expiry = Instant.fromEpochSeconds(now.epochSeconds - 100, 0),
    )

    @Test
    fun `projects only stable active preferences`() {
        val (repo, p) = repoAndProjection()
        repo.upsertAuthorPreference(stable(PreferenceDimension.PACING, "稳定偏好", "s1"))
        repo.upsertAuthorPreference(candidate())
        repo.upsertAuthorPreference(paused())
        repo.upsertAuthorPreference(expired())

        val ctx = p.project(null)
        assertEquals(1, ctx.preferences.size, "只应投影稳定且激活偏好")
        assertEquals("s1", ctx.preferences.first().preferenceId.value)
    }

    @Test
    fun `novel preference overrides global on same dimension`() {
        val (repo, p) = repoAndProjection()
        val novel = NovelId("n1")
        repo.upsertAuthorPreference(stable(PreferenceDimension.PACING, "全局慢节奏", "g-pacing"))
        repo.upsertAuthorPreference(
            stable(PreferenceDimension.PACING, "本书高速推进", "n-pacing").copy(
                scope = PreferenceScope.NOVEL,
                novelId = novel,
            ),
        )

        val ctx = p.project(novel)
        assertEquals(listOf("n-pacing"), ctx.preferences.map { it.preferenceId.value }, "Novel Override 应覆盖全局")
    }

    @Test
    fun `global preserved when no novel override on that dimension`() {
        val (repo, p) = repoAndProjection()
        val novel = NovelId("n1")
        repo.upsertAuthorPreference(stable(PreferenceDimension.STRUCTURE, "全局偏好结构", "g-struct"))
        repo.upsertAuthorPreference(
            stable(PreferenceDimension.PACING, "本书高速推进", "n-pacing").copy(
                scope = PreferenceScope.NOVEL,
                novelId = novel,
            ),
        )

        val ctx = p.project(novel)
        assertEquals(2, ctx.preferences.size, "不同维度同时保留")
        assertEquals(
            setOf("g-struct", "n-pacing"),
            ctx.preferences.map { it.preferenceId.value }.toSet(),
        )
    }
}