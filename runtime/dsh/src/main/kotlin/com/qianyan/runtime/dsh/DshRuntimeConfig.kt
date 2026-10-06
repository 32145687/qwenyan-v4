package com.qianyan.runtime.dsh

import com.qianyan.runtime.contract.RuntimeError
import java.io.File
import java.nio.file.Path

/**
 * DSH ACP Runtime 配置（**只存在于 Adapter 层**，不进入 Domain / Storage / UI）。
 *
 * 启动命令不做硬编码假设：按 `配置 → 系统属性 → 环境变量 → PATH` 顺序解析，
 * 解析不到时**不猜测可执行文件**，`start()` 直接抛 typed [RuntimeError.StartupFailed]。
 *
 * 可覆盖项（系统属性 / 环境变量二选一）：
 * ```
 * -Dqianyan.dsh.executable / DSH_EXECUTABLE      可执行文件（如 dsh / node）
 * -Dqianyan.dsh.args       / DSH_ARGS            以空格分隔的参数（如 "--profile acp"）
 * -Dqianyan.dsh.workdir    / DSH_WORKDIR         working directory
 * ```
 */
data class DshRuntimeConfig(
    val executable: String,
    val arguments: List<String> = listOf("--profile", "acp"),
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val startupTimeoutMillis: Long = 10_000,
    val requestTimeoutMillis: Long = 30_000,
) {

    /** 解析默认配置：系统属性 → 环境变量 → "dsh"。 */
    companion object {

        const val PROP_EXECUTABLE = "qianyan.dsh.executable"
        const val PROP_ARGS = "qianyan.dsh.args"
        const val PROP_WORKDIR = "qianyan.dsh.workdir"

        fun resolveDefault(): DshRuntimeConfig {
            val exe = System.getProperty(PROP_EXECUTABLE)
                ?: System.getenv("DSH_EXECUTABLE")
                ?: "dsh"
            val args = (System.getProperty(PROP_ARGS) ?: System.getenv("DSH_ARGS"))
                ?.split(" ")?.filter { it.isNotBlank() }
                ?: listOf("--profile", "acp")
            val workdir = (System.getProperty(PROP_WORKDIR) ?: System.getenv("DSH_WORKDIR"))?.let { Path.of(it) }
            return DshRuntimeConfig(exe, args, workdir)
        }

        /**
         * 探测本机是否存在可用的 DSH 可执行文件（**不做网络安装**）。
         * 用于让真实集成测试在缺依赖时 SKIP 而不是失败。
         */
        fun isAvailable(config: DshRuntimeConfig = resolveDefault()): Boolean = resolveExecutable(config) != null

        /** 解析可执行文件路径：绝对/相对路径 → PATH 查找。 */
        fun resolveExecutable(config: DshRuntimeConfig = resolveDefault()): String? {
            val exe = config.executable
            val direct = File(exe)
            if (direct.isAbsolute || exe.contains(File.separator)) {
                return if (direct.canExecute()) direct.absolutePath else null
            }
            val path = System.getenv("PATH") ?: return null
            val exts = if (System.getProperty("os.name").startsWith("Windows")) listOf(".exe", ".cmd", ".bat", "") else listOf("")
            return path.split(File.pathSeparator).asSequence()
                .flatMap { dir -> exts.asSequence().map { File(dir, exe + it) } }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath
        }

        /** 校验可执行文件；不存在时抛 typed 启动失败（不进入无限等待）。 */
        fun requireExecutable(config: DshRuntimeConfig = resolveDefault()): String =
            resolveExecutable(config) ?: throw RuntimeError.StartupFailed(
                "找不到 Runtime 可执行文件 '${config.executable}'（可用 -D$PROP_EXECUTABLE 或 DSH_EXECUTABLE 指定）",
            )
    }
}