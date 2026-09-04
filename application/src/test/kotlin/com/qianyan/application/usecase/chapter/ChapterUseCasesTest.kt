package com.qianyan.application.usecase.chapter

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.core.VariantContext
import com.qianyan.provider.impl.MockLLMGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.1.6 — Chapter Use Case（列表 / 创建 / order / Variant & Novel isolation / 错误）。
 * 内存库 + Mock LLM（无网络）；验证真实 Chapter 进入 SQLite。
 */
class ChapterUseCasesTest {

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    /* T1 — Chapter List：指定 Novel(+Variant) 读取真实 Chapter */
    @Test
    fun `t1 chapter list reads real chapters`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        app.chapters.createNextChapter(title = "第一章", novelId = novelId)

        val list = app.chapters.listByNovel(novelId)

        assertEquals(1, list.size)
        assertEquals("第一章", list.first().title)
        assertEquals(1, list.first().order)
    }

    /* T2 — Chapter 创建 → SQLite 存在真实 Chapter（可按 id 读回） */
    @Test
    fun `t2 create chapter persists to sqlite`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")

        val created = app.chapters.createNextChapter(title = "开篇", novelId = novelId)

        assertNotNull(created.chapterId)
        val reloaded = app.chapters.findById(created.chapterId)
        assertNotNull(reloaded)
        assertEquals("开篇", reloaded.title)
        assertEquals(novelId, reloaded.novelId)
    }

    /* T3 — Order：连续创建 1/2/3 → order 1/2/3（复用 createNextChapter 原子 order） */
    @Test
    fun `t3 successive chapters get increasing order`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        app.chapters.createNextChapter(title = "一", novelId = novelId)
        app.chapters.createNextChapter(title = "二", novelId = novelId)
        app.chapters.createNextChapter(title = "三", novelId = novelId)

        val list = app.chapters.listByNovel(novelId)
        assertEquals(listOf(1, 2, 3), list.map { it.order })
        assertEquals(listOf("一", "二", "三"), list.map { it.title })
    }

    /* T4 — Variant isolation：Variant A 创建的 Chapter，Variant B 查询不到 */
    @Test
    fun `t4 variant isolation`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val vA = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("va")), "Variant A")
        val vB = app.novels.createVariant(VariantContext(BaseNovelId(novelId.value), VariantId("vb")), "Variant B")

        app.chapters.createNextChapter(title = "VA章", novelId = novelId, variantId = vA)

        // Variant B 查询不到（isolation 由 Storage 层 listByNovel(novelId, variantId) 保证）
        assertTrue(app.chapters.listByNovel(novelId, variantId = vB).isEmpty())
        // Variant A 可见真实章节
        assertEquals(listOf("VA章"), app.chapters.listByNovel(novelId, variantId = vA).map { it.title })
        // Original（variantId=null）也不见 Variant 章节
        assertTrue(app.chapters.listByNovel(novelId).isEmpty())
    }

    /* T5 — Novel isolation：Novel A 的 Chapter，Novel B 查询不到 */
    @Test
    fun `t5 novel isolation`() {
        val app = app()
        val nA = app.novels.createOriginal(title = "A")
        val nB = app.novels.createOriginal(title = "B")

        app.chapters.createNextChapter(title = "A章", novelId = nA)

        assertTrue(app.chapters.listByNovel(nB).isEmpty())
        assertTrue(app.chapters.listByNovel(nA).isNotEmpty())
    }

    /* T7 — Persistence：创建后再次读取（重进/重查）Chapter 仍存在 */
    @Test
    fun `t7 chapter persists across refetch`() {
        val app = app()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val created = app.chapters.createNextChapter(title = "持久", novelId = novelId)

        // 重新从 DB 读取（等价于 Detail 页重进），仍存在
        val again = app.chapters.listByNovel(novelId).singleOrNull { it.chapterId == created.chapterId }
        assertNotNull(again)
        assertEquals("持久", again.title)
    }

    /* T8 — Create 错误：目标 Novel 不存在 / Variant 不匹配 → 类型化错误 */
    @Test
    fun `t8 create errors are typed`() {
        val app = app()
        // Novel 不存在
        val missingNovel = assertFailsWith<ApplicationException> {
            app.chapters.createNextChapter(title = "x", novelId = NovelId("ghost"))
        }
        assertIs<ApplicationError.EntityNotFound>(missingNovel.error)

        // Variant 不属于该 Novel
        val novelId = app.novels.createOriginal(title = "仙侠")
        val badVariant = assertFailsWith<ApplicationException> {
            app.chapters.createNextChapter(title = "x", novelId = novelId, variantId = VariantId("no-such"))
        }
        assertIs<ApplicationError.VariantMismatch>(badVariant.error)
    }
}