package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.story.ForeshadowLifecycleState
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteStoryStateRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P13 LCL-C · Foreshadow 生命周期持久化：条件 UPDATE 影响行数语义 + state/resolved 双字段一致。
 */
class ForeshadowLifecyclePersistenceTest {

    private val now: Instant = Instant.fromEpochSeconds(1787999000, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)

    private fun seedNovel(db: QianyanDb, novelId: String) {
        SqliteNovelRepository(db).createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now, updatedAt = now,
            ),
        )
    }

    private fun foreshadow(id: String, novel: NovelId, variant: VariantId?, state: ForeshadowLifecycleState) = Foreshadow(
        foreshadowId = ForeshadowingId(id), novelId = novel, variantId = variant,
        scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        chapterId = ChapterId("ch"), content = "c-$id", state = state, createdAt = now, updatedAt = now,
    )

    @Test
    fun `state resolved double-field consistency on roundtrip`() {
        val h = handle(); seedNovel(h.db, "n1")
        val repo = SqliteStoryStateRepository(h.db); val novel = NovelId("n1"); val v = VariantId("v1")

        repo.saveForeshadow(foreshadow("f-planted", novel, v, ForeshadowLifecycleState.PLANTED))
        assertEquals(ForeshadowLifecycleState.PLANTED, repo.getForeshadowById(ForeshadowingId("f-planted"))!!.state)
        assertTrue(!repo.getForeshadowById(ForeshadowingId("f-planted"))!!.resolved)

        repo.saveForeshadow(foreshadow("f-resolved", novel, v, ForeshadowLifecycleState.RESOLVED))
        assertTrue(repo.getForeshadowById(ForeshadowingId("f-resolved"))!!.resolved, "RESOLVED → resolved=true")

        repo.saveForeshadow(foreshadow("f-abandoned", novel, v, ForeshadowLifecycleState.ABANDONED))
        assertTrue(repo.getForeshadowById(ForeshadowingId("f-abandoned"))!!.resolved, "ABANDONED → resolved=true")

        repo.saveForeshadow(foreshadow("f-active", novel, v, ForeshadowLifecycleState.ACTIVE))
        assertTrue(!repo.getForeshadowById(ForeshadowingId("f-active"))!!.resolved, "ACTIVE → resolved=false")
    }

    @Test
    fun `conditional update succeeds only when expectedState matches`() {
        val h = handle(); seedNovel(h.db, "n1")
        val repo = SqliteStoryStateRepository(h.db); val novel = NovelId("n1"); val v = VariantId("v1")

        repo.saveForeshadow(foreshadow("f", novel, v, ForeshadowLifecycleState.PLANTED))
        // 1) 成功：expectedState 匹配 → affect 1
        assertEquals(1, repo.transitionForeshadowState(
            ForeshadowingId("f"), ForeshadowLifecycleState.PLANTED, ForeshadowLifecycleState.ACTIVE, "reason", now, null,
        ))
        assertEquals(ForeshadowLifecycleState.ACTIVE, repo.getForeshadowById(ForeshadowingId("f"))!!.state)

        // 2) expectedState 不匹配（当前已 ACTIVE，仍给 PLANTED）→ affect 0
        assertEquals(0, repo.transitionForeshadowState(
            ForeshadowingId("f"), ForeshadowLifecycleState.PLANTED, ForeshadowLifecycleState.RESOLVED, null, now, null,
        ))

        // 3) 不存在 ID → affect 0
        assertEquals(0, repo.transitionForeshadowState(
            ForeshadowingId("missing"), ForeshadowLifecycleState.PLANTED, ForeshadowLifecycleState.ACTIVE, null, now, null,
        ))
    }

    @Test
    fun `resolved state stores payoff chapter`() {
        val h = handle(); seedNovel(h.db, "n1")
        val repo = SqliteStoryStateRepository(h.db); val novel = NovelId("n1"); val v = VariantId("v1")
        repo.saveForeshadow(foreshadow("f", novel, v, ForeshadowLifecycleState.PLANTED))
        repo.transitionForeshadowState(ForeshadowingId("f"), ForeshadowLifecycleState.PLANTED, ForeshadowLifecycleState.ACTIVE, null, now, null)
        repo.transitionForeshadowState(ForeshadowingId("f"), ForeshadowLifecycleState.ACTIVE, ForeshadowLifecycleState.RESOLVED, "兑现", now, ChapterId("p1"))
        val f = repo.getForeshadowById(ForeshadowingId("f"))!!
        assertEquals(ForeshadowLifecycleState.RESOLVED, f.state)
        assertEquals(ChapterId("p1"), f.payoffChapterId)
        assertEquals("兑现", f.lastTransitionReason)
        assertEquals(now, f.updatedAt)
    }
}