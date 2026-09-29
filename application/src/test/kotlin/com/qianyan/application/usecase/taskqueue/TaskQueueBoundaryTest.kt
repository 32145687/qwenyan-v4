package com.qianyan.application.usecase.taskqueue

import com.qianyan.model.task.TaskStatus
import com.qianyan.model.taskqueue.TaskQueueItemStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I13 · Boundary（§19 / §20 / §24 / §26 / §28 N / §30 / §31）。
 *
 * 结构守卫证明：Background Queue **只做调度** ——
 * 不直连数据库 / 不是第二套 Task / 不是第二套状态机 / 不是第二套 Workflow / 不吸收既有 I3·I6·I10·I11·I12 /
 * 不依赖 Redis·Kafka·MQ / 不引入线程池 / 不建第二套 Registry。
 */
class TaskQueueBoundaryTest {

    private val queueDir = "src/main/kotlin/com/qianyan/application/usecase/taskqueue"
    private val modelDir = "../core/model/src/main/kotlin/com/qianyan/model/taskqueue"

    @Test
    fun `queue never touches storage implementations or other layers`() {
        val code = sourceOf(queueDir)
        listOf(
            // 不直连数据库（Worker 不得访问业务数据库，§20）
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "Sqlite", "PRAGMA", "sqlite_master",
            // 不调 LLM / Provider / Agent runtime
            "LLMGateway", "ModelProfile", "ProviderAssembler", "AgentRuntime",
            // 不重造 Agent 编排 / Workflow / Tool / Skill / Context（§19 / §24 / §30）
            "NovelAgent", "WorkflowOrchestrator", "WorkflowService", "ToolExecutor", "ToolRegistry",
            "ProductToolService", "ContextEngineUseCases", "ContextPack", "ContextSelector", "SkillRegistry",
            // 不把既有模型吸收进队列（§23 / §31）
            "AgentSession", "ProjectState", "CommitHistory", "ChapterDraft", "WorkingDraft", "ChangeArtifact",
            // 不依赖外部中间件 / 分布式调度（§26）
            "Redis", "Kafka", "RabbitMQ", "ThreadPool", "ExecutorService", "CoroutineScope",
            // 不建立第二套状态机 / 搜索基础设施
            "StateMachine", "Lucene", "Elasticsearch", "Embedding",
        ).forEach { token -> assertTrue(token !in code, "Background Queue 不得出现 '$token'（只做调度）") }

        // 复用证据（正面断言）：生命周期一律经既有 TaskManager，队列自身只有调度语义
        listOf(
            "TaskManagerUseCases", "taskManager.start", "taskManager.pause", "taskManager.resume",
            "taskManager.cancel", "taskManager.complete", "taskManager.fail",
            "TaskQueueOrdering", "TaskQueueItemStatus.QUEUED", "TaskQueueItemStatus.CLAIMED",
            "TaskQueueRepository", "tryClaim", "recoverExpiredLeases",
        ).forEach { token -> assertTrue(token in code, "队列必须复用既有 Task 生命周期能力：'$token'") }
    }

    @Test
    fun `queue depends on exactly one repository and never on sqlite`() {
        val code = sourceOf(queueDir)
        val storageImports = code.lines()
            .map { it.trim() }
            .filter { it.startsWith("import com.qianyan.storage") }
            .map { it.removePrefix("import ").trim() }

        assertEquals(
            listOf("com.qianyan.storage.repository.TaskQueueRepository"),
            storageImports.sorted(),
            "队列只允许依赖自身的调度条目仓储（不得直连 TaskRepository / SQLDelight / SQLite）",
        )
        listOf(
            "TaskRepository", "ChapterRepository", "NovelRepository", "DraftRepository", "WorkflowRepository",
            "CommitHistoryRepository", "AgentSessionRepository", "ProjectStateRepository",
        ).forEach { token -> assertTrue(token !in code, "队列不得直连 '$token'（必须经 Application 能力）") }
    }

