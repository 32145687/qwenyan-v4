package com.qianyan.application.usecase.context

import com.qianyan.model.IntentType
import com.qianyan.model.agent.ToolName
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.tool.ToolRequest
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I6 · 边界测试（§16 / §17 / §26 Tool-Context Boundary）。
 *
 * 验证：
 *  - `Tool Result ≠ Context Pack`：工具调用是"能取什么"，Context 是"模型应该看到什么"；
 *  - `ToolCallLog`（Agent 做过什么）**不会**自动全部进入 Context；
 *  - Context Engine 源码不越界（不直连存储实现、不调用 LLM / Provider / Agent、不吸收写路径）。
 */
class ContextBoundaryTest {

    @Test
    fun `tool call logs never enter the context pack automatically`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        // 产生真实 Tool 调用记录（I5）
        val session = f.app.agentSessions.startSession(w.novelId)
        val activity = f.app.activities.start(session.sessionId, "I6_TEST")
        val toolResult = f.app.productTools.invoke(
            activity.activityId,
            ToolRequest(ToolName("get_novel"), buildJsonObject { put("novelId", w.novelId.value) }),
        )
        assertTrue(toolResult.success, "Tool 调用应成功（前置条件）")
        val logs = f.app.toolCallLogs.listByActivity(activity.activityId)
        assertEquals(1, logs.size, "Tool 调用留下记录（I4）")

        val pack = f.app.contextEngine.build(
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2),
        )

        assertTrue(
            pack.items.all { it.source in ContextSourceKind.entries.toSet() },
            "Context 条目只能来自声明的 ContextSource：${pack.items.map { it.source }}",
        )
        assertTrue(
            pack.items.none { item ->
                val logIds = logs.map { it.toolCallId.value }
                logIds.any { item.itemId.contains(it) || item.content.contains(it) }
            },
            "Tool 调用记录不得自动进入 Context",
        )
        assertTrue(
            pack.items.none { item -> logs.any { it.toolName.value == item.source.name.lowercase() } },
            "Context 的来源不是 Tool 名（Tool ≠ Context）：${pack.items.map { it.source }}",
        )
        f.close()
    }

    @Test
    fun `invoking a product tool does not change the context pack`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val engine = fixedClockEngine(f)
        val request = ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2)

        val before = engine.build(request)
        val session = f.app.agentSessions.startSession(w.novelId)
        val activity = f.app.activities.start(session.sessionId, "I6_TEST")
        f.app.productTools.invoke(
            activity.activityId,
            ToolRequest(ToolName("get_chapter"), buildJsonObject { put("chapterId", w.chapter2.value) }),
        )
        val after = engine.build(request)

        assertEquals(before, after, "Tool 调用不改变 Context Pack（Context 由请求与项目数据决定）")
        f.close()
    }

    @Test
    fun `context engine sources stay read only and provider free`() {
        val dir = File("src/main/kotlin/com/qianyan/application/usecase/context")
        assertTrue(dir.isDirectory, "找不到 Context Engine 源码目录：${dir.absolutePath}")
        val code = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
        listOf(
            // 不直连存储实现（Repository 契约允许：与既有 LCL / Planning 同层读法）
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "storage.repository.Sqlite",
            // 不调用 LLM / Provider / Agent（§15）
            "LLMGateway", "ModelProfile", "AgentRuntime", "provider.api", "provider.impl",
            // 不吸收写路径 / 后续阶段职责
            "saveContent", "createOriginal", "createNextChapter", "confirmFinalDraft", "approveGate",
            "WorkflowOrchestrator", "ToolCallLog", "ContextInspector", "ProjectIndex",
        ).forEach { token -> assertTrue(token !in code, "Context Engine 不得出现 '$token'（只读 + 分层）") }
    }
}