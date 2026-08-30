package com.qianyan.application.usecase.writing

import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.writing.DraftStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * P11.3 DraftParser 单元测试。
 *
 * 验证 LLM 原始输出（AgentRuntime 提取后的 answer 文本）→ 现有
 * [com.qianyan.model.writing.Draft] 的解析：
 *  - 合法正文 → Draft（content 必填且为 String，结构 ID 由 [DraftStructure] 提供）；
 *  - 空输出 / 非 JSON / 缺 content / content 类型错误 / 空白正文 → [WritingException.InvalidOutput]（类型化）。
 * 绝不把解析失败伪装成成功 Draft。全程无网络。
 */
class DraftParserTest {

    private val structure = DraftStructure(
        draftId = "draft-1",
        novelId = NovelId("novel-1"),
        variantId = VariantId("var-1"),
        scope = VariantScope.VARIANT,
        chapterId = ChapterId("ch-1"),
        planId = ChapterPlanId("plan-1"),
        sourceModel = "mock-v1",
        now = Instant.parse("2026-01-01T00:00:00Z"),
    )

    /* 合法正文：content 来自 LLM，结构 ID 全部来自 Structure（不由 LLM 生成） */
    @Test
    fun `valid draft output maps to draft with structure ids`() {
        val draft = DraftParser.parse("{\"content\":\"第一章正文内容\"}", structure)

        assertEquals("draft-1", draft.draftId.value)
        assertEquals("novel-1", draft.novelId.value)
        assertEquals(VariantId("var-1"), draft.variantId)
        assertEquals(VariantScope.VARIANT, draft.scope)
        assertEquals(ChapterId("ch-1"), draft.chapterId)
        assertEquals(ChapterPlanId("plan-1"), draft.planId)
        assertEquals("第一章正文内容", draft.content)
        assertEquals(DraftStatus.WRITTEN, draft.status)
        assertEquals("mock-v1", draft.sourceModel)
    }

    /* 空输出：raw 为空 → InvalidOutput（类型化） */
    @Test
    fun `empty output fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> { DraftParser.parse("", structure) }
        assertFailsWith<WritingException.InvalidOutput> { DraftParser.parse("   ", structure) }
    }

    /* 非 JSON：raw 不是合法 JSON → InvalidOutput */
    @Test
    fun `illegal json fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("这不是 JSON 正文", structure)
        }
    }

    /* 缺 content：JSON 合法但无 content 字段 → InvalidOutput */
    @Test
    fun `missing content fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"other\":1}", structure)
        }
    }

    /* content 类型错误：content 不是 String → InvalidOutput */
    @Test
    fun `wrong content type fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"content\":123}", structure)
        }
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"content\":[\"a\"]}", structure)
        }
    }

    /* 空白正文：content 字段存在但为空串 → InvalidOutput（拒绝"无意义成功"） */
    @Test
    fun `blank content fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"content\":\"\"}", structure)
        }
    }

    /* 非法输出断言：全部得到类型化 InvalidOutput（不伪造成功 Draft） */
    @Test
    fun `all failures are typed invalid output not silent drafts`() {
        val raws = listOf(
            "not json",
            "{\"other\":1}",
            "{\"content\":42}",
            "{\"content\":\"\"}",
            "",
        )
        raws.forEach { r ->
            val ex = assertFailsWith<WritingException.InvalidOutput> { DraftParser.parse(r, structure) }
            assertEquals(true, ex.message!!.isNotBlank())
        }
    }

    /* 边界：content 带换行/中文等真实正文也能原样还原 */
    @Test
    fun `multi line content preserved verbatim`() {
        val json = """{"content":"第一段。\n\n第二段，主角抬头望向远方。"}"""
        val draft = DraftParser.parse(json, structure)
        assertEquals("第一段。\n\n第二段，主角抬头望向远方。", draft.content)
    }
}