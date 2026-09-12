package com.qianyan.application.usecase.lcl

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.NarrativeDeltaId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.VariantContext
import com.qianyan.model.lcl.NarrativeDelta
import com.qianyan.model.lcl.NarrativeState
import com.qianyan.model.lcl.OpenThread
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P13 LCL-A · Narrative State Use Case 测试。
 * 覆盖 5 项要求：① append 后 version 增加；② 同 variant 可更新；③ 不同 variant 隔离；
 * ④ Original 不可写；⑤（migration 正常在 storage 层验证）。
 */
class NarrativeStateUseCasesTest {

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

    private fun delta(
        id: String,
        novel: NovelId,
        variant: VariantId?,
        mainGoal: String = "",
        thread: OpenThread? = null,
        summary: String = "delta-$id",
    ) = NarrativeDelta(
        deltaId = NarrativeDeltaId(id),
        novelId = novel,
        variantId = variant,
        scope = if (variant == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
        chapterId = ChapterId("ch-$id"),
        mainGoal = mainGoal.ifEmpty { null },
        openThreads = if (thread == null) emptyList() else listOf(thread),
        summary = summary,
        createdAt = now,
    )

    // ① append delta 后 state version 增加
    @Test
    fun `append delta increments state version`() {
        val s = seed()
        s.app.narrativeState.appendNarrativeDelta(delta("d1", s.novel, s.va, mainGoal = "开放世界"))
        val state1 = s.app.narrativeState.projectNarrativeState(s.novel, s.va)
        assertEquals(1L, state1.version, "第一次 append+project 后 version 应为 1")

        s.app.narrativeState.appendNarrativeDelta(delta("d2", s.novel, s.va, mainGoal = "扩大世界观"))
        val state2 = s.app.narrativeState.projectNarrativeState(s.novel, s.va)
        assertEquals(2L, state2.version, "第二次 append+project 后 version 应为 2")
        assertNotNull(s.app.narrativeState.getNarrativeState(s.novel, s.va))
    }

    // ② 同 variant 可以更新（追加多章增量，状态向前推进）
    @Test
    fun `same variant can update narrative state`() {
        val s = seed()
        s.app.narrativeState.appendNarrativeDelta(
            delta("d1", s.novel, s.va, mainGoal = "找到上古封印", thread = OpenThread("t1", "封印松动"), summary = "第一章"),
        )
        s.app.narrativeState.projectNarrativeState(s.novel, s.va)
        s.app.narrativeState.appendNarrativeDelta(
            delta("d2", s.novel, s.va, mainGoal = "封印完全崩溃", summary = "第二章"),
        )
        val state = s.app.narrativeState.projectNarrativeState(s.novel, s.va)

        assertEquals("封印完全崩溃", state.mainGoal, "同 variant 更新后主线应推进")
        assertEquals("第二章", state.lastChapterDelta, "最近的摘要应为第二章")
        assertTrue(state.openThreads.isEmpty(), "第二章未声明未解决线程 → replace 语义清空")
    }

    // ③ 不同 variant 数据隔离
    @Test
    fun `different variants are isolated`() {
        val s = seed()
        s.app.narrativeState.appendNarrativeDelta(delta("da", s.novel, s.va, mainGoal = "VariantA 主线"))
        s.app.narrativeState.appendNarrativeDelta(delta("db", s.novel, s.vb, mainGoal = "VariantB 主线"))

        val a = s.app.narrativeState.projectNarrativeState(s.novel, s.va)
        val b = s.app.narrativeState.projectNarrativeState(s.novel, s.vb)

        assertEquals("VariantA 主线", a.mainGoal)
        assertEquals("VariantB 主线", b.mainGoal)
        assertEquals(1L, a.version)
        assertEquals(1L, b.version)

        // 各自快照互不串据
        assertEquals("VariantA 主线", s.app.narrativeState.getNarrativeState(s.novel, s.va)!!.mainGoal)
        assertEquals("VariantB 主线", s.app.narrativeState.getNarrativeState(s.novel, s.vb)!!.mainGoal)
    }

    // ④ Original 不可写（Original 账本只能读/折叠，写入一律拒绝）
    @Test
    fun `original narrative state is read only`() {
        val s = seed()
        val ex = assertFailsWith<ApplicationException> {
            s.app.narrativeState.appendNarrativeDelta(delta("d-orig", s.novel, null, mainGoal = "原著作设定"))
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error, "Original 写入应抛 InvalidOperation")

        // Original 只读不阻挡折叠（得到空账本）
        val orig = s.app.narrativeState.projectNarrativeState(s.novel, null)
        assertEquals(0L, orig.version)
        assertEquals("", orig.mainGoal)
    }

    // ⑤ 确定性折叠：相同 Delta 集产出稳定状态（佐证 migration 后运行路径正确）
    @Test
    fun `fold is deterministic and repeatable`() {
        val s = seed()
        repeat(3) { i ->
            s.app.narrativeState.appendNarrativeDelta(
                delta("d$i", s.novel, s.va, mainGoal = "目标$i", thread = OpenThread("t$i", "线程$i"), summary = "章$i"),
            )
        }
        val first: NarrativeState = s.app.narrativeState.projectNarrativeState(s.novel, s.va)
        val second: NarrativeState = s.app.narrativeState.projectNarrativeState(s.novel, s.va)
        assertEquals(first, second, "确定性折叠：同 Delta 集两次 project 结果一致")
        assertEquals(3L, first.version)
    }
}