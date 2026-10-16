package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.model.worldmodel.CandidateFactKind
import com.qianyan.model.worldmodel.CandidateFactOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Phase 3 · WorldModelUpdateParser 结构边界测试（镜像 `KnowledgeUpdateParserTest` 范式）。
 *
 * 只断言"能否得到可用候选列表"与类型化异常种类，不涉及数据库、不裁决冲突。
 */
class WorldModelUpdateParserTest {

    private fun parse(raw: String) = WorldModelUpdateParser.parse(raw)

    private fun invalid(raw: String): WorldModelUpdateException.InvalidOutput =
        assertFailsWith<WorldModelUpdateException.InvalidOutput> { parse(raw) }

    @Test
    fun `valid batch parses every field`() {
        val facts = parse(
            """{"facts":[{"factId":"f1","kind":"EVENT","operation":"ADD","name":"主角抵达北境",""" +
                """"summary":"主角进入北境王城","excerpt":"正文片段","reason":"正文出现"}]}""",
        )
        assertEquals(1, facts.size)
        val f = facts.single()
        assertEquals("f1", f.factId)
        assertEquals(CandidateFactKind.EVENT, f.kind)
        assertEquals(CandidateFactOperation.ADD, f.operation)
        assertEquals("主角抵达北境", f.name)
        assertEquals("主角进入北境王城", f.summary)
        assertEquals("正文片段", f.excerpt)
        assertEquals("正文出现", f.reason)
    }

    @Test
    fun `optional excerpt and reason default to blank and stay parseable`() {
        val f = parse("""{"facts":[{"factId":"f1","operation":"ADD","name":"N","summary":"S"}]}""").single()
        assertEquals("", f.excerpt)
        assertEquals("", f.reason)
    }

    @Test
    fun `empty facts array yields empty list`() {
        assertEquals(emptyList(), parse("""{"facts":[]}"""))
    }

    @Test
    fun `absent facts key yields empty list`() {
        assertEquals(emptyList(), parse("""{"other":1}"""))
    }

    @Test
    fun `blank output is invalid`() {
        invalid("   ")
    }

    @Test
    fun `prose output without json envelope is invalid`() {
        invalid("主角推开了古碑，封印松动。")
    }

    @Test
    fun `missing required field is invalid`() {
        invalid("""{"facts":[{"factId":"f1","kind":"EVENT","name":"N","summary":"S"}]}""")
    }

    @Test
    fun `wrong field type is invalid`() {
        invalid("""{"facts":[{"factId":123,"operation":"ADD","name":"N","summary":"S"}]}""")
    }

    @Test
    fun `illegal operation enum is invalid`() {
        invalid("""{"facts":[{"factId":"f1","kind":"EVENT","operation":"MERGE","name":"N","summary":"S"}]}""")
    }

    @Test
    fun `illegal kind enum is invalid`() {
        invalid("""{"facts":[{"factId":"f1","kind":"PLACE","operation":"ADD","name":"N","summary":"S"}]}""")
    }

    @Test
    fun `blank identity fields are invalid`() {
        invalid("""{"facts":[{"factId":"","operation":"ADD","name":"N","summary":"S"}]}""")
        invalid("""{"facts":[{"factId":"f1","operation":"ADD","name":"  ","summary":"S"}]}""")
        invalid("""{"facts":[{"factId":"f1","operation":"ADD","name":"N","summary":""}]}""")
    }

    @Test
    fun `unknown keys are ignored but every known fact is kept`() {
        val facts = parse(
            """{"facts":[{"factId":"f1","operation":"ADD","name":"N","summary":"S","modelConfidence":0.9},""" +
                """{"factId":"f2","operation":"ADD","name":"N2","summary":"S2","scope":"ORIGINAL"}],"note":"额外字段"}""",
        )
        assertEquals(listOf("f1", "f2"), facts.map { it.factId })
    }
}
