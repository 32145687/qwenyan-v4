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
 * P11.3 + O-75 DraftParser 单元测试。
 *
 * 验证 LLM 原始输出（AgentRuntime 提取后的 answer 文本）→ 现有
 * [com.qianyan.model.writing.Draft] 的解析：
 *  - 结构化路径（以 `{` / `[` 开头）：必须是 `{"content":"…"}`；失败/缺 content/类型错误 → [WritingException.InvalidOutput]；
 *  - 自由正文路径（O-75）：非空纯文本 → content 原样保留（换行 / 中文引号 / 英文引号）；
 *  - 空 / 纯空白 / Markdown 围栏（```）→ [WritingException.InvalidOutput]。
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

    /* 自由正文（O-75）：非 JSON 的纯文本即正文 → content 原样保留（换行 / 中文引号 / 英文引号） */
    @Test
    fun `free form prose maps to draft content verbatim`() {
        val prose = "雨落在青石板上。\n\n「你回来了。」门里的人说。\n\n少年没有回答，只把木箱放在台阶上。\n他说:\"先看看这个。\""
        val draft = DraftParser.parse(prose, structure)
        assertEquals(prose, draft.content)
        assertEquals(DraftStatus.WRITTEN, draft.status)
    }

    /* 空输出：raw 为空 / 纯空白 → InvalidOutput（类型化） */
    @Test
    fun `empty output fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> { DraftParser.parse("", structure) }
        assertFailsWith<WritingException.InvalidOutput> { DraftParser.parse("   \n\t ", structure) }
    }

    /* 围栏包裹（O-75）：```…``` → InvalidOutput，不静默剥离 */
    @Test
    fun `fenced output fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("```json\n{\"content\":\"正文\"}\n```", structure)
        }
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("```\n雨落在青石板上。\n```", structure)
        }
    }

    /* 缺 content：JSON 合法但无 content 字段 → InvalidOutput（结构化路径不回落成正文） */
    @Test
    fun `missing content fails with typed error`() {
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"other\":1}", structure)
        }
        // O-75：结构化残片（含 answer 包装 / tool 包）同样拒绝，绝不回落为正文
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"answer\":{\"chapterGoal\":\"x\"}}", structure)
        }
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("{\"tool\":\"continue_chapter\",\"arguments\":{}}", structure)
        }
        assertFailsWith<WritingException.InvalidOutput> {
            DraftParser.parse("[1,2,3]", structure)
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

    /* 非法输出断言：结构化路径的全部失败都是类型化 InvalidOutput（不伪造成功 Draft） */
    @Test
    fun `all structured failures are typed invalid output not silent drafts`() {
        val raws = listOf(
            "{\"other\":1}",
            "{\"content\":42}",
            "{\"content\":\"\"}",
            "{\"answer\":{\"content\":\"嵌套对象\"}}",
            "[{\"content\":\"数组包一层\"}]",
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