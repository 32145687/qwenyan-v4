package com.qianyan.application.usecase.task

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.WriterFacade
import com.qianyan.application.usecase.workflow.GateOutcome
import com.qianyan.model.TaskId
import com.qianyan.model.task.Checkpoint
import com.qianyan.model.task.Task
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.storage.repository.TaskRepository
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
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
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * O-76（裁决 A-26）· Task 生命周期归创建方：Critique / Revision / KnowledgeUpdate 任务必须收敛到终态。
 *
 * 断言口径全部读真实 Task 表（file-backed SQLite，只读 SQL 投影），不看内存对象：
 *  - 改写成功：critique 与 revision 两个 WRITING 任务均 COMPLETED；
 *  - 改写失败：无 PENDING 残留，任务为 FAILED 且 error 非空；
 *  - 工作流 CRITIQUE / REVISION：所有 WRITING 任务 COMPLETED；
 *  - WorkflowService 批准路径：KU 任务 COMPLETED；
 *  - Writing 前置校验失败：任务 FAILED 且 error 含原因（不再停在 PENDING）。
 * 全程脚本化 Gateway，无网络。
 */
class TaskLifecycleOwnershipTest {

    private data class TaskRow(val id: String, val type: String, val status: String, val error: String?, val progress: Double)

    private fun taskRows(handle: QianyanDbHandle): List<TaskRow> {
        val connection = (handle.driver as JdbcSqliteDriver).getConnection()
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT task_id, type, status, error, progress FROM Task ORDER BY created_at, rowid").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5)))
                    }
                }
            }
        }
    }

    /** file-backed 容器（同 URL 可二次打开做 SQL 投影），结束后删除临时库。 */
    private fun withEnv(gateway: LLMGateway, block: (ApplicationContainer, QianyanDbHandle) -> Unit) {
        val tmp = Files.createTempFile("qianyan-o76", ".db")
        val handle = QianyanDbFactory.open("jdbc:sqlite:$tmp")
        try {
            block(ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway), handle)
        } finally {
            runCatching { (handle.driver as JdbcSqliteDriver).getConnection().close() }
            Files.deleteIfExists(tmp)
        }
    }

    private data class Seeded(val novelId: NovelId, val chapterId: ChapterId, val base: Draft)

    /** 改写路径前置：novel + Project 运行态 + 有 Draft 的章节。 */
    private fun seed(app: ApplicationContainer): Seeded {
        val novelId = app.novels.createOriginal(title = "O-76 改写")
        app.projects.openProject(novelId)
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val now = Clock.System.now()
        val base = Draft(
            draftId = DraftId("o76-base"),
            novelId = novelId,
            variantId = null,
            scope = VariantScope.ORIGINAL,
            chapterId = chapter.chapterId,
            content = "原正文",
            format = DraftFormat.CONTROLLED_MARKDOWN,
            status = DraftStatus.DRAFTING,
            createdAt = now,
            updatedAt = now,
        )
        app.draftRepository.save(base)
        return Seeded(novelId, chapter.chapterId, base)
    }

    private fun answer(body: String): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
        usage = Usage(1, 1, 2),
        finishReason = FinishReason.STOP,
    )

    private fun okGateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        when {
            "StoryWriterAgent" in system -> answer("""{"content":"AI 生成正文"}""")
            "StoryRevisionAgent" in system -> answer("""{"content":"AI 修订正文"}""")
            "StoryCriticAgent" in system -> answer("""{"passed":true}""")
            "KnowledgeUpdateAgent" in system -> answer("""{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"突破金丹期"}]}""")
            else -> answer("""{"chapterGoal":"推进主线"}""")
        }
    }

    /** 评审阶段即失败的网关（Provider 级错误）。 */
    private fun criticFailsGateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        if ("StoryCriticAgent" in system) throw ProviderException.Timeout("mock critic timeout")
        when {
            "StoryWriterAgent" in system -> answer("""{"content":"AI 生成正文"}""")
            "StoryRevisionAgent" in system -> answer("""{"content":"AI 修订正文"}""")
            else -> answer("""{"chapterGoal":"推进主线"}""")
        }
    }

    /** 评审恒判 REVISION：驱动 Workflow 的 CRITIQUE + REVISION 两条执行路径。 */
    private fun alwaysRevisionGateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        when {
            "StoryWriterAgent" in system -> answer("""{"content":"AI 生成正文"}""")
            "StoryRevisionAgent" in system -> answer("""{"content":"AI 修订正文"}""")
            "StoryCriticAgent" in system -> answer("""{"passed":false}""")
            else -> answer("""{"chapterGoal":"推进主线"}""")
        }
    }

    /* 1. 改写成功：两个 WRITING 任务都 COMPLETED（此前均停在 PENDING） */
    @Test
    fun `rewrite completes critique and revision tasks`() {
        withEnv(okGateway()) { app, handle ->
            val seeded = seed(app)

            app.writerGateway.rewrite(seeded.novelId, null, seeded.chapterId)

            val writing = taskRows(handle).filter { it.type == "WRITING" }
            assertEquals(2, writing.size, "一次改写应只创建 critique + revision 两个 WRITING 任务")
            writing.forEach {
                assertEquals("COMPLETED", it.status, "任务 ${it.id} 未 COMPLETED")
                assertEquals(1.0, it.progress, "任务 ${it.id} progress 应为 1.0")
                assertNull(it.error, "任务 ${it.id} 不应有错误")
            }
        }
    }

    /* 2. 改写失败：无 PENDING 残留，任务 FAILED 且 error 非空 */
    @Test
    fun `failed rewrite leaves no pending writing task`() {
        withEnv(criticFailsGateway()) { app, handle ->
            val seeded = seed(app)

            assertFailsWith<ApplicationException> {
                app.writerGateway.rewrite(seeded.novelId, null, seeded.chapterId)
            }

            val writing = taskRows(handle).filter { it.type == "WRITING" }
            assertEquals(2, writing.size, "失败路径同样应创建 critique + revision 两个任务")
            assertTrue(writing.none { it.status == "PENDING" }, "失败后不得残留 PENDING：$writing")
            writing.forEach {
                assertEquals("FAILED", it.status, "任务 ${it.id} 应为 FAILED")
                assertFalse(it.error.isNullOrBlank(), "任务 ${it.id} 失败原因不得为空")
            }
        }
    }

    /* 3. 工作流 CRITIQUE / REVISION：所有 WRITING 任务 COMPLETED */
    @Test
    fun `workflow critique and revision tasks complete`() {
        withEnv(alwaysRevisionGateway()) { app, handle ->
            val novelId = app.novels.createOriginal(title = "O-76 工作流")
            val chapter = app.chapters.createNextChapter(title = "章", novelId = novelId)
            val wfId = WorkflowId("W-O76")
            app.workflowRepository.createWorkflow(
                Workflow(
                    wfId, novelId, null, WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.CREATED, activeChapterId = chapter.chapterId,
                    createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                ),
            )

            val result = app.workflowOrchestrator.runForward(wfId)

            assertEquals(WorkflowStatus.WAITING_HUMAN, result.status, "预算耗尽应停在人工门，而不是 FAILED")
            val phases = app.workflowRepository.listSteps(wfId).map { it.phase }.toSet()
            assertTrue(WorkflowStepPhase.CRITIQUE in phases, "工作流应执行过 CRITIQUE")
            assertTrue(WorkflowStepPhase.REVISION in phases, "工作流应执行过 REVISION")

            val writing = taskRows(handle).filter { it.type == "WRITING" }
            assertTrue(writing.size >= 3, "writing + critique + revision 至少 3 个任务，实际 ${writing.size}")
            assertTrue(writing.none { it.status == "PENDING" }, "工作流路径不得残留 PENDING：$writing")
            writing.forEach {
                assertEquals("COMPLETED", it.status, "任务 ${it.id} 未 COMPLETED")
            }
        }
    }

    /* 4. WorkflowService 批准路径：KU 任务终态 COMPLETED */
    @Test
    fun `workflow service approval completes knowledge update task`() {
        withEnv(okGateway()) { app, handle ->
            val novelId = NovelId("n-ku-o76")
            val chapterId = ChapterId("c-ku-o76")
            // 外键：ChapterDraft.novel_id → Novel，需先建父 Novel（显式 id）。
            app.novelRepository.createOriginal(
                Novel(
                    novelId = novelId, projectId = ProjectId("proj-$novelId"), title = "O-76 KU",
                    source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                    status = ProjectStatus.DRAFT, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                ),
            )
            val wfId = WorkflowId("W-KU-O76")
            val stepId = WorkflowStepId("S-KU-O76")
            app.workflowRepository.createWorkflow(
                Workflow(
                    wfId, novelId, null, WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.WAITING_HUMAN, activeChapterId = chapterId,
                    createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                ),
            )
            app.workflowRepository.createStep(
                WorkflowStep(
                    stepId, wfId, chapterId, WorkflowStepPhase.CONFIRMATION, "W-KU-O76:CONFIRMATION",
                    WorkflowStepStatus.RUNNING, createdAt = Clock.System.now(),
                ),
            )
            val now = Clock.System.now()
            val draft = Draft(
                draftId = DraftId("o76-ku-base"),
                novelId = novelId,
                variantId = null,
                scope = VariantScope.ORIGINAL,
                chapterId = chapterId,
                content = "已定稿正文",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.FINAL,
                createdAt = now,
                updatedAt = now,
            )
            app.draftRepository.save(draft)
            val gate = app.workflowService.createPendingGate(wfId, stepId, draft.draftId)

            assertIs<GateOutcome.Approved>(app.workflowService.approveGate(gate.gateId))

            val ku = taskRows(handle).filter { it.type == "KNOWLEDGE_UPDATE" }
            assertEquals(1, ku.size, "批准一次应只创建一个 KU 任务")
            assertEquals("COMPLETED", ku.single().status, "KU 任务未 COMPLETED")
            assertEquals(1.0, ku.single().progress)
            assertNull(ku.single().error)
        }
    }

    /* 5. Writing 前置校验失败：任务 FAILED 且 error 含原因（不再是 PENDING） */
    @Test
    fun `writing precondition failure fails the task with reason`() {
        withEnv(okGateway()) { app, _ ->
            val novelId = app.novels.createOriginal(title = "O-76 校验")
            val taskId = app.tasks.create(com.qianyan.model.task.TaskType.WRITING)
            val request = com.qianyan.model.context.UserWritingRequest(
                requestId = com.qianyan.model.RequestId("req-o76"),
                intentType = com.qianyan.model.IntentType.CONTINUE,
                target = com.qianyan.model.context.TargetRef(com.qianyan.model.context.TargetKind.CHAPTER, null),
                planningScope = com.qianyan.model.PlanningScope.CHAPTER,
                baseNovelId = com.qianyan.model.BaseNovelId(novelId.value),
            )
            val unboundPlan = com.qianyan.model.story.ChapterPlan(
                chapterPlanId = com.qianyan.model.ChapterPlanId("plan-o76"),
                chapterId = null,
                arcId = com.qianyan.model.ArcId("arc-o76"),
                actId = com.qianyan.model.ActId("act-o76"),
                novelId = novelId,
                scope = VariantScope.ORIGINAL,
                chapterGoal = "目标",
            )

            val ex = assertFailsWith<ApplicationException> {
                app.writingExecution.executeIntoWorkingDraft(taskId, request, unboundPlan)
            }
            assertIs<com.qianyan.application.error.ApplicationError.InvalidOperation>(ex.error)

            val task = app.tasks.findById(taskId)
            assertEquals(com.qianyan.model.task.TaskStatus.FAILED, task.status, "校验失败的任务应 FAILED，而不是 PENDING")
            assertTrue(task.error.orEmpty().contains("未绑定"), "失败原因应包含真实原因，实际=${task.error}")
            assertTrue(task.error!!.isNotBlank())
        }
    }

    /* 6. start 失败（O-92）：新任务 CANCELLED、已 start 的任务 FAILED，异常原样上抛 */
    @Test
    fun `start failure cancels new task and fails the started one`() {
        val tmp = Files.createTempFile("qianyan-o92", ".db")
        val handle = QianyanDbFactory.open("jdbc:sqlite:$tmp")
        try {
            val app = ApplicationContainer.fromDriver(handle.driver, analysisGateway = okGateway())
            val seeded = seed(app)
            val repo = FailingStartRepository(app.taskRepository)
            val facade = WriterFacade(
                chapters = app.chapters,
                projects = app.projects,
                novelRepository = app.novelRepository,
                drafts = app.writerUseCases,
                workflow = app.workflowFacade,
                critique = app.critique,
                revision = app.revision,
                taskManager = TaskManagerUseCases(repo, ErrorMapper),
                commits = app.commits,
                workingDrafts = app.workingDrafts,
                vocabularyRules = app.vocabularyRules,
                errorMapper = ErrorMapper,
            )

            val ex = assertFailsWith<IllegalStateException> {
                facade.rewrite(seeded.novelId, null, seeded.chapterId)
            }
            assertEquals("injected start failure", ex.message, "原始异常必须原样上抛")

            val writing = taskRows(handle).filter { it.type == "WRITING" }
            assertEquals(2, writing.size, "应创建 critique + revision 两个任务")
            assertTrue(writing.none { it.status == "PENDING" }, "start 失败后不得残留 PENDING：$writing")
            val failed = writing.filter { it.status == "FAILED" }
            val cancelled = writing.filter { it.status == "CANCELLED" }
            assertEquals(1, failed.size, "已 start 的任务应 FAILED：$writing")
            assertEquals(1, cancelled.size, "start 失败的新任务应 CANCELLED：$writing")
            assertFalse(failed.single().error.isNullOrBlank(), "FAILED 任务必须带失败原因")
        } finally {
            runCatching { (handle.driver as JdbcSqliteDriver).getConnection().close() }
            Files.deleteIfExists(tmp)
        }
    }

    /* 7. executeCritique 的 start 失败（O-92 收尾）：任务 CANCELLED，不留 PENDING，异常原样上抛 */
    @Test
    fun `critique start failure cancels the task instead of leaving it pending`() {
        val tmp = Files.createTempFile("qianyan-o92-critique", ".db")
        val handle = QianyanDbFactory.open("jdbc:sqlite:$tmp")
        try {
            val app = ApplicationContainer.fromDriver(handle.driver, analysisGateway = okGateway())
            val novelId = app.novels.createOriginal(title = "O-92 critique")
            val chapter = app.chapters.createNextChapter(title = "章", novelId = novelId)
            val wfId = WorkflowId("W-O92-CRITIQUE")
            app.workflowRepository.createWorkflow(
                Workflow(
                    wfId, novelId, null, WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.CREATED, activeChapterId = chapter.chapterId,
                    createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
                ),
            )
            // 委托仓储：第二个创建的 WRITING 任务（= critique 任务）的 start（update→RUNNING）抛错。
            val repo = FailingStartRepository(app.taskRepository)
            val orchestrator = com.qianyan.application.usecase.workflow.WorkflowOrchestrator(
                workflowRepository = app.workflowRepository,
                taskManager = TaskManagerUseCases(repo, ErrorMapper),
                writing = app.writingExecution,
                planning = app.planning,
                critique = app.critique,
                revision = app.revision,
                knowledgeUpdate = app.knowledgeUpdate,
                draftRepository = app.draftRepository,
                chapterRepository = app.chapterRepository,
                commits = app.commits,
                workingDrafts = app.workingDrafts,
                changes = app.changes,
                canonicalRead = app.canonicalRead,
                errorMapper = ErrorMapper,
            )

            val ex = assertFailsWith<IllegalStateException> { orchestrator.runForward(wfId) }
            assertEquals("injected start failure", ex.message, "原始异常必须原样上抛")

            val writing = taskRows(handle).filter { it.type == "WRITING" }
            assertEquals(2, writing.size, "writing + critique 两个 WRITING 任务")
            assertTrue(writing.none { it.status == "PENDING" }, "critique 的 start 失败后不得残留 PENDING：$writing")
            assertEquals(1, writing.count { it.status == "CANCELLED" }, "start 失败的 critique 任务应 CANCELLED：$writing")
            assertEquals(1, writing.count { it.status == "COMPLETED" }, "writing 任务应已完成：$writing")
        } finally {
            runCatching { (handle.driver as JdbcSqliteDriver).getConnection().close() }
            Files.deleteIfExists(tmp)
        }
    }
}

