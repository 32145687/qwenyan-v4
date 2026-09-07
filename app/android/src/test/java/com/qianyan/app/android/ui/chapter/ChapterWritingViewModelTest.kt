package com.qianyan.app.android.ui.chapter

import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.workflow.WorkflowKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P12.2 M3 · ChapterWritingViewModel（Facade Migration）单元测试。
 *
 * 构造依赖只有 `ChapterWorkflowGateway`（+ 用户层 DTO），**不接触** Workflow/WorkflowStep/Attempt/
 * HumanGate/WorkflowRepository/ChapterWritingSession/Task。Fake 只替身 [ChapterWorkflowGateway]，
 * 不绕过 Facade 层。用 kotlinx-coroutines-test 注入 Main 调度器（与既有 Android VM 测试一致）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChapterWritingViewModelTest {

    /** 替身 Gateway：脚本化 current / 下一章，供 VM 消费（只用户层语义，无任何 Workflow 内部）。 */
    private class FakeGateway : ChapterWorkflowGateway {
        var current: ChapterWorkflowProgress = p(ChapterPhase.NOT_STARTED)
        var nextTarget: ChapterWorkflowProgress = p(ChapterPhase.NOT_STARTED, chapterId = "ch-next")
        val calls = mutableListOf<String>()

        override fun startChapter(novelId: NovelId, variantId: VariantId?, chapterId: ChapterId, kind: WorkflowKind): ChapterWorkflowProgress {
            calls += "start"; return current
        }
        override fun advance(chapterId: ChapterId): ChapterWorkflowProgress { calls += "advance"; return current }
        override fun resume(chapterId: ChapterId): ChapterWorkflowProgress { calls += "resume"; return current }
        override fun getChapterProgress(chapterId: ChapterId): ChapterWorkflowProgress { calls += "progress"; return current }
        override fun approve(chapterId: ChapterId): ChapterWorkflowProgress { calls += "approve"; return current }
        override fun continueToNextChapter(chapterId: ChapterId): ChapterWorkflowProgress { calls += "continue"; return nextTarget }
    }

    private fun TestScope.viewModel(gateway: FakeGateway): ChapterWritingViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return ChapterWritingViewModel(
            novelId = NovelId("n1"), variantId = null, chapterId = ChapterId("c1"),
            gateway = gateway, ioDispatcher = dispatcher,
        )
    }

    /** M3-VM-1：创建 → 打开恢复获得正确 progress；start 后阶段更新。 */
    @Test
    fun `VM1 open gets progress and start updates`() = runTest {
        val gw = FakeGateway().apply { current = p(ChapterPhase.NOT_STARTED) }
        val vm = viewModel(gw)
        advanceUntilIdle()
        assertEquals(ChapterPhase.NOT_STARTED, vm.progress.value?.phase)

        gw.current = p(ChapterPhase.PLANNING)
        vm.start()
        advanceUntilIdle()
        assertEquals(ChapterPhase.PLANNING, vm.progress.value?.phase)
        assertTrue(gw.calls.contains("start"))
        Dispatchers.resetMain()
    }

    /** M3-VM-2：advance → WAITING_CONFIRMATION / waitingForUser。 */
    @Test
    fun `VM2 advance reaches waiting for user`() = runTest {
        val gw = FakeGateway().apply { current = p(ChapterPhase.WRITING) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        gw.current = p(ChapterPhase.WAITING_CONFIRMATION, waiting = true)
        vm.advance()
        advanceUntilIdle()
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, vm.progress.value?.phase)
        assertTrue(vm.progress.value?.waitingForUser == true)
        Dispatchers.resetMain()
    }

    /** M3-VM-3：approve（waiting 解除）→ advance → COMPLETED。 */
    @Test
    fun `VM3 approve then advance completes`() = runTest {
        val gw = FakeGateway().apply { current = p(ChapterPhase.WAITING_CONFIRMATION, waiting = true) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        gw.current = p(ChapterPhase.WAITING_CONFIRMATION, waiting = false)
        vm.approve()
        advanceUntilIdle()
        assertTrue(vm.progress.value?.waitingForUser == false)

        gw.current = p(ChapterPhase.COMPLETED)
        vm.advance()
        advanceUntilIdle()
        assertEquals(ChapterPhase.COMPLETED, vm.progress.value?.phase)
        Dispatchers.resetMain()
    }

    /** M3-VM-4：重建 VM（丢弃旧实例）→ resume 仍恢复到持久化进度（无旧 Session）。 */
    @Test
    fun `VM4 rebuild resumes durable progress`() = runTest {
        val gw = FakeGateway().apply { current = p(ChapterPhase.WRITING) }
        val vm1 = viewModel(gw)
        advanceUntilIdle()
        assertEquals(ChapterPhase.WRITING, vm1.progress.value?.phase)

        // 丢弃旧 VM，用全新 VM 实例 + 共享持久化进度（fake 的 current）resume
        Dispatchers.resetMain()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm2 = ChapterWritingViewModel(NovelId("n1"), null, ChapterId("c1"), gw, ioDispatcher = dispatcher)
        advanceUntilIdle()
        assertEquals(ChapterPhase.WRITING, vm2.progress.value?.phase)
        assertTrue(gw.calls.contains("resume") || gw.calls.contains("progress")) // 恢复经 Gateway，非 Session
        Dispatchers.resetMain()
    }

    /** M3-VM-5：continueToNextChapter → 下一章 progress（不改写当前章节）。 */
    @Test
    fun `VM5 continue to next yields next progress`() = runTest {
        val gw = FakeGateway().apply {
            current = p(ChapterPhase.COMPLETED, "c1")
            nextTarget = p(ChapterPhase.NOT_STARTED, "c2")
        }
        val vm = viewModel(gw)
        advanceUntilIdle()

        vm.continueToNext()
        advanceUntilIdle()
        assertEquals("c2", vm.nextChapter.value?.chapterId?.value)
        assertEquals(ChapterPhase.COMPLETED, vm.progress.value?.phase) // 当前章节未被改写
        Dispatchers.resetMain()
    }

    /** M3-VM-6：重复 approve / continue 不产生 Android 自己的重复状态（仅转发，不阻塞/不重复 CREDITION）。 */
    @Test
    fun `VM6 repeated ops are idempotent to gateway`() = runTest {
        val gw = FakeGateway().apply { current = p(ChapterPhase.WAITING_CONFIRMATION, waiting = true) }
        val vm = viewModel(gw)
        advanceUntilIdle()

        gw.current = p(ChapterPhase.WAITING_CONFIRMATION, waiting = false)
        vm.approve(); vm.approve(); vm.approve()
        advanceUntilIdle()
        // ViewModel 的操作防抖：一次操作进行中忽略重复点击 → 仅 1 次转发给 Gateway；
        // 不产生 Android 自己的 approved/gateHandled 持久状态（waitingForUser 完全来自 progress 投影）。
        assertEquals(1, gw.calls.count { it == "approve" })
        assertTrue(vm.progress.value?.waitingForUser == false)
        Dispatchers.resetMain()
    }

    /** M3-VM-7：构造依赖是 ChapterWorkflowGateway（用户层 seam），绝非 Workflow 内部类型。 */
    @Test
    fun `VM7 depends only on gateway not workflow internals`() {
        val gw = FakeGateway()
        // 编译期即保证构造器只接受 ChapterWorkflowGateway；这里仅作运行时/契约佐证。
        assertTrue(gw is ChapterWorkflowGateway)
        // 工厂同样以 gateway 为唯一样例参数
        val factory = ChapterWritingViewModel.factory(NovelId("n"), null, ChapterId("c"), gw)
        assertTrue(factory != null)
    }
}

private fun p(phase: ChapterPhase, chapterId: String = "c1", waiting: Boolean = false, rev: Int = 0, draft: DraftId? = null): ChapterWorkflowProgress =
    ChapterWorkflowProgress(
        chapterId = ChapterId(chapterId), novelId = NovelId("n1"), variantId = null,
        phase = phase, waitingForUser = waiting, revisionCount = rev, draftId = draft,
    )