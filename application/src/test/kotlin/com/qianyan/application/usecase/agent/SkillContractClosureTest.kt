package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.tool.AgentToolBridge
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.model.AgentSessionId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-2C · Skill 契约收口（V-1 / V-2 / V-3）。 */
class SkillContractClosureTest {

    /* ---------------- V-1 ------------- */

    @Test
    fun `V1 skill-driven planner registers exactly the selected skill allowedTools`() {
        val scripts = ArrayDeque<String>()
        val gateway = CaptureGateway { scripts.removeFirstOrNull() ?: answer(PLAN_JSON) }
        val f = novelAgentFixture(gateway)
        val w = seedNovelWorld(f)

        scripts += toolCall("get_latest_draft", "chapterId" to w.chapterId.value)
        scripts += answer(PLAN_JSON)

        val custom = CoreSkills.STORY_PLANNING.copy(
            allowedTools = listOf(ToolName("get_world_context")),
        )
        val registry = SkillRegistry.of(
            CoreSkills.all().map { if (it.skillId == CoreSkills.STORY_PLANNING.skillId) custom else it },
        )
        val result = novelAgentWithRegistry(f, registry).run(request(w, "规划第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        val plannerRequests = gateway.plannerRequests()
        val system = plannerRequests.first().messages.first { it.role == ChatRole.SYSTEM }.content
        assertTrue(system.contains("get_world_context"), system)
        listOf("list_chapters", "get_chapter", "get_latest_draft").forEach {
            assertFalse(system.contains(it), "不得声明 Skill.allowedTools 之外的 $it：$system")
        }
        assertTrue(
            plannerRequests[1].messages.last().content.contains("ToolNotFound"),
            "未注册工具必须在运行时被拒绝：${plannerRequests[1].messages.last().content}",
        )
        f.close()
    }

    @Test
    fun `V1 non-skill planner keeps default PLANNER_TOOLS`() {
        val gateway = CaptureGateway { answer(PLAN_JSON) }
        val f = novelAgentFixture(gateway)
        val w = seedNovelWorld(f)
        val sessionId = f.app.agentSessions.startSession(w.novelId).sessionId
        val activity = f.app.activities.start(sessionId, "TEST", "direct planner")
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK, AgentToolBridge(f.app.productTools))

        val plan = planner.plan(planningContext(), emptyList(), activity.activityId)

        assertEquals("揭开秘辛", plan.chapterGoal)
        val system = gateway.requests.last().messages.first { it.role == ChatRole.SYSTEM }.content
        listOf("list_chapters", "get_chapter", "get_latest_draft").forEach {
            assertTrue(system.contains(it), "非 Skill 调用方应保留默认 $it：$system")
        }
        listOf("get_novel", "get_world_context", "search_vocabulary").forEach {
            assertFalse(system.contains(it), "默认 PLANNER_TOOLS 不得包含 $it：$system")
        }
        f.close()
    }

    @Test
    fun `V1 planner without activity anchor keeps empty registration and empty allowedTools`() {
        val gateway = CaptureGateway { answer(PLAN_JSON) }
        val f = novelAgentFixture(gateway)
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK, AgentToolBridge(f.app.productTools))

        planner.plan(planningContext(), emptyList(), null)

        val system = gateway.requests.last().messages.first { it.role == ChatRole.SYSTEM }.content
        assertFalse(system.contains("可用工具"), system)
        listOf(
            "list_chapters", "get_chapter", "get_latest_draft",
            "get_novel", "get_world_context", "search_vocabulary",
        ).forEach { assertFalse(system.contains(it), "无 Activity 锚不得声明 $it：$system") }
        f.close()
    }

    /* ---------------- V-2 ------------- */

    @Test
    fun `V2 observed tool outside allowedTools is rejected after execution`() {
        lateinit var f: NovelAgentFixture
        var sessionId: AgentSessionId? = null
        var seeded = false
        val gateway = CaptureGateway {
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
        val w = seedNovelWorld(f)
        sessionId = f.app.agentSessions.startSession(w.novelId).sessionId
        val custom = CoreSkills.STORY_PLANNING.copy(
            allowedTools = listOf(ToolName("get_world_context")),
        )
        val registry = SkillRegistry.of(
            CoreSkills.all().map { if (it.skillId == CoreSkills.STORY_PLANNING.skillId) custom else it },
        )

        val result = novelAgentWithRegistry(f, registry)
            .run(request(w, "规划第一章", sessionId = sessionId))

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.TOOL_NOT_ALLOWED, result.failure?.code)
        assertTrue(result.failure?.detail.orEmpty().contains("get_novel"), result.failure?.detail.orEmpty())
        f.close()
    }

    @Test
    fun `V2 observed tool within allowedTools passes`() {
        val scripts = ArrayDeque<String>()
        val gateway = CaptureGateway { scripts.removeFirstOrNull() ?: answer(PLAN_JSON) }
        val f = novelAgentFixture(gateway)
        val w = seedNovelWorld(f)
        scripts += toolCall("get_world_context", "novelId" to w.novelId.value)
        scripts += answer(PLAN_JSON)
        val custom = CoreSkills.STORY_PLANNING.copy(
            allowedTools = listOf(ToolName("get_world_context")),
        )
        val registry = SkillRegistry.of(
            CoreSkills.all().map { if (it.skillId == CoreSkills.STORY_PLANNING.skillId) custom else it },
        )

        val result = novelAgentWithRegistry(f, registry).run(request(w, "规划第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        val sessionId = assertNotNull(result.sessionId)
        assertTrue(
            f.app.toolCallLogs.listBySession(sessionId).any { it.toolName == ToolName("get_world_context") },
            "allowedTools 内调用必须真实执行并留痕",
        )
        f.close()
    }

    @Test
    fun `V2 zero observed tools passes`() {
        val gateway = CaptureGateway { answer(PLAN_JSON) }
        val f = novelAgentFixture(gateway)
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(request(w, "规划第一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertNull(result.failure)
        f.close()
    }

    /* ---------------- V-3 ------------- */

    @Test
    fun `V3 auto selection refuses when every matching skill lacks execution binding`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val agent = novelAgentWithRegistry(f, SkillRegistry.of(listOf(CoreSkills.KNOWLEDGE_WORLD_MODEL)))

        val result = agent.run(request(w, "分析第一章"))

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertNull(result.skillId, "不可执行 Skill 不得进入选中状态")
        assertEquals(NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE, result.failure?.code)
        assertTrue(
            result.failure?.detail.orEmpty().contains("Knowledge Update"),
            result.failure?.detail.orEmpty(),
        )
        f.close()
    }

    /* ---------------- fixture ---------------- */

    private fun planningContext(): PlanningContext {
        val request = UserWritingRequest(
            requestId = RequestId("req-t2c"),
            intentType = IntentType.PLAN,
            target = TargetRef(TargetKind.CHAPTER, null),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId("novel-1"),
        )
        return PlanningContext(
            request = request,
            novelId = NovelId("novel-1"),
            novelTitle = "测试小说",
        )
    }

    private class CaptureGateway(
        private val plannerBody: (ProviderRequest) -> String,
    ) : LLMGateway {
        val requests = mutableListOf<ProviderRequest>()

        override fun chat(request: ProviderRequest): ProviderResponse {
            requests += request
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = if ("StoryPlannerAgent" in system) plannerBody(request) else answer(PLAN_JSON)
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, body),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }

        fun plannerRequests(): List<ProviderRequest> = requests.filter { request ->
            request.messages.any { it.role == ChatRole.SYSTEM && "StoryPlannerAgent" in it.content }
        }
    }

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
