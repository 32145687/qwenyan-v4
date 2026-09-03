package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.knowledge.KnowledgeOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P11.5 Knowledge Update Parser 单元测试。
 * 严格解析：空 / 非 JSON / 根类型错 / 缺字段 / 类型错 / operation 非法 / target/content 空 → 类型化失败。
 */
class KnowledgeUpdateParserTest {

    private fun parse(raw: String): List<com.qianyan.model.knowledge.CandidateKnowledgeChange> =
        KnowledgeUpdateParser.parse(raw, NovelId("novel-1"), null, VariantScope.ORIGINAL)

    @Test
    fun `valid changes parse`() {
        val list = parse(
            """{"changes":[
                {"changeId":"c1","operation":"ADD","target":"主角","content":"已突破筑基期"},
                {"changeId":"c2","operation":"UPDATE","target":"反派","content":"境界为金丹"}
            ]}""",
        )
        assertEquals(2, list.size)
        assertEquals(KnowledgeOperation.ADD, list[0].operation)
        assertEquals("主角", list[0].target)
        assertEquals(VariantScope.ORIGINAL, list[0].scope)
        assertEquals(NovelId("novel-1"), list[0].novelId)
        assertEquals("已突破筑基期", list[0].content)
    }

    @Test
    fun `empty output fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> { parse("") }
    }

    @Test
    fun `non json output fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> { parse("随便一段话") }
    }

    @Test
    fun `root is not an object with changes list fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> { parse("""{"foo":1}""") }
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> { parse("""[1,2]""") }
    }

    @Test
    fun `missing required field fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> { parse("""{"changes":[{"changeId":"c1","operation":"ADD","content":"x"}]}""") }
    }

    @Test
    fun `wrong field type fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> {
            parse("""{"changes":[{"changeId":"c1","operation":"ADD","target":"主角","content":42}]}""")
        }
    }

    @Test
    fun `invalid operation enum fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> {
            parse("""{"changes":[{"changeId":"c1","operation":"DELETE_FOREVER","target":"主角","content":"x"}]}""")
        }
    }

    @Test
    fun `empty target or content fails`() {
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> {
            parse("""{"changes":[{"changeId":"c1","operation":"ADD","target":"","content":"x"}]}""")
        }
        assertFailsWith<KnowledgeUpdateException.InvalidOutput> {
            parse("""{"changes":[{"changeId":"c1","operation":"ADD","target":"主角","content":""}]}""")
        }
    }

    @Test
    fun `empty changes is valid but empty`() {
        val list = parse("""{"changes":[]}""")
        assertTrue(list.isEmpty())
    }

    /* confidence 越界被夹紧到 [0,1] */
    @Test
    fun `confidence is clamped`() {
        val list = parse("""{"changes":[{"changeId":"c1","operation":"ADD","target":"t","content":"x","confidence":5}]}""")
        assertEquals(1f, list[0].confidence)
    }
}