package com.qianyan.app.desktop

import app.cash.sqldelight.db.QueryResult
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterTaskStatus
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O33 · Desktop 续篇入口（`continueToNextChapter` 生产 caller）。
 *
 * 覆盖：
 *  ① 正常续篇 → 下一章 Workflow（`WorkflowKind.CONTINUE`）+ 人工门 PENDING（候选待确认）；
 *  ② 前章无已提交 Canonical → 明确降级文案，不建下一章 / 不建 Workflow / 不建 Gate；
 *  ③ 连点两次 → 收敛到同一个下一章 Workflow 与同一个 PENDING Gate。
 *
 * 全部走真实 SQLite + 真实 Application 层 + 真实 `ChapterWorkflowGateway`（无替身 UI）。
 */
class DesktopContinueNextChapterTest {

    private val opened = mutableListOf<app.cash.sqldelight.db.SqlDriver>()

    @AfterTest
    fun closeDrivers() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    /* ---------- fixtures ---------- */

    private fun openContainer(): Pair<ApplicationContainer, QianyanDbHandle> {
        val dir = Files.createTempDirectory("qianyan-desktop-o33")
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        opened += handle.driver
        val container = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
        return container to handle
    }

    private fun seedFirstChapter(c: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = c.novels.createOriginal(title = "O-33 续篇测试")
        val chapter = c.chapters.createNextChapter(title = "第 1 章 · 起笔", novelId)
        return novelId to chapter.chapterId
    }

    /** 测试用同步调度：调用返回即状态已更新（确定性断言）。 */
    private fun controllerFor(c: ApplicationContainer, novelId: NovelId, chapterId: ChapterId): WriterController =
        WriterController(
            novelId = novelId,
            chapter = assertNotNull(c.chapters.findById(chapterId)),
            gateway = c.writerGateway,
            workflow = c.workflowFacade,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

    /** 真实链路把第 1 章推进到 COMPLETED（人工门显式批准，不自动批准）。 */
    private fun completeChapter(c: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) {
        var guard = 0
        while (guard++ < 12) {
            val progress = c.workflowFacade.getChapterProgress(chapterId)
            when {
                progress.phase == ChapterPhase.COMPLETED -> return
                progress.waitingForUser -> c.workflowFacade.approve(chapterId)
                else -> c.writerGateway.continueWriting(novelId, null, chapterId)
            }
        }
        assertEquals(ChapterPhase.COMPLETED, c.workflowFacade.getChapterProgress(chapterId).phase)
    }

    private fun countRows(handle: QianyanDbHandle, table: String, where: String = ""): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table $where",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value

    /* ---------- ① 正常续篇 ---------- */

    @Test
    fun `continuation creates the next chapter workflow and a pending gate`() {
        val (c, handle) = openContainer()
        val (novelId, ch1) = seedFirstChapter(c)
        completeChapter(c, novelId, ch1)
        assertEquals(ChapterPhase.COMPLETED, c.workflowFacade.getChapterProgress(ch1).phase)

        val controller = controllerFor(c, novelId, ch1)
        controller.load()
        assertEquals(WriterTaskStatus.COMPLETED, controller.uiState.value.taskStatus, "前置：本章 COMPLETED")
        assertNull(controller.uiState.value.error)

        var target: ChapterId? = null
        controller.continueToNextChapter { target = it }
        val ch2 = assertNotNull(target, "续篇应回调下一章")

        val wf2 = assertNotNull(c.workflowRepository.getWorkflowByActiveChapter(ch2), "下一章应建立 Workflow")
        assertEquals(WorkflowKind.CONTINUE, wf2.kind)
        assertEquals(ch2, wf2.activeChapterId)
        assertEquals(WorkflowStatus.WAITING_HUMAN, wf2.status, "下一章应停在人工门")
        assertNotNull(wf2.pendingGateId, "应建立 PENDING Gate")
        assertEquals(
            "PENDING",
            c.workflowRepository.getGate(assertNotNull(wf2.pendingGateId))!!.status.name,
            "Gate 必须是 PENDING（等待用户确认）",
        )
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, c.workflowFacade.getChapterProgress(ch2).phase)
        assertEquals(2, c.chapters.listByNovel(novelId, null).size, "应创建第 2 章")
        assertEquals(2L, countRows(handle, "Workflow"), "ch1 + ch2 恰两个 Workflow")
    }

    /* ---------- ② 无 Canonical 的降级 ---------- */

    @Test
    fun `continuation without confirmed canonical degrades with an explicit message and no side effects`() {
        val (c, handle) = openContainer()
        val (novelId, ch1) = seedFirstChapter(c)
        val now = Clock.System.now()
        c.workflowRepository.createWorkflow(
            Workflow(
                workflowId = WorkflowId("wf:o33:no-canonical"),
                novelId = novelId, kind = WorkflowKind.WRITE_NOVEL,
                status = WorkflowStatus.COMPLETED, activeChapterId = ch1,
                createdAt = now, updatedAt = now,
            ),
        )

        val controller = controllerFor(c, novelId, ch1)
        controller.load()
        assertEquals(WriterTaskStatus.COMPLETED, controller.uiState.value.taskStatus, "前置：COMPLETED 但无 Canonical")

        var target: ChapterId? = null
        controller.continueToNextChapter { target = it }

        val error = assertNotNull(controller.uiState.value.error, "必须给明确降级文案，不得静默")
        assertTrue("已确认正文" in error, "文案应说明缺少已确认正文，实际：$error")
        assertNull(target, "降级时不得回调下一章")
        assertEquals(1, c.chapters.listByNovel(novelId, null).size, "不得创建下一章")
        assertEquals(1L, countRows(handle, "Workflow"), "不得创建新 Workflow")
        assertEquals(0L, countRows(handle, "WorkflowHumanGate"), "不得创建 Gate")
    }

    /* ---------- ③ 连点两次幂等 ---------- */

    @Test
    fun `repeated continuation clicks converge to one workflow and one pending gate`() {
        val (c, handle) = openContainer()
        val (novelId, ch1) = seedFirstChapter(c)
        completeChapter(c, novelId, ch1)

        val controller = controllerFor(c, novelId, ch1)
        controller.load()
        assertEquals(WriterTaskStatus.COMPLETED, controller.uiState.value.taskStatus)

        var first: ChapterId? = null
        var second: ChapterId? = null
        controller.continueToNextChapter { first = it }
        controller.continueToNextChapter { second = it }

        val target = assertNotNull(first)
        assertEquals(target, second, "连点两次应收敛到同一下一章")
        assertEquals(2L, countRows(handle, "Workflow"), "不得重复创建 Workflow")
        assertEquals(1L, countRows(handle, "WorkflowHumanGate", "WHERE status = 'PENDING'"), "不得重复创建 PENDING Gate")
        assertEquals(2, c.chapters.listByNovel(novelId, null).size, "不得重复创建章节")
    }
}
