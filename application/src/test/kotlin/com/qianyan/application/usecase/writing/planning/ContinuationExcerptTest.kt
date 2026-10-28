package com.qianyan.application.usecase.writing.planning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6 · LongContinuation Canonical 摘录截断（T5）。
 *
 * 口径：按 Unicode code point 截尾；3999/4000/4001 边界与代理对不得拆分。
 */
class ContinuationExcerptTest {

    @Test
    fun `3999 code points stay untruncated`() {
        val text = "a".repeat(3999)
        val excerpt = continuationExcerpt(text, 4000)
        assertFalse(excerpt.truncated)
        assertEquals(3999, excerpt.originalCodePoints)
        assertEquals(text, excerpt.text)
        assertEquals(
            "truncated=false; originalCodePoints=3999; keptCodePoints=3999",
            excerpt.annotation(),
        )
    }

    @Test
    fun `4000 code points stay untruncated`() {
        val text = "a".repeat(4000)
        val excerpt = continuationExcerpt(text, 4000)
        assertFalse(excerpt.truncated)
        assertEquals(4000, excerpt.originalCodePoints)
        assertEquals(text, excerpt.text)
        assertEquals(
            "truncated=false; originalCodePoints=4000; keptCodePoints=4000",
            excerpt.annotation(),
        )
    }

    @Test
    fun `4001 code points keep the last 4000`() {
        val text = "a" + "b".repeat(4000)
        val excerpt = continuationExcerpt(text, 4000)
        assertTrue(excerpt.truncated)
        assertEquals(4001, excerpt.originalCodePoints)
        assertEquals(4000, excerpt.text.codePointCount(0, excerpt.text.length))
        assertEquals("b".repeat(4000), excerpt.text)
        assertEquals(
            "truncated=true; originalCodePoints=4001; keptCodePoints=4000",
            excerpt.annotation(),
        )
    }

    @Test
    fun `surrogate pairs are never split`() {
        val emoji = "\uD83D\uDE00"
        // 4001 code points；截尾 4000 时切割点恰好落在 emoji 的 UTF-16 代理对内部。
        val text = "a" + emoji + "b".repeat(3999)
        assertEquals(4001, text.codePointCount(0, text.length))

        val excerpt = continuationExcerpt(text, 4000)
        assertTrue(excerpt.truncated)
        assertEquals(4001, excerpt.originalCodePoints)
        assertEquals(4000, excerpt.text.codePointCount(0, excerpt.text.length))
        assertEquals(0x1F600, excerpt.text.codePointAt(0), "截尾必须从完整代理对边界开始")
        assertTrue(excerpt.text.endsWith("b"))
    }
}
