package com.qianyan.application.usecase.writing.revision

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.TaskType
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * P11.4 Revision 测试（manifest：WorkingDraft 正文 → Critique → Gate → Revision 闭环）。
 *
 * 经 ApplicationContainer + Mock LLM（按 Agent 名返回不同产物）验证：
 *  - `executeIntoWorkingDraft` 产 WorkingDraft（不落 ChapterDraft）
 *  - `reviseWorking` 经 RevisionGate（allowed）返回修订正文，revisionCount 增加
 *  - 修订本体不持久化章节 Draft（Phase 1 直写边界）
 *  - revision 达上限 → RevisionNotAllowed，**不调用 LLM**
 * 全程 Mock LLM，无网络。
 */
class RevisionExecutionTest {

    private var llmCalls = 0

    private val originalContent = "女主推开古碑。"
    private val revisedContent = "女主推开古碑，雷劫降临。"

    /** 按 Agent 名返回对应产物的注入 Mock LLM（每次调用计数）。 */
    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        llmCalls++
        val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
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

    private fun plan(app: ApplicationContainer, novelId: NovelId): ChapterPlan {
        val chapterId = app.chapters.createNextChapter("第一章", novelId).chapterId
        return ChapterPlan(
            chapterPlanId = ChapterPlanId("plan-rev"),
            chapterId = chapterId,
            arcId = ArcId("arc-rev"),
            actId = ActId("act-rev"),
            novelId = novelId,
            scope = VariantScope.ORIGINAL,
            chapterGoal = "救下女主并揭开秘辛",
            expectedEvents = listOf("发现古碑"),
        )
    }

    /* 全闭环：WorkingDraft → critique → gate(allowed) → revision 正文 → REVISION Checkpoint */
    @Test
    fun `working draft critique gate revision closes loop and survives reopen`() {
        val tmp = Files.createTempFile("qianyan-revision", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            llmCalls = 0
            var app = containerFile("jdbc:sqlite:$tmp", handles, gateway())
            val novelId = app.novels.createOriginal(title = "测试仙侠")
            val taskId = app.tasks.create(TaskType.WRITING)

            // P1-02c+d：AI 产物是 WorkingDraft
            val working = app.writingExecution.executeIntoWorkingDraft(taskId, request(novelId), plan(app, novelId))
            assertEquals(originalContent, working.content)

            // Critique 结果由调用方提供（内容门控不伪造 Draft）
            val critique = ValidationResult(passed = false)

            // Revision：gate allowed → 修订正文（不落 ChapterDraft）
            val revised = app.revision.reviseWorking(
                taskId,
                working.workingDraftId.value,
                working.content,
                critique,
                novelId,
            )
            assertEquals(revisedContent, revised)

            // revisionCount 已增加（WRITING + REVISION = 2 个 checkpoint）
            assertEquals(2, app.tasks.findById(taskId).revisionCount)

            // Phase 1：AI 写作/修订都不写 ChapterDraft
            assertEquals(0, app.draftRepository.listByNovel(novelId).size)

            // Checkpoint 序列：WRITING / REVISION（最新为 REVISION）
            val stages = app.tasks.findCheckpoints(taskId).map { it.stage }
            assertEquals(listOf("WRITING", "REVISION"), stages)
            assertEquals("REVISION", app.tasks.restoreCheckpoint(taskId).stage)

            // reopen：任务级状态一致；WorkingDraft 是进程内工作区 → 引用失效必须显式返回 null
            app = containerFile("jdbc:sqlite:$tmp", handles, gateway())
            assertEquals(2, app.tasks.findById(taskId).revisionCount)
            assertEquals("REVISION", app.tasks.restoreCheckpoint(taskId).stage)
            assertEquals(null, app.writingExecution.workingDraftFrom(app.tasks.restoreCheckpoint(taskId)))
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
        val working = app.writingExecution.executeIntoWorkingDraft(taskId, request(novelId), plan(app, novelId))
        val critique = ValidationResult(passed = false)

        app.revision.reviseWorking(taskId, working.workingDraftId.value, working.content, critique, novelId)
        app.revision.reviseWorking(taskId, working.workingDraftId.value, working.content, critique, novelId)
        // 现在 revisionCount == 3（WRITING+REVISION+REVISION），已达上限
        assertEquals(3, app.tasks.findById(taskId).revisionCount)

        val callsBefore = llmCalls
        val ex = assertFailsWith<ApplicationException> {
            app.revision.reviseWorking(taskId, working.workingDraftId.value, working.content, critique, novelId)
        }
        assertIs<ApplicationError.RevisionNotAllowed>(ex.error)
        // Gate 拒绝 → 不进入 LLM / 不产生新 checkpoint
        assertEquals(callsBefore, llmCalls)
        assertEquals(3, app.tasks.findCheckpoints(taskId).size)
    }

    /* 非 WRITING Task 经 reviseWorking → InvalidOperation */
    @Test
    fun `non writing task rejected by revision`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val id = app.tasks.create(TaskType.PLANNING)
        val ex = assertFailsWith<ApplicationException> {
            app.revision.reviseWorking(id, "wd-standalone", originalContent, ValidationResult(passed = true), NovelId("novel-standalone"))
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
            app.revision.reviseWorking(id, "wd-standalone", originalContent, ValidationResult(passed = true), NovelId("novel-standalone"))
        }
        assertIs<ApplicationError.InvalidRevisionOutput>(ex.error)
        // 失败不产生 REVISION checkpoint
        assertEquals(0, app.tasks.findCheckpoints(id).size)
    }
}
