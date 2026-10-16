package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.model.worldmodel.CandidateFactKind
import com.qianyan.model.worldmodel.CandidateFactOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3 · WorldModelFactValidator 确定性校验测试（v3.1 §12.8：复用 `KnowledgeValidator` 范式，不建第二套体系）。
 *
 * 断言"相同输入 → 相同裁决"与每条拒绝规则；冲突判定不在本对象（需要既有事实索引）。
 */
class WorldModelFactValidatorTest {

    private val content = "林川推开古碑，封印松动。"

    private fun fact(
        id: String = "f1",
        name: String = "古碑封印松动",
        summary: String = "封印松动",
        excerpt: String = "封印松动",
        kind: CandidateFactKind = CandidateFactKind.EVENT,
        operation: CandidateFactOperation = CandidateFactOperation.ADD,
    ) = ExtractedFact(
        factId = id,
        kind = kind,
        operation = operation,
        name = name,
        summary = summary,
        excerpt = excerpt,
        reason = "正文出现该描述",
    )

    private fun rejectedReason(f: ExtractedFact): String? =
        WorldModelFactValidator.validate(listOf(f), content).rejected.singleOrNull()?.reason

    @Test
    fun `fact with verbatim source excerpt is accepted`() {
        val result = WorldModelFactValidator.validate(listOf(fact()), content)
        assertEquals(1, result.accepted.size)
        assertEquals(0, result.rejected.size)
        assertTrue(!result.hasRejected)
    }

    @Test
    fun `blank identity fields are rejected`() {
        assertTrue(rejectedReason(fact(id = "  "))!!.contains("factId"))
        assertTrue(rejectedReason(fact(name = ""))!!.contains("name"))
        assertTrue(rejectedReason(fact(summary = ""))!!.contains("summary"))
    }

    @Test
    fun `duplicate fact id rejects only the later occurrence`() {
        val result = WorldModelFactValidator.validate(
            listOf(fact(id = "f1"), fact(id = "f1", name = "第二条"), fact(id = "f2")),
            content,
        )
        assertEquals(listOf("f1", "f2"), result.accepted.map { it.factId }, "非重复项仍应接受")
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().reason.contains("重复"))
        assertEquals("f1", result.rejected.single().fact.factId)
    }

    @Test
    fun `forged source excerpt is rejected`() {
        val reason = rejectedReason(fact(excerpt = "封印彻底碎裂"))
        assertTrue(reason!!.contains("逐字"), reason)
    }

    @Test
    fun `blank source excerpt is rejected because provenance is mandatory`() {
        val reason = rejectedReason(fact(excerpt = "   "))
        assertTrue(reason!!.contains("excerpt"), reason)
    }

    @Test
    fun `kind boundary currently admits only event facts`() {
        // 阶段边界：CandidateFactKind 只有 EVENT，因此"非 EVENT 被拒"路径在添加新 kind 前不可达；
        // 这里锁定枚举边界，防止未评审的 kind 悄悄进入落地路径。
        assertEquals(listOf(CandidateFactKind.EVENT), CandidateFactKind.values().toList())
        assertNull(rejectedReason(fact(kind = CandidateFactKind.EVENT)))
    }

    @Test
    fun `non add operations are rejected outside current implementation scope`() {
        for (op in listOf(CandidateFactOperation.UPDATE, CandidateFactOperation.REMOVE)) {
            val reason = rejectedReason(fact(operation = op))
            assertTrue(reason!!.contains("operation"), "$op 应被实现范围规则拒绝：$reason")
        }
    }

    @Test
    fun `same input yields same verdict twice`() {
        val input = listOf(fact(), fact(id = "f2", excerpt = "不存在的片段"), fact(id = "f1"))
        val first = WorldModelFactValidator.validate(input, content)
        val second = WorldModelFactValidator.validate(input, content)
        assertEquals(first.accepted.map { it.factId }, second.accepted.map { it.factId })
        assertEquals(first.rejected.map { it.fact.factId to it.reason }, second.rejected.map { it.fact.factId to it.reason })
    }
}
