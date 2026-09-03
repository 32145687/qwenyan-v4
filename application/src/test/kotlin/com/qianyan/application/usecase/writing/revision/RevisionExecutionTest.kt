package com.qianyan.application.usecase.writing.revision

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.task.TaskType
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderRequest
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * P11.4 Revision 测试（manifest：Draft → Critique → Gate → Revision 闭环）。
 *
 * 经 ApplicationContainer + Mock LLM（按 Agent 名返回不同产物）验证：
 *  - executeWriting 产原 Draft v1（P11.3 链路）
 *  - executeCritique 产 CRITIQUE Checkpoint
 *  - executeRevision 经 RevisionGate（allowed）产 REVISED Draft v2，revisionCount 增加、原 Draft 保留
 *  - reopen 后所有 Draft / Checkpoint 仍一致
 *  - revision 达上限 → RevisionNotAllowed，**不调用 LLM**
 * 全程 Mock LLM，无网络。
 */
class RevisionExecutionTest {

    private var llmCalls = 0

    private val originalContent = "女主推开古碑。"
    private val revisedContent = "女主推开古碑，雷劫降临。"
    private val critiqueJson = """{"passed":false,"issues":[{"message":"结局张力不足"}]}"""

    /** 按 Agent 名返回对应产物的注入 Mock LLM（每次调用计数）。 */
    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        llmCalls++
        val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryCriticAgent" in agent -> critiqueJson
            "StoryRevisionAgent" in agent -> """{"content":"$revisedContent"}"""
            else -> """{"content":"$originalContent"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun containerFile(url: String, handles: MutableList<QianyanDbHandle>, gateway: MockLLMGateway): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway)
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-rev"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun plan(novelId: NovelId) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-rev"),
        chapterId = ChapterId("ch-rev"),
        arcId = ArcId("arc-rev"),
        actId = ActId("act-rev"),
        novelId = novelId,
        scope = VariantScope.ORIGINAL,
        chapterGoal = "救下女主并揭开秘辛",
        expectedEvents = listOf("发现古碑"),
    )

    /* 全闭环：write(Draft v1) → critique → gate(allowed) → revision(Draft v2) → REVISION Checkpoint → reopen 一致 */
    @Test
    fun `draft critique gate revision closes loop and survives reopen`() {
        val tmp = Files.createTempFile("qianyan-revision", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            llmCalls = 0
            var app = containerFile("jdbc:sqlite:$tmp", handles, gateway())
            val novelId = app.novels.createOriginal(title = "测试仙侠")
            val taskId = app.tasks.create(TaskType.WRITING)

            // P11.3：原 Draft v1
            val v1 = app.taskRunner.executeWriting(taskId, request(novelId), plan(novelId))
            assertEquals(DraftStatus.WRITTEN, v1.status)
            assertEquals(originalContent, v1.content)

            // Critique：返回 ValidationResult + CRITIQUE Checkpoint
            val critique = app.taskRunner.executeCritique(taskId, v1)
            assertIs<com.qianyan.model.spec.ValidationResult>(critique)
            assertEquals(false, critique.passed)

            // Revision：gate allowed → REVISED Draft v2
            val v2 = app.taskRunner.executeRevision(taskId, v1, critique)
            assertNotEquals(v1.draftId, v2.draftId)
            assertEquals(DraftStatus.REVISED, v2.status)
            assertEquals(revisedContent, v2.content)
            assertEquals(v1.novelId, v2.novelId)

            // revisionCount 已增加（WRITING + CRITIQUE + REVISION = 3 个 checkpoint）
            assertEquals(3, app.tasks.findById(taskId).revisionCount)

            // 原 Draft 保留，修订 Draft 已持久化
            assertEquals(originalContent, app.draftRepository.getById(v1.draftId)!!.content)
            assertEquals(DraftStatus.WRITTEN, app.draftRepository.getById(v1.draftId)!!.status)
            assertEquals(revisedContent, app.draftRepository.getById(v2.draftId)!!.content)
            assertEquals(DraftStatus.REVISED, app.draftRepository.getById(v2.draftId)!!.status)

            // Checkpoint 序列：WRITING / CRITIQUE / REVISION（最新为 REVISION）
            val stages = app.tasks.findCheckpoints(taskId).map { it.stage }
            assertEquals(listOf("WRITING", "CRITIQUE", "REVISION"), stages)
            assertEquals("REVISION", app.tasks.restoreCheckpoint(taskId).stage)

            // reopen：所有状态一致
            app = containerFile("jdbc:sqlite:$tmp", handles, gateway())
            assertEquals(originalContent, app.draftRepository.getById(v1.draftId)!!.content)
            assertEquals(revisedContent, app.draftRepository.getById(v2.draftId)!!.content)
            assertEquals(3, app.tasks.findById(taskId).revisionCount)
            assertEquals("REVISION", app.tasks.restoreCheckpoint(taskId).stage)
        } finally {
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* gate 达上限（revisionCount=3）：RevisionNotAllowed，不得调用 LLM，且无新 checkpoint */
    @Test
    fun `revision at max is rejected without calling llm`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "测试")
        val taskId = app.tasks.create(TaskType.WRITING)

        val v1 = app.taskRunner.executeWriting(taskId, request(novelId), plan(novelId))
        val critique = app.taskRunner.executeCritique(taskId, v1)
        app.taskRunner.executeRevision(taskId, v1, critique)
        // 现在 revisionCount == 3（WRITING+CRITIQUE+REVISION），已达上限
        assertEquals(3, app.tasks.findById(taskId).revisionCount)

        val callsBefore = llmCalls
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeRevision(taskId, v1, critique)
        }
        assertIs<ApplicationError.RevisionNotAllowed>(ex.error)
        // Gate 拒绝 → 不进入 LLM / 不产生新 Draft
        assertEquals(callsBefore, llmCalls)
        assertEquals(3, app.tasks.findCheckpoints(taskId).size)
    }

    /* 非 WRITING Task 经 executeRevision → InvalidOperation */
    @Test
    fun `non writing task rejected by revision`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val id = app.tasks.create(TaskType.PLANNING)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeRevision(id, originalDraft(), com.qianyan.model.spec.ValidationResult(passed = true))
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }

    /* Revision 输出非法 → InvalidRevisionOutput（经 RevisionAgent 复用 DraftParser 转写） */
    @Test
    fun `illegal revision output fails typed`() {
        val bad = MockLLMGateway { req ->
            llmCalls++
            val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = if ("StoryRevisionAgent" in agent) "不是 JSON" else """{"content":"$originalContent"}"""
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val app = ApplicationContainer.open(analysisGateway = bad)
        val id = app.tasks.create(TaskType.WRITING)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeRevision(id, originalDraft(), com.qianyan.model.spec.ValidationResult(passed = true))
        }
        assertIs<ApplicationError.InvalidRevisionOutput>(ex.error)
        // 失败不产生 REVISION checkpoint
        assertEquals(0, app.tasks.findCheckpoints(id).size)
    }

    private fun originalDraft(): com.qianyan.model.writing.Draft = com.qianyan.model.writing.Draft(
        draftId = com.qianyan.model.DraftId("d-standalone"),
        novelId = NovelId("novel-standalone"),
        content = originalContent,
        status = DraftStatus.WRITTEN,
        createdAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
        updatedAt = kotlinx.datetime.Instant.fromEpochMilliseconds(0),
    )
}