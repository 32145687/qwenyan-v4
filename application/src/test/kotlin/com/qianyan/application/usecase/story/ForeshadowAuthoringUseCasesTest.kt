package com.qianyan.application.usecase.story

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.ForeshadowLifecycleState
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.repository.StoryStateRepository
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O07-A · 作者伏笔入口（Original 生命周期元数据）。
 *
 * 覆盖：创建/推进/兑现（必选章节）/放弃/非法与同态/跨作品/并发（affected=0）/零正文写入语义/上下文包联动。
 */
class ForeshadowAuthoringUseCasesTest {

    private val now: Instant = Instant.fromEpochSeconds(1788100000, 0)

    private class World(
        val app: ApplicationContainer,
        val novelId: NovelId,
        val chapter1: ChapterId,
        val chapter2: ChapterId,
    )

    private fun world(): World {
        val app = ApplicationContainer.open(analysisGateway = MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "伏笔书")
        val chapter1 = app.chapters.createNextChapter("c1", novelId).chapterId
        val chapter2 = app.chapters.createNextChapter("c2", novelId).chapterId
        return World(app, novelId, chapter1, chapter2)
    }

    private fun create(w: World, content: String = "少年箱底刻着「归」字", chapter: ChapterId? = null) =
        w.app.foreshadowAuthoring.create(w.novelId, chapter ?: w.chapter1, content)

    /* 1. 空内容拒绝 */
    @Test
    fun `blank content is rejected`() {
        val w = world()
        listOf("", "   ", "\n\t ").forEach { text ->
            val ex = assertFailsWith<ApplicationException> { w.app.foreshadowAuthoring.create(w.novelId, null, text) }
            assertTrue(ex.message!!.contains("伏笔内容不能为空"))
        }
    }

    /* 2. 创建：Original / PLANTED / 章节绑定 / 内容 trim */
    @Test
    fun `create inserts an original planted foreshadow`() {
        val w = world()
        val created = w.app.foreshadowAuthoring.create(w.novelId, w.chapter1, "  少年箱底刻着「归」字  ")

        assertEquals(ForeshadowLifecycleState.PLANTED, created.state)
        assertEquals(VariantScope.ORIGINAL, created.scope)
        assertNull(created.variantId)
        assertEquals(w.chapter1, created.chapterId)
        assertEquals("少年箱底刻着「归」字", created.content)
        val listed = w.app.foreshadowAuthoring.list(w.novelId).single()
        assertEquals(created.foreshadowId, listed.foreshadowId)
        assertEquals(created.content, listed.content)
        assertEquals(created.chapterId, listed.chapterId)
        assertEquals(ForeshadowLifecycleState.PLANTED, listed.state)
        assertEquals(created.createdAt.toEpochMilliseconds(), listed.createdAt.toEpochMilliseconds())
    }

    /* 3. 跨作品章节拒绝 */
    @Test
    fun `chapter from another novel is rejected on create`() {
        val w = world()
        val otherNovel = w.app.novels.createOriginal(title = "另一本")
        val otherChapter = w.app.chapters.createNextChapter("x1", otherNovel).chapterId
        val ex = assertFailsWith<ApplicationException> {
            w.app.foreshadowAuthoring.create(w.novelId, otherChapter, "别人的章节")
        }
        assertTrue(ex.message!!.contains("不属于作品"), "实际: ${ex.message}")
    }

    /* 4/5/6. 推进 → 兑现（章节落库）/ 放弃（无 payoff） */
    @Test
    fun `planted to active then resolved stores payoff chapter`() {
        val w = world()
        val f = create(w)

        val active = w.app.foreshadowAuthoring.transition(
            w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, "推进主线", occurredAt = now,
        )
        assertEquals(ForeshadowLifecycleState.ACTIVE, active.state)
        assertEquals("推进主线", active.lastTransitionReason)

        val resolved = w.app.foreshadowAuthoring.transition(
            w.novelId, f.foreshadowId, ForeshadowLifecycleState.RESOLVED, "兑现", w.chapter2, now,
        )
        assertEquals(ForeshadowLifecycleState.RESOLVED, resolved.state)
        assertEquals(w.chapter2, resolved.payoffChapterId)
        assertTrue(resolved.resolved)

        val stored = w.app.storyState.getForeshadowById(f.foreshadowId)!!
        assertEquals(ForeshadowLifecycleState.RESOLVED, stored.state)
        assertEquals(w.chapter2, stored.payoffChapterId)
        assertEquals("兑现", stored.lastTransitionReason)
        assertEquals(f.content, stored.content, "生命周期迁移不得改内容")
        assertEquals(f.createdAt.toEpochMilliseconds(), stored.createdAt.toEpochMilliseconds(), "生命周期迁移不得改创建时间")
    }

