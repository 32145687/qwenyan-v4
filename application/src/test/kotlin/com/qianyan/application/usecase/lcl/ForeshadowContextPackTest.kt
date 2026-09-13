package com.qianyan.application.usecase.lcl

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.story.ForeshadowLifecycleState
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P13 LCL-C · Foreshadow 生命周期 与 ChapterContextPack 集成测试。
 * 验证：「PLANTED / ACTIVE ∈ activeForeshadows」，「RESOLVED / ABANDONED ∉ activeForeshadows」；
 * deterministic；packVersion 在输出变化时随之变化、在 PLANTED↔ACTIVE（输出不变）时不变化。
 * （不修改 LCL-B 逻辑 —— activeForeshadows 由 `!resolved` 派生自 state。）
 */
class ForeshadowContextPackTest {

    private val now: Instant = Instant.fromEpochSeconds(1788000000, 0)

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private data class Seed(val app: ApplicationContainer, val novel: NovelId, val va: VariantId) {
        val ctxA get() = VariantContext(BaseNovelId(novel.value), va)
    }

    private fun seed(): Seed {
        val c = app()
        val novel = c.novels.createOriginal(title = "N")
        val va = c.novels.createVariant(VariantContext(BaseNovelId(novel.value), VariantId("va")), "A")
        c.chapters.createNextChapter("c1", novel, va)
        c.chapters.createNextChapter("c2", novel, va)
        c.chapters.createNextChapter("c3", novel, va)
        return Seed(c, novel, va)
    }

    private fun foreshadow(id: String, s: Seed, state: ForeshadowLifecycleState) = Foreshadow(
        foreshadowId = ForeshadowingId(id), novelId = s.novel, variantId = s.va,
        scope = VariantScope.VARIANT, content = "v-$id", state = state, createdAt = now, updatedAt = now,
    )

    private fun ch(s: Seed) = s.app.chapters.listByNovel(s.novel, s.va).last().chapterId

    @Test
    fun `planted and active appear while resolved and abandoned are excluded`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("p", s, ForeshadowLifecycleState.PLANTED))
        s.app.storyState.saveForeshadow(foreshadow("a", s, ForeshadowLifecycleState.ACTIVE))
        s.app.storyState.saveForeshadow(foreshadow("r", s, ForeshadowLifecycleState.RESOLVED))
        s.app.storyState.saveForeshadow(foreshadow("ab", s, ForeshadowLifecycleState.ABANDONED))

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch(s), windowSize = 5, budget = 400)
        val activeIds = pack.activeForeshadows.map { it.foreshadowId.value }
        assertTrue(activeIds.contains("p"), "PLANTED 应进入 activeForeshadows")
        assertTrue(activeIds.contains("a"), "ACTIVE 应进入 activeForeshadows")
        assertTrue(!activeIds.contains("r"), "RESOLVED 应被排除")
        assertTrue(!activeIds.contains("ab"), "ABANDONED 应被排除")
    }

    @Test
    fun `compilation is deterministic for lifecycle states`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("a", s, ForeshadowLifecycleState.ACTIVE))
        val p1 = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch(s), windowSize = 5, budget = 400)
        val p2 = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch(s), windowSize = 5, budget = 400)
        assertEquals(p1, p2, "相同输入两次编译必须一致")
    }

    @Test
    fun `resolving or abandoning changes packVersion while planted to active does not`() {
        val s = seed()
        s.app.storyState.saveForeshadow(foreshadow("f", s, ForeshadowLifecycleState.PLANTED))
        val base = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch(s), windowSize = 5, budget = 400)

        // PLANTED -> ACTIVE：activeForeshadows 内容集合不变 → packVersion 不变
        s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.ACTIVE, null, now)
        val afterActive = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch(s), windowSize = 5, budget = 400)
        assertEquals(base.packVersion, afterActive.packVersion, "PLANTED↔ACTIVE 输出不变，packVersion 应不变")

        // ACTIVE -> RESOLVED：activeForeshadows 集合变化 → packVersion 变化
        s.app.foreshadowLifecycle.transitionForeshadow(s.ctxA, ForeshadowingId("f"), ForeshadowLifecycleState.RESOLVED, null, now)
        val afterResolved = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch(s), windowSize = 5, budget = 400)
        assertTrue(afterResolved.packVersion != base.packVersion, "RESOLVED 导致 activeForeshadows 变化，packVersion 应变化")
        assertTrue(afterResolved.activeForeshadows.none { it.foreshadowId.value == "f" })
    }
}