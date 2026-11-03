package com.qianyan.app.desktop

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.qianyan.app.desktop.adapter.DesktopProviderAssembler
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterTaskStatus
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * T-O54 · Desktop 续篇入口在目标章已有 active Workflow 时的行为（阶段 C 的可执行等价）。
 *
 * 与 `DesktopContinueNextChapterTest` 同一真实链路（真实 SQLite + WriterController + Facade），
 * 追加目标章占位的两个场景：
 *  ① 目标章 WAITING_HUMAN 且工作产物随重启丢失 ⇒ UI 明确降级文案，不新建 Workflow / Gate；
 *  ② 目标章无 Workflow ⇒ 正常新建 1 条 CONTINUE 并进入 PENDING Gate。
 */
class DesktopContinueNextChapterExistingWorkflowTest {

    private val opened = mutableListOf<SqlDriver>()

    private fun openContainer(dir: Path): Pair<ApplicationContainer, QianyanDbHandle> {
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        opened += handle.driver
        val container = ApplicationContainer.fromDriver(
            handle.driver,
            DesktopProviderAssembler(DefaultProviderAssembler(InMemoryProviderCredentialStore())),
            ProviderConfiguration(ProviderType.MOCK),
        )
        return container to handle
    }

    private fun controllerFor(c: ApplicationContainer, novelId: NovelId, chapterId: ChapterId): WriterController =
        WriterController(
            novelId = novelId,
            chapter = assertNotNull(c.chapters.findById(chapterId)),
            gateway = c.writerGateway,
            workflow = c.workflowFacade,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

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

    @Test
    fun `continuation degrades clearly when the target chapter lost its live product`() {
        val dir = Files.createTempDirectory("qianyan-desktop-o54-lost")
        try {
            var (c, handle) = openContainer(dir)
            val novelId = c.novels.createOriginal(title = "T-O54 Desktop 丢失")
            val ch1 = c.chapters.createNextChapter(title = "第一章", novelId = novelId).chapterId
            val ch2 = c.chapters.createNextChapter(title = "第二章", novelId = novelId).chapterId
            completeChapter(c, novelId, ch1)
            c.writerGateway.continueWriting(novelId, null, ch2)
            val ch2Wf = assertNotNull(c.workflowRepository.getWorkflowByActiveChapter(ch2))
            assertNotNull(ch2Wf.pendingGateId, "前置：第二章应停在人工门")
            val workflowsBefore = countRows(handle, "Workflow")
            val gatesBefore = countRows(handle, "WorkflowHumanGate")
            handle.driver.close() // 进程退出：内存 WorkingDraft 丢失

            val (c2, handle2) = openContainer(dir)
            val commitsBefore = c2.commits.history(c2.projects.projectOf(novelId).projectId).size
            val controller = controllerFor(c2, novelId, ch1)
            controller.load()
            assertEquals(WriterTaskStatus.COMPLETED, controller.uiState.value.taskStatus, "前置：第一章 COMPLETED")

            var target: ChapterId? = null
            controller.continueToNextChapter { target = it }

            val error = assertNotNull(controller.uiState.value.error, "必须给明确降级文案")
            assertTrue("候选已失效" in error, "文案应说明候选已失效，实际：$error")
            assertTrue("重新发起任务" in error, "文案应给出下一步，实际：$error")
            assertNull(target, "降级时不得回调下一章")
            assertEquals(commitsBefore, c2.commits.history(c2.projects.projectOf(novelId).projectId).size, "降级不得产生 Commit")
            assertEquals(workflowsBefore, countRows(handle2, "Workflow"), "不得新建 Workflow")
            assertEquals(gatesBefore, countRows(handle2, "WorkflowHumanGate"), "不得新建 Gate")
            assertEquals(ch2Wf.workflowId, assertNotNull(c2.workflowRepository.getWorkflowByActiveChapter(ch2)).workflowId)
        } finally {
            opened.forEach { runCatching { it.close() } }
            opened.clear()
        }
    }

    @Test
    fun `continuation creates one workflow when the target chapter has none`() {
        val dir = Files.createTempDirectory("qianyan-desktop-o54-clean")
        try {
            val (c, handle) = openContainer(dir)
            val novelId = c.novels.createOriginal(title = "T-O54 Desktop 干净")
            val ch1 = c.chapters.createNextChapter(title = "第一章", novelId = novelId).chapterId
            completeChapter(c, novelId, ch1)

            val controller = controllerFor(c, novelId, ch1)
            controller.load()
            assertEquals(WriterTaskStatus.COMPLETED, controller.uiState.value.taskStatus)
            val commitsBefore = c.commits.history(c.projects.projectOf(novelId).projectId).size

            var target: ChapterId? = null
            controller.continueToNextChapter { target = it }
            val ch2 = assertNotNull(target, "续篇应回调下一章")

            assertEquals(commitsBefore, c.commits.history(c.projects.projectOf(novelId).projectId).size, "续篇进入人工门不得自动 Commit")

            assertEquals(2L, countRows(handle, "Workflow"), "ch1 + ch2 恰两条 Workflow")
            val wf2 = assertNotNull(c.workflowRepository.getWorkflowByActiveChapter(ch2))
            assertEquals(WorkflowKind.CONTINUE, wf2.kind)
            assertEquals(WorkflowStatus.WAITING_HUMAN, wf2.status)
            assertNotNull(wf2.pendingGateId)
            assertEquals(
                1L,
                countRows(handle, "WorkflowHumanGate", "WHERE status = 'PENDING'"),
                "恰 1 个 PENDING Gate",
            )
        } finally {
            opened.forEach { runCatching { it.close() } }
            opened.clear()
        }
    }
}
