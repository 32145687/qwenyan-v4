package com.qianyan.application.usecase.tool

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolError
import com.qianyan.agent.tool.ToolException
import com.qianyan.agent.tool.ToolRegistry
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I5 · Tool Registry 行为测试（复用既有 `ToolRegistry`，不造第二套）。
 *
 * 覆盖：register / find / list(all) / unknown tool / duplicate registration /
 * Product Tool 定义清单（名字 + 必填参数）/ 被拒动作不执行。
 */
class ProductToolRegistryTest {

    private class ProbeTool(name: String, kind: AgentActionKind = AgentActionKind.READ) : ReadOnlyProductTool {
        var executed: Int = 0
        override val actionKind: AgentActionKind = kind
        override val definition: ToolDefinition = ToolDefinition(ToolName(name), "probe", emptyList())
        override fun execute(request: ToolRequest, context: ToolContext): ToolResult {
            executed += 1
            return ToolResult(request.toolName, success = true)
        }
    }

    @Test
    fun `register and find resolve the same tool`() {
        val registry = ToolRegistry()
        val tool = ProbeTool("probe_read")

        registry.register(tool)

        assertTrue(registry.contains(ToolName("probe_read")))
        assertEquals(tool, registry.find(ToolName("probe_read")))
        assertEquals(1, registry.size())
    }

    @Test
    fun `unknown tool is not found`() {
        val registry = ToolRegistry()
        registry.register(ProbeTool("probe_read"))

        assertNull(registry.find(ToolName("ghost")), "未注册工具 → null")
        assertTrue(!registry.contains(ToolName("ghost")))
    }

    @Test
    fun `all returns tools in registration order`() {
        val registry = ToolRegistry()
        val first = ProbeTool("probe_a")
        val second = ProbeTool("probe_b")

        registry.register(first)
        registry.register(second)

        assertEquals(listOf(first, second), registry.all())
    }

    @Test
    fun `duplicate registration overwrites by name`() {
        val registry = ToolRegistry()
        val original = ProbeTool("probe_dup")
        val replacement = ProbeTool("probe_dup")

        registry.register(original)
        registry.register(replacement)

        assertEquals(1, registry.size(), "同名覆盖，不产生第二个注册项")
        assertEquals(replacement, registry.find(ToolName("probe_dup")))
    }

    @Test
    fun `service lists product tool definitions sorted with required params`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
        val definitions = app.productTools.availableTools().associateBy { it.toolName.value }

        assertEquals(
            setOf(
                "get_project", "get_project_state", "get_novel", "list_chapters",
                "get_chapter", "get_latest_draft", "search_vocabulary",
            ),
            definitions.keys,
            "I5 只读 Product Tool 共 7 个",
        )
        listOf("projectId").forEach {
            assertTrue(definitions.getValue("get_project").parameters.any { p -> p.name == it && p.required }, "get_project 必填 $it")
        }
        assertTrue(definitions.getValue("get_novel").parameters.any { it.name == "novelId" && it.required })
        assertTrue(definitions.getValue("get_chapter").parameters.any { it.name == "chapterId" && it.required })
        assertTrue(definitions.getValue("list_chapters").parameters.any { it.name == "novelId" && it.required })
        assertTrue(definitions.getValue("list_chapters").parameters.any { it.name == "variantId" && !it.required })
        assertTrue(definitions.getValue("get_latest_draft").parameters.any { it.name == "chapterId" && it.required })
        assertTrue(definitions.getValue("search_vocabulary").parameters.any { it.name == "novelId" && it.required })
        assertTrue(definitions.getValue("search_vocabulary").parameters.any { it.name == "query" && !it.required })

        assertEquals(
            app.productTools.availableTools().map { it.toolName.value },
            app.productTools.availableTools().map { it.toolName.value }.sorted(),
            "工具清单按名字排序（确定性）",
        )
        assertNotNull(app.productTools.find(ToolName("get_novel")), "find 可取回已注册工具")
        assertNull(app.productTools.find(ToolName("ghost")), "未注册 → null")
        handle.driver.close()
    }

    @Test
    fun `service rejects unknown tool with typed error`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
        val novelId = app.novels.createOriginal(title = "I5 书")
        val activity = app.activities.start(app.agentSessions.startSession(novelId).sessionId, "I5_TEST")

        val ex = assertFailsWith<ToolException> {
            app.productTools.invoke(activity.activityId, ToolRequest(ToolName("ghost"), kotlinx.serialization.json.buildJsonObject { }))
        }
        assertTrue(ex.error is ToolError.ToolNotFound, "未知工具 → ToolNotFound（既有 P10 类型化错误）")
        handle.driver.close()
    }

    @Test
    fun `denied and needs human actions never execute their tool`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
        val denied = ProbeTool("probe_delete", AgentActionKind.DELETE)
        val needsHuman = ProbeTool("probe_commit", AgentActionKind.COMMIT_CANONICAL)
        val service = ProductToolService(
            tools = listOf(denied, needsHuman),
            activities = app.activities,
            toolCallLogs = app.toolCallLogs,
            actionPolicy = app.actionPolicy,
        )
        val novelId = app.novels.createOriginal(title = "I5 书")
        val activity = app.activities.start(app.agentSessions.startSession(novelId).sessionId, "I5_TEST")

        val deniedResult = service.invoke(activity.activityId, ToolRequest(ToolName("probe_delete")))
        val humanResult = service.invoke(activity.activityId, ToolRequest(ToolName("probe_commit")))

        assertEquals(0, denied.executed, "DENIED 动作不得执行工具")
        assertEquals(0, needsHuman.executed, "NEEDS_HUMAN 动作不得执行工具")
        assertTrue(!deniedResult.success && (deniedResult.error ?: "").startsWith("FORBIDDEN"), "DENIED → FORBIDDEN")
        assertTrue(!humanResult.success && (humanResult.error ?: "").startsWith("NEEDS_HUMAN"), "NEEDS_HUMAN → NEEDS_HUMAN")
        handle.driver.close()
    }
}