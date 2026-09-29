package com.qianyan.application.usecase.tool

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.agent.tool.ToolContext
import com.qianyan.agent.tool.ToolError
import com.qianyan.agent.tool.ToolException
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActivityId
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.action.isAllowed
import com.qianyan.model.agent.ToolName
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I5 · Product Tool Service（调度层）测试。
 *
 * 覆盖：ActionPolicy 映射与强制（READ/SEARCH/ANALYZE/VALIDATE 放行；DENIED/NEEDS_HUMAN 不执行）、
 * ToolCallLog 记录（start / success / failure）、非法输入路径、
 * 工具调用不创建/改写 AgentSession / Workflow / Task。
 */
class ProductToolServiceTest {

    private class ProbeTool(name: String, kind: AgentActionKind = AgentActionKind.READ) : ReadOnlyProductTool {
        var executed: Int = 0
        override val actionKind: AgentActionKind = kind
        override val definition: ToolDefinition = ToolDefinition(ToolName(name), "probe", emptyList())
        override fun execute(request: ToolRequest, context: ToolContext): ToolResult {
            executed += 1
            return ToolResult(request.toolName, success = true, output = buildJsonObject { put("ok", true) })
        }
    }

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fixture(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
    }

    private fun activity(f: Fixture, title: String = "I5 书"): ActivityId {
        val novelId = f.app.novels.createOriginal(title = title)
        val session = f.app.agentSessions.startSession(novelId)
        return f.app.activities.start(session.sessionId, "I5_TEST").activityId
    }

    // ---------- ActionPolicy 集成 ----------

    @Test
    fun `read search analyze and validate actions are allowed and execute`() {
        val f = fixture()
        val activityId = activity(f)
        val kinds = listOf(
            "probe_read" to AgentActionKind.READ,
            "probe_search" to AgentActionKind.SEARCH,
            "probe_analyze" to AgentActionKind.ANALYZE,
            "probe_validate" to AgentActionKind.VALIDATE,
        )
        val probes = kinds.map { (name, kind) -> ProbeTool(name, kind) }
        val service = ProductToolService(
            tools = probes,
            activities = f.app.activities,
            toolCallLogs = f.app.toolCallLogs,
            actionPolicy = f.app.actionPolicy,
        )

        probes.forEach { probe ->
            val result = service.invoke(activityId, ToolRequest(probe.definition.toolName))
            assertTrue(result.success, "${probe.definition.toolName.value} 应放行执行")
            assertEquals(1, probe.executed, "工具应真正执行一次")
            // I2 ActionPolicy 直接断言：同一动作类别 → Allowed
            assertTrue(f.app.actionPolicy.evaluate(com.qianyan.model.action.AgentAction(probe.actionKind)).isAllowed)
        }
        f.close()
    }

    @Test
    fun `product tools map to read or search actions only`() {
        val f = fixture()
        val expected = mapOf(
            "get_project" to AgentActionKind.READ,
            "get_project_state" to AgentActionKind.READ,
            "get_novel" to AgentActionKind.READ,
            "list_chapters" to AgentActionKind.READ,
            "get_chapter" to AgentActionKind.READ,
            "get_latest_draft" to AgentActionKind.READ,
            "search_vocabulary" to AgentActionKind.SEARCH,
        )
        expected.forEach { (name, kind) ->
            val tool = assertNotNull(f.app.productTools.find(ToolName(name)), "$name 必须已注册") as ReadOnlyProductTool
            assertEquals(kind, tool.actionKind, "$name 的动作类别映射")
            assertTrue(f.app.actionPolicy.evaluate(com.qianyan.model.action.AgentAction(tool.actionKind)).isAllowed)
        }
        f.close()
    }

    // ---------- ToolCallLog 记录 ----------

