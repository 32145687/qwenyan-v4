package com.qianyan.application.usecase.lcl

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.NarrativeDeltaId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.lcl.NarrativeDelta
import com.qianyan.model.lcl.OpenThread
import com.qianyan.model.lcl.RollingHorizonProjector
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P13 LCL-D · Rolling Horizon Use Case 测试（bounded / deterministic / Workflow Checkpoint 承载 / isolation / scaling）。
 */
class RollingHorizonUseCasesTest {

    private val now = Clock.System.now()

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private data class Seed(val app: ApplicationContainer, val novel: NovelId, val va: VariantId) {
        val ctxA get() = VariantContext(BaseNovelId(novel.value), va)
    }

    private fun seed(chapters: Int = 5): Seed {
        val c = app()
        val novel = c.novels.createOriginal(title = "N")
        val va = c.novels.createVariant(VariantContext(BaseNovelId(novel.value), VariantId("va")), "A")
        repeat(chapters) { c.chapters.createNextChapter("c$it", novel, va) }
        return Seed(c, novel, va)
    }

    private fun addThread(s: Seed, id: String, desc: String) {
        s.app.narrativeState.appendNarrativeDelta(
            NarrativeDelta(
                deltaId = NarrativeDeltaId(id), novelId = s.novel, variantId = s.va,
                openThreads = listOf(OpenThread("t-$id", desc)), summary = "s-$id", createdAt = now,
            ),
        )
    }

    private fun pack(s: Seed) = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, s.app.chapters.listByNovel(s.novel, s.va).last().chapterId, windowSize = 5, budget = 400)

    @Test
    fun `propose candidates is deterministic and bounded`() {
        val s = seed()
        repeat(6) { addThread(s, "$it", "线程$it") } // 6 线程 > MAX_CANDIDATES
        val p = pack(s)
        val a = s.app.rollingHorizon.proposeCandidates(p)
        val b = s.app.rollingHorizon.proposeCandidates(p)
        assertEquals(a, b, "deterministic")
        assertTrue(a.size <= RollingHorizonProjector.MAX_CANDIDATES, "候选有上限")
        assertTrue(a.isNotEmpty())
        a.forEach { cand ->
            assertTrue(cand.expectedChapterRange.size <= 8, "窗口有界")
            assertTrue(cand.references.size <= RollingHorizonProjector.MAX_REFERENCES, "引用有界")
        }
    }

    @Test
    fun `fallback candidate when no threads`() {
        val s = seed()
        val p = pack(s)
        val c = s.app.rollingHorizon.proposeCandidates(p)
        assertTrue(c.isNotEmpty(), "无线程时应产出 fallback 候选")
        assertTrue(c.first().goal.isNotBlank())
    }

    @Test
    fun `store and restore candidates to task checkpoint`() {
        val s = seed(chapters = 3)
        addThread(s, "x", "主线")
        val candidates = s.app.rollingHorizon.proposeCandidates(pack(s))
        val taskId = s.app.tasks.create(com.qianyan.model.task.TaskType.PLANNING)

        s.app.rollingHorizon.storeCandidates(taskId, candidates)
        val restored = s.app.rollingHorizon.candidatesFrom(s.app.tasks.restoreCheckpoint(taskId)!!)
        assertEquals(candidates, restored, "Checkpoint 往返一致")

        // duplicate store → 最新一次覆盖（有界 payload，无正文）
        val again = s.app.rollingHorizon.proposeCandidates(pack(s))
        s.app.rollingHorizon.storeCandidates(taskId, again)
        assertEquals(again, s.app.rollingHorizon.candidatesFrom(s.app.tasks.restoreCheckpoint(taskId)!!))
    }

    @Test
    fun `candidates are variant isolated and window bounded at scale`() {
        val s = seed(chapters = 200)
        addThread(s, "va-only", "仅A线程")
        val p = pack(s)
        // pack 窗口有界，不随 200 章线性膨胀
        assertTrue(p.horizonWindow.size <= 5, "pack 窗口有界")
        assertTrue(p.horizonWindow.size != 200, "不应扫描全部 200 章")
        val candidates = s.app.rollingHorizon.proposeCandidates(p)
        assertTrue(candidates.size <= RollingHorizonProjector.MAX_CANDIDATES)
        // 候选引用落在 A 的伏笔上下文（无正文），不携带整部小说
        assertTrue(candidates.first().expectedChapterRange.size <= 8)
        assertTrue(candidates.all { it.summary.isNotBlank() })
    }
}