package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.NarrativeDeltaId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.StoryConflictId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.lcl.NarrativeDelta
import com.qianyan.model.lcl.NarrativeStateFold
import com.qianyan.model.lcl.OpenThread
import com.qianyan.storage.db.QianyanDb
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import com.qianyan.storage.repository.SqliteNarrativeStateRepository
import com.qianyan.storage.repository.SqliteNovelRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Narrative State 账本仓储测试（P13 LCL-A）。
 * 覆盖：同一 scope 追加→折叠版本递增；快照可读取；不同 Variant / Novel 隔离；乐观锁；冲突引用往返。
 */
class NarrativeStateRepositoryTest {

    private val now: Instant = Instant.fromEpochSeconds(1787777777, 0)

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

    private fun delta(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        mainGoal: String = "",
        thread: String? = null,
        summary: String = "delta-$id",
    ) = NarrativeDelta(
        deltaId = NarrativeDeltaId(id),
        novelId = novel,
        variantId = variant,
        scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        chapterId = ChapterId("ch-$id"),
        mainGoal = mainGoal.ifEmpty { null },
        openThreads = if (thread == null) emptyList() else listOf(OpenThread("t-$id", thread)),
        summary = summary,
        createdAt = now,
    )

    @Test
    fun `append then project increments version and persists snapshot`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteNarrativeStateRepository(h.db)
        val novel = NovelId("n1")
        val v = VariantId("v1")

        assertNull(repo.getNarrativeState(novel, v), "无 Delta 时快照应为 null")

        repo.appendNarrativeDelta(delta("d1", novel, v, mainGoal = "寻找失窃灵石"))
        val s1 = repo.project(novel, v)
        assertEquals(1L, s1.version)
        assertEquals("寻找失窃灵石", s1.mainGoal)
        assertEquals(NarrativeStateFold.ledgerId(novel, v), s1.id)
        assertNotNull(repo.getNarrativeState(novel, v), "project 后 get 应可读到快照")

        repo.appendNarrativeDelta(delta("d2", novel, v, mainGoal = "查明灵石去向", thread = "神秘对手"))
        val s2 = repo.project(novel, v)
        assertEquals(2L, s2.version, "追加第二个 Delta 后版本应为 2")
        assertEquals("查明灵石去向", s2.mainGoal)
        assertEquals(1, s2.openThreads.size)
        assertEquals("神秘对手", s2.openThreads.single().description)
        assertEquals("delta-d2", s2.lastChapterDelta)
    }

    @Test
    fun `different variants and novels are isolated`() {
        val h = handle()
        seedNovel(h.db, "n1")
        seedNovel(h.db, "n2")
        val repo = SqliteNarrativeStateRepository(h.db)
        val n1 = NovelId("n1")
        val n2 = NovelId("n2")
        val v1 = VariantId("v1")
        val v2 = VariantId("v2")

        repo.appendNarrativeDelta(delta("d-v1", n1, v1, mainGoal = "主线A", thread = "线A"))
        repo.appendNarrativeDelta(delta("d-v2", n1, v2, mainGoal = "主线B"))

        // Delta 列表隔离
        assertEquals(listOf("d-v1"), repo.listNarrativeDeltas(n1, v1).map { it.deltaId.value })
        assertEquals(listOf("d-v2"), repo.listNarrativeDeltas(n1, v2).map { it.deltaId.value })
        assertEquals(0, repo.listNarrativeDeltas(n1, null).size, "Original 不应看到任何 Variant Delta")

        // 折叠后各 scope 状态互不影响
        val a = repo.project(n1, v1)
        val b = repo.project(n1, v2)
        val orig = repo.project(n1, null)
        assertEquals("主线A", a.mainGoal)
        assertEquals("主线B", b.mainGoal)
        assertEquals("", orig.mainGoal, "Original 无 Delta → 空主线")
        assertEquals(1L, a.version)
        assertEquals(1L, b.version)
        assertEquals(0L, orig.version)

        // 不同 Novel 互不可见
        assertEquals(0, repo.listNarrativeDeltas(n2, null).size)
    }

    @Test
    fun `optimistic lock rejects concurrent append on stale version`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteNarrativeStateRepository(h.db)
        val novel = NovelId("n1")
        val v = VariantId("v1")

        repo.appendNarrativeDelta(delta("d1", novel, v), expectedVersion = 0)
        assertFailsWith<IllegalStateException> {
            repo.appendNarrativeDelta(delta("d2", novel, v), expectedVersion = 0)
        }
        // 失败后不产生插入
        assertEquals(1, repo.listNarrativeDeltas(novel, v).size)

        // 使用正确版本可追加
        repo.appendNarrativeDelta(delta("d3", novel, v), expectedVersion = 1)
        assertEquals(2, repo.listNarrativeDeltas(novel, v).size)
    }

    @Test
    fun `current conflict reference persists and reads back`() {
        val h = handle()
        seedNovel(h.db, "n1")
        val repo = SqliteNarrativeStateRepository(h.db)
        val novel = NovelId("n1")
        val v = VariantId("v1")

        repo.appendNarrativeDelta(
            delta("d-conf", novel, v, mainGoal = "夺回圣器").copy(currentConflict = StoryConflictId("conf-9")),
        )
        val s = repo.project(novel, v)
        assertEquals(StoryConflictId("conf-9"), s.currentConflict)
        assertEquals(StoryConflictId("conf-9"), repo.getNarrativeState(novel, v)!!.currentConflict)
    }
}