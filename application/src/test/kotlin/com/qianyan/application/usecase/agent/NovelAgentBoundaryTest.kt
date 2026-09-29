package com.qianyan.application.usecase.agent

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * I11 · Boundary（§3 / §4 / §12 / §20 / §22 / §26）。
 *
 * 结构守卫证明：
 *  - NovelAgent **只做编排决策**：不直连存储 / 不调 LLM / 不绕过 Tool / Skill / Context / Draft / Artifact /
 *    ActionPolicy / Commit，也没有第二套注册中心、状态机或权限系统；
 *  - **五个旧 Agent 保持**：文件与 `agentId` 均未被删除 / 重写；旧写作链 / Workflow / Task / AgentSession 仍在；
 *  - 不提前实现 I12+（Queue / Project Index / Context Inspector / UI），不是 Git（无 Branch / Merge）。
 */
class NovelAgentBoundaryTest {

    private val agentDir = "src/main/kotlin/com/qianyan/application/usecase/agent"
    private val modelDir = "../core/model/src/main/kotlin/com/qianyan/model/novelagent"

    @Test
    fun `novel agent only orchestrates and never bypasses existing capabilities`() {
        val code = sourceOf(agentDir)
        listOf(
            // 不直连存储 / 不写 Canonical 表
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "Sqlite", "Repository", "ChapterDraft",
            "StoryFoundation", "ProjectState",
            // 不调 LLM / Provider / Agent runtime（执行仍由既有 Agent / Application 能力完成）
            "LLMGateway", "ModelProfile", "provider.api", "provider.impl", "AgentRuntime",
            // 不绕过 I5：Tool 只能经 ProductToolService
            "ToolExecutor", "ToolRegistry", "ToolContext",
            // 不新建第二套权限 / 状态机 / 流程引擎
            "CommitPolicy", "RevertPolicy", "HistoryPolicy", "DecisionPolicy", "DecisionModel",
            "StateMachine", "WorkflowStatus", "TaskStatus", "WorkflowOrchestrator", "approveGate", "createPendingGate",
            // 不是 Git / VCS
            "Branch", "Merge",
            // 不提前实现 I12+
            "Queue", "ProjectIndex", "Inspector",
            // 无无限 Agent Loop（固定七相位）
            "while (",
        ).forEach { token -> assertTrue(token !in code, "NovelAgent 不得出现 '$token'（只做编排决策）") }

        // 复用证据（正面断言；防止重新实现一套）
        listOf(
            "SkillRegistry", "ContextEngineUseCases", "ProductToolService", "WorkingDraftUseCases",
            "ChangeUseCases", "CommitUseCases", "ActionPolicyUseCases", "AgentSessionUseCases", "ActivityUseCases",
            "PlannerAgent", "WriterAgent", "CritiqueAgent", "RevisionAgent",
        ).forEach { token -> assertTrue(token in code, "NovelAgent 必须复用既有能力：'$token'") }
    }

    @Test
    fun `novel agent models stay pure domain and do not duplicate existing models`() {
        val code = sourceOf(modelDir)
        listOf(
            "SqlDriver", "QianyanDb", "storage", "LLMGateway", "ActionPolicyUseCases", "StateMachine", "while (",
            "ValidationResult", "ValidationIssue", "CommitPolicy",
        ).forEach { token -> assertTrue(token !in code, "I11 领域契约不得依赖 / 复制 '$token'") }

        listOf("NovelAgentRequest", "NovelAgentResult", "NovelAgentOutcome", "NovelIntentAnalysis", "NovelAgentErrorCodes")
            .forEach { token -> assertTrue(token in code, "缺少 I11 契约：'$token'") }
    }

    @Test
    fun `five existing writing agents and legacy orchestration are preserved`() {
        // 五个旧 Agent 必须仍然存在，且 agentId 未被改写（保留原 Prompt / Parser / Snapshot 语义）
        val agents = mapOf(
            "src/main/kotlin/com/qianyan/application/usecase/writing/planning/PlannerAgent.kt" to "story-planner",
            "src/main/kotlin/com/qianyan/application/usecase/writing/WriterAgent.kt" to "story-writer",
            "src/main/kotlin/com/qianyan/application/usecase/writing/critique/CritiqueAgent.kt" to "story-critic",
            "src/main/kotlin/com/qianyan/application/usecase/writing/revision/RevisionAgent.kt" to "story-writer-revision",
            "src/main/kotlin/com/qianyan/application/usecase/writing/knowledgeupdate/KnowledgeUpdateAgent.kt" to "knowledge-update",
        )
        agents.forEach { (path, agentId) ->
            val file = File(path)
            assertTrue(file.isFile, "既有 Agent 不得被删除：$path")
            assertTrue(agentId in file.readText(), "既有 Agent 的 agentId 不得被改写：$path（期望 $agentId）")
        }

        // 旧写作链 / Workflow / Task / AgentSession 仍在（I11 不删除、不重写）
        listOf(
            "src/main/kotlin/com/qianyan/application/usecase/chapter/ChapterWritingUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/workflow/WorkflowService.kt",
            "src/main/kotlin/com/qianyan/application/usecase/workflow/WorkflowOrchestrator.kt",
            "src/main/kotlin/com/qianyan/application/usecase/session/AgentSessionUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/log/ActivityUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/tool/ProductToolService.kt",
            "src/main/kotlin/com/qianyan/application/usecase/context/ContextEngineUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/commit/CommitUseCases.kt",
        ).forEach { path -> assertTrue(File(path).isFile, "既有能力不得被删除：$path") }
    }

    @Test
    fun `legacy five agent contracts are still usable through the container`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        // 五个旧 Agent 仍可由容器装配（未被 I11 改写 / 破坏）
        assertTrue(f.app.planner::class.qualifiedName == "com.qianyan.application.usecase.writing.planning.PlannerAgent")
        assertTrue(f.app.writer::class.qualifiedName == "com.qianyan.application.usecase.writing.WriterAgent")
        assertTrue(f.app.critic::class.qualifiedName == "com.qianyan.application.usecase.writing.critique.CritiqueAgent")
        assertTrue(f.app.rewriter::class.qualifiedName == "com.qianyan.application.usecase.writing.revision.RevisionAgent")
        assertTrue(
            f.app.knowledgeUpdater::class.qualifiedName ==
                "com.qianyan.application.usecase.writing.knowledgeupdate.KnowledgeUpdateAgent",
        )
        // 旧写作链入口仍在（I11 逐步接管，未一次性重写）
        assertTrue(f.app.chapterWriting.open(w.chapterId, w.novelId, null).current().chapterId == w.chapterId)
        f.close()
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