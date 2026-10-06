package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimeSessionRequest
import com.qianyan.runtime.contract.RuntimeStopReason
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 真实 DSH ACP 集成测试（**仅当本机存在 DSH 可执行文件时执行**）。
 *
 * 官方入口（README「Entry modes」）：
 * ```
 * dsh --profile acp     # Serve automation clients over ACP stdio until disconnect
 * ```
 * 本测试通过 `-Dqianyan.dsh.executable` / `-Dqianyan.dsh.args` 指定真实命令，例如：
 * ```
 * -Dqianyan.dsh.executable=<path-to-dsh-executable>
 * -Dqianyan.dsh.args="--profile acp"
 * ```
 * `session/new` 需要**绝对 cwd**（官方 acp profile 契约要求），由 `qianyan.dsh.workdir` 提供。
 *
 * 缺依赖时 **SKIP 并说明原因**（不把"本机没有 DSH"当成代码失败）。
 */
class DshRealIntegrationTest {

    @Test
    fun `real dsh acp session returns DSH_POC_OK`() {
        val config = DshRuntimeConfig.resolveDefault()
        assumeTrue(
            DshRuntimeConfig.isAvailable(config),
            "SKIPPED：本机找不到 Runtime 可执行文件 '${config.executable}'；" +
                "如需运行真实集成测试，请用 -Dqianyan.dsh.executable=<path> 或环境变量 DSH_EXECUTABLE 指定（不做网络安装）",
        )

        // ACP 会话需要真实存在的绝对工作目录
        val workdir: Path = config.workingDirectory
            ?: Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val client = DshRuntimeClient(config)
        val handle = client.start()
        try {
            val init = client.initialize()
            println("DSH_INITIALIZE_RESULT=$init")
            println("DSH_DIAGNOSTICS=${client.diagnostics().take(5)}")

            val session = client.createSession(RuntimeSessionRequest(workingDirectory = workdir))
            println("DSH_SESSION_ID=${session.sessionId}")

            val run = client.prompt(session, "Return exactly: DSH_POC_OK")
            println("DSH_STOP_REASON=${run.stopReason} raw=${run.rawStopReason}")
            println("DSH_UPDATES=${run.updates.size} kinds=${run.updates.map { it.kind }.distinct()}")
            println("DSH_FINAL_TEXT=${run.finalText}")

            assertEquals("DSH_POC_OK", run.finalText.trim(), "真实 DSH 应答不符合约定")
            assertEquals(RuntimeStopReason.END_TURN, run.stopReason, "真实 DSH 应以 end_turn 结算")
            client.close(session)
        } finally {
            client.shutdown()
        }
    }
}