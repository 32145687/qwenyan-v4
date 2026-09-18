package com.qianyan.application.usecase.author

import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.AuthorCoreId
import com.qianyan.model.AuthorCorePatternId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorCore
import com.qianyan.model.author.AuthorCorePattern
import com.qianyan.model.author.AuthorCoreScope
import com.qianyan.model.author.AuthorCoreStatus
import com.qianyan.model.author.Confidence
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.AuthorCoreRepository
import com.qianyan.storage.repository.AuthorPreferenceRepository
import com.qianyan.storage.repository.SqliteAuthorCoreRepository
import com.qianyan.storage.repository.SqliteAuthorPreferenceRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P17 · AuthorContext Core Lite 投影测试：只投 STABLE；Novel 覆盖 Global；暂停隐藏；防泄漏。
 */
class AuthorCoreProjectionTest {

    private val now = Clock.System.now()

    private fun setup(): Pair<AuthorPreferenceRepository, AuthorCoreRepository> {
        val db = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db
        return SqliteAuthorPreferenceRepository(db) to SqliteAuthorCoreRepository(db)
    }

    private fun stableCore(repo: AuthorCoreRepository, key: String, scope: AuthorCoreScope, novelId: NovelId? = null, statement: String = "决策倾向-$key") {
        repo.upsertAuthorCore(
            AuthorCore(
                coreId = AuthorCoreId("core-${scope.name}-$key"),
                scope = scope,
                novelId = novelId,
                status = AuthorCoreStatus.STABLE,
                confirmed = true,
                confidence = Confidence(0.8),
                corePatternKey = key,
                createdAt = now,
                updatedAt = now,
            ),
        )
        repo.upsertAuthorCorePattern(
            AuthorCorePattern(
                patternId = AuthorCorePatternId("pat-${scope.name}-$key"),
                patternKey = key,
                statement = statement,
                condition = if (scope == AuthorCoreScope.NOVEL) "本书适用" else null,
                scope = scope,
                novelId = novelId,
                status = AuthorCoreStatus.STABLE,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `projects stable global core as minimal lite`() {
        val (prefRepo, coreRepo) = setup()
        stableCore(coreRepo, "core:foundation", AuthorCoreScope.GLOBAL, statement = "作者稳定地逐步揭示关键信息")
        val p = AuthorContextProjection(prefRepo, coreRepo, ErrorMapper)

        val ctx = p.project(NovelId("n1"))
        assertEquals(1, ctx.cores.size)
        val lite = ctx.cores.first()
        assertEquals("core:foundation", lite.patternKey)
        assertEquals("作者稳定地逐步揭示关键信息", lite.statement)
        assertTrue(lite.confidence.value > 0)
        assertEquals(AuthorCoreScope.GLOBAL, lite.scope)
        // 防泄漏：AuthorCoreLite 仅含 patternKey/statement/confidence/scope/condition（结构层面已保证）
    }

    @Test
    fun `novel core overrides global on same pattern`() {
        val (prefRepo, coreRepo) = setup()
        val novel = NovelId("n1")
        stableCore(coreRepo, "core:foundation", AuthorCoreScope.GLOBAL, statement = "全局慢热")
        stableCore(coreRepo, "core:foundation", AuthorCoreScope.NOVEL, novelId = novel, statement = "本书节奏更快")

        val ctx = AuthorContextProjection(prefRepo, coreRepo, ErrorMapper).project(novel)
        assertEquals(1, ctx.cores.size)
        assertEquals(AuthorCoreScope.NOVEL, ctx.cores.first().scope, "本书覆盖全局")
        assertEquals("本书节奏更快", ctx.cores.first().statement)
    }

    @Test
    fun `paused learning hides cores`() {
        val (prefRepo, coreRepo) = setup()
        stableCore(coreRepo, "core:foundation", AuthorCoreScope.GLOBAL)
        coreRepo.setLearningPaused(true)
        val ctx = AuthorContextProjection(prefRepo, coreRepo, ErrorMapper).project(null)
        assertTrue(ctx.cores.isEmpty(), "暂停学习时不投影 Core")
    }
}