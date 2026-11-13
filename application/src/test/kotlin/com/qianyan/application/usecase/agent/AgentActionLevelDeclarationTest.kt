package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.agent.tool.ToolContext
import com.qianyan.application.usecase.tool.AgentToolBridge
import com.qianyan.application.usecase.tool.ProductToolService
import com.qianyan.application.usecase.tool.ReadOnlyProductTool
import com.qianyan.application.usecase.tool.readOnlyProductTools
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.model.AgentSessionId
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O-31（A-33 D7/D8）：action 级 `observed ⊆ expectedActions` 判定。
 *
 * 语义：
 *  - 工具级越权仍报 `TOOL_NOT_ALLOWED`（文案不变）；
 *  - 工具折算出的动作类别超出 Skill 声明 → `ACTION_NOT_DECLARED`；
 *  - 正常路径（8 个只读工具）零行为变化。
 */
class AgentActionLevelDeclarationTest {

    @Test
    fun `observed tool action kind outside expected actions is rejected`() {
        val scripts = ArrayDeque<String>()
        val gateway = ScriptedGateway(scripts)
        val f = novelAgentFixture(gateway)
        try {
            val w = seedNovelWorld(f)
            scripts += toolCall("probe_edit")
            scripts += answer(PLAN_JSON)

            val service = ProductToolService(
                tools = readOnlyProductTools(
                    f.app.projects, f.app.novels, f.app.chapters,
                    f.app.writerUseCases, f.app.vocabularies, f.app.worldModelRead,
                ) + ProbeActionTool("probe_edit", AgentActionKind.EDIT_WORKING_DRAFT),
                activities = f.app.activities,
                toolCallLogs = f.app.toolCallLogs,
                actionPolicy = f.app.actionPolicy,
            )
            val registry = SkillRegistry.of(
                CoreSkills.all().map {
                    if (it.skillId == CoreSkills.STORY_PLANNING.skillId) {
                        it.copy(allowedTools = listOf(ToolName("probe_edit")))
                    } else {
                        it
                    }
                },
            )

            val result = novelAgentWith(
                f = f,
                service = service,
                registry = registry,
                planner = PlannerAgent(gateway, f.app.errorMapper, ModelProfile.MOCK, AgentToolBridge(service)),
            ).run(request(w, "规划第一章"))

            assertEquals(NovelAgentOutcome.FAILED, result.outcome)
            assertEquals(NovelAgentErrorCodes.ACTION_NOT_DECLARED, result.failure?.code)
            val detail = result.failure?.detail.orEmpty()
            assertTrue(detail.contains("probe_edit"), detail)
            assertTrue(detail.contains("EDIT_WORKING_DRAFT"), detail)
        } finally {
            f.close()
        }
    }

    @Test
    fun `every skill allowed tool action kind is declared in expected actions`() {
        val f = novelAgentFixture()
        try {
            val service = f.app.productTools
            val violations = CoreSkills.all().flatMap { skill ->
                skill.allowedTools.mapNotNull { tool ->
                    val kind = service.actionKindOf(tool)
                    if (kind == null || kind !in skill.expectedActions) {
                        "skill=${skill.skillId.value} tool=${tool.value} kind=${kind?.name ?: "UNMAPPED"}"
                    } else {
                        null
                    }
                }
            }
            assertTrue(
                violations.isEmpty(),
                "allowedTools 的动作类别未被 expectedActions 声明（O-112）：$violations",
            )
        } finally {
            f.close()
        }
    }

    @Test
    fun `normal read only tool path stays unchanged`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)

            val result = f.app.novelAgent.run(request(w, "继续写第一章"))

            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertNull(result.failure)
            assertTrue(result.toolCallCount >= 1, "正常写作路径应经只读工具读取基线正文")
        } finally {
            f.close()
        }
    }

    @Test
    fun `tool level violation still reports tool not allowed`() {
        lateinit var f: NovelAgentFixture
        var sessionId: AgentSessionId? = null
        var seeded = false
        val gateway = CallbackGateway {
            if (!seeded) {
                seeded = true
                val session = sessionId ?: error("sessionId 未初始化")
                val execute = f.app.activities.listBySession(session).first { it.kind == "EXECUTE" }
                val log = f.app.toolCallLogs.start(execute.activityId, ToolName("get_novel"))
                f.app.toolCallLogs.succeed(log.toolCallId)
            }
            answer(PLAN_JSON)
        }
        f = novelAgentFixture(gateway)
        try {
            val w = seedNovelWorld(f)
            sessionId = f.app.agentSessions.startSession(w.novelId).sessionId
            val registry = SkillRegistry.of(
                CoreSkills.all().map {
                    if (it.skillId == CoreSkills.STORY_PLANNING.skillId) it.copy(allowedTools = emptyList()) else it
                },
            )

            val result = novelAgentWithRegistry(f, registry).run(request(w, "规划第一章", sessionId = sessionId))

            assertEquals(NovelAgentOutcome.FAILED, result.outcome)
            assertEquals(NovelAgentErrorCodes.TOOL_NOT_ALLOWED, result.failure?.code)
            assertTrue(result.failure?.detail.orEmpty().contains("get_novel"), result.failure?.detail.orEmpty())
        } finally {
            f.close()
        }
    }

    /* ---------------- fixture ---------------- */

    private class ProbeActionTool(
        name: String,
        override val actionKind: AgentActionKind,
    ) : ReadOnlyProductTool {
        override val definition: ToolDefinition = ToolDefinition(ToolName(name), "probe", emptyList())
        override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
            ToolResult(request.toolName, success = true)
    }

    private class ScriptedGateway(private val script: ArrayDeque<String>) : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse {
            val body = script.removeFirstOrNull() ?: answer(PLAN_JSON)
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, body),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    private class CallbackGateway(private val body: (ProviderRequest) -> String) : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse = ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, body(request)),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun novelAgentWith(
        f: NovelAgentFixture,
        service: ProductToolService,
        registry: SkillRegistry,
        planner: PlannerAgent,
    ): NovelAgent =
        NovelAgent(
            projects = f.app.projects,
            chapters = f.app.chapters,
            sessions = f.app.agentSessions,
            activities = f.app.activities,
            toolCallLogs = f.app.toolCallLogs,
            skillRegistry = registry,
            contextEngine = f.app.contextEngine,
            productTools = service,
            workingDrafts = f.app.workingDrafts,
            changes = f.app.changes,
            commits = f.app.commits,
            actionPolicy = f.app.actionPolicy,
            planner = planner,
            writer = f.app.writer,
            critic = f.app.critic,
            rewriter = f.app.rewriter,
            characterConsistency = f.app.characterConsistency,
            characterConsistencyAgent = f.app.characterConsistencyAgent,
            planningContexts = f.app.planningContextAssembly,
            continuationResolver = f.app.continuationResolver,
            canonicalRead = f.app.canonicalRead,
            reader = f.app.writerUseCases,
            errorMapper = f.app.errorMapper,
        )

    private companion object {
        const val PLAN_JSON = """{"chapterGoal":"揭开秘辛","mainConflict":"主线冲突","expectedEvents":["遇险"]}"""

        fun answer(body: String): String = buildJsonObject { put("answer", body) }.toString()

        fun toolCall(name: String, vararg args: Pair<String, String>): String = buildJsonObject {
            put("tool", name)
            put(
                "arguments",
                buildJsonObject { args.forEach { (key, value) -> put(key, JsonPrimitive(value)) } },
            )
        }.toString()
    }
}
