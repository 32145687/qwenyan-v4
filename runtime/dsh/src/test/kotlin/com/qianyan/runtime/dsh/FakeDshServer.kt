package com.qianyan.runtime.dsh

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

/**
 * Fake ACP server（**仅测试用**）：用同一个 JVM 启动，讲 newline-delimited JSON-RPC over stdio。
 *
 * 用途：让 `./gradlew test` **不依赖**开发者本机安装 DSH；
 * 同时可以注入故障（非法帧 / 静默 / 启动即退 / 崩溃 / stderr 噪声）来验证 typed 失败与清理。
 *
 * stdout 只写 JSON-RPC；诊断一律走 stderr。
 *
 * 模式（第一个参数）：
 *  - `normal`（默认）：完整 initialize / session/new / prompt(+update) / close
 *  - `permission`：prompt 期间发 `session/request_permission`（server → client 请求），
 *      等待客户端应答后把结果作为 assistant 文本回传（验证权限映射方向）
 *  - `garbage`：回答前先吐一行非 JSON
 *  - `silent`：不响应任何请求（触发超时）
 *  - `failstart`：立即 exit(3)（stdout 无内容）
 *  - `eof`：回答 initialize 后关闭流并 exit(0)
 *  - `crash`：回答 initialize 后 exit(7)
 *  - `stderr-noise`：正常行为 + 往 stderr 写诊断
 *
 * 收到 `session/cancel` 通知时一律往 stderr 写 `cancel-received`（供测试观察）。
 */
object FakeDshServer {

    private const val SESSION_ID = "sess-fake-1"
    private const val PERMISSION_REQUEST_ID = 900L

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: "normal"
        if (mode == "failstart") {
            System.err.println("fake-dsh: startup failed")
            exitProcess(3)
        }
        val out = BufferedWriter(OutputStreamWriter(System.out, StandardCharsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))

        if (mode == "stderr-noise") System.err.println("fake-dsh: diagnostic line")

        var pendingPromptId: Long? = null

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            if (mode == "silent") continue

            val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)

            if (mode == "garbage") {
                out.write("this-is-not-json\n"); out.flush()
            }

            when (method) {
                "initialize" -> {
                    emit(out, """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":1,"serverInfo":{"name":"fake-dsh","version":"0.0.1"}}}""")
                    if (mode == "eof" || mode == "crash") {
                        out.close()
                        exitProcess(if (mode == "crash") 7 else 0)
                    }
                }

                "session/new" -> emit(
                    out,
                    """{"jsonrpc":"2.0","id":$id,"result":{"sessionId":"$SESSION_ID"}}""",
                )

                "session/prompt" -> {
                    if (mode == "permission") {
                        // 先发 server → client 权限请求，prompt 结算推迟到客户端应答之后
                        pendingPromptId = id
                        emit(
                            out,
                            """{"jsonrpc":"2.0","id":$PERMISSION_REQUEST_ID,"method":"session/request_permission","params":{"sessionId":"$SESSION_ID","toolCall":{"toolCallId":"call-1"},"options":[{"optionId":"allow-once","name":"Allow once","kind":"allow_once"},{"optionId":"reject-once","name":"Reject","kind":"reject_once"}]}}""",
                        )
                    } else {
                        // 最终答案只通过 session/update 交付（验证 notification 路径确实是答案来源）
                        emit(out, """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$SESSION_ID","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"DSH_"}}}}""")
                        emit(out, """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$SESSION_ID","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"POC_OK"}}}}""")
                        emit(out, """{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"end_turn"}}""")
                    }
                }

                "session/cancel" -> System.err.println("fake-dsh: cancel-received")

                "session/close" -> emit(out, """{"jsonrpc":"2.0","id":$id,"result":{}}""")

                // 客户端对 server → client 请求的**应答**（无 method、有 id）
                null -> {
                    if (id == PERMISSION_REQUEST_ID) {
                        val outcomeValue = Regex("\"outcome\"\\s*:\\s*\"(selected|cancelled)\"").find(line)
                            ?.groupValues?.get(1) ?: "unknown"
                        val optionId = Regex("\"optionId\"\\s*:\\s*\"([^\"]+)\"").find(line)
                            ?.groupValues?.get(1) ?: "none"
                        val echo = "PERM:$outcomeValue:$optionId"
                        emit(out, """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$SESSION_ID","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"$echo"}}}}""")
                        pendingPromptId?.let { emit(out, """{"jsonrpc":"2.0","id":$it,"result":{"stopReason":"end_turn"}}""") }
                        pendingPromptId = null
                    }
                }

                else -> if (id != null) {
                    emit(out, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"method not found: $method"}}""")
                }
            }
        }
        out.flush()
    }

    private fun emit(out: BufferedWriter, json: String) {
        out.write(json)
        out.write("\n")
        out.flush()
    }
}