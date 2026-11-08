package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.db.QueryResult
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.TaskId
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O72 · KU 任务状态生命周期（审批链自动执行的知识更新）。
 *
 * 缺陷：`WorkflowOrchestrator.executeKnowledgeUpdate` 只 create 不推进状态，库中 11 条
 * `Task(type=KNOWLEDGE_UPDATE)` 全部停留 PENDING；执行器只写 Checkpoint，不管理状态。
 *
 * 修复后的生产语义：KU 任务 PENDING → RUNNING → COMPLETED（成功）/ FAILED + error（失败且异常继续上抛）。
 * 历史 PENDING 行不回填（历史数据），本测试只约束新建任务。
 */
class KnowledgeUpdateTaskLifecycleTest {

    private fun gateway(failKnowledgeUpdate: Boolean): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.first { it.role == ChatRole.SYSTEM }.content
        if (failKnowledgeUpdate && ("KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys)) {
            throw ProviderException.ProviderUnavailable("O-72 simulated knowledge-update failure")
        }
        val body = when {
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}"""
            "StoryWriterAgent" in sys -> """{"content":"$AI_BODY"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys ->
                """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"$KU_FACT"}]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun open(gateway: LLMGateway): Pair<ApplicationContainer, QianyanDbHandle> {
        val handle = QianyanDbFactory.open()
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway) to handle
    }

    private fun seed(app: ApplicationContainer): Pair<NovelId, ChapterId> {
        val novelId = app.novels.createOriginal(title = "T-O72 测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        return novelId to chapter.chapterId
    }

    private fun driveToGate(app: ApplicationContainer, novelId: NovelId, chapterId: ChapterId) {
        app.writerGateway.continueWriting(novelId, null, chapterId)
        assertTrue(app.workflowFacade.getChapterProgress(chapterId).waitingForUser, "前置：应停在人工门")
    }

    /** 成功路径：批准后新建的 KU Task 必须走到终态 COMPLETED，且 Checkpoint 写在同一个 Task 上。 */
    @Test
    fun `approved knowledge update task ends COMPLETED`() {
        val (app, handle) = open(gateway(failKnowledgeUpdate = false))
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)

        app.workflowFacade.approve(chapterId)

        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId))
        val kuStep = app.workflowRepository.listSteps(wf.workflowId)
            .single { it.phase == WorkflowStepPhase.KNOWLEDGE_UPDATE }
        val ref = assertNotNull(kuStep.resultReference, "KU 步骤必须记录任务引用")
        assertTrue(ref.startsWith("KU:"), "KU 结果引用应为 KU:<taskId>，实际 $ref")

        val kuTask = app.tasks.findById(TaskId(ref.removePrefix("KU:")))
        assertEquals(TaskStatus.COMPLETED, kuTask.status, "KU 任务必须终态 COMPLETED，不得停留 PENDING")
        assertNull(kuTask.error, "成功任务不应带失败原因")
        assertEquals(1, kuTask.revisionCount, "KU Checkpoint 必须写在同一个任务上")

        assertEquals(1, kuTaskRows(handle).size, "批准链只应创建 1 条 KNOWLEDGE_UPDATE 任务")
    }

    /** 失败路径：KU 抛错 ⇒ Task FAILED 且 error 有文案，异常仍向上抛给调用方。 */
    @Test
    fun `failed knowledge update task ends FAILED and rethrows`() {
        val (app, handle) = open(gateway(failKnowledgeUpdate = true))
        val (novelId, chapterId) = seed(app)
        driveToGate(app, novelId, chapterId)

        val ex = assertFailsWith<ApplicationException> { app.workflowFacade.approve(chapterId) }
        assertTrue(
            ex.error is ApplicationError.ProviderUnavailable,
            "原类型化错误必须继续上抛（实际 ${ex.error::class.simpleName}）",
        )

        val row = kuTaskRows(handle).single()
        assertEquals("FAILED", row.status, "失败后 KU 任务必须终态 FAILED，不得停留 RUNNING/PENDING")
        val error = assertNotNull(row.error, "FAILED 任务必须记录失败原因")
        assertTrue(error.isNotBlank(), "失败原因不得为空")
        assertTrue("ProviderUnavailable" in error, "失败原因应包含类型化错误标识，实际: $error")
    }

    private data class KuTaskRow(val taskId: String, val status: String, val error: String?)

    /** 按类型查 Task（仓储无列表 API；失败路径拿不到 resultReference，只能直查）。 */
    private fun kuTaskRows(handle: QianyanDbHandle): List<KuTaskRow> =
        handle.driver.executeQuery(
            null,
            "SELECT task_id, status, error FROM Task WHERE type = 'KNOWLEDGE_UPDATE'",
            { cursor ->
                val rows = mutableListOf<KuTaskRow>()
                while (cursor.next().value) {
                    rows += KuTaskRow(
                        cursor.getString(0)!!,
                        cursor.getString(1)!!,
                        cursor.getString(2),
                    )
                }
                QueryResult.Value(rows)
            },
            0,
        ).value

    private companion object {
        const val AI_BODY = "AI 待审正文"
        const val KU_FACT = "已突破金丹期"
    }
}
