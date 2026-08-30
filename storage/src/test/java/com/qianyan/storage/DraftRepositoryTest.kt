package com.qianyan.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteDraftRepository
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P11.3 Draft（ChapterDraft 表）持久化测试（storage）。
 * 每个测试使用独立内存数据库（IN_MEMORY），互不影响。
 */
class DraftRepositoryTest {

    private fun handle(url: String = JdbcSqliteDriver.IN_MEMORY) = QianyanDbFactory.open(url)

    private fun randomId(prefix: String) = "$prefix-${Random.nextLong().toString(16)}"

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private fun makeDraft(
        draftId: String = randomId("draft"),
        novelId: String = "novel-1",
        variantId: String? = null,
        chapterId: String? = null,
        planId: String? = null,
        content: String = "正文内容",
        status: DraftStatus = DraftStatus.WRITTEN,
    ) = Draft(
        draftId = DraftId(draftId),
        novelId = NovelId(novelId),
        variantId = variantId?.let { VariantId(it) },
        scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        chapterId = chapterId?.let { ChapterId(it) },
        planId = planId?.let { ChapterPlanId(it) },
        content = content,
        status = status,
        sourceModel = "mock-v1",
        createdAt = fixed,
        updatedAt = fixed,
    )

    /* 1. save → getById：保存后可读回等价 Draft */
    @Test
    fun `save then getById returns equivalent draft`() {
        val repo = SqliteDraftRepository(handle().db)
        val draft = makeDraft(
            chapterId = "ch-1",
            planId = "plan-1",
            content = "第一章正文",
            status = DraftStatus.WRITTEN,
        )
        repo.save(draft)

        val read = repo.getById(draft.draftId)
        assertNotNull(read)
        assertEquals(draft, read)
    }

    /* 2. listByNovel：列出该 Novel 下全部 Draft（升序），其它 Novel 互不影响 */
    @Test
    fun `listByNovel returns drafts of the novel ordered by creation`() {
        val repo = SqliteDraftRepository(handle().db)
        val a = makeDraft(novelId = "novel-a", draftId = "draft-a1", chapterId = "ch-1")
        val b = makeDraft(novelId = "novel-a", draftId = "draft-a2", chapterId = "ch-2")
        val other = makeDraft(novelId = "novel-b", draftId = "draft-b1")

        repo.save(b) // 乱序保存
        repo.save(a)
        repo.save(other)

        val listA = repo.listByNovel(NovelId("novel-a"))
        assertEquals(listOf(a.draftId, b.draftId), listA.map { it.draftId })
        listA.forEach { assertEquals(DraftStatus.WRITTEN, it.status) }

        val listB = repo.listByNovel(NovelId("novel-b"))
        assertEquals(listOf(other.draftId), listB.map { it.draftId })
    }

    /* 3. 同 draftId 再次 save → 幂等覆盖（不报唯一约束错误） */
    @Test
    fun `resave same draftId overwrites idempotently`() {
        val repo = SqliteDraftRepository(handle().db)
        val first = makeDraft(draftId = "draft-x", content = "第一版")
        repo.save(first)

        val second = makeDraft(draftId = "draft-x", content = "第二版", status = DraftStatus.FINAL)
        repo.save(second)

        assertEquals("第二版", repo.getById(first.draftId)?.content)
        assertEquals(DraftStatus.FINAL, repo.getById(first.draftId)?.status)
        assertEquals(1, repo.listByNovel(first.novelId).size)
    }

    /* 4. 缺失 Draft → getById 返回 null */
    @Test
    fun `getById returns null for unknown draft`() {
        val repo = SqliteDraftRepository(handle().db)
        assertNull(repo.getById(DraftId("ghost")))
        assertEquals(0, repo.listByNovel(NovelId("novel-ghost")).size)
    }

    /* 5. Variant 作用域 Draft 完整往返（variantId + scope + planId 读回一致） */
    @Test
    fun `variant draft roundtrip preserves scope fields`() {
        val repo = SqliteDraftRepository(handle().db)
        val draft = makeDraft(
            novelId = "novel-v",
            variantId = "var-1",
            chapterId = "ch-9",
            planId = "plan-9",
            content = "变体正文",
        )
        repo.save(draft)
        val read = repo.getById(draft.draftId)
        assertNotNull(read)
        assertEquals(VariantScope.VARIANT, read.scope)
        assertEquals(VariantId("var-1"), read.variantId)
        assertEquals(ChapterPlanId("plan-9"), read.planId)
        assertEquals(ChapterId("ch-9"), read.chapterId)
    }

    /* 6. save → close → reopen → 数据一致（持久化往返） */
    @Test
    fun `draft survives close and reopen`() {
        val tmp = java.nio.file.Files.createTempFile("qianyan_p113_draft", ".db").toAbsolutePath()
        try {
            val url = "jdbc:sqlite:$tmp"
            val repo = SqliteDraftRepository(handle(url).db)
            val draft = makeDraft(
                novelId = "novel-p",
                variantId = "var-p",
                chapterId = "ch-p",
                planId = "plan-p",
                content = "持久化正文",
            )
            repo.save(draft)

            val reopened = SqliteDraftRepository(handle(url).db)
            assertEquals(draft, reopened.getById(draft.draftId))
            assertEquals(listOf(draft), reopened.listByNovel(NovelId("novel-p")))
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }
}