package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ReasoningEffort
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import kotlin.system.measureTimeMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertTrue

/**
 * O-58 · 真机冒烟（默认关闭；`QIANYAN_REAL_SMOKE=1` 才执行）。
 *
 * 只做取证，不证明产品链路：
 *  - 真实 `JdkLlmHttpClient` + 本机 `credentials.properties` 的真实 Key；
 *  - 请求正文仅 `user: "只回复 ok"`（最小正文，绝不发送稿件 / 项目内容）；
 *  - Key 只在运行时读取，输出前统一脱敏；Authorization 头永不输出；
 *  - 逐组合记录 HTTP / 耗时 / 回显 model / usage / finish_reason；
 *  - 余额用原始 JDK GET 探测，金额只写本地报告。
 *
 * 默认全量只会多 1 条 skipped（门闸），不是失败。
 */
@EnabledIfEnvironmentVariable(named = "QIANYAN_REAL_SMOKE", matches = "1")
class RealProviderSmokeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private data class Row(
        val provider: String,
        val model: String,
        val thinking: String,
        val result: String,
        val status: Int?,
        val elapsedMs: Long,
        val echoedModel: String,
        val prompt: String,
        val completion: String,
        val total: String,
        val otherUsage: String,
        val finishReason: String,
        val contentLength: String,
        val verdict: String,
    ) {
        fun line(): String = listOf(
            provider, model, thinking, result, elapsedMs.toString(), echoedModel,
            "$prompt/$completion/$total", otherUsage, finishReason, contentLength, verdict,
        ).joinToString(" | ")
    }

    /** 真实传输 + 记录最后一次响应（仅用于取证；不改变传输行为）。 */
    private class RecordingClient : LlmHttpClient {
        private val delegate = JdkLlmHttpClient()
        var status: Int? = null
        var responseBody: String = ""

        override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
            val response = delegate.postJson(url, headers, body)
            status = response.statusCode
            responseBody = response.body
            return response
        }
    }

    @Test
    fun `real provider smoke matrix`() {
        val appDir = appDir()
        val credentials = loadCredentials(appDir)
        val deepSeekKey = credentials.getProperty("DEEPSEEK").orEmpty().trim()
        if (deepSeekKey.isBlank()) {
            println("SKIPPED_NO_KEY: DEEPSEEK 未配置（${appDir.resolve("credentials.properties")}）")
            assumeTrue(false, "SKIPPED_NO_KEY: DEEPSEEK 未配置")
        }
        val secrets = listOf(deepSeekKey, credentials.getProperty("MIMO").orEmpty())
        val rows = mutableListOf<Row>()

        // DeepSeek：3 个候选模型 ×（不发送思考参数、HIGH）；返回 2xx 的组合再补 MAX。
        DEEPSEEK_MODELS.forEach { model ->
            val nullRow = runRow("DeepSeek", model, "none(不发送)", null, secrets) { client ->
                DeepSeekLLMGateway(apiKey = deepSeekKey, client = client, wireModel = model)
            }
            rows += nullRow
            val highRow = runRow("DeepSeek", model, "reasoning_effort=high", ReasoningEffort.HIGH, secrets) { client ->
                DeepSeekLLMGateway(apiKey = deepSeekKey, client = client, wireModel = model)
            }
            rows += highRow
            if (nullRow.is2xx() || highRow.is2xx()) {
                rows += runRow("DeepSeek", model, "reasoning_effort=max", ReasoningEffort.MAX, secrets) { client ->
                    DeepSeekLLMGateway(apiKey = deepSeekKey, client = client, wireModel = model)
                }
            }
        }

        // MiMo：无 Key → SKIPPED_NO_KEY；有 Key → 3 模型 × thinking 开/关。
        val miMoKey = credentials.getProperty("MIMO").orEmpty().trim()
        val miMoStatus: String
        if (miMoKey.isBlank()) {
            miMoStatus = "SKIPPED_NO_KEY"
            println("SKIPPED_NO_KEY: MIMO 未配置")
        } else {
            miMoStatus = "RUN"
            MIMO_MODELS.forEach { model ->
                rows += runRow("MiMo", model, "thinking=disabled", ReasoningEffort.NONE, secrets) { client ->
                    MiMoLLMGateway(apiKey = miMoKey, client = client, wireModel = model)
                }
                rows += runRow("MiMo", model, "thinking=enabled", ReasoningEffort.HIGH, secrets) { client ->
                    MiMoLLMGateway(apiKey = miMoKey, client = client, wireModel = model)
                }
            }
        }

        val balance = probeDeepSeekBalance(deepSeekKey, secrets)

        val header = listOf(
            "厂商", "请求模型ID", "思考参数", "结果", "耗时ms", "回显model",
            "prompt/completion/total", "其它usage字段", "finish_reason", "content长度", "判定",
        ).joinToString(" | ")
        println("SMOKE_MATRIX_BEGIN")
        println(header)
        rows.forEach { println(it.line()) }
        println("SMOKE_MATRIX_END")
        println("MIMO_STATUS=$miMoStatus")
        println("BALANCE=${balance.summary}")
        println("LOCAL_REPORT=${reportPath().toAbsolutePath()}")

        reportPath().toFile().writeText(
            buildString {
                appendLine("Qianyan real-provider smoke (O-58)")
                appendLine("server_time_ms=${System.currentTimeMillis()}")
                appendLine(header)
                rows.forEach { appendLine(it.line()) }
                appendLine("MIMO_STATUS=$miMoStatus")
                appendLine("BALANCE=${balance.summary}")
                appendLine("BALANCE_LOCAL=${balance.localOnly}")
                appendLine("NOTE: 金额仅在本文件；不会进入聊天回报。")
            },
            Charsets.UTF_8,
        )

        assertTrue(
            rows.any { it.is2xx() },
            "至少一个候选必须返回 2xx；实际：${rows.joinToString("; ") { "${it.provider}/${it.model}/${it.thinking}=${it.result}" }}",
        )
    }

    private fun Row.is2xx(): Boolean = status != null && status in 200..299

    private fun runRow(
        provider: String,
        model: String,
        thinking: String,
        effort: ReasoningEffort?,
        secrets: List<String>,
        gateway: (LlmHttpClient) -> LLMGateway,
    ): Row {
        val client = RecordingClient()
        val request = ProviderRequest(
            model = ModelProfile(id = model),
            messages = listOf(ChatMessage(ChatRole.USER, "只回复 ok")),
            temperature = 0.0,
            maxTokens = 64,
            reasoningEffort = effort,
        )
        var failure: Throwable? = null
        val elapsed = measureTimeMillis {
            failure = runCatching { gateway(client).chat(request) }.exceptionOrNull()
        }
        val status = client.status
        val body = client.responseBody
        val result = when {
            failure != null -> "HTTP ${status ?: "-"} / ${failure!!.javaClass.simpleName}"
            else -> "HTTP ${status ?: "-"}"
        }
        val verdict = verdict(status, failure, body)
        val usage = parseUsage(body)
        val other = usage.filterKeys { it !in USAGE_CORE }.entries.joinToString(",") { "${it.key}=${it.value}" }
        return Row(
            provider = provider,
            model = model,
            thinking = thinking,
            result = sanitize(result, secrets),
            status = status,
            elapsedMs = elapsed,
            echoedModel = sanitize(str(body, "model"), secrets),
            prompt = usage["prompt_tokens"] ?: "-",
            completion = usage["completion_tokens"] ?: "-",
            total = usage["total_tokens"] ?: "-",
            otherUsage = sanitize(other.ifBlank { "-" }, secrets),
            finishReason = finishReason(body),
            contentLength = contentLength(body).toString(),
            verdict = sanitize(verdict, secrets),
        )
    }

    private fun verdict(status: Int?, failure: Throwable?, body: String): String = when {
        status == null -> "网络异常"
        status in 200..299 -> if (failure == null) "可用" else "解析层崩溃(L-2)"
        status == 401 || status == 403 -> "鉴权失败"
        status == 402 -> "欠费"
        status == 404 -> "模型不存在"
        status == 429 -> "限流"
        status == 400 -> {
            val code = errorCode(body)
            when {
                code == null -> "请求/参数被拒"
                "model" in code.lowercase() -> "模型不存在"
                "reasoning" in code.lowercase() || "thinking" in code.lowercase() ||
                    "parameter" in code.lowercase() || "unsupported" in code.lowercase() -> "参数被拒"
                else -> "其它(code=$code)"
            }
        }
        status >= 500 -> "服务端错误"
        else -> "其它"
    }

    private fun parseUsage(body: String): Map<String, String> = runCatching {
        val usage = (json.parseToJsonElement(body).jsonObject["usage"] as? JsonObject) ?: return emptyMap()
        // 嵌套对象（如 *_tokens_details）保留为 JSON 文本，不能让整段解析失败。
        usage.entries.associate { (k, v) ->
            val primitive = v as? kotlinx.serialization.json.JsonPrimitive
            k to (primitive?.contentOrNull ?: v.toString())
        }
    }.getOrDefault(emptyMap())

    private fun finishReason(body: String): String = runCatching {
        (json.parseToJsonElement(body).jsonObject["choices"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull ?: "-"
    }.getOrDefault("-")

    private fun str(body: String, key: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.contentOrNull ?: "-"
    }.getOrDefault("-")

    private fun contentLength(body: String): Int = runCatching {
        json.parseToJsonElement(body).jsonObject["choices"]
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull?.length ?: 0
    }.getOrDefault(0)

    private fun errorCode(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject ?: return null
        error["code"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun sanitize(text: String, secrets: List<String>): String =
        if (text.isBlank()) text else secrets.filter { it.isNotBlank() }.fold(text) { acc, s -> acc.replace(s, "***") }

    private data class Balance(val summary: String, val localOnly: String)

    /** 原始 JDK HttpClient GET；金额只写本地报告。 */
    private fun probeDeepSeekBalance(key: String, secrets: List<String>): Balance {
        if (key.isBlank()) return Balance("SKIPPED_NO_KEY", "-")
        return runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val request = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/user/balance"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .GET()
                .build()
            val response = client.send(request, BodyHandlers.ofString())
            val root = json.parseToJsonElement(response.body()).jsonObject
            val available = root["is_available"]?.toString() ?: "-"
            val infos = root["balance_infos"]?.toString().orEmpty()
            val fields = runCatching {
                (json.parseToJsonElement(infos) as? kotlinx.serialization.json.JsonArray)
                    ?.firstOrNull()?.jsonObject?.keys?.joinToString(",") ?: "-"
            }.getOrDefault("-")
            val currencies = runCatching {
                (json.parseToJsonElement(infos) as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { it.jsonObject["currency"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString(",") ?: "-"
            }.getOrDefault("-")
            Balance(
                summary = sanitize("HTTP ${response.statusCode()} is_available=$available currency=$currencies fields=[$fields]", secrets),
                localOnly = sanitize("HTTP ${response.statusCode()} await=${response.body()}", secrets),
            )
        }.getOrElse { e ->
            Balance("ERROR ${e.javaClass.simpleName}: ${sanitize(e.message.orEmpty(), secrets)}", "-")
        }
    }

    private fun appDir(): Path {
        System.getenv("QIANYAN_APP_DIR")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        val appData = System.getenv("APPDATA")
        return if (appData.isNullOrBlank()) {
            Path.of(System.getProperty("user.home"), "AppData", "Roaming", "Qianyan")
        } else {
            Path.of(appData, "Qianyan")
        }
    }

    private fun loadCredentials(dir: Path): Properties = Properties().apply {
        val file = dir.resolve("credentials.properties")
        if (Files.exists(file)) file.toFile().inputStream().use { load(it) }
    }

    private fun reportPath(): Path {
        val dir = Path.of("build")
        if (!Files.exists(dir)) Files.createDirectories(dir)
        return dir.resolve("real-smoke-report.txt")
    }

    private companion object {
        val DEEPSEEK_MODELS = listOf("deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro")
        val MIMO_MODELS = listOf("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.6-pro-ultraspeed")
        val USAGE_CORE = setOf("prompt_tokens", "completion_tokens", "total_tokens")
    }
}
