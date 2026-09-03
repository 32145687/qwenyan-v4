package com.qianyan.application.usecase.writing

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P12.0.1 P0/P1：Chapter 查询严格 Variant 隔离 + order 原子（createNextChapter 同 scope 不重复）。
 */
class ChapterVariantIsolationTest {

    private fun app() = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun saveChapter(c: ApplicationContainer, novelId: NovelId, variant: VariantId?, scope: VariantScope, order: Int, id: String) {
        c.chapterRepository.save(
            Chapter(
                chapterId = ChapterId(id), novelId = novelId, variantId = variant, scope = scope,
                order = order, status = ChapterStatus.PLANNED,
                createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
            ),
        )
    }

    @Test
    fun `original and variant chapters do not cross`() {
        val c = app()
        val novelN = c.novels.createOriginal(title = "N")
        saveChapter(c, novelN, null, VariantScope.ORIGINAL, 1, "c-o1")
        saveChapter(c, novelN, null, VariantScope.ORIGINAL, 2, "c-o2")
        saveChapter(c, novelN, VariantId("v"), VariantScope.VARIANT, 1, "c-v1")
        saveChapter(c, novelN, VariantId("v"), VariantScope.VARIANT, 2, "c-v2")

        val original = c.chapterRepository.listByNovel(novelN, null)
        assertEquals(listOf("c-o1", "c-o2"), original.map { it.chapterId.value })
        val variantV = c.chapterRepository.listByNovel(novelN, VariantId("v"))
        assertEquals(listOf("c-v1", "c-v2"), variantV.map { it.chapterId.value })
    }

    @Test
    fun `variant a does not see variant b`() {
        val c = app()
        val novelN = c.novels.createOriginal(title = "N")
        saveChapter(c, novelN, VariantId("a"), VariantScope.VARIANT, 1, "ca-1")
        saveChapter(c, novelN, VariantId("b"), VariantScope.VARIANT, 1, "cb-1")

        val onlyA = c.chapterRepository.listByNovel(novelN, VariantId("a"))
        assertEquals(listOf("ca-1"), onlyA.map { it.chapterId.value })
        val onlyB = c.chapterRepository.listByNovel(novelN, VariantId("b"))
        assertEquals(listOf("cb-1"), onlyB.map { it.chapterId.value })
    }

    /* P1：createNextChapter 在同一 scope 内生成连续唯一 order */
    @Test
    fun `create next chapter yields unique increasing orders per scope`() {
        val c = app()
        val novelN = c.novels.createOriginal(title = "N")
        fun next(variant: VariantId?) = c.chapterRepository.createNextChapter(
            Chapter(
                chapterId = ChapterId(java.util.UUID.randomUUID().toString()), novelId = novelN,
                variantId = variant, scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
                title = "t", status = ChapterStatus.PLANNED,
                createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
            ),
        )

        val o1 = next(null); val o2 = next(null)
        val v1 = next(VariantId("v")); val v2 = next(VariantId("v"))
        assertTrue(o1.order < o2.order)
        assertTrue(v1.order < v2.order)
        // 不同 scope 各自独立 start 于 1
        assertEquals(1, o1.order)
        assertEquals(1, v1.order)
        // 无重复
        val orders = c.chapterRepository.listByNovel(novelN, null).map { it.order }
        assertEquals(orders.distinct(), orders)
    }
}