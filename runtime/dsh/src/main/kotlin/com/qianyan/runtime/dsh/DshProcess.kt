package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimeError
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * DSH ACP 子进程载体（stdio transport）。
 *
 * **stdout 纪律**：
 *  - stdout **只**承载 JSON-RPC protocol stream（只读、逐行交给 [onLine]）；
 *  - 本类与 Adapter **不向 stdout 写任何调试 / 日志 / 异常堆栈**；
 *  - stderr 单独 pump 到诊断缓冲（[diagnostics]），**不与 protocol 流混淆**。
 */
internal class DshProcess(
    private val config: DshRuntimeConfig,
    private val onLine: (String) -> Unit,
    private val onClosed: (exitCode: Int?) -> Unit,
) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val diagnostics = Collections.synchronizedList(mutableListOf<String>())

    private val DIAGNOSTIC_LIMIT = 200

    val alive: Boolean get() = process?.isAlive == true

    val exitCode: Int? get() = process?.let { if (it.isAlive) null else runCatching { it.exitValue() }.getOrNull() }

    /** 启动子进程；找不到可执行文件或无法启动 ⇒ typed 启动失败（不进入等待）。 */
    fun start() {
        val executable = DshRuntimeConfig.requireExecutable(config)
        val builder = ProcessBuilder(listOf(executable) + config.arguments)
        config.workingDirectory?.let { builder.directory(it.toFile()) }
        if (config.environment.isNotEmpty()) builder.environment().putAll(config.environment)
        // stdout / stderr 必须分开：protocol 与 diagnostics 不能混流
        builder.redirectErrorStream(false)

        val p = try {
            builder.start()
        } catch (e: IOException) {
            throw RuntimeError.StartupFailed("启动 Runtime 进程失败：${e.message}", e)
        }
        process = p
        writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        Thread({ pumpStdout(p) }, "dsh-acp-stdout").apply { isDaemon = true; start() }
        Thread({ pumpStderr(p) }, "dsh-acp-stderr").apply { isDaemon = true; start() }
    }

    /**
     * 写入一行 protocol 消息（必须以换行结尾由本方法负责）。
     *
     * **必须同步**：请求由调用线程写、对服务端请求（如 session/request_permission）的应答
     * 由 reader 线程写，两个线程并发写同一个 writer 会让行互相穿插。
     */
    @Synchronized
    fun send(line: String) {
        val w = writer ?: throw RuntimeError.NotAvailable("Runtime 进程尚未启动")
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (e: IOException) {
            throw RuntimeError.StreamClosed("写入 stdin", exitCode)
        }
    }

    fun diagnostics(): List<String> = synchronized(diagnostics) { diagnostics.toList() }

    /** 优雅关闭：先关 stdin 等退出，再强制销毁；不泄漏进程。 */
    fun destroy(graceMillis: Long = 1_500) {
        val p = process ?: return
        runCatching { writer?.close() }
        if (p.isAlive) {
            p.destroy()
            val exited = runCatching { p.waitFor(graceMillis, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited && p.isAlive) p.destroyForcibly()
        }
        writer = null
    }

    private fun pumpStdout(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } catch (_: IOException) {
            // 流异常结束：由下面 onClosed 统一收敛
        } finally {
            val code = runCatching { p.waitFor() }.getOrNull()
            onClosed(code)
        }
    }

    private fun pumpStderr(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.errorStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    synchronized(diagnostics) {
                        if (diagnostics.size < DIAGNOSTIC_LIMIT) diagnostics.add(line)
                    }
                }
            }
        } catch (_: IOException) {
            // 诊断流异常结束无需处理
        }
    }
}