package com.qianyan.application.usecase.writing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.task.TaskType
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P11.7 / 官方 P11 完成标准（Preflight §17 验收 #1）：
 * 单章节 Writing slice 端到端(E2E)跑通 ——
 *   目标 → 规划(PLANNING) → 写作(WRITING) → 评审(CRITIQUE) → 修订(REVISION) → 知识沉淀(KNOWLEDGE_UPDATE)
 *   → Story World Context → close/reopen 恢复一致。
 * 全程 Mock LLM（按 Agent 名返回对应结构化产物），无网络、无真实 API。
 */
class P11_7WritingSliceE2ETest {

    private var llmCalls = 0

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        llmCalls++
        val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryPlannerAgent" in agent -> """{"chapterGoal":"揭开古碑秘辛"}"""
            "StoryWriterAgent" in agent -> """{"content":"女主推开古碑。"}"""
            "StoryCriticAgent" in agent -> """{"passed":true}"""
            "StoryRevisionAgent" in agent -> """{"content":"女主推开古碑，雷劫降临。"}"""
            "KnowledgeUpdateAgent" in agent ->
                """{"changes":[{"changeId":"k1","operation":"ADD","target":"古碑","content":"古碑是上古传送阵"}]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-e2e"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun openContainer(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())
    }

    @Test
    fun `end to end writing slice closes loop and survives reopen`() {
        val tmp = Files.createTempFile("qianyan-e2e", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            llmCalls = 0
            var app = openContainer("jdbc:sqlite:$tmp", handles)
            val novelId = app.novels.createOriginal(title = "星辰大陆")
            val request = request(novelId)

            // 1) PLANNING Task → ChapterPlan
            val planTaskId = app.tasks.create(TaskType.PLANNING)
            val plan = app.taskRunner.executePlanning(planTaskId, request)
            assertEquals("揭开古碑秘辛", plan.chapterGoal)

            // 2) WRITING Task → Draft v1
            val writeTaskId = app.tasks.create(TaskType.WRITING)
            val v1 = app.taskRunner.executeWriting(writeTaskId, request, plan)
            assertEquals(DraftStatus.WRITTEN, v1.status)

            // 3) CRITIQUE
            val critique = app.taskRunner.executeCritique(writeTaskId, v1)
            assertNotNull(critique)

            // 4) REVISION → Draft v2（gate 允许，revisionCount ≤ 3）
            val v2 = app.taskRunner.executeRevision(writeTaskId, v1, critique)
            assertEquals(DraftStatus.REVISED, v2.status)
            assertTrue(v2.draftId != v1.draftId)

            // P12.1.4：KU 前需最终稿 + 确认（confirm 不调用 LLM，llmCalls 保持 5）。
            app.draftRepository.save(v2.copy(status = DraftStatus.FINAL))
            val confirmedV2 = app.confirmations.confirmFinalDraft(v2.draftId, v2.novelId, v2.variantId)
            assertEquals(DraftStatus.CONFIRMED, confirmedV2.status)

            // 5) KNOWLEDGE_UPDATE Task（官方验收 #2：独立 KNOWLEDGE_UPDATE 类型可执行）
            val kuTaskId = app.tasks.create(TaskType.KNOWLEDGE_UPDATE)
            val outcome = app.taskRunner.executeKnowledgeUpdate(kuTaskId, confirmedV2)
            assertEquals(1, outcome.applied.size)
            assertTrue(app.tasks.findById(kuTaskId).revisionCount >= 1)

            // 6) Story World Context：knowledge 沉淀可被 Resolver 读到（memory 层）
            val world = app.storyWorldContextResolver.resolve(novelId, worldSummary = "星辰大陆")
            assertTrue(world.memories.any { it.contains("古碑是上古传送阵") })

            // Mock LLM 调用 = plan/write/critique/revise/knowledge-update = 5
            assertEquals(5, llmCalls)

            // 7) close → reopen：v1/v2、checkpoint、memory 上下文全部一致
            app = openContainer("jdbc:sqlite:$tmp", handles)
            assertEquals(v1.content, app.draftRepository.getById(v1.draftId)!!.content)
            assertEquals(v2.content, app.draftRepository.getById(v2.draftId)!!.content)
            // v2 经最终稿 + 确认 → 持久化状态为 CONFIRMED
            assertEquals(DraftStatus.CONFIRMED, app.draftRepository.getById(v2.draftId)!!.status)
            // WRITING Task checkpoint 序列
            assertEquals(listOf("WRITING", "CRITIQUE", "REVISION"), app.tasks.findCheckpoints(writeTaskId).map { it.stage })
            // KNOWLEDGE_UPDATE Task checkpoint
            assertEquals("KNOWLEDGE_UPDATE", app.tasks.restoreCheckpoint(kuTaskId).stage)
            // knowledge 沉淀仍可解析为 World Context
            val reopenedWorld = app.storyWorldContextResolver.resolve(novelId)
            assertTrue(reopenedWorld.memories.any { it.contains("古碑是上古传送阵") })
        } finally {
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    @Test
    fun `knowledge update task type is accepted by runner`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "T")
        // 独立 KNOWLEDGE_UPDATE 类型 Task 可执行（官方验收 #2）
        val kuId = app.tasks.create(TaskType.KNOWLEDGE_UPDATE)
        val draft = com.qianyan.model.writing.Draft(
            draftId = com.qianyan.model.DraftId("d-ku-e2e"),
            novelId = novelId,
            content = "古碑显现。",
            status = DraftStatus.CONFIRMED,
            createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
            updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
        )
        app.draftRepository.save(draft)
        val outcome = app.taskRunner.executeKnowledgeUpdate(kuId, draft)
        assertEquals(1, outcome.applied.size)
    }
}