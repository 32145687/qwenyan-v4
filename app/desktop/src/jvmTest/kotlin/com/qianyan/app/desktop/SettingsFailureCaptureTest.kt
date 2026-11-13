package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-O87-H（O-87 加固）· 设置页可抛点边界捕获 + 未捕获异常落盘。
 *
 * 全部使用临时 appDir 与假 Key，无真实网络；断言里不出现任何真实凭证。
 */
class SettingsFailureCaptureTest {

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            com.qianyan.provider.impl.transport.LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    /** 让 [com.qianyan.app.desktop.DesktopCrashLog] 的日志落到临时文件，避免污染真实 %APPDATA%。 */
    private fun withCrashLog(dir: Path, block: (Path) -> Unit) {
        val logFile = dir.resolve("logs").resolve("qianyan-crash.log")
        DesktopCrashLog.fileOverride = logFile
        try {
            block(logFile)
        } finally {
            DesktopCrashLog.fileOverride = null
        }
    }

    /** (a) 凭据文件不可读（这里是同名目录）时，isConfigured 返回 false 且不抛。 */
    @Test
    fun `unreadable credential file reports unconfigured instead of throwing`() {
        val dir = Files.createTempDirectory("qianyan-o87-unreadable")
        Files.createDirectory(dir.resolve("credentials.properties"))
        val graph = graphAt(dir)

        withCrashLog(dir) {
            assertFalse(graph.isConfigured(ProviderType.DEEPSEEK), "读不到凭据必须回落为未配置")
        }
    }

    /** (b) persist / build 失败时返回 false、给出可读提示、不抛出。 */
    @Test
    fun `selection persist failure reports message instead of throwing`() {
        val dir = Files.createTempDirectory("qianyan-o87-persist")
        val graph = graphAt(dir)
        // provider-selection.properties 是目录 ⇒ Properties.store 写失败。
        Files.createDirectory(dir.resolve("provider-selection.properties"))

        withCrashLog(dir) { logFile ->
            val ok = graph.saveProviderConfig(ProviderType.MOCK)
            assertFalse(ok, "落盘失败必须返回 false")
            val message = graph.providerMessage.value
            assertNotNull(message, "失败必须通过 providerMessage 可见")
            assertTrue("保存失败" in message, "提示需含「保存失败」：$message")
            assertTrue(Files.exists(logFile), "失败必须写入日志文件")
        }
    }

    /** (c) 清除 Key 写文件失败时返回 false、给出可读提示、不抛出。 */
    @Test
    fun `clear key failure reports message instead of throwing`() {
        val dir = Files.createTempDirectory("qianyan-o87-clear")
        Files.createDirectory(dir.resolve("credentials.properties"))
        val graph = graphAt(dir)

        withCrashLog(dir) {
            assertFalse(graph.clearApiKey(ProviderType.DEEPSEEK), "写失败必须返回 false")
            val message = graph.providerMessage.value
            assertNotNull(message, "失败必须通过 providerMessage 可见")
            assertTrue("无法写入本地凭据文件" in message, "提示需说明写凭据失败：$message")
        }
    }

    /** (d) 未捕获异常处理器把完整栈（含 Caused by 与 at com.qianyan）落盘，且不含凭据值。 */
    @Test
    fun `uncaught handler logs full stack chain without credential values`() {
        val dir = Files.createTempDirectory("qianyan-o87-crashlog")
        val sentinel = "sentinel-key-DO-NOT-LOG-9f3a"
        FileProviderCredentialStore(dir).setApiKey(ProviderType.DEEPSEEK, sentinel)

        withCrashLog(dir) { logFile ->
            DesktopCrashLog.installGlobalHandlers()
            val handler = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(handler, "必须注册全局未捕获异常处理器")
            handler.uncaughtException(Thread.currentThread(), failingChain())

            val text = Files.readString(logFile)
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""").containsMatchIn(text), "日志需带 ISO 时间戳")
            assertTrue("Caused by" in text, "日志需含 Caused by 链")
            assertTrue("at com.qianyan" in text, "日志需含 com.qianyan 栈帧")
            assertTrue(sentinel !in text, "日志不得出现凭据值")
        }
    }

    /** 从 com.qianyan 栈帧构造、带 cause 的异常（保证栈里出现 at com.qianyan）。 */
    private fun failingChain(): Throwable =
        try {
            throw IllegalStateException("inner failure")
        } catch (e: IllegalStateException) {
            RuntimeException("outer failure", e)
        }
}
