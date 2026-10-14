package com.qianyan.application.usecase.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I14 · Boundary（§3 / §4 / §17 / §21 / §22 / §24 / §25 / §26 / §28）。
 *
 * 结构守卫证明：Workspace / Context Inspector **只是只读投影 + 薄 Facade** ——
 * 不直连存储 / 不建第二套 Context·Activity·Change·Task·StateMachine / 不改 I1–I13 / 不改 P19·P20。
 */
class WorkspaceBoundaryTest {

    private val appDir = "src/main/kotlin/com/qianyan/application/usecase/workspace"
    private val modelDir = "../core/model/src/main/kotlin/com/qianyan/model/workspace"

    @Test
    fun `workspace never touches storage implementations or other layers`() {
        val code = sourceOf(appDir)
        listOf(
            // 不直连数据库（§24：UI / 投影不得访问 SQLDelight / SQLite / Database / SqlDriver）
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "Sqlite", "PRAGMA", "sqlite_master",
            // 不调 LLM / Provider / Agent runtime
            "LLMGateway", "ModelProfile", "AgentRuntime", "ProviderException",
            // 不重造既有编排层（§21 / §26）
            "NovelAgent", "WorkflowOrchestrator", "WorkflowService", "ToolExecutor", "ToolRegistry",
            "ProductToolService", "SkillRegistry", "ContextSelector",
            // 不建第二套状态机 / 搜索基础设施
            "StateMachine", "Lucene", "Elasticsearch", "Embedding",
            // 本地优先：不引入外部中间件 / 线程池
            "Redis", "Kafka", "RabbitMQ", "ThreadPool",
        ).forEach { token -> assertTrue(token !in code, "Workspace 不得出现 '$token'（只做只读投影）") }

        // 唯一允许的 storage 依赖是「只读 Story State」仓储（与 I6 / I12 同一范式）
        val storageImports = code.lines()
            .map { it.trim() }
            .filter { it.startsWith("import com.qianyan.storage") }
            .map { it.removePrefix("import ").trim() }
        assertEquals(
            listOf("com.qianyan.storage.repository.StoryStateRepository"),
            storageImports.sorted(),
            "Workspace 只允许复用只读 Story State 仓储",
        )
    }

    @Test
    fun `workspace reuses existing engines instead of redefining them`() {
        val code = sourceOf(appDir)
        listOf(
            // §3 / §10：Context 一律来自既有 I6（本类只投影）
            "ContextEngineUseCases", "contextEngine.build", "ContextPack", "ContextRequest",
            // §11：Activity 来自既有 I4
            "ActivityUseCases", "ToolCallLogUseCases", "listByActivity", "listByProject",
            // §12：Change / History 来自既有 I9 / I10
            "ChangeUseCases", "CommitUseCases",
            // §4：当前对象定位复用既有 I12（不新增索引 / 搜索）
            "ProjectIndexUseCases",
            // §19：Task 状态仍以既有 I13 / I1 为准
            "TaskQueueUseCases", "TaskManagerUseCases", "ProjectUseCases",
            // §16：会话链路复用既有 I3
            "AgentSessionUseCases",
        ).forEach { token -> assertTrue(token in code, "Workspace 必须复用既有能力：'$token'") }
    }

    @Test
    fun `workspace models stay pure domain and define no second model`() {
        val code = sourceOf(modelDir)
        listOf(
            "SqlDriver", "QianyanDb", "storage", "Repository", "StateMachine", "Workflow",
            "LLMGateway", "OpenAI", "http",
        ).forEach { token -> assertTrue(token !in code, "I14 领域契约不得依赖 / 复制 '$token'") }

        // 只定义「选择 + 投影」，不定义任何领域事实对象
        listOf(
            "WorkspaceSelection", "WorkspaceObjectKind", "InspectorTab",
            "WorkspaceInspectorView", "InspectorContextView", "InspectorActivityView",
            "InspectorChangeView", "InspectorHistoryView", "WorkspaceErrorCodes",
        ).forEach { token -> assertTrue(token in code, "缺少 I14 契约：'$token'") }

        // 选择只保存引用（不复制业务字段）
        listOf("val projectId: ProjectId", "val kind: WorkspaceObjectKind").forEach { token ->
            assertTrue(token in code, "选择必须只存引用：'$token'")
        }
        // Inspector 标签固定三个（§8）
        assertTrue(
            code.contains("enum class InspectorTab { CONTEXT, ACTIVITY, CHANGES }"),
            "Inspector 必须固定为 上下文 / 活动 / 修改 三个标签",
        )
        // 明确不定义没有稳定来源的对象类型（§6 不伪造）
        listOf("PLACE", "FACTION", "PLOT", "SCENE").forEach { token ->
            assertTrue(token !in code, "不得为没有稳定 Canonical 来源的对象定义选择类型：'$token'")
        }
    }

    @Test
    fun `earlier phases stay untouched by workspace wiring`() {
        listOf(
            "src/main/kotlin/com/qianyan/application/usecase/project/ProjectUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/context/ContextEngineUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/log/ActivityUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/log/ToolCallLogUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/change/ChangeUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/commit/CommitUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/index/ProjectIndexUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/taskqueue/TaskQueueUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/agent/NovelAgent.kt",
            "src/main/kotlin/com/qianyan/application/usecase/session/AgentSessionUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/task/TaskManagerUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/draft/WorkingDraftUseCases.kt",
            // P19 / P20 冻结面：文件必须仍在且不被 I14 改写
            "src/main/kotlin/com/qianyan/application/usecase/decision/DecisionModelUseCases.kt",
            "../core/model/src/main/kotlin/com/qianyan/model/writing/WritingModels.kt",
        ).forEach { path -> assertTrue(File(path).isFile, "既有能力 / 冻结面不得被删除：$path") }

        // 既有编排层不得被注入 Workspace（各阶段职责保持独立）
        listOf(
            "src/main/kotlin/com/qianyan/application/usecase/agent",
            "src/main/kotlin/com/qianyan/application/usecase/commit",
            "src/main/kotlin/com/qianyan/application/usecase/index",
            "src/main/kotlin/com/qianyan/application/usecase/taskqueue",
            "src/main/kotlin/com/qianyan/application/usecase/context",
        ).forEach { dir ->
            assertTrue("WorkspaceUseCases" !in sourceOf(dir), "既有阶段不得被注入 I14 Workspace：$dir")
        }

        // I14 不碰 P19 Decision Policy / P20 Draft 格式契约
        val code = sourceOf(appDir)
        listOf("DecisionPolicy", "DraftFormat", "CONTROLLED_MARKDOWN", "controlled_markdown").forEach { token ->
            assertTrue(token !in code, "I14 不得触碰 P19 / P20 契约：'$token'")
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