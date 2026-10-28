package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.tool.AgentToolBridge
import com.qianyan.model.ActivityId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent ↔ Tool 调用链（断链最小修复）验证。
 *
 * 覆盖：声明 == 注册（Planner 只声明其桥接注册的只读工具）；工具经 ProductToolService 调度链执行
 * （ActionPolicy + ToolCallLog + 项目作用域）；结果作为观察回传模型；未注册工具 / 无效参数 /
 * 跨项目读取被类型化拒绝且不伪装成功；无工具 Agent 保持零声明并能在模型自发工具调用时安全收敛；
 * 正式正文写入路径不变（Plan 全程不落 Canonical）。
 * maxSteps 与 allowedTools 的执行期强制由 :agent:runtime 既有测试覆盖（AgentRuntimeTest）。
 */
class AgentToolChainTest {

    internal val planJson = """{"chapterGoal":"揭开秘辛","mainConflict":"主线冲突","expectedEvents":["遇险"]}"""

    /** 按 Agent 名路由的脚本网关：每队按调用序出队；队列空则回退既有 routingGateway。 */
    internal fun scriptedGateway(
        plannerScripts: MutableList<String>,
        writerScripts: MutableList<String> = mutableListOf(),
        recorded: MutableList<ProviderRequest> = mutableListOf(),
    ): MockLLMGateway {
        val fallback = routingGateway()
        return MockLLMGateway { req ->
            recorded.add(req)
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val queue = when {
                "StoryPlannerAgent" in system -> plannerScripts
                "StoryWriterAgent" in system -> writerScripts
                else -> null
            }
            if (queue != null && queue.isNotEmpty()) rawResponse(queue.removeFirst()) else fallback.chat(req)
        }
    }

    /** 模型单步输出：最终回答（[body] 为 answer 字符串值，多为内层 JSON 文本）。 */
    /** 最终回答的模型输出内容（{\"answer\": body} 信封）。 */
    internal fun final(body: String): String = buildJsonObject { put("answer", body) }.toString()

