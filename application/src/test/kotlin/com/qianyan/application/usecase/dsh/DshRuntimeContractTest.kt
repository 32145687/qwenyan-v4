package com.qianyan.application.usecase.dsh

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DSH-04 · Runtime Adapter 契约测试（纯契约 + 内存假件，不接生产路径）。
 *
 * 覆盖：编排事件回放（含多轮 TOOL_CALL）、取消语义、失败语义、权限三态、
 * 摘要上限，以及"零小说概念"源码扫描硬断言。
 */
class DshRuntimeContractTest {

    private fun task() = RuntimeTask(
        input = "把第二章的冲突拉高",
        allowedTools = listOf("list_chapters", "get_latest_draft"),
        maxRounds = 3,
        budgetMillis = 60_000,
    )

    /* 1. 编排事件回放：顺序一致、含 ≥1 次 TOOL_CALL 多轮、摘要受控、可重复 */
    @Test
    fun `dispatch replays scripted events including multi round tool calls`() {
        val session = InMemoryDshRuntime().openSession("wf-demo")
        val events = session.dispatch(task()).events().toList()

        assertEquals(
            listOf(
                RuntimeEventKind.PHASE_START,
                RuntimeEventKind.SKILL_SELECTED,
                RuntimeEventKind.MODEL_CALL,
                RuntimeEventKind.TOOL_CALL,
                RuntimeEventKind.TOOL_RESULT,
                RuntimeEventKind.MODEL_CALL,
                RuntimeEventKind.TOOL_CALL,
                RuntimeEventKind.TOOL_RESULT,
                RuntimeEventKind.CANDIDATE_READY,
                RuntimeEventKind.PHASE_END,
            ),
            events.map { it.kind },
            "事件序列必须与编排逐项一致",
        )
        assertTrue(
            events.count { it.kind == RuntimeEventKind.TOOL_CALL } >= 2,
            "编排必须覆盖多轮工具调用（≥2 次 TOOL_CALL）",
        )
        assertTrue(events.all { it.detailSummary.length <= RuntimeEvent.MAX_SUMMARY })
        assertEquals(
            events,
            session.dispatch(task()).events().toList(),
            "同一编排必须可重复回放",
        )
        assertEquals(
            session.sessionId,
            InMemoryDshRuntime().openSession("wf-demo").sessionId,
            "同一 workflowRef 的会话标识必须确定（可重复）",
        )
    }

    /* 2. 取消：事件流以 CANCELLED 结束，之后无事件 */
    @Test
    fun `cancel ends stream with cancelled and no further events`() {
        val handle = InMemoryDshRuntime().openSession("wf-cancel").dispatch(task())
        val iterator = handle.events().iterator()
        assertEquals(RuntimeEventKind.PHASE_START, iterator.next().kind)

        handle.cancel()
        val rest = iterator.asSequence().toList()
        assertEquals(RuntimeEventKind.CANCELLED, rest.last().kind, "取消后事件流必须以 CANCELLED 结束")
        assertEquals(1, rest.count { it.kind == RuntimeEventKind.CANCELLED }, "CANCELLED 只出现一次")
    }

    /* 3. 失败：FAILED 携带摘要，不抛到调用方 */
    @Test
    fun `failure emits failed event and never throws`() {
        val runtime = InMemoryDshRuntime(failureSummary = "provider timeout: upstream 504 " + "x".repeat(120))
        val events = runtime.openSession("wf-fail").dispatch(task()).events().toList()

        assertEquals(RuntimeEventKind.FAILED, events.last().kind)
        assertEquals(false, events.last().ok, "FAILED 的 ok 必须为 false")
        assertTrue(events.last().detailSummary.length <= RuntimeEvent.MAX_SUMMARY, "失败摘要必须 ≤80 字")
        assertTrue(events.last().detailSummary.endsWith("…"), "超长失败摘要必须截断")
    }

    /* 4. 权限：请求 → 应答 → 回传三态各 1 例 */
    @Test
    fun `permission request and response covers approve deny timeout`() {
        val requests = listOf(
            PermissionRequest("p-1", "write_file", "写入第二章草稿"),
            PermissionRequest("p-2", "run_tool", "调用外部工具"),
            PermissionRequest("p-3", "delete_draft", "删除候选草稿"),
        )
        val runtime = InMemoryDshRuntime(permissions = requests)

        assertEquals(requests, runtime.pendingPermissions(), "请求必须先进入待应答列表")
        assertEquals(PermissionResponse.APPROVED, runtime.respond("p-1", PermissionResponse.APPROVED))
        assertEquals(PermissionResponse.DENIED, runtime.respond("p-2", PermissionResponse.DENIED))
        assertEquals(PermissionResponse.TIMEOUT, runtime.respond("p-3", PermissionResponse.TIMEOUT))
        assertTrue(runtime.pendingPermissions().isEmpty(), "三态应答后不得残留待应答请求")
        assertNull(runtime.respond("p-1", PermissionResponse.DENIED), "已回答的请求不得再次应答")
        assertNull(runtime.respond("p-unknown", PermissionResponse.APPROVED), "未知请求不得伪造应答")
    }

    /* 5. 摘要契约：≤80 字，超长截断加 …；直接构造超限必须拒绝 */
    @Test
    fun `event summary is capped at eighty characters`() {
        val long = "字".repeat(200)
        assertEquals(RuntimeEvent.MAX_SUMMARY, RuntimeEvent.summarize(long).length)
        assertTrue(RuntimeEvent.summarize(long).endsWith("…"))
        assertEquals("短摘要", RuntimeEvent.summarize("短摘要"))
        assertFailsWith<IllegalArgumentException> {
            RuntimeEvent(RuntimeEventKind.MODEL_CALL, "model", long)
        }
    }

    /* 6. 零小说概念：契约包源码不得出现业务类型标识符（注释除外） */
    @Test
    fun `dsh package has zero novel domain concepts`() {
        val dir = Path.of("src/main/kotlin/com/qianyan/application/usecase/dsh")
        val files = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.toList()
        }
        assertTrue(files.isNotEmpty(), "契约包不得为空")

        val forbidden = listOf("Novel", "Chapter", "Canonical", "HumanGate", "WorldModel")
        val offenders = files.flatMap { path ->
            val code = stripComments(Files.readString(path))
            forbidden.filter { Regex("\\b$it\\b").containsMatchIn(code) }
                .map { "${path.fileName}: $it" }
        }
        assertTrue(offenders.isEmpty(), "DSH 契约包不得出现小说领域标识符：$offenders")
    }

    /** 去掉块注释与行注释后再扫描（说明文字不算标识符）。 */
    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
}
