package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-O54 · 续篇目标章已有 active Workflow 时的确定性边界。
 *
 * 缺陷（R7 复现）：`continueToNextWorkflow` 不检查目标章占位，直接按确定性 id 新建 CONTINUE；
 * 目标章原有 `WAITING_HUMAN` 行仍在，`singleWorkflowByActiveChapter`（无 ORDER BY）可能命中旧行，
 * advance 落到旧流程 → O-53 降级，新 CONTINUE 停在 CREATED。
 *
 * 断言：
 *  ① 目标章 `WAITING_HUMAN` + 工作产物已丢（跨重启）⇒ 不新建、明确降级，Workflow/Gate 总数不变；
 *  ② 目标章 `WAITING_HUMAN` + 产物可解析 ⇒ 复用该 Workflow 推进，不新建、不重复建 Gate；
 *  ③ 目标章无 Workflow ⇒ 正常新建 1 条 CONTINUE 并进入 PENDING Gate；
 *  ④ 重复续篇 3 次 ⇒ 同一章始终 1 条 active Workflow、1 个 PENDING Gate。
 */
class ContinueWithExistingActiveWorkflowTest {

    private fun okGateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"推进主线","mainConflict":"c","expectedEvents":["e"]}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys -> """{"changes":[]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun openFile(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val h = QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = okGateway())
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { h -> (h.driver as? JdbcSqliteDriver)?.getConnection()?.close() }
        handles.clear()
    }

    private fun chapter(app: ApplicationContainer, novelId: NovelId, title: String): ChapterId =
        app.chapters.createNextChapter(title = title, novelId = novelId).chapterId

    /** 真实链路跑到 COMPLETED（人工门显式批准）。 */
    private fun completeChapter(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) {
        var guard = 0
        while (guard++ < 12) {
            val progress = app.workflowFacade.getChapterProgress(chapterId)
            when {
                progress.phase == ChapterPhase.COMPLETED -> return
                progress.waitingForUser -> app.workflowFacade.approve(chapterId)
                else -> app.writerGateway.continueWriting(novelId, null, chapterId)
            }
        }
        assertEquals(ChapterPhase.COMPLETED, app.workflowFacade.getChapterProgress(chapterId).phase)
    }

    /** 走真实生产入口让该章停在人工门，返回其 Workflow id 与 PENDING Gate id。 */
    private fun startToGate(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId): Pair<String, String> {
        app.writerGateway.continueWriting(novelId, null, chapterId)
        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "应建立 Workflow")
        return wf.workflowId.value to assertNotNull(wf.pendingGateId, "应停在人工门").value
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

    /* ---------- ① 产物已丢：明确降级，不新建 ---------- */

    @Test
    fun `continuation into a chapter whose live product is lost degrades without creating anything`() {
        val tmp = Files.createTempFile("qianyan-o54-lost", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "T-O54 产物丢失")
            val ch1 = chapter(app, novelId, "第一章")
            val ch2 = chapter(app, novelId, "第二章")
            completeChapter(app, novelId, ch1)
            val (ch2Wf, gateId) = startToGate(app, novelId, ch2)
            val workflowsBefore = countRows(handles.last(), "Workflow")
            val gatesBefore = countRows(handles.last(), "WorkflowHumanGate")

            closeAll(handles) // 进程退出：ch2 的 WorkingDraft（内存工作区）丢失

            app = openFile("jdbc:sqlite:$tmp", handles)
            assertEquals(
                WorkflowStatus.WAITING_HUMAN,
                app.workflowRepository.getWorkflow(com.qianyan.model.workflow.WorkflowId(ch2Wf))!!.status,
                "前置：旧流程仍 WAITING_HUMAN 占位",
            )

            val error = assertFailsWith<ApplicationException> { app.workflowFacade.continueToNextChapter(ch1) }
            assertTrue(
                "工作产物" in error.message.orEmpty(),
                "必须给明确降级文案，实际：${error.message}",
            )
            assertEquals(workflowsBefore, countRows(handles.last(), "Workflow"), "不得新建 Workflow")
            assertEquals(gatesBefore, countRows(handles.last(), "WorkflowHumanGate"), "不得新建 Gate")
            assertEquals(
                ch2Wf,
                app.workflowRepository.getWorkflowByActiveChapter(ch2)!!.workflowId.value,
                "占位仍指向旧行，不得切换到新行",
            )
            assertEquals(
                gateId,
                app.workflowRepository.getWorkflow(com.qianyan.model.workflow.WorkflowId(ch2Wf))!!.pendingGateId?.value,
                "不得替换 Gate",
            )
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /* ---------- ② 产物可解析：复用并推进 ---------- */

    @Test
    fun `continuation reuses the chapter's live workflow when its product is resolvable`() {
        val tmp = Files.createTempFile("qianyan-o54-reuse", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "T-O54 复用")
            val ch1 = chapter(app, novelId, "第一章")
            val ch2 = chapter(app, novelId, "第二章")
            completeChapter(app, novelId, ch1)
            val (ch2Wf, gateId) = startToGate(app, novelId, ch2)
            val workflowsBefore = countRows(handles.last(), "Workflow")

            val progress = app.workflowFacade.continueToNextChapter(ch1)
            assertEquals(ch2, progress.chapterId, "续篇应落在既有第二章")
            app.workflowFacade.advance(ch2)

            assertEquals(workflowsBefore, countRows(handles.last(), "Workflow"), "必须复用，不得新建 Workflow")
            val reused = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(ch2))
            assertEquals(ch2Wf, reused.workflowId.value, "必须复用目标章既有 Workflow")
            assertEquals(gateId, reused.pendingGateId?.value, "不得新建 / 替换 Gate")
            assertEquals(
                1L,
                countRows(handles.last(), "WorkflowHumanGate", "WHERE status = 'PENDING'"),
                "同一章恰 1 个 PENDING Gate",
            )
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /* ---------- ③ 目标章无 Workflow：正常新建 ---------- */

    @Test
    fun `continuation into a chapter without a workflow creates one and reaches a pending gate`() {
        val tmp = Files.createTempFile("qianyan-o54-clean", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "T-O54 干净续篇")
            val ch1 = chapter(app, novelId, "第一章")
            completeChapter(app, novelId, ch1)

            val progress = app.workflowFacade.continueToNextChapter(ch1)
            val ch2 = progress.chapterId
            app.workflowFacade.advance(ch2)

            val wf2 = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(ch2), "下一章应建立 Workflow")
            assertEquals(WorkflowKind.CONTINUE, wf2.kind)
            assertEquals(WorkflowStatus.WAITING_HUMAN, wf2.status, "应停在人工门")
            assertNotNull(wf2.pendingGateId, "应建立 PENDING Gate")
            assertEquals(2L, countRows(handles.last(), "Workflow"), "ch1 + ch2 恰两条 Workflow")
            assertEquals(
                1L,
                countRows(handles.last(), "WorkflowHumanGate", "WHERE status = 'PENDING'"),
                "恰 1 个 PENDING Gate",
            )
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /* ---------- ④ 重复续篇：收敛 ---------- */

    @Test
    fun `repeated continuation converges to one active workflow and one pending gate`() {
        val tmp = Files.createTempFile("qianyan-o54-repeat", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "T-O54 重复续篇")
            val ch1 = chapter(app, novelId, "第一章")
            completeChapter(app, novelId, ch1)

            repeat(3) {
                val target = app.workflowFacade.continueToNextChapter(ch1).chapterId
                app.workflowFacade.advance(target)
            }

            val chapters = app.chapters.listByNovel(novelId, null)
            assertEquals(2, chapters.size, "不得重复创建章节")
            val ch2 = chapters.last().chapterId
            assertEquals(
                1L,
                countRows(handles.last(), "Workflow", "WHERE active_chapter_id = '${ch2.value}'"),
                "同一章始终 1 条 active Workflow",
            )
            val active = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(ch2))
            assertEquals(WorkflowStatus.WAITING_HUMAN, active.status)
            assertNotNull(active.pendingGateId)
            assertEquals(
                1L,
                countRows(handles.last(), "WorkflowHumanGate", "WHERE status = 'PENDING'"),
                "同一章恰 1 个 PENDING Gate",
            )
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
    }
}
