package com.qianyan.application.usecase.writing

import com.qianyan.model.IntentType
import com.qianyan.application.usecase.writing.planning.CurrentDraftLite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O-116 · 生成形态参数化（纯函数层）：
 *
 *  - CONTINUE + 已有正文 → 追加语义（候选 = 原文 + 新段）——修复「续写变替换」；
 *  - REWRITE / 空章节 → 替换 / 全文（现状语义保持）；
 *  - 续写模式提示仅在 CONTINUE + 已有正文时渲染。
 */
class O116GenerationFormTest {

    private val draft = CurrentDraftLite(
        draftId = com.qianyan.model.DraftId("draft-1"),
        content = "雪落进雁回关的时候，沈砚想起了师父临终前的那句话。",
    )

    /* ---------- 候选装配 ---------- */

    @Test
    fun `continue with existing draft appends segment after original`() {
        val out = assembleCandidateContent(IntentType.CONTINUE, draft.content, "门外传来马蹄声。")
        assertEquals(draft.content + "\n\n门外传来马蹄声。", out)
        assertTrue(out.startsWith(draft.content), "原文必须原样保留在候选头部（追加而非替换）")
    }

    @Test
    fun `rewrite keeps replacement semantics`() {
        val out = assembleCandidateContent(IntentType.REWRITE, draft.content, "全新的一章。")
        assertEquals("全新的一章。", out, "REWRITE 保持替换语义（既有改写路径不变）")
    }

    @Test
    fun `continue without existing draft writes fresh chapter`() {
        val out = assembleCandidateContent(IntentType.CONTINUE, null, "全书第一章。")
        assertEquals("全书第一章。", out)
    }

    @Test
    fun `expand keeps replacement semantics`() {
        val out = assembleCandidateContent(IntentType.EXPAND, draft.content, "扩写内容。")
        assertEquals("扩写内容。", out)
    }

    /* ---------- 续写模式提示 ---------- */

    @Test
    fun `continuation section renders only for continue with draft`() {
        val section = continuationModeSection(IntentType.CONTINUE, draft)
        assertEquals(
            "【续写模式】本章已有正文（见上）。只输出接续在其后的新段落：" +
                "不要重复、改写或总结已有内容，第一句自然衔接已有结尾；仍遵守【输出契约】。",
            section,
        )
    }

    @Test
    fun `continuation section hidden for rewrite and for missing draft`() {
        assertNull(continuationModeSection(IntentType.REWRITE, draft))
        assertNull(continuationModeSection(IntentType.CONTINUE, null))
    }
}
