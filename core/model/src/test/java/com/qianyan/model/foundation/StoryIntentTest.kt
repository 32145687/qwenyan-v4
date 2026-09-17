package com.qianyan.model.foundation

import com.qianyan.model.NovelId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P15-C · StoryIntent 领域模型测试（纯领域，无其它模块依赖）。
 */
class StoryIntentTest {

    private val t: Instant = Instant.fromEpochSeconds(1790000000, 0)

    private fun intent(raw: String = "我想写一个少年逆天改命的故事", summary: String? = null) = StoryIntent(
        id = StoryIntentId("i-1"),
        novelId = NovelId("n1"),
        rawIdea = raw,
        aiSummary = summary,
        createdAt = t,
        updatedAt = t,
    )

    @Test
    fun `rawIdea is preserved verbatim`() {
        val raw = " 我想写一个\n少年逆天改命的故事 "
        val i = intent(raw = raw)
        assertEquals(raw, i.rawIdea, "rawIdea 必须原样保留，不得被 AI/application 修改")
    }

    @Test
    fun `aiSummary may be null before understanding`() {
        val before = intent(summary = null)
        assertNull(before.aiSummary)
        val after = intent(summary = "热血成长，主角逆天改命")
        assertNotNull(after.aiSummary)
        assertEquals("热血成长，主角逆天改命", after.aiSummary)
    }

    @Test
    fun `createdAt and updatedAt are retained`() {
        val i = intent()
        assertEquals(t, i.createdAt)
        assertEquals(t, i.updatedAt)
    }

    @Test
    fun `storyIntent is immutable and value semantics`() {
        val a = intent()
        // data class 值相等
        assertEquals(a, intent())
        // copy 不改原实例
        val b = a.copy(aiSummary = "新摘要")
        assertEquals(a.aiSummary, null, "原实例不应被 copy 影响")
        assertEquals("新摘要", b.aiSummary)
        assertNotEquals(a, b)
    }

    @Test
    fun `aiSummary does not overwrite rawIdea`() {
        val raw = "原始想法"
        val withSummary = intent(raw = raw, summary = "AI 理解为别的方向")
        assertEquals(raw, withSummary.rawIdea, "AI 摘要不得覆盖用户原文")
    }
}