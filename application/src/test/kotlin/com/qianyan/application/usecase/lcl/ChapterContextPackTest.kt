package com.qianyan.application.usecase.lcl

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.EventId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NarrativeDeltaId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.lcl.ChapterContextPack
import com.qianyan.model.lcl.OpenThread
import com.qianyan.model.lcl.PackGroup
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P13 LCL-B · ChapterContextPack 投影测试。
 * 覆盖 10 项要求：determinism / 窗口 / 未解决兜底 / 未关闭兜底 / 非膨胀 / 预算裁剪 / 100章 / isolation / Original只读 / LCL-A回归。
 */
class ChapterContextPackTest {

    private val now = Clock.System.now()

    private fun app(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private data class Seed(
        val app: ApplicationContainer,
        val novel: NovelId,
        val base: BaseNovelId,
        val va: VariantId,
        val vb: VariantId,
    )

    private fun seed(): Seed {
        val c = app()
        val novel = c.novels.createOriginal(title = "N")
        val base = BaseNovelId(novel.value)
        val va = c.novels.createVariant(VariantContext(base, VariantId("va")), "A")
        val vb = c.novels.createVariant(VariantContext(base, VariantId("vb")), "B")
        return Seed(c, novel, base, va, vb)
    }

    private fun Seed.makeChapters(n: Int, variant: VariantId?) = (1..n).map { i ->
        app.chapters.createNextChapter("章$i", novel, variant)
    }

    private fun event(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        chapter: ChapterId?,
        status: EventStatus = EventStatus.CONFIRMED,
        desc: String = "d-$id",
        at: Instant = now,
    ) = Event(
        id = EventId(id), novelId = novel, variantId = variant,
        scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        name = "事件-$id", description = desc, type = EventType.MAIN_PLOT, status = status,
        chapterId = chapter, createdAt = at,
    )

    private fun foreshadow(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        content: String,
        resolved: Boolean = false,
        at: Instant = now,
    ) = Foreshadow(
        foreshadowId = ForeshadowingId(id), novelId = novel, variantId = variant,
        scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        content = content, resolved = resolved, createdAt = at,
    )

    private fun narrativeDelta(id: String, novel: NovelId, variant: VariantId, thread: String? = null, mainGoal: String = "") =
        com.qianyan.model.lcl.NarrativeDelta(
            deltaId = NarrativeDeltaId(id), novelId = novel, variantId = variant,
            openThreads = if (thread == null) emptyList() else listOf(OpenThread("t-$id", thread)),
            mainGoal = mainGoal.ifEmpty { null }, summary = "sum-$id", createdAt = now,
        )

    // ① 相同输入 → ContextPack 完全 deterministic
    @Test
    fun `same input yields identical context pack`() {
        val s = seed()
        val chapters = s.makeChapters(6, s.va)
        val last = chapters.last().chapterId
        s.app.storyState.saveEvent(event("e1", s.novel, s.va, last))
        s.app.storyState.saveForeshadow(foreshadow("f1", s.novel, s.va, "伏笔"))
        s.app.narrativeState.appendNarrativeDelta(narrativeDelta("d1", s.novel, s.va, thread = "未解线"))

        val a = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last)
        val b = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last)
        assertEquals(a, b, "相同输入两次编译必须完全一致")
    }

    // ② 最近 N 章窗口生效
    @Test
    fun `recent window is honored for events`() {
        val s = seed()
        val chapters = s.makeChapters(10, s.va)
        val old = chapters.first().chapterId     // 窗口外
        val recent = chapters.last().chapterId    // 窗口内
        s.app.storyState.saveEvent(event("e-old", s.novel, s.va, old))
        s.app.storyState.saveEvent(event("e-new", s.novel, s.va, recent))

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, recent, windowSize = 5)
        assertTrue(pack.recentEvents.any { it.id.value == "e-new" }, "窗口内事件应进入 recentEvents")
        assertTrue(pack.recentEvents.none { it.id.value == "e-old" }, "窗口外已确认事件不应进入 recentEvents")
        assertEquals(5, pack.horizonWindow.size, "horizonWindow 应为最近 5 章")
    }

    // ③ active thread 不因窗口裁剪而丢失
    @Test
    fun `active thread survives window trimming`() {
        val s = seed()
        val chapters = s.makeChapters(20, s.va)
        val last = chapters.last().chapterId
        s.app.narrativeState.appendNarrativeDelta(narrativeDelta("d1", s.novel, s.va, thread = "不灭主线"))

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last, windowSize = 3)
        assertTrue(pack.activeThreads.any { it.description == "不灭主线" }, "活跃线程不应因窗口裁剪丢失")
    }

    // ④ active foreshadow 不因窗口裁剪而丢失
    @Test
    fun `active foreshadow survives window trimming`() {
        val s = seed()
        val chapters = s.makeChapters(20, s.va)
        val last = chapters.last().chapterId
        s.app.storyState.saveForeshadow(foreshadow("f-active", s.novel, s.va, "长线伏笔"))

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last, windowSize = 2)
        assertTrue(pack.activeForeshadows.any { it.foreshadowId.value == "f-active" }, "未兑现伏笔不应因窗口裁剪丢失")
    }

    // ⑤ resolved / closed 项不会无限进入 pack
    @Test
    fun `resolved and closed items do not inflate pack`() {
        val s = seed()
        val chapters = s.makeChapters(8, s.va)
        val ch = chapters[3].chapterId
        s.app.storyState.saveForeshadow(foreshadow("f-r", s.novel, s.va, "已兑现伏笔", resolved = true))
        s.app.storyState.saveForeshadow(foreshadow("f-o", s.novel, s.va, "未兑现伏笔"))
        s.app.storyState.saveEvent(event("e-closed", s.novel, s.va, ch, status = EventStatus.COMPLETED))

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, ch, windowSize = 5)
        assertTrue(pack.activeForeshadows.none { it.foreshadowId.value == "f-r" }, "已兑现伏笔不应进入 activeForeshadows")
        assertTrue(pack.activeForeshadows.any { it.foreshadowId.value == "f-o" })
        assertTrue(pack.recentEvents.none { it.id.value == "e-closed" }, "已关闭事件不应进入 recentEvents")
    }

    // ⑥ token budget 超限时按固定优先级裁剪（1 narrative → 2 threads → 3 foreshadows → 4 events）
    @Test
    fun `token budget truncation follows fixed priority`() {
        val s = seed()
        val chapters = s.makeChapters(6, s.va)
        val last = chapters.last().chapterId
        s.app.narrativeState.appendNarrativeDelta(narrativeDelta("d1", s.novel, s.va, thread = "thread", mainGoal = "goal"))
        s.app.storyState.saveForeshadow(foreshadow("f1", s.novel, s.va, "伏笔"))
        // 大量长事件 → events 组必然超预算
        repeat(20) { i ->
            s.app.storyState.saveEvent(event("big-$i", s.novel, s.va, last, desc = "很长的事件描述".repeat(20)))
        }

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last, windowSize = 5, budget = 300)
        assertTrue(pack.tokenBudgetGuard.isTruncated, "预算不足应发生裁剪")
        assertTrue(pack.activeNarrative != null, "顶优先级 narrative 保留")
        assertTrue(pack.activeThreads.any { it.description == "thread" }, "线程应保留（优先级2）")
        assertTrue(pack.activeForeshadows.any { it.foreshadowId.value == "f1" }, "伏笔保留（优先级3）")
        assertTrue(
            pack.recentEvents.size < 20,
            "事件（优先级4）应被裁剪，保留数 < 注入数",
        )
        assertTrue(pack.tokenBudgetGuard.omittedGroups.contains(PackGroup.RECENT_EVENTS), "被丢弃分组应含 RECENT_EVENTS")
    }

    // ⑦ 100+ 章模拟：pack 大小不随全部历史线性膨胀
    @Test
    fun `pack size stays bounded across 100 plus chapters`() {
        val s = seed()
        val chapters = s.makeChapters(120, s.va)
        val last = chapters.last().chapterId
        // 每个历史章节一个已关闭事件（应被排除），最近一章放一个进行中事件（应保留）
        chapters.forEachIndexed { i, c ->
            s.app.storyState.saveEvent(event("hist-$i", s.novel, s.va, c.chapterId, status = EventStatus.COMPLETED))
        }
        s.app.storyState.saveEvent(event("ongoing", s.novel, s.va, last, status = EventStatus.IN_PROGRESS))

        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last, windowSize = 5, budget = 400)
        assertFalse(pack.tokenBudgetGuard.omittedGroups.contains(PackGroup.ACTIVE_NARRATIVE))
        assertTrue(pack.recentEvents.size <= 6, "历史事件已被排除，recentEvents 应受控（<=窗口+进行中 1）")
        assertTrue(pack.recentEvents.any { it.id.value == "ongoing" })
        assertTrue(pack.recentEvents.none { it.id.value.startsWith("hist-") }, "已关闭历史事件不应进入 recentEvents")
    }

    // ⑧ Variant A / Variant B 隔离
    @Test
    fun `variant a and variant b are isolated`() {
        val s = seed()
        val aChapters = s.makeChapters(3, s.va)
        val aLast = aChapters.last().chapterId
        s.makeChapters(3, s.vb)
        // 只在 A 放事件与伏笔
        s.app.storyState.saveEvent(event("eA", s.novel, s.va, aLast))
        s.app.storyState.saveForeshadow(foreshadow("fA", s.novel, s.va, "A伏笔"))

        val packA = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, aLast, windowSize = 5)
        val packB = s.app.chapterContextPack.compileChapterContext(
            s.novel, s.vb,
            s.app.chapters.listByNovel(s.novel, s.vb).last().chapterId,
            windowSize = 5,
        )
        assertTrue(packA.recentEvents.any { it.id.value == "eA" })
        assertTrue(packA.activeForeshadows.any { it.foreshadowId.value == "fA" })
        assertTrue(packB.recentEvents.none { it.id.value == "eA" }, "B 不应看到 A 的变体事件")
        assertTrue(packB.activeForeshadows.none { it.foreshadowId.value == "fA" }, "B 不应看到 A 的变体伏笔")
    }

    // ⑨ Original 不允许通过该 Use Case 修改任何状态
    @Test
    fun `original scope is read only and unchanged by compile`() {
        val s = seed()
        s.makeChapters(2, null)
        val origCh = s.app.chapters.listByNovel(s.novel, null).last().chapterId
        s.app.storyState.saveEvent(event("eOrig", s.novel, null, origCh))

        val before = s.app.storyState.listEvents(s.novel, null).size
        val beforeDeltas = s.app.narrativeState.listNarrativeDeltas(s.novel, null).size
        val pack = s.app.chapterContextPack.compileChapterContext(s.novel, null, origCh, windowSize = 5)
        assertNotNull(pack)
        assertEquals(VariantScope.ORIGINAL, pack.scope)
        assertEquals(0L, pack.activeNarrative!!.version, "Original 叙事账本应为空只读")
        // 编译后状态未变
        assertEquals(before, s.app.storyState.listEvents(s.novel, null).size, "编译不应写入 Story State")
        assertEquals(beforeDeltas, s.app.narrativeState.listNarrativeDeltas(s.novel, null).size, "编译不应写入 Narrative Delta")
    }

    // ⑩ LCL-A NarrativeState 不发生回归：编译只读，不改变 Delta 账本
    @Test
    fun `compile does not regress narrative delta ledger`() {
        val s = seed()
        val chapters = s.makeChapters(5, s.va)
        val last = chapters.last().chapterId
        s.app.narrativeState.appendNarrativeDelta(narrativeDelta("d1", s.novel, s.va, mainGoal = "目标"))
        val before = s.app.narrativeState.listNarrativeDeltas(s.novel, s.va).size

        val p1 = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last)
        val p2 = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last)
        assertEquals(before, s.app.narrativeState.listNarrativeDeltas(s.novel, s.va).size, "编译前后 Delta 数不变")
        assertEquals("目标", p1.activeNarrative!!.mainGoal)
        assertEquals(p1, p2, "再次编译仍一致")

        // invalidatePack seam：输入未变 → 不失效；变化 → 失效
        assertFalse(s.app.chapterContextPack.invalidatePack(p1))
        s.app.storyState.saveEvent(event("eX", s.novel, s.va, last))
        assertTrue(s.app.chapterContextPack.invalidatePack(p1), "新增事件后旧 pack 应判定失效")
    }

    @Test
    fun `pack version changes when inputs change`() {
        val s = seed()
        val chapters = s.makeChapters(5, s.va)
        val last = chapters.last().chapterId
        val base = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last)
        s.app.narrativeState.appendNarrativeDelta(narrativeDelta("d2", s.novel, s.va, mainGoal = "变化"))
        val after = s.app.chapterContextPack.compileChapterContext(s.novel, s.va, last)
        assertTrue(after.packVersion != base.packVersion, "输入变化时 packVersion 应变")
    }
}