package com.qianyan.agent.tool

import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolParameterSpec
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 测试用假工具：固定返回 success=true。 */
private class StubTool(val name: String) : Tool {
    override val definition = com.qianyan.model.tool.ToolDefinition(
        toolName = ToolName(name),
        description = "stub $name",
        parameters = listOf(ToolParameterSpec("a", required = true)),
    )

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        ToolResult(toolName = ToolName(name), success = true)
}

class ToolRegistryTest {

    @Test
    fun `register and find a tool`() {
        val registry = ToolRegistry()
        val tool = StubTool("echo")
        registry.register(tool)

        assertSame(tool, registry.find(ToolName("echo")))
        assertTrue(registry.contains(ToolName("echo")))
        assertEquals(1, registry.size())
        assertEquals(listOf(tool), registry.all())
    }

    @Test
    fun `find unknown tool returns null`() {
        val registry = ToolRegistry()
        assertNull(registry.find(ToolName("nope")))
        assertFalse(registry.contains(ToolName("nope")))
        assertEquals(0, registry.size())
    }

    @Test
    fun `registering same name overwrites`() {
        val registry = ToolRegistry()
        registry.register(StubTool("echo"))
        val replacement = StubTool("echo")
        registry.register(replacement)

        assertEquals(1, registry.size())
        assertSame(replacement, registry.find(ToolName("echo")))
    }

    @Test
    fun `all preserves registration order`() {
        val registry = ToolRegistry()
        val a = StubTool("a")
        val b = StubTool("b")
        registry.register(a)
        registry.register(b)

        assertEquals(listOf(a, b), registry.all())
    }

    @Test
    fun `registry is empty initially`() {
        val registry = ToolRegistry()
        assertTrue(registry.all().isEmpty())
        assertFalse(registry.contains(ToolName("anything")))
        assertNotNull(registry.all())
    }
}