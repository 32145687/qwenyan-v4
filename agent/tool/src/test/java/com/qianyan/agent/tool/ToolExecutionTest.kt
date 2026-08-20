package com.qianyan.agent.tool

import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolParameterSpec
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 简单回显工具（可配置是否失败）。 */
private class EchoTool(var failByThrow: Boolean = false) : Tool {
    override val definition = ToolDefinition(
        toolName = ToolName("echo"),
        description = "回显输入 value",
        parameters = listOf(ToolParameterSpec("value", required = true)),
    )

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult {
        if (failByThrow) throw IllegalStateException("boom")
        val value = request.arguments["value"]?.let { (it as JsonPrimitive).content } ?: ""
        return ToolResult(toolName = ToolName("echo"), success = true, output = buildJsonObject { put("value", value) })
    }
}

/** 返回 success=false 的业务失败工具。 */
private class AlwaysFailTool : Tool {
    override val definition = ToolDefinition(toolName = ToolName("fail"), description = "总是失败")
    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        ToolResult(toolName = ToolName("fail"), success = false, error = "业务不允许")
}

class ToolExecutionTest {

    private fun executor(vararg tools: Tool): ToolExecutor = ToolExecutor(ToolRegistry().apply { tools.forEach { register(it) } })

    private fun echoRequest(value: String): ToolRequest =
        ToolRequest(ToolName("echo"), buildJsonObject { put("value", value) })

    @Test
    fun `execute success returns result`() {
        val executor = executor(EchoTool())
        val result = executor.execute(echoRequest("ping"), ToolContext())

        assertTrue(result.success)
        val echoed = result.output["value"]?.let { (it as JsonPrimitive).content }
        assertEquals("ping", echoed)
        assertNullError(result)
    }

    @Test
    fun `execute unknown tool throws ToolNotFound`() {
        val executor = executor(EchoTool())
        val ex = assertFailsWith<ToolException> {
            executor.execute(ToolRequest(ToolName("nope")), ToolContext())
        }
        assertTrue(ex.error is ToolError.ToolNotFound)
    }

    @Test
    fun `execute missing required param throws InvalidToolRequest`() {
        val executor = executor(EchoTool())
        val ex = assertFailsWith<ToolException> {
            executor.execute(ToolRequest(ToolName("echo"), buildJsonObject { }), ToolContext())
        }
        assertTrue(ex.error is ToolError.InvalidToolRequest)
    }

    @Test
    fun `execute unknown param throws InvalidToolRequest`() {
        val executor = executor(EchoTool())
        val ex = assertFailsWith<ToolException> {
            executor.execute(
                ToolRequest(ToolName("echo"), buildJsonObject { put("unknown", "x") }),
                ToolContext(),
            )
        }
        assertTrue(ex.error is ToolError.InvalidToolRequest)
    }

    @Test
    fun `execute throwing tool throws ToolExecutionFailed`() {
        val executor = executor(EchoTool(failByThrow = true))
        val ex = assertFailsWith<ToolException> {
            executor.execute(echoRequest("ping"), ToolContext())
        }
        assertTrue(ex.error is ToolError.ToolExecutionFailed)
    }

    @Test
    fun `business failure returns success=false result without throwing`() {
        val executor = executor(AlwaysFailTool())
        val result = executor.execute(ToolRequest(ToolName("fail")), ToolContext())

        assertFalse(result.success)
        assertEquals("业务不允许", result.error)
    }

    @Test
    fun `availableTools lists registered definitions sorted by name`() {
        val executor = executor(EchoTool(), AlwaysFailTool())
        val names = executor.availableTools().map { it.toolName.value }
        // "echo" 与 "fail" 排序后：echo < fail
        assertEquals(listOf("echo", "fail"), names)
    }

    private fun assertNullError(result: ToolResult) = assertEquals(null, result.error)
}