    @Test
    fun `tool call log records start success and failure`() {
        val f = fixture()
        val activityId = activity(f)
        val novelId = f.app.novels.listOriginals().first().novelId

        val success = f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName("get_novel"), buildJsonObject { put("novelId", novelId.value) }),
        )
        assertTrue(success.success)
        val failure = f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName("get_novel"), buildJsonObject { put("novelId", "n-ghost") }),
        )
        assertTrue(!failure.success && (failure.error ?: "").startsWith("NOT_FOUND"), "不存在 → NOT_FOUND")

        val logs = f.app.toolCallLogs.listByActivity(activityId)
        assertEquals(2, logs.size, "成功 + 失败各记录一条 ToolCallLog")
        val ok = logs.single { it.status == AgentLogStatus.COMPLETED }
        val bad = logs.single { it.status == AgentLogStatus.FAILED }
        assertEquals(ToolName("get_novel"), ok.toolName)
        assertNotNull(ok.startedAt)
        assertNotNull(ok.completedAt)
        assertNotNull(ok.inputSummary, "记录输入摘要")
        assertNotNull(ok.outputSummary, "记录输出摘要")
        assertTrue((bad.error ?: "").startsWith("NOT_FOUND"), "失败记录携带 NOT_FOUND 原因")
        f.close()
    }

    @Test
    fun `policy denial and invalid input are recorded as failed calls`() {
        val f = fixture()
        val activityId = activity(f)
        val denied = ProbeTool("probe_delete", AgentActionKind.DELETE)
        val service = ProductToolService(
            tools = listOf(denied),
            activities = f.app.activities,
            toolCallLogs = f.app.toolCallLogs,
            actionPolicy = f.app.actionPolicy,
        )

        val deniedResult = service.invoke(activityId, ToolRequest(ToolName("probe_delete")))
        assertTrue(!deniedResult.success)
        val deniedLog = f.app.toolCallLogs.listByActivity(activityId).single()
        assertEquals(AgentLogStatus.FAILED, deniedLog.status, "被拒动作也留痕（审计）")
        assertTrue((deniedLog.error ?: "").startsWith("FORBIDDEN"))

        // 缺必填参数 → 既有 ToolError.InvalidToolRequest（P10 归一），同样留痕
        val ex = assertFailsWith<ToolException> {
            f.app.productTools.invoke(activityId, ToolRequest(ToolName("get_chapter")))
        }
        assertTrue(ex.error is ToolError.InvalidToolRequest, "缺必填参数 → InvalidToolRequest（既有类型）")
        val afterInvalid = f.app.toolCallLogs.listByActivity(activityId)
        assertEquals(2, afterInvalid.size)
        assertEquals(AgentLogStatus.FAILED, afterInvalid.single { it.toolCallId != deniedLog.toolCallId }.status)
        f.close()
    }

    // ---------- 不创建 / 不改写生命周期 ----------

    @Test
    fun `tool invocation does not create or modify sessions workflows and tasks`() {
        val f = fixture()
        val activityId = activity(f)
        val novelId = f.app.novels.listOriginals().first().novelId
        val sessions = count(f.handle, "AgentSession")
        val workflows = count(f.handle, "Workflow")
        val tasks = count(f.handle, "Task")

        f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName("get_novel"), buildJsonObject { put("novelId", novelId.value) }),
        )

        assertEquals(sessions, count(f.handle, "AgentSession"), "工具调用不创建 Session")
        assertEquals(workflows, count(f.handle, "Workflow"), "工具调用不创建 Workflow")
        assertEquals(tasks, count(f.handle, "Task"), "工具调用不创建 Task")
        f.close()
    }

    @Test
    fun `invoke requires an existing activity as scope anchor`() {
        val f = fixture()
        val ex = assertFailsWith<com.qianyan.application.error.ApplicationException> {
            f.app.productTools.invoke(
                ActivityId("a-ghost"),
                ToolRequest(ToolName("get_novel"), buildJsonObject { put("novelId", "n-ghost") }),
            )
        }
        assertTrue(ex.error is com.qianyan.application.error.ApplicationError.EntityNotFound, "无 Activity → 无作用域，拒绝执行")
        f.close()
    }

    private fun count(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}