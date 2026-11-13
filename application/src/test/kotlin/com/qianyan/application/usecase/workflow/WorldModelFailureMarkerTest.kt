package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.task.TaskStatus
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
import com.qianyan.storage.repository.SqliteWorldModelCandidateRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-O110（O-110）· 后台收尾的 WMU 失败也必须留可追溯失败标记。
 *
 * 33 号的快路径让 `PostCommitFinalizer` 直接调 `WorldModelUpdateUseCases.onCommit`，
 * 绕过了 `CommitUseCases.runWorldModelUpdate` 里"类型化失败 → recordFailure"的包装
 * ⇒ 后台失败只在 `Task.error` 可见，`CandidateFact` 没有标记（34 号真机实测：失败 2 次，标记数不变）。
 *
 * 断言：
 *  1. finalizer 遇到 `WorldModelUpdateException` → Task FAILED 且同一 historyId 恰 1 条失败标记；
 *  2. 成功路径不得写标记；
 *  3. 再次失败/重试不得重复写标记（同 historyId 至多 1 条）。
 */
class WorldModelFailureMarkerTest {

    private val invalidWorldModel = AtomicBoolean(false)

    private fun gateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.firstOrNull { it.role == ChatRole.SYSTEM }?.content.orEmpty()
        val user = req.messages.firstOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        val isWorldModel = "提取候选世界事实" in user
        val isKnowledgeUpdate = "KnowledgeUpdateAgent" in sys
        val body = when {
            isWorldModel && invalidWorldModel.get() -> "这不是 JSON"
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"推进主线","mainConflict":"c","expectedEvents":["遇险"]}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            isKnowledgeUpdate -> """{"changes":[]}"""
            isWorldModel ->
                """{"facts":[{"factId":"f1","kind":"EVENT","operation":"ADD","name":"雨夜提灯",""" +
                    """"summary":"主角在雨夜提灯行走","excerpt":"雪是她提灯走进这条巷子时落下来的","reason":"正文"}]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private class Fixture(
        val app: ApplicationContainer,
        val handle: QianyanDbHandle,
        val chapterId: ChapterId,
        val projectId: com.qianyan.model.ProjectId,
        val historyId: String,
    )

    private fun fixture(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T-O110 失败标记")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        assertTrue(app.workflowFacade.getChapterProgress(chapter.chapterId).waitingForUser, "前置：应停在人工门")
        app.workflowFacade.approveDecision(chapter.chapterId)
        val projectId = app.projects.projectOf(novelId).projectId
        val historyId = assertNotNull(
            app.commits.history(projectId).singleOrNull()?.historyId?.value,
            "前置：快路径必须已提交 Canonical",
        )
        return Fixture(app, handle, chapter.chapterId, projectId, historyId)
    }

    private fun markers(f: Fixture) =
        SqliteWorldModelCandidateRepository(f.handle.db).listByHistory(f.historyId)
            .filter { it.factName == FAILURE_MARKER_NAME }

    @Test
    fun `finalizer world model failure writes exactly one failure marker`() {
        val f = fixture()
        invalidWorldModel.set(true)

        f.app.postCommitFinalizer.resumeOnStartup()

        val task = f.app.taskRepository.findCheckpointsByStage(PostCommitFinalizer.STAGE, 200)
            .map { it.taskId }.distinct()
            .mapNotNull { f.app.taskRepository.findById(it) }
            .single()
        assertEquals(TaskStatus.FAILED, task.status, "WMU 失败必须落 Task FAILED")
        assertNotNull(task.error, "失败原因必须可见")

        val markers = markers(f)
        assertEquals(1, markers.size, "后台 WMU 失败必须写且只写 1 条失败标记（O-110）")
        assertTrue(
            markers.single().decisionReason.orEmpty().contains("World Model Update 流程失败"),
            "标记必须带失败原因前缀，实际=${markers.single().decisionReason}",
        )
        assertEquals(
            WorkflowStatus.RUNNING,
            assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(f.chapterId)).status,
            "WMU 失败不得推进 Workflow",
        )
    }

    @Test
    fun `successful world model update writes no failure marker`() {
        val f = fixture()

        f.app.postCommitFinalizer.resumeOnStartup()

        assertEquals(0, markers(f).size, "成功路径不得写失败标记")
        assertTrue(
            SqliteWorldModelCandidateRepository(f.handle.db).listByHistory(f.historyId)
                .any { it.factName == "雨夜提灯" },
            "成功路径应落正常候选",
        )
    }

    @Test
    fun `recording the same failure twice keeps exactly one marker`() {
        val f = fixture()
        val entry = f.app.commits.history(f.projectId).single()

        f.app.worldModelUpdate.recordFailure(entry, "第一次失败")
        f.app.worldModelUpdate.recordFailure(entry, "第二次失败")

        val markers = markers(f)
        assertEquals(1, markers.size, "同一 historyId 不得重复写第二条失败标记（recordFailure 幂等）")
        assertTrue(
            markers.single().decisionReason.orEmpty().contains("第一次失败"),
            "必须保留首条标记（后写不得覆盖/追加）",
        )
    }

    private companion object {
        const val AI_BODY = "雪是她提灯走进这条巷子时落下来的，很碎，落在灯笼上没有声音。"
        const val FAILURE_MARKER_NAME = "(非候选事实：抽取流程失败标记)"
    }
}
