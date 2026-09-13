package com.qianyan.application.usecase.story

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.story.ForeshadowLifecycleState
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P13 LCL-C · Foreshadow 生命周期 Use Case 测试。
 */
class ForeshadowLifecycleUseCasesTest {

    private val now = Clock.System.now()

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private data class Seed(
        val app: ApplicationContainer,
        val novel: NovelId,
        val base: BaseNovelId,
        val va: VariantId,
        val vb: VariantId,
    ) {
        val ctxA get() = VariantContext(base, va)
        val ctxB get() = VariantContext(base, vb)
    }

    private fun seed(): Seed {
        val c = app()
        val novel = c.novels.createOriginal(title = "N")
        val base = BaseNovelId(novel.value)
        val va = c.novels.createVariant(VariantContext(base, VariantId("va")), "A")
        val vb = c.novels.createVariant(VariantContext(base, VariantId("vb")), "B")
        return Seed(c, novel, base, va, vb)
    }

    private fun foreshadow(id: String, novel: NovelId, variant: VariantId, state: ForeshadowLifecycleState, at: Instant = now) =
        Foreshadow(
            foreshadowId = ForeshadowingId(id), novelId = novel, variantId = variant,
            scope = VariantScope.VARIANT, content = "伏笔-$id", state = state, createdAt = at, updatedAt = at,
        )

    private fun reject(body: () -> Any?): ApplicationException {
        val ex = assertFailsWith<ApplicationException> { body() }
        assertIs<ApplicationError.InvalidOperation>(ex.error, "应为 InvalidOperation")
        return ex
    }

    // 初始状态 PLANTED
    @Test
    fun `freshly added foreshadow is PLANTED`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f0", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        assertEquals(ForeshadowLifecycleState.PLANTED, s.app.storyState.getForeshadowById(ForeshadowingId("f0"))!!.state)
        assertTrue(!s.app.storyState.getForeshadowById(ForeshadowingId("f0"))!!.resolved)
    }

    // PLANTED -> ACTIVE
    @Test
    fun `planted to active succeeds and records reason and updatedAt`() {
        val s = seed()
        val at = Instant.fromEpochSeconds(1700000000, 0)
        s.app.storyState.saveForeshadow(foreshadow("f", s.novel, s.va, ForeshadowLifecycleState.PLANTED, at))
        val out = s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.ACTIVE, "推进主线", at)
        assertEquals(ForeshadowLifecycleState.ACTIVE, out.state)
        assertEquals("推进主线", out.lastTransitionReason)
        assertEquals(at, out.updatedAt)
        assertTrue(!out.resolved)
    }

    // PLANTED -> ABANDONED
    @Test
    fun `planted to abandoned succeeds`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        val out = s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.ABANDONED, "放弃", now)
        assertEquals(ForeshadowLifecycleState.ABANDONED, out.state)
        assertTrue(out.resolved, "ABANDONED 应视为 resolved(closed)")
    }

    // ACTIVE -> RESOLVED + payoffChapterId
    @Test
    fun `active to resolved stores payoffChapterId`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.ACTIVE, "a", now)
        val out = s.app.foreshadowLifecycle.transitionForeshadow(
            s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.RESOLVED, "兑现",
            now, payoffChapterId = ChapterId("payoff-ch1"),
        )
        assertEquals(ForeshadowLifecycleState.RESOLVED, out.state)
        assertEquals(ChapterId("payoff-ch1"), out.payoffChapterId)
        assertEquals("兑现", out.lastTransitionReason)
        assertTrue(out.resolved)
    }

    // ACTIVE -> ABANDONED
    @Test
    fun `active to abandoned succeeds and clears payoff`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.ACTIVE, "a", now)
        val out = s.app.foreshadowLifecycle.transitionForeshadow(
            s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.ABANDONED, "弃线", now, payoffChapterId = ChapterId("x"),
        )
        assertEquals(ForeshadowLifecycleState.ABANDONED, out.state)
        assertNull(out.payoffChapterId, "非 RESOLVED 不应产生 payoffChapterId")
    }

    // 非法转换
    @Test
    fun `illegal transitions rejected`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("p", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("p"), ForeshadowLifecycleState.RESOLVED, null, now) } // PLANTED->RESOLVED
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("p"), ForeshadowLifecycleState.PLANTED, null, now) } // 同态

        s.app.storyState.saveForeshadow(foreshadow("a", s.novel, s.va, ForeshadowLifecycleState.ACTIVE))
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("a"), ForeshadowLifecycleState.PLANTED, null, now) } // ACTIVE->PLANTED

        // 终态出向非法 + 同态非法
        s.app.storyState.saveForeshadow(foreshadow("r", s.novel, s.va, ForeshadowLifecycleState.RESOLVED))
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("r"), ForeshadowLifecycleState.ACTIVE, null, now) }
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("r"), ForeshadowLifecycleState.RESOLVED, null, now) }

        s.app.storyState.saveForeshadow(foreshadow("ab", s.novel, s.va, ForeshadowLifecycleState.ABANDONED))
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("ab"), ForeshadowLifecycleState.ACTIVE, null, now) }
    }

    // 不存在 ID -> EntityNotFound
    @Test
    fun `transition unknown id throws EntityNotFound`() {
        val s = seed()
        val ex = assertFailsWith<ApplicationException> {
            s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("nope"), ForeshadowLifecycleState.ACTIVE, null, now)
        }
        assertIs<ApplicationError.EntityNotFound>(ex.error)
    }

    // Original 只读
    @Test
    fun `original context rejects transition`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        reject { s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA.copy(variantId = null), ForeshadowingId("f"), ForeshadowLifecycleState.ACTIVE, null, now) }
    }

    // Variant A 不影响 Variant B
    @Test
    fun `variant a transition does not affect variant b`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("fA", s.novel, s.va, ForeshadowLifecycleState.PLANTED))
        s.app.storyState.saveForeshadow(foreshadow("fB", s.novel, s.vb, ForeshadowLifecycleState.PLANTED))

        s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("fA"), ForeshadowLifecycleState.ACTIVE, null, now)

        assertEquals(ForeshadowLifecycleState.ACTIVE, s.app.storyState.getForeshadowById(ForeshadowingId("fA"))!!.state)
        assertEquals(ForeshadowLifecycleState.PLANTED, s.app.storyState.getForeshadowById(ForeshadowingId("fB"))!!.state, "B 不受 A 迁移影响")
    }
}