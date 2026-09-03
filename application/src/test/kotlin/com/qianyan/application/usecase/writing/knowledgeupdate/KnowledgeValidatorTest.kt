package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.model.NovelId
import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.knowledge.KnowledgeOperation
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.VariantScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P11.5 Deterministic Validator + Applicator 测试。
 * - Validator: immutable canon 保护（Original 不允许 UPDATE/REMOVE）、重复 changeId 拒绝、空值拒绝。
 * - Applicator: accepted → MemoryEntry(layer=WRITING)，相同输入 → 相同结果，不调 LLM。
 */
class KnowledgeValidatorTest {

    private fun change(
        id: String,
        op: KnowledgeOperation,
        scope: VariantScope = VariantScope.VARIANT,
        target: String = "主角",
        content: String = "突破金丹",
    ) = CandidateKnowledgeChange(
        changeId = id, novelId = NovelId("novel-1"), variantId = null, scope = scope,
        operation = op, target = target, content = content,
    )

    @Test
    fun `variant add is accepted`() {
        val r = KnowledgeValidator.validate(listOf(change("a1", KnowledgeOperation.ADD)))
        assertEquals(1, r.accepted.size)
        assertFalse(r.hasRejected)
    }

    @Test
    fun `variant update and remove are accepted`() {
        val r = KnowledgeValidator.validate(
            listOf(change("a1", KnowledgeOperation.UPDATE), change("a2", KnowledgeOperation.REMOVE)),
        )
        assertEquals(2, r.accepted.size)
    }

    @Test
    fun `original update and remove are rejected immutable canon`() {
        val r = KnowledgeValidator.validate(
            listOf(change("r1", KnowledgeOperation.UPDATE, VariantScope.ORIGINAL), change("r2", KnowledgeOperation.REMOVE, VariantScope.ORIGINAL)),
        )
        assertEquals(0, r.accepted.size)
        assertEquals(2, r.rejected.size)
        assertTrue(r.rejected.all { "immutable" in it.reason })
    }

    @Test
    fun `original add is accepted`() {
        val r = KnowledgeValidator.validate(listOf(change("o1", KnowledgeOperation.ADD, VariantScope.ORIGINAL)))
        assertEquals(1, r.accepted.size)
    }

    @Test
    fun `empty target and content rejected`() {
        val r = KnowledgeValidator.validate(
            listOf(change("x1", KnowledgeOperation.ADD, target = "", content = "x"), change("x2", KnowledgeOperation.ADD, target = "t", content = "")),
        )
        assertEquals(0, r.accepted.size)
        assertEquals(2, r.rejected.size)
    }

    @Test
    fun `duplicate changeId rejected`() {
        val r = KnowledgeValidator.validate(
            listOf(change("dup", KnowledgeOperation.ADD), change("dup", KnowledgeOperation.ADD)),
        )
        assertEquals(1, r.accepted.size)
        assertEquals(1, r.rejected.size)
    }

    @Test
    fun `apply is deterministic and no llm`() {
        val accepted = KnowledgeValidator.validate(listOf(change("a1", KnowledgeOperation.ADD))).accepted
        val r1 = KnowledgeApplicator.apply(accepted)
        val r2 = KnowledgeApplicator.apply(accepted)
        assertEquals(1, r1.size)
        assertEquals(MemoryLayer.WRITING, r1[0].layer)
        // 相同输入 → 相同可过期内容（仅 id 不同，内容 deterministic）
        assertEquals(r1[0].content, r2[0].content)
        assertTrue(r1[0].content.contains("【知识更新·ADD】"))
    }
}