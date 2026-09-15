package com.qianyan.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.core.VariantStatus
import com.qianyan.model.foundation.FoundationOverride
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNovelRepository
import com.qianyan.storage.repository.SqliteStoryFoundationRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story Foundation 仓储测试（P14-F.2）。
 * 覆盖：insert/get、update、delete；GenreId/StoryDirection/NarrativeProfile/WritingPolicy JSON 往返；
 * Override 的可空字段与 NULL=inherit 保持；同 Novel 单 Foundation / 同 Variant 单 Override；
 * Novel/NovelVariant 删除级联。
 */
class StoryFoundationRepositoryTest {

    private val now: Instant = Instant.fromEpochSeconds(1789000000, 0)

    private fun handle(): QianyanDbHandle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)

    private fun seedNovel(db: com.qianyan.storage.db.QianyanDb, novelId: String, scope: VariantScope = VariantScope.ORIGINAL) =
        SqliteNovelRepository(db).createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = scope, status = ProjectStatus.DRAFT,
                createdAt = now, updatedAt = now,
            ),
        )

    private fun seedVariant(db: com.qianyan.storage.db.QianyanDb, variantId: String, baseNovelId: NovelId): VariantId =
        SqliteNovelRepository(db).createVariant(
            NovelVariant(
                variantId = VariantId(variantId),
                novelId = baseNovelId,
                baseNovelId = BaseNovelId(baseNovelId.value),
                projectId = ProjectId("proj-$baseNovelId"),
                name = "改线",
                status = VariantStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )

    private fun foundation(novelId: String, genre: List<String> = listOf("romance", "fantasy_eastern")) = StoryFoundation(
        novelId = NovelId(novelId),
        baseNovelId = BaseNovelId(novelId),
        scope = VariantScope.ORIGINAL,
        version = 1L,
        genre = genre.map(::GenreId),
        direction = StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻"),
        audience = NarrativeProfile(pov = "第三人称", readerTone = "热血"),
        policy = WritingPolicy(listOf("不OOC", "章节紧凑")),
        createdAt = now,
        updatedAt = now,
    )

    private fun count(driver: app.cash.sqldelight.db.SqlDriver, table: String, where: String): Long =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table WHERE $where LIMIT 1",
            { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) },
            0,
        ).value

    @Test
    fun `storyFoundation insert and get round-trips`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        assertNull(repo.getStoryFoundation(NovelId("n1")), "未写入时应为 null")

        repo.upsertStoryFoundation(foundation("n1"))
        val read = repo.getStoryFoundation(NovelId("n1"))
        assertNotNull(read)
        assertEquals("n1", read.novelId.value)
        assertEquals(listOf<GenreId>(GenreId("romance"), GenreId("fantasy_eastern")), read.genre)
        assertEquals(StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻"), read.direction)
        assertEquals(NarrativeProfile(pov = "第三人称", readerTone = "热血"), read.audience)
        assertEquals(WritingPolicy(listOf("不OOC", "章节紧凑")), read.policy)
        assertTrue(repo.existsStoryFoundation(NovelId("n1")))
    }

    @Test
    fun `genre GenreId list json round-trips including value reconstruction`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertStoryFoundation(foundation("n1", genre = listOf("romance")))
        val read = repo.getStoryFoundation(NovelId("n1"))!!
        assertEquals(listOf(GenreId("romance")), read.genre, "GenreId 需以其 value 序列化并重构")
    }

    @Test
    fun `storyDirection round-trips`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        val dir = StoryDirection(theme = "战乱", conflict = "家国", promise = "归来", storyType = "历史")
        repo.upsertStoryFoundation(foundation("n1").copy(direction = dir))
        assertEquals(dir, repo.getStoryFoundation(NovelId("n1"))!!.direction)
    }

    @Test
    fun `narrativeProfile round-trips`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        val aud = NarrativeProfile(pov = "第一人称", readerTone = "细腻")
        repo.upsertStoryFoundation(foundation("n1").copy(audience = aud))
        assertEquals(aud, repo.getStoryFoundation(NovelId("n1"))!!.audience)
    }

    @Test
    fun `writingPolicy round-trips`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        val pol = WritingPolicy(listOf("禁止AI感", "黄金三章"))
        repo.upsertStoryFoundation(foundation("n1").copy(policy = pol))
        assertEquals(pol, repo.getStoryFoundation(NovelId("n1"))!!.policy)
    }

    @Test
    fun `storyFoundation update replaces row`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertStoryFoundation(foundation("n1").copy(version = 1L))
        val updated = foundation("n1").copy(version = 2L, genre = listOf(GenreId("romance")))
        repo.upsertStoryFoundation(updated)
        val read = repo.getStoryFoundation(NovelId("n1"))!!
        assertEquals(2L, read.version)
        assertEquals(listOf(GenreId("romance")), read.genre)
    }

    @Test
    fun `storyFoundation delete removes row`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertStoryFoundation(foundation("n1"))
        assertTrue(repo.existsStoryFoundation(NovelId("n1")))
        repo.deleteStoryFoundation(NovelId("n1"))
        assertFalse(repo.existsStoryFoundation(NovelId("n1")))
        assertNull(repo.getStoryFoundation(NovelId("n1")))
    }

    @Test
    fun `same novel cannot hold two foundations`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertStoryFoundation(foundation("n1").copy(genre = listOf(GenreId("a"))))
        repo.upsertStoryFoundation(foundation("n1").copy(genre = listOf(GenreId("b"))))
        assertEquals(1L, count(h.driver, "StoryFoundation", "novel_id='n1'"), "同 Novel 只能存在一个 Foundation（PK=novel_id）")
        assertEquals(listOf(GenreId("b")), repo.getStoryFoundation(NovelId("n1"))!!.genre, "后写覆盖前写")
    }

    @Test
    fun `foundationOverride insert and get round-trips`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        assertNull(repo.getFoundationOverride(VariantId("v1")))

        repo.upsertFoundationOverride(
            FoundationOverride(
                variantId = v,
                direction = StoryDirection(theme = "改线"),
                updatedAt = now,
            ),
        )
        val read = repo.getFoundationOverride(VariantId("v1"))
        assertNotNull(read)
        assertEquals(VariantId("v1"), read.variantId)
        assertEquals(StoryDirection(theme = "改线"), read.direction)
        assertTrue(repo.existsFoundationOverride(VariantId("v1")))
    }

    @Test
    fun `nullable override fields stay null after round-trip`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, updatedAt = now))
        val read = repo.getFoundationOverride(VariantId("v1"))!!
        assertNull(read.genre, "未覆盖字段必须保持 null（NULL = inherit）")
        assertNull(read.direction)
        assertNull(read.audience)
        assertNull(read.policy)
    }

    @Test
    fun `null override fields preserve inherit semantics`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        val override = FoundationOverride(
            variantId = v,
            genre = null,
            direction = null,
            audience = null,
            policy = null,
            updatedAt = now,
        )
        repo.upsertFoundationOverride(override)
        val read = repo.getFoundationOverride(VariantId("v1"))!!
        assertEquals(override, read, "全空 Override 读写一致：所有字段均为 null，不生成空对象")
    }

    @Test
    fun `foundationOverride update replaces row`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, policy = WritingPolicy(listOf("初始")), updatedAt = now))
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, policy = WritingPolicy(listOf("更新")), updatedAt = now))
        assertEquals(WritingPolicy(listOf("更新")), repo.getFoundationOverride(v)!!.policy)
    }

    @Test
    fun `foundationOverride delete restores inherit`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, genre = listOf(GenreId("romance")), updatedAt = now))
        assertTrue(repo.existsFoundationOverride(v))
        repo.deleteFoundationOverride(v)
        assertFalse(repo.existsFoundationOverride(v))
        assertNull(repo.getFoundationOverride(v), "删除 Override 后该 Variant 自然恢复继承 Original")
    }

    @Test
    fun `same variant cannot hold two overrides`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, policy = WritingPolicy(listOf("a")), updatedAt = now))
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, policy = WritingPolicy(listOf("b")), updatedAt = now))
        assertEquals(1L, count(h.driver, "FoundationOverride", "variant_id='v1'"), "同 Variant 只能存在一个 Override（PK=variant_id）")
        assertEquals(WritingPolicy(listOf("b")), repo.getFoundationOverride(v)!!.policy)
    }

    @Test
    fun `deleting novel cascades storyFoundation`() {
        val h = handle()
        // Original 删除受守卫触发器保护，本场景用 scope=VARIANT 的可删除 Novel 验证 FK CASCADE。
        h.driver.execute(
            null,
            "INSERT INTO Novel(novel_id, project_id, title, source, genre, synopsis, scope, status, created_at, updated_at) " +
                "VALUES ('nv-del', 'p-del', 'T', 'ORIGINAL_NOVEL', '[]', '', 'VARIANT', 'DRAFT', 0, 0)",
            0,
        )
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertStoryFoundation(foundation("nv-del"))
        assertTrue(repo.existsStoryFoundation(NovelId("nv-del")))

        h.driver.execute(null, "DELETE FROM Novel WHERE novel_id = 'nv-del'", 0)
        assertNull(repo.getStoryFoundation(NovelId("nv-del")), "Novel 删除后 StoryFoundation 应级联删除")
    }

    @Test
    fun `deleting variant cascades foundationOverride`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val v = seedVariant(h.db, "v1", NovelId("n1"))
        val repo = SqliteStoryFoundationRepository(h.db)
        repo.upsertFoundationOverride(FoundationOverride(variantId = v, updatedAt = now))
        assertTrue(repo.existsFoundationOverride(v))

        h.driver.execute(null, "DELETE FROM NovelVariant WHERE variant_id = 'v1'", 0)
        assertNull(repo.getFoundationOverride(VariantId("v1")), "NovelVariant 删除后 FoundationOverride 应级联删除")
    }
}