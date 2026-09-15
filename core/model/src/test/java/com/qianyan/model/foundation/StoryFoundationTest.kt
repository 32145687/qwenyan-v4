package com.qianyan.model.foundation

import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P14-F.1 · StoryFoundation 领域模型最小测试（纯领域，无其它模块依赖）。
 */
class StoryFoundationTest {

    private val t: Instant = Instant.fromEpochSeconds(1789000000, 0)

    @Test
    fun `storyFoundation can be constructed`() {
        val f = StoryFoundation(
            novelId = NovelId("n1"),
            baseNovelId = BaseNovelId("n1"),
            scope = VariantScope.ORIGINAL,
            version = 0L,
            createdAt = t,
            updatedAt = t,
        )
        assertEquals(NovelId("n1"), f.novelId)
        assertEquals(VariantScope.ORIGINAL, f.scope)
    }

    @Test
    fun `genre is a list of GenreId`() {
        val f = StoryFoundation(
            novelId = NovelId("n1"),
            baseNovelId = BaseNovelId("n1"),
            genre = listOf(GenreId("romance"), GenreId("fantasy_eastern")),
            createdAt = t,
            updatedAt = t,
        )
        assertEquals(listOf<GenreId>(GenreId("romance"), GenreId("fantasy_eastern")), f.genre)
    }

    @Test
    fun `storyDirection can be constructed`() {
        val d = StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻")
        assertEquals("成长", d.theme)
        assertEquals("逆袭", d.promise)
    }

    @Test
    fun `narrativeProfile can be constructed`() {
        val p = NarrativeProfile(pov = "第三人称", readerTone = "热血")
        assertEquals("第三人称", p.pov)
        assertEquals("热血", p.readerTone)
    }

    @Test
    fun `writingPolicy can be constructed`() {
        val w = WritingPolicy(listOf("不OOC", "章节紧凑"))
        assertEquals(listOf("不OOC", "章节紧凑"), w.rules)
    }

    @Test
    fun `foundationOverride can override only part of fields`() {
        val o = FoundationOverride(
            variantId = VariantId("va"),
            direction = StoryDirection(theme = "改线"),
            updatedAt = t,
        )
        // genre/audience/policy 未覆盖 → null（继承 Original）
        assertNull(o.genre)
        assertNull(o.audience)
        assertNull(o.policy)
        assertEquals(StoryDirection(theme = "改线"), o.direction)
    }

    @Test
    fun `null override fields preserve inheritance semantics`() {
        val original = StoryFoundation(
            novelId = NovelId("n1"),
            baseNovelId = BaseNovelId("n1"),
            genre = listOf(GenreId("romance")),
            audience = NarrativeProfile(pov = "第一人称"),
            createdAt = t,
            updatedAt = t,
        )
        val ov = FoundationOverride(variantId = VariantId("va"), updatedAt = t)
        // 继承语义：override 的 null 字段应回落到 Original 的对应值
        assertEquals(original.genre, ov.genre ?: original.genre)
        assertEquals(original.audience, ov.audience ?: original.audience)
        assertTrue(original.genre.isNotEmpty())
    }
}