package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * O-55 · COMPLETED 章节占位不变式（裁决 C）。
 *
 * `activeChapterId` 是 chapter → workflow 的唯一寻址键（`ChapterWorkflowFacade` /
 * `WorkflowOrchestrator.continueToNextWorkflow` 都依赖它）。COMPLETED 时**不清空**；"同章单占位"
 * 由 `startChapter` 先查后建与 `runForward` 的终态分支保证。本测试把这三条现状钉成回归：
 *  ① 已完成章节再次 `startChapter` ⇒ 不新建、不重置，进度仍 COMPLETED；
 *  ② 对 COMPLETED workflow 再次 `advance` ⇒ 真 no-op，Step 数量不变；
 *  ③ 从 COMPLETED 章节 `continueToNextChapter` ⇒ 只给下一章建 Workflow，原章占位与状态不变。
 *
 * 断言全部基于生产链路产生的数据；不构造时间戳，也不依赖插入顺序。
 */
class CompletedChapterPlaceholderInvariantTest {

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

    private fun completedWorkflow(app: ApplicationContainer, chapterId: ChapterId) =
        assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId), "已完成章节必须仍可寻址")

    /* ---------- ① 已完成章节再次 startChapter：不新建、不重置 ---------- */

    @Test
    fun `startChapter on a completed chapter keeps the single workflow and COMPLETED progress`() {
        val tmp = Files.createTempFile("qianyan-o55-restart", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "O-55 再次 startChapter")
            val ch1 = chapter(app, novelId, "第一章")
            completeChapter(app, novelId, ch1)

            val before = completedWorkflow(app, ch1)
            assertEquals(WorkflowStatus.COMPLETED, before.status, "前置：章节必须已完成")
            val stepsBefore = app.workflowRepository.listSteps(before.workflowId).size

            val progress = app.workflowFacade.startChapter(novelId, null, ch1, WorkflowKind.WRITE_NOVEL)

            assertEquals(ChapterPhase.COMPLETED, progress.phase, "再次 startChapter 必须返回 COMPLETED，不得重置进度")
            assertEquals(ch1, progress.chapterId)
            val after = completedWorkflow(app, ch1)
            assertEquals(before.workflowId, after.workflowId, "不得新建第二条 Workflow")
            assertEquals(WorkflowStatus.COMPLETED, after.status, "不得重置状态")
            assertNull(after.pendingGateId, "已完成行不得残留待决 Gate")
            assertEquals(
                1L,
                countRows(handles.last(), "Workflow", "WHERE active_chapter_id = '${ch1.value}'"),
                "同一章必须仍只有 1 条 Workflow",
            )
            assertEquals(1L, countRows(handles.last(), "Workflow"), "不得新增任何 Workflow")
            assertEquals(stepsBefore, app.workflowRepository.listSteps(after.workflowId).size, "不得重建 Step")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /* ---------- ② 对 COMPLETED workflow 再次 advance：真 no-op ---------- */

    @Test
    fun `advance on a completed workflow is a true no-op`() {
        val tmp = Files.createTempFile("qianyan-o55-advance", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "O-55 再次 advance")
            val ch1 = chapter(app, novelId, "第一章")
            completeChapter(app, novelId, ch1)

            val before = completedWorkflow(app, ch1)
            val stepsBefore = app.workflowRepository.listSteps(before.workflowId)

            val progress = app.workflowFacade.advance(ch1)

            assertEquals(ChapterPhase.COMPLETED, progress.phase, "终态 advance 必须直接返回 COMPLETED")
            val after = assertNotNull(app.workflowRepository.getWorkflow(before.workflowId))
            assertEquals(WorkflowStatus.COMPLETED, after.status, "状态必须仍为 COMPLETED")
            assertNull(after.pendingGateId, "不得重新打开人工门")
            val stepsAfter = app.workflowRepository.listSteps(after.workflowId)
            assertEquals(stepsBefore.size, stepsAfter.size, "Step 数量不得变化")
            assertEquals(
                stepsBefore.map { it.stepId to it.status },
                stepsAfter.map { it.stepId to it.status },
                "Step 身份与状态都不得变化",
            )
            assertEquals(1L, countRows(handles.last(), "Workflow"), "advance 不得新建 Workflow")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    /* ---------- ③ 从 COMPLETED 章节续篇：只占下一章 ---------- */

    @Test
    fun `continuation from a completed chapter only places the next chapter`() {
        val tmp = Files.createTempFile("qianyan-o55-continue", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            val app = openFile("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "O-55 续篇占位")
            val ch1 = chapter(app, novelId, "第一章")
            completeChapter(app, novelId, ch1)

            val sourceBefore = completedWorkflow(app, ch1)
            val sourceStepsBefore = app.workflowRepository.listSteps(sourceBefore.workflowId).size

            val progress = app.workflowFacade.continueToNextChapter(ch1)
            val ch2 = progress.chapterId

            assertNotEquals(ch1, ch2, "续篇目标必须是下一章")
            assertEquals(2, app.chapters.listByNovel(novelId, null).size, "只应存在本章与下一章")

            val sourceAfter = completedWorkflow(app, ch1)
            assertEquals(sourceBefore.workflowId, sourceAfter.workflowId, "原章 Workflow 身份不变")
            assertEquals(ch1, sourceAfter.activeChapterId, "原章占位不得被移走")
            assertEquals(WorkflowStatus.COMPLETED, sourceAfter.status, "原章状态必须仍为 COMPLETED")
            assertNull(sourceAfter.pendingGateId, "原章不得残留待决 Gate")
            assertEquals(
                sourceStepsBefore,
                app.workflowRepository.listSteps(sourceAfter.workflowId).size,
                "续篇不得改动原章 Steps",
            )
            assertEquals(
                1L,
                countRows(handles.last(), "Workflow", "WHERE active_chapter_id = '${ch1.value}'"),
                "原章必须仍只有 1 条 Workflow",
            )

            val target = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(ch2), "下一章应建立 Workflow")
            assertEquals(ch2, target.activeChapterId, "新占位必须指向下一章")
            assertEquals(WorkflowKind.CONTINUE, target.kind)
            assertEquals(
                1L,
                countRows(handles.last(), "Workflow", "WHERE active_chapter_id = '${ch2.value}'"),
                "下一章恰 1 条 Workflow",
            )
            assertEquals(2L, countRows(handles.last(), "Workflow"), "续篇只新增下一章这一条")
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path.of(tmp))
        }
    }

    private companion object {
        const val AI_BODY = "AI 待审正文"
    }
}
