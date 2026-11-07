package com.qianyan.app.desktop

import com.qianyan.app.desktop.di.UsageLedger
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderType
import com.qianyan.provider.UsageSink
import com.qianyan.provider.impl.DeepSeekLLMGateway
import com.qianyan.provider.impl.UsageRecordingAssembler
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * J8/J9 · 真实生成写入账本（默认关闭；`QIANYAN_REAL_SMOKE=1` 才执行）。
 *
 * 链路：`UsageRecordingAssembler`（装配口装饰器）→ 真实 `DeepSeekLLMGateway`（`JdkLlmHttpClient`）
 * → 一次最小 chat → `UsageSink` → `UsageLedger.record` → `<临时目录>/usage.json`
 * → 重新 `UsageLedger(同一文件)` 读回。
 *
 * 纪律：Key 只在运行时读取，绝不打印；账本写在临时目录，不碰真实 `%APPDATA%`；不发送稿件正文。
 * 默认全量只会多 1 条 skipped（门闸），不是失败。
 */
@EnabledIfEnvironmentVariable(named = "QIANYAN_REAL_SMOKE", matches = "1")
class UsageLedgerRealSmokeTest {

    @Test
    fun `real chat is recorded into the usage ledger and survives reopen`() {
        val key = deepSeekKey()
        if (key.isBlank()) {
            println("SKIPPED_NO_KEY: DEEPSEEK 未配置")
            assumeTrue(false, "SKIPPED_NO_KEY: DEEPSEEK 未配置")
        }

        val dir = Files.createTempDirectory("qianyan-usage-real")
        val ledgerPath = dir.resolve("usage.json")
        val ledger = UsageLedger(ledgerPath)

        // 最小装配口：只把配置映射到真实 DeepSeek 网关（Key 不进配置对象）。
        val realAssembler = object : ProviderAssembler {
            override fun assemble(config: ProviderConfiguration): LLMGateway =
                DeepSeekLLMGateway(
                    apiKey = key,
                    client = JdkLlmHttpClient(),
                    baseUrl = config.baseUrl ?: DEEPSEEK_BASE_URL,
                    wireModel = config.model?.id ?: MODEL,
                    reasoningEffort = config.reasoningEffort,
                )

            override fun validate(config: ProviderConfiguration): List<String> = emptyList()
        }
        val recording = UsageRecordingAssembler(
            delegate = realAssembler,
            sink = UsageSink { provider, modelId, usage -> ledger.record(provider, modelId, usage) },
            modelId = MODEL,
        )
        val config = ProviderConfiguration(provider = ProviderType.DEEPSEEK, model = ModelProfile(MODEL))
        val response = recording.assemble(config).chat(
            ProviderRequest(
                model = ModelProfile(MODEL),
                messages = listOf(ChatMessage(ChatRole.USER, "只回复 ok")),
                temperature = 0.0,
                maxTokens = 128,
            ),
        )

        assertTrue(response.content.isNotBlank(), "真实 chat 必须返回非空正文")
        assertEquals(null, ledger.corruptNotice(), "账本不得出现损坏重置")

        val summary = ledger.summary(1)
        assertEquals(1, summary.size, "1 天内应恰有一个（日期×厂商×模型）桶")
        val row = summary.single()
        assertEquals(ProviderType.DEEPSEEK, row.provider)
        assertEquals(MODEL, row.modelId, "装饰器的 modelId 必须原样入账")
        assertEquals(1, row.calls, "一次成功 chat 必须记 1 次调用")
        assertTrue(
            row.total > 0,
            "账本 total 必须 > 0；实际 prompt=${row.prompt} completion=${row.completion}",
        )

        assertTrue(Files.exists(ledgerPath), "usage.json 必须落盘")
        assertTrue(Files.size(ledgerPath) > 0, "usage.json 不得为空")

        val reopened = UsageLedger(ledgerPath)
        assertEquals(summary, reopened.summary(1), "重新打开同一文件必须读回相同聚合")

        // 脱敏结构：只有键名与 token 数值，无 Key。
        println(
            "USAGE_LEDGER_OK provider=${row.provider} model=${row.modelId} calls=${row.calls} " +
                "prompt=${row.prompt} completion=${row.completion} total=${row.total}",
        )
        println("USAGE_JSON=" + ledgerPath.toFile().readText())
    }

    private fun deepSeekKey(): String {
        val dir = System.getenv("QIANYAN_APP_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: run {
                val appData = System.getenv("APPDATA")
                if (appData.isNullOrBlank()) {
                    Path.of(System.getProperty("user.home"), "AppData", "Roaming", "Qianyan")
                } else {
                    Path.of(appData, "Qianyan")
                }
            }
        val file = dir.resolve("credentials.properties")
        if (!Files.exists(file)) return ""
        return Properties().apply { file.toFile().inputStream().use { load(it) } }
            .getProperty("DEEPSEEK").orEmpty().trim()
    }

    private companion object {
        const val MODEL = "deepseek-v4-flash"
        const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    }
}