/** 委托仓储：对「第二个创建的 WRITING 任务的 start（update→RUNNING）」抛错，其余全部转发真实实现。 */
private class FailingStartRepository(private val delegate: TaskRepository) : TaskRepository {

    private var writingCreates = 0
    private var secondWritingTaskId: String? = null

    override fun create(task: Task): TaskId {
        val id = delegate.create(task)
        if (task.type == TaskType.WRITING) {
            writingCreates += 1
            if (writingCreates == 2) secondWritingTaskId = id.value
        }
        return id
    }

    override fun findById(taskId: TaskId): Task? = delegate.findById(taskId)

    override fun update(task: Task) {
        if (task.status == TaskStatus.RUNNING && task.taskId.value == secondWritingTaskId) {
            throw IllegalStateException("injected start failure")
        }
        delegate.update(task)
    }

    override fun delete(taskId: TaskId) = delegate.delete(taskId)

    override fun saveCheckpoint(checkpoint: Checkpoint) = delegate.saveCheckpoint(checkpoint)

    override fun findCheckpoints(taskId: TaskId): List<Checkpoint> = delegate.findCheckpoints(taskId)

    override fun findLatestCheckpoint(taskId: TaskId): Checkpoint? = delegate.findLatestCheckpoint(taskId)

    override fun findCheckpointsByStage(stage: String, limit: Long): List<Checkpoint> =
        delegate.findCheckpointsByStage(stage, limit)
}
