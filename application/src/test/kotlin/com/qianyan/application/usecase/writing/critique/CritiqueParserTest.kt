package com.qianyan.application.usecase.writing.critique

import com.qianyan.model.spec.IssueSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P11.4 Critique Parser 单元测试。
 *
 * 验证对 Critic LLM 输出的严格解析：
 *  - 合法评审 → [com.qianyan.model.spec.ValidationResult]；
 *  - 空输出 / 非 JSON / 缺 passed / passed 类型错误 / issue 缺 message → [CritiqueException.InvalidOutput]。
 * 不经 String.contains 判类型，无 silent fallback。
 */
class CritiqueParserTest {

    @Test
    fun `valid critique output parses`() {
        val result = CritiqueParser.parse(
            """{"passed":false,"issues":[
                {"field":"逻辑","severity":"ERROR","message":"前后矛盾"},
                {"field":"","severity":"WARNING","message":"节奏偏快"}
            ]}""",
        )
        assertEquals(false, result.passed)
        assertEquals(2, result.issues.size)
        assertEquals("前后矛盾", result.issues[0].message)
        assertEquals(IssueSeverity.ERROR, result.issues[0].severity)
        assertEquals("逻辑", result.issues[0].field)
        assertEquals(IssueSeverity.WARNING, result.issues[1].severity)
    }

    /* 空输出 → InvalidOutput */
    @Test
    fun `empty critique output fails`() {
        assertFailsWith<CritiqueException.InvalidOutput> { CritiqueParser.parse("") }
    }

    /* 非 JSON（如纯文本）→ InvalidOutput；自由文本绝不视为成功 */
    @Test
    fun `non json critique output fails`() {
        val ex = assertFailsWith<CritiqueException.InvalidOutput> { CritiqueParser.parse("这段写得不好") }
        assertTrue(ex.message!!.contains("无法解析"))
    }

    /* 缺 passed（必填字段）→ InvalidOutput */
    @Test
    fun `missing required passed field fails`() {
        assertFailsWith<CritiqueException.InvalidOutput> { CritiqueParser.parse("""{"issues":[]}""") }
    }

    /* passed 类型错误 → InvalidOutput */
    @Test
    fun `wrong passed field type fails`() {
        assertFailsWith<CritiqueException.InvalidOutput> { CritiqueParser.parse("""{"passed":"yes"}""") }
    }

    /* 单条 issue 缺 message / 空 message → InvalidOutput（静默接受空意见是缺陷） */
    @Test
    fun `issue without message fails`() {
        assertFailsWith<CritiqueException.InvalidOutput> {
            CritiqueParser.parse("""{"passed":true,"issues":[{"field":"x"}]}""")
        }
        assertFailsWith<CritiqueException.InvalidOutput> {
            CritiqueParser.parse("""{"passed":true,"issues":[{"field":"x","message":""}]}""")
        }
    }

    /* 通过评审：无 issues 也合法（passed=true 且无意见） */
    @Test
    fun `passed with no issues is valid`() {
        val result = CritiqueParser.parse("""{"passed":true}""")
        assertTrue(result.passed)
        assertEquals(0, result.issues.size)
    }
}