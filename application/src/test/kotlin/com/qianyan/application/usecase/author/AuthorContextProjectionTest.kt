package com.qianyan.application.usecase.author

import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorCorePatternId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCore
import com.qianyan.model.author.AuthorCorePattern
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceOrigin
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.AuthorPreferenceRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
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

    // =============== DEC-P18-008：project(null) → GLOBAL only ===============

    private fun coreProjection(): Pair<AuthorCoreRepository, AuthorContextProjection> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        val coreRepo: AuthorCoreRepository = SqliteAuthorCoreRepository(db)
        val prefRepo = SqliteAuthorPreferenceRepository(db)
        return coreRepo to AuthorContextProjection(prefRepo, coreRepo, ErrorMapper)
    }

    private fun seedStableCore(repo: AuthorCoreRepository, coreId: String, scope: AuthorCoreScope, novelId: NovelId?, stmt: String) {
        val pattern = AuthorCorePattern(
            patternId = AuthorCorePatternId("pat-$coreId"),
            patternKey = "core:foundation",
            statement = stmt,
            condition = null,
            scope = scope,
            novelId = novelId,
            version = 1L,
            confidence = Confidence(0.8),
            status = AuthorCoreStatus.STABLE,
            evidenceRefs = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        repo.upsertAuthorCorePattern(pattern)
        repo.upsertAuthorCore(
            AuthorCore(
                coreId = AuthorCoreId(coreId),
                version = 1L,
                scope = scope,
                novelId = novelId,
                status = AuthorCoreStatus.STABLE,
                confirmed = true,
                confidence = Confidence(0.8),
                corePatternKey = "core:foundation",
                patternId = pattern.patternId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `project null returns only GLOBAL core even when novel cores exist`() {
        val (coreRepo, p) = coreProjection()
        seedStableCore(coreRepo, "g", AuthorCoreScope.GLOBAL, null, "全局倾向")
        seedStableCore(coreRepo, "a", AuthorCoreScope.NOVEL, NovelId("n-a"), "A 书倾向")

        val ctx = p.project(null)
        assertEquals(1, ctx.cores.size, "null 上下文只应投影 GLOBAL")
        assertEquals(AuthorCoreScope.GLOBAL, ctx.cores.first().scope)
        assertEquals("全局倾向", ctx.cores.first().statement)
    }

    @Test
    fun `project novelA returns novelA over global`() {
        val (coreRepo, p) = coreProjection()
        seedStableCore(coreRepo, "g", AuthorCoreScope.GLOBAL, null, "全局倾向")
        seedStableCore(coreRepo, "a", AuthorCoreScope.NOVEL, NovelId("n-a"), "A 书覆盖")

        val ctx = p.project(NovelId("n-a"))
        assertEquals(1, ctx.cores.size)
        assertEquals(AuthorCoreScope.NOVEL, ctx.cores.first().scope)
        assertEquals("A 书覆盖", ctx.cores.first().statement)
    }

    @Test
    fun `project novelB returns global core when no novelB override`() {
        val (coreRepo, p) = coreProjection()
        seedStableCore(coreRepo, "g", AuthorCoreScope.GLOBAL, null, "全局倾向")
        seedStableCore(coreRepo, "a", AuthorCoreScope.NOVEL, NovelId("n-a"), "A 书倾向")

        val ctx = p.project(NovelId("n-b"))
        assertEquals(1, ctx.cores.size, "无 novelB override 时回退 GLOBAL")
        assertEquals(AuthorCoreScope.GLOBAL, ctx.cores.first().scope)
    }
}