    @Test
    fun `queue has no second lifecycle state machine`() {
        // 队列条目只有调度层状态：QUEUED / CLAIMED（业务结果一律由 Task 表达）
        assertEquals(
            listOf(TaskQueueItemStatus.QUEUED, TaskQueueItemStatus.CLAIMED),
            TaskQueueItemStatus.values().toList(),
            "队列不得拥有第二套业务生命周期状态",
        )
        // 业务生命周期仍完整属于既有 Task（未被队列取代 / 复制）
        listOf(
            TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.CANCELLED,
            TaskStatus.COMPLETED, TaskStatus.FAILED,
        ).forEach { status ->
            assertTrue(
                TaskQueueItemStatus.values().none { it.name == status.name },
                "队列不得复制 Task 状态 '${status.name}'",
            )
        }
    }

    @Test
    fun `queue models stay pure domain and do not redefine existing models`() {
        val code = sourceOf(modelDir)
        listOf(
            "SqlDriver", "QianyanDb", "storage", "LLMGateway", "Repository", "ContextPack", "ToolName",
            "Workflow", "AgentAction", "StateMachine",
        ).forEach { token -> assertTrue(token !in code, "I13 领域契约不得依赖 / 复制 '$token'") }

        listOf(
            "TaskQueueItem", "TaskQueueItemStatus", "TaskPriority", "TaskKind",
            "TaskQueueOrdering", "TaskQueueErrorCodes",
        ).forEach { token -> assertTrue(token in code, "缺少 I13 契约：'$token'") }

        // 条目只引用 taskId / projectId / kind（不复制业务负载，§9）
        listOf("val taskId: TaskId", "val projectId: ProjectId", "val kind: TaskKind").forEach { token ->
            assertTrue(token in code, "条目必须只存引用：'$token'")
        }
    }

    @Test
    fun `storage keeps the scheduling entry attached to the existing task table`() {
        val sq = File("../storage/src/main/sqldelight/com/qianyan/storage/db/TaskQueue.sq")
        assertTrue(sq.isFile, "缺少 I13 持久化定义：${sq.absolutePath}")
        val ddl = sq.readText()
        // 依附于既有 Task（不是第二个 Task 表）+ 同一 Task 至多一条（重复入队兜底）
        assertTrue("REFERENCES Task(task_id)" in ddl, "队列条目必须外键引用既有 Task")
        assertTrue("UNIQUE (task_id)" in ddl, "同一 Task 至多一条调度条目")
        // 存储层不得写入 Canonical 表
        listOf("Chapter ", "ChapterDraft", "StoryFoundation", "CommitHistory", "ProjectState").forEach { token ->
            assertTrue("CREATE TABLE $token" !in ddl && "UPDATE $token" !in ddl, "队列不得写 '$token'")
        }
        // migration 必须存在且为 additive
        val migration = File("../storage/src/main/sqldelight/com/qianyan/storage/db/21.sqm")
        assertTrue(migration.isFile, "缺少 v21 → v22 迁移：${migration.absolutePath}")
        assertTrue("CREATE TABLE IF NOT EXISTS TaskQueueItem" in migration.readText(), "迁移必须是 additive")
    }

    @Test
    fun `earlier phases stay untouched by queue wiring`() {
        // 既有能力不得被删除 / 重写
        listOf(
            "src/main/kotlin/com/qianyan/application/usecase/task/TaskManagerUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/task/TaskStateMachine.kt",
            "src/main/kotlin/com/qianyan/application/usecase/commit/CommitUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/index/ProjectIndexUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/agent/NovelAgent.kt",
            "src/main/kotlin/com/qianyan/application/usecase/context/ContextEngineUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/session/AgentSessionUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/workflow/WorkflowOrchestrator.kt",
            "../core/model/src/main/kotlin/com/qianyan/model/task/TaskModels.kt",
        ).forEach { path -> assertTrue(File(path).isFile, "既有能力不得被删除 / 重写：$path") }

        // 队列不得被塞进 I10 / I11 / I12 编排队列（保持各阶段职责独立）
        listOf(
            "src/main/kotlin/com/qianyan/application/usecase/agent",
            "src/main/kotlin/com/qianyan/application/usecase/commit",
            "src/main/kotlin/com/qianyan/application/usecase/index",
        ).forEach { dir ->
            assertTrue("TaskQueueUseCases" !in sourceOf(dir), "既有编排层不得被注入后台队列：$dir")
        }
    }

    private fun sourceOf(relativeDir: String): String {
        val dir = File(relativeDir)
        assertTrue(dir.isDirectory, "找不到源码目录：${dir.absolutePath}")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
    }
}