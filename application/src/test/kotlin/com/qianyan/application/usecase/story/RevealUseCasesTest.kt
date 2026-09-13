package com.qianyan.application.usecase.story

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.RevealId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Reveal
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P13 LCL-D · Reveal Use Case 测试（Reader-only Story State fact）。
 */
class RevealUseCasesTest {

    private val now: Instant = Instant.fromEpochSeconds(1789000000, 0)

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private data class Seed(val app: ApplicationContainer, val novel: NovelId, val base: BaseNovelId, val va: VariantId, val vb: VariantId) {
        val ctxA get() = VariantContext(base, va)
        val ctxB get() = VariantContext(base, vb)
        val ctxOrig get() = VariantContext(base, null)
    }

    private fun seed(): Seed {
        val c = app()
        val novel = c.novels.createOriginal(title = "N")
        val base = BaseNovelId(novel.value)
        val va = c.novels.createVariant(VariantContext(base, VariantId("va")), "A")
        val vb = c.novels.createVariant(VariantContext(base, VariantId("vb")), "B")
        return Seed(c, novel, base, va, vb)
    }

    private fun reveal(id: String, s: Seed, information: String, at: Instant = now, reason: String? = null) = Reveal(
        revealId = RevealId(id), novelId = s.novel, variantId = s.va, scope = VariantScope.VARIANT,
        chapterId = ChapterId("ch-1"), informationId = information, occurredAt = at, reason = reason, createdAt = at,
    )

    @Test
    fun `create reveal then read back and list by scope`() {
        val s = seed()
        val created = s.app.reveals.createReveal(s.ctxA, reveal("r1", s, "info-A"))
        assertEquals(VariantScope.VARIANT, created.scope)
        assertEquals(s.va, created.variantId, "createReveal 应把作用域归一化到 ctx.variantId")
        assertTrue(s.app.reveals.getRevealById(RevealId("r1")) != null)
        assertEquals(listOf("r1"), s.app.reveals.listByScope(s.ctxA).map { it.revealId.value })
    }

    @Test
    fun `duplicate reveal id rejected`() {
        val s = seed()
        s.app.reveals.createReveal(s.ctxA, reveal("r", s, "info"))
        val ex = assertFailsWith<ApplicationException> { s.app.reveals.createReveal(s.ctxA, reveal("r", s, "info")) }
        assertIs<ApplicationError.DuplicateTarget>(ex.error)
    }

    @Test
    fun `variant a reveal does not affect variant b or original`() {
        val s = seed()
        s.app.reveals.createReveal(s.ctxA, reveal("rA", s, "info-A"))
        assertTrue(s.app.reveals.listByScope(s.ctxB).isEmpty(), "B 不应看到 A 的 Reveal")
        assertTrue(s.app.reveals.listByScope(s.ctxOrig).isEmpty(), "Original 不应看到 Variant A 的 Reveal")
    }

    @Test
    fun `original context rejects create but can list empty`() {
        val s = seed()
        val ex = assertFailsWith<ApplicationException> { s.app.reveals.createReveal(s.ctxOrig, reveal("r", s, "info")) }
        assertIs<ApplicationError.InvalidOperation>(ex.error, "Original 只读")
        assertTrue(s.app.reveals.listByScope(s.ctxOrig).isEmpty())
    }

    @Test
    fun `list is deterministically ordered by occurredAt then revealId`() {
        val s = seed()
        val at1 = Instant.fromEpochSeconds(1700000000, 0)
        val at2 = Instant.fromEpochSeconds(1700000100, 0)
        s.app.reveals.createReveal(s.ctxA, reveal("r-b", s, "b", at2))
        s.app.reveals.createReveal(s.ctxA, reveal("r-a", s, "a", at1))
        val ids = s.app.reveals.listByScope(s.ctxA).map { it.revealId.value }
        assertEquals(listOf("r-a", "r-b"), ids)
        assertEquals(ids, s.app.reveals.listByScope(s.ctxA).map { it.revealId.value }, "deterministic")
        assertEquals("a", s.app.reveals.listByScope(s.ctxA).first().informationId)
        assertEquals("b", s.app.reveals.listByScope(s.ctxA).last().informationId)
    }

    @Test
    fun `reason persists and unknown id returns null`() {
        val s = seed()
        s.app.reveals.createReveal(s.ctxA, reveal("r", s, "info", reason = "真相揭晓"))
        assertEquals("真相揭晓", s.app.reveals.getRevealById(RevealId("r"))!!.reason)
        assertNull(s.app.reveals.getRevealById(RevealId("ghost")))
    }
}