    @Test
    fun `abandon clears payoff and keeps content`() {
        val w = world()
        val f = create(w)
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        val abandoned = w.app.foreshadowAuthoring.transition(
            w.novelId, f.foreshadowId, ForeshadowLifecycleState.ABANDONED, "弃线", w.chapter2, now,
        )
        assertEquals(ForeshadowLifecycleState.ABANDONED, abandoned.state)
        assertNull(abandoned.payoffChapterId, "非 RESOLVED 不得产生 payoff")
        val stored = w.app.storyState.getForeshadowById(f.foreshadowId)!!
        assertNull(stored.payoffChapterId)
        assertEquals(f.content, stored.content)
    }

    /* 7. RESOLVED 缺章节拒绝 */
    @Test
    fun `resolved requires a payoff chapter`() {
        val w = world()
        val f = create(w)
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        val ex = assertFailsWith<ApplicationException> {
            w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.RESOLVED, null, null, now)
        }
        assertTrue(ex.message!!.contains("必须指定兑现章节"))
        assertEquals(ForeshadowLifecycleState.ACTIVE, w.app.storyState.getForeshadowById(f.foreshadowId)!!.state)
    }

    /* 8. 非法 / 同态转换拒绝（含终态出向） */
    @Test
    fun `illegal and same state transitions are rejected`() {
        val w = world()
        val f = create(w)
        // PLANTED -> RESOLVED
        assertFailsWith<ApplicationException> {
            w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.RESOLVED, null, w.chapter1, now)
        }
        // 同态
        assertFailsWith<ApplicationException> {
            w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.PLANTED, null, occurredAt = now)
        }
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        // ACTIVE -> PLANTED
        assertFailsWith<ApplicationException> {
            w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.PLANTED, null, occurredAt = now)
        }
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.RESOLVED, null, w.chapter1, now)
        // 终态出向
        assertFailsWith<ApplicationException> {
            w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        }
    }

    /* 9. 跨作品 id 拒绝 */
    @Test
    fun `foreshadow from another novel is not found`() {
        val a = world()
        val b = world()
        val f = create(a)
        assertFailsWith<ApplicationException> {
            b.app.foreshadowAuthoring.transition(b.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        }
    }

    /* 10. 并发（affected=0）→ 复用既有并发错误文案 */
    @Test
    fun `zero affected rows raises the concurrency error`() {
        val w = world()
        val f = create(w)
        val zero = object : StoryStateRepository by w.app.storyState {
            override fun transitionForeshadowState(
                foreshadowId: ForeshadowingId,
                expectedState: ForeshadowLifecycleState,
                targetState: ForeshadowLifecycleState,
                reason: String?,
                occurredAt: Instant,
                payoffChapterId: ChapterId?,
            ): Int = 0
        }
        val useCase = ForeshadowAuthoringUseCases(zero, w.app.chapterRepository, w.app.novels, w.app.errorMapper)
        val ex = assertFailsWith<ApplicationException> {
            useCase.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        }
        assertTrue(ex.message!!.contains("并发状态变化"))
    }

    /* 11. 上下文包联动：非终态进入 activeForeshadows，兑现后退出 */
    @Test
    fun `authoring lifecycle is reflected in the chapter context pack`() {
        val w = world()
        val f = create(w)
        val before = w.app.chapterContextPack.compileChapterContext(w.novelId, null, w.chapter2, windowSize = 5, budget = 400)
        assertTrue(
            before.activeForeshadows.any { it.foreshadowId == f.foreshadowId },
            "PLANTED 应进入 activeForeshadows",
        )
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        val afterActive = w.app.chapterContextPack.compileChapterContext(w.novelId, null, w.chapter2, windowSize = 5, budget = 400)
        assertTrue(afterActive.activeForeshadows.any { it.foreshadowId == f.foreshadowId }, "ACTIVE 仍活跃")

        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.RESOLVED, null, w.chapter2, now)
        val afterResolved = w.app.chapterContextPack.compileChapterContext(w.novelId, null, w.chapter2, windowSize = 5, budget = 400)
        assertTrue(
            afterResolved.activeForeshadows.none { it.foreshadowId == f.foreshadowId },
            "RESOLVED 必须退出 activeForeshadows",
        )
        assertTrue(afterResolved.packVersion != before.packVersion, "输出变化 ⇒ packVersion 变化")
    }

    /* 12. 零正文写入：Draft / Commit 行数不变 */
    @Test
    fun `authoring writes no draft or commit rows`() {
        val w = world()
        val draftsBefore = w.app.draftRepository.listByChapter(w.chapter1).size
        val commitsBefore = w.app.commits.history(w.app.projects.projectOf(w.novelId).projectId).size
        val f = create(w)
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.ACTIVE, null, occurredAt = now)
        w.app.foreshadowAuthoring.transition(w.novelId, f.foreshadowId, ForeshadowLifecycleState.RESOLVED, null, w.chapter2, now)

        assertEquals(draftsBefore, w.app.draftRepository.listByChapter(w.chapter1).size)
        assertEquals(commitsBefore, w.app.commits.history(w.app.projects.projectOf(w.novelId).projectId).size)
    }
}