    internal fun rawResponse(content: String): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, content),
        usage = Usage(10, 10, 20),
        finishReason = FinishReason.STOP,
    )

    /** 模型单步输出：工具调用。 */
    internal fun toolCall(name: String, vararg args: Pair<String, String>): String =
        buildJsonObject {
            put("tool", name)
            put(
                "arguments",
                buildJsonObject { args.forEach { (k, v) -> put(k, JsonPrimitive(v)) } },
            )
        }.toString()

    internal fun plannerMessages(recorded: List<ProviderRequest>): List<ProviderRequest> =
        recorded.filter { req -> req.messages.any { it.role == ChatRole.SYSTEM && "StoryPlannerAgent" in it.content } }

    internal fun writerMessages(recorded: List<ProviderRequest>): List<ProviderRequest> =
        recorded.filter { req -> req.messages.any { it.role == ChatRole.SYSTEM && "StoryWriterAgent" in it.content } }

    /* ---------------- 声明 == 注册；工具经调度链执行；结果回传 ---------------- */

    @Test
    fun `planner declares exactly its bridged tools and executes a tool call through the service chain`() {
        val plannerScripts = mutableListOf<String>()
        val recorded = mutableListOf<ProviderRequest>()
        val t = AgentToolChainTest()
        val f = novelAgentFixture(t.scriptedGateway(plannerScripts, recorded = recorded))
        val w = seedNovelWorld(f)

        plannerScripts += t.toolCall("list_chapters", "novelId" to w.novelId.value)
        plannerScripts += t.planJson
        val result = f.app.novelAgent.run(request(w, "规划下一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertNotNull(result.planChapterId, "规划产物应就绪")

        // 声明 == 注册：系统提示只声明被选中 STORY_PLANNING Skill 的 allowedTools
        val system = plannerMessages(recorded).first().messages.first().content
        assertTrue(system.contains("可用工具"), "桥接后系统提示应声明工具：$system")
        CoreSkills.STORY_PLANNING.allowedTools.map { it.value }.forEach {
            assertTrue(system.contains(it), "应声明 Skill.allowedTools 中的 $it：$system")
        }
        listOf("get_latest_draft", "search_vocabulary").forEach {
            val declared = system.lineSequence().any { line -> line.trimStart().startsWith("- $it:") }
            assertTrue(!declared, "不得声明该 Skill 未授权的工具 $it：$system")
        }

        // 工具结果作为观察回传模型（第二轮请求可见 ToolResult）
        val observation = plannerMessages(recorded)[1].messages.last().content
        assertTrue(observation.contains("ToolResult"), observation)
        assertTrue(observation.contains("\"success\":true"), "工具应真实执行成功：$observation")

        // 审计：调用事实落在既有 ToolCallLog（EXECUTE 相位的 Activity 作用域）
        val sessionId = assertNotNull(result.sessionId)
        val logs = f.app.toolCallLogs.listBySession(sessionId).filter { it.toolName == ToolName("list_chapters") }
        assertEquals(1, logs.size, "桥接调用必须留痕")
        assertEquals(AgentLogStatus.COMPLETED, logs.single().status)

        // 只读：规划全程不写 Canonical / CommitHistory
        assertTrue(f.app.commits.history(w.projectId).isEmpty())
        f.close()
    }

    /* ---------------- 未注册工具 / 无效参数 / 跨项目读取 ---------------- */

    @Test
    fun `unregistered tool name is refused before execution and the run still completes`() {
        val plannerScripts = mutableListOf<String>()
        val recorded = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(scriptedGateway(plannerScripts, recorded = recorded))
        val w = seedNovelWorld(f)

        plannerScripts += toolCall("get_latest_draft", "chapterId" to w.chapterId.value) // 不在 STORY_PLANNING allowedTools 内
        plannerScripts += final(planJson)
        val result = f.app.novelAgent.run(request(w, "规划下一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome, "拒绝后应能把控制权交回模型并完成")
        val observation = plannerMessages(recorded)[1].messages.last().content
        assertTrue(observation.contains("\"success\":false"), observation)
        assertTrue(observation.contains("ToolNotFound"), "未授权/未注册工具必须显式失败观察：$observation")
        assertTrue(
            f.app.toolCallLogs.listBySession(assertNotNull(result.sessionId))
                .none { it.toolName == ToolName("get_latest_draft") },
            "未授权工具不得产生任何执行留痕（调度层根本没被触达）",
        )
        f.close()
    }

    @Test
    fun `invalid tool arguments are refused with a typed observation`() {
        val plannerScripts = mutableListOf<String>()
        val recorded = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(scriptedGateway(plannerScripts, recorded = recorded))
        val w = seedNovelWorld(f)

        plannerScripts += toolCall("list_chapters") // 缺必填 novelId
        plannerScripts += final(planJson)
        val result = f.app.novelAgent.run(request(w, "规划下一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        val observation = plannerMessages(recorded)[1].messages.last().content
        assertTrue(observation.contains("\"success\":false"), observation)
        assertTrue(observation.contains("缺少必填参数"), observation)
        f.close()
    }

    @Test
    fun `cross project read is refused as not found without leaking existence`() {
        val plannerScripts = mutableListOf<String>()
        val recorded = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(scriptedGateway(plannerScripts, recorded = recorded))
        val w = seedNovelWorld(f)
        val otherNovel = f.app.novels.createOriginal(title = "别人的书")
        val otherChapter = f.app.chapters.createNextChapter("别章", otherNovel).chapterId

        plannerScripts += toolCall("get_chapter", "chapterId" to otherChapter.value)
        plannerScripts += final(planJson)
        val result = f.app.novelAgent.run(request(w, "规划下一章"))

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        val observation = plannerMessages(recorded)[1].messages.last().content
        assertTrue(observation.contains("\"success\":false"), observation)
        assertTrue(observation.contains("NOT_FOUND"), "跨项目读取按不存在拒绝：$observation")
        f.close()
    }

    /* ---------------- 无工具 Agent：零声明 + 自发工具调用安全收敛 ---------------- */

    @Test
    fun `writer declares no tools and survives a spontaneous tool call without an infinite loop`() {
        val writerScripts = mutableListOf<String>()
        val recorded = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(scriptedGateway(mutableListOf(), writerScripts, recorded))
        val w = seedNovelWorld(f)

        writerScripts += toolCall("get_project", "projectId" to w.projectId.value) // Writer 无任何工具
        writerScripts += final("""{"content":"第一章正文（新写）。"}""")
        val result = f.app.novelAgent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome, "工具被拒后模型仍给出正文 ⇒ 正常进入人工确认")
        assertNotNull(result.workingDraftId)
        val system = writerMessages(recorded).first().messages.first().content
        assertTrue(!system.contains("可用工具"), "无工具 Agent 不得声明任何工具：$system")
        val observation = writerMessages(recorded)[1].messages.last().content
        assertTrue(observation.contains("ToolNotFound"), "自发工具调用必须得到失败观察：$observation")
        f.close()
    }

    /* ---------------- 桥的装配一致性（fail-fast） ---------------- */

    @Test
    fun `bridge fails fast when a declared tool is not registered in the service`() {
        val f = novelAgentFixture()
        val bridge = AgentToolBridge(f.app.productTools)
        val ex = assertFailsWith<IllegalArgumentException> {
            bridge.runtimeRegistry(ActivityId("a-1"), listOf(ToolName("teleport")))
        }
        assertTrue(ex.message!!.contains("声明与 ProductToolService 注册不一致"))
        f.close()
    }
}
