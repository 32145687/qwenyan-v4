package com.qianyan.application.usecase.writing.critique

import com.qianyan.agent.runtime.AgentResponseParser
import com.qianyan.agent.runtime.AgentStep
import com.qianyan.application.error.ErrorMapper
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.impl.DeepSeekLLMGateway
import com.qianyan.provider.impl.MiMoLLMGateway
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-O78-D · Critic 真实输出取证（默认关闭；`QIANYAN_REAL_SMOKE=1` 才执行）。
 *
 * 只调 `CritiqueAgent.critiqueContent(content)`（不传 planId / vocabulary，不写 Checkpoint），
 * 记录每轮 raw / finish_reason / AgentResponseParser 判定 / 4 个机械判定 / CritiqueParser 结果，
 * 用于回答：Q1 原文形态、Q2 失败层（H1–H4）、Q3 是否稳定复现。
 *
 * **报告型探针**：只断言样本数与输出非空，不断言"必须全部可解析"（O-73 F3 降级口径）。
 */
@EnabledIfEnvironmentVariable(named = "QIANYAN_REAL_SMOKE", matches = "1")
class CriticRealOutputProbeTest {

    private val report = StringBuilder()
    private val json = Json { ignoreUnknownKeys = true }
    private val rows = mutableListOf<ProbeRow>()

    private data class ProbeRow(
        val label: String,
        val provider: String,
        val model: String,
        val inputLen: Int,
        val finish: String,
        val contentLen: Int,
        val step: String,
        val fence: Boolean,
        val answerKey: Boolean,
        val passedKey: Boolean,
        val empty: Boolean,
        val critiqueRaw: String,
        val critiqueAnswer: String,
        val outcome: String,
        val detail: String,
        val layer: String,
        val skipped: Boolean,
    )

    private class RecordingGateway(private val delegate: LLMGateway) : LLMGateway {
        val turns = mutableListOf<String>()
        val finishes = mutableListOf<String>()
        var systemPrompt: String = ""

        override fun chat(request: ProviderRequest): ProviderResponse {
            if (systemPrompt.isBlank()) {
                systemPrompt = request.messages.firstOrNull()?.content.orEmpty()
            }
            val response = delegate.chat(request)
            turns += response.message.content
            finishes += response.finishReason.name
            return response
        }
    }

    private fun parseResult(raw: String): String = runCatching {
        val r = CritiqueParser.parse(raw)
        "OK(passed=${r.passed},issues=${r.issues.size})"
    }.getOrElse { e -> "ERR: ${e.message?.take(120)}" }

    private fun objectOf(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun classify(
        success: Boolean,
        empty: Boolean,
        fence: Boolean,
        answerKey: Boolean,
        passedKey: Boolean,
        detail: String,
        rawParse: String,
        answerParse: String,
    ): String {
        if (success) return "NONE"
        // 出口 B 的网关结构错误（ProviderException.InvalidResponse / MalformedOutput 的文案）
        val gatewayLike = listOf("返回非法 JSON", "响应不是 JSON 对象", "响应缺少 choices", "响应 choices 为空",
            "choice 条目非法", "choice 缺少 message", "critique output malformed")
        if (gatewayLike.any { it in detail }) return "H4-网关结构非法"
        if (empty) return "H3-content 为空"
        if (fence) return "H1-围栏"
        if (detail.contains("empty critique output")) return "H3-content 为空"
        if (answerKey && !passedKey) return "H2-双协议(answer 包裹)"
        if (!passedKey && rawParse.startsWith("ERR")) return "H1-前言/非 JSON"
        if (answerParse.startsWith("OK") && rawParse.startsWith("ERR")) return "H2-外层无效内层有效"
        return "JSON 层其他(缺 passed/类型错误)"
    }

    private fun probe(label: String, provider: String, model: String, gateway: LLMGateway, content: String) {
        val recording = RecordingGateway(gateway)
        val agent = CritiqueAgent(recording, ErrorMapper, ModelProfile(id = model, label = model))
        val outcome = runCatching { agent.critiqueContent(content) }
        val raw = recording.turns.lastOrNull().orEmpty()
        val step = when (val s = AgentResponseParser.parse(raw)) {
            is AgentStep.Tool -> "TOOL"
            is AgentStep.Final -> "FINAL"
        }
        val answer = when (val s = AgentResponseParser.parse(raw)) {
            is AgentStep.Tool -> "<TOOL:${s.request.toolName.value}>"
            is AgentStep.Final -> s.answer
        }
        val obj = objectOf(raw)
        val fence = raw.trimStart().startsWith("```")
        val answerKey = obj?.containsKey("answer") == true
        val passedKey = obj?.containsKey("passed") == true
        val empty = raw.isEmpty()
        val rawParse = parseResult(raw)
        val answerParse = parseResult(answer)
        val error = outcome.exceptionOrNull()
        val outcomeText = if (outcome.isSuccess) "OK" else "${error?.let { it::class.simpleName }}: ${error?.message?.take(160)}"
        val layer = classify(
            outcome.isSuccess, empty, fence, answerKey, passedKey, error?.message.orEmpty(), rawParse, answerParse,
        )
        // T-O78-F：形态统计（围栏 / 直出 / answer 包裹），不改探针输入与断言口径。
        val shape = when {
            empty -> "EMPTY"
            fence -> "FENCE"
            answerKey -> "ANSWER_ENVELOPE"
            else -> "DIRECT_JSON"
        }

        val row = ProbeRow(
            label = label, provider = provider, model = model, inputLen = content.length,
            finish = recording.finishes.lastOrNull().orEmpty(),
            contentLen = raw.length, step = step, fence = fence, answerKey = answerKey,
            passedKey = passedKey, empty = empty, critiqueRaw = rawParse, critiqueAnswer = answerParse,
            outcome = outcomeText, detail = error?.message.orEmpty(), layer = layer, skipped = false,
        )
        rows += row

        report.appendLine("=== $label ($provider / $model) input=${content.length}字 ===")
        report.appendLine("systemPrompt[0..400]: ${recording.systemPrompt.replace("\n", "\\n").take(400)}")
        report.appendLine("turns=${recording.turns.size}")
        report.appendLine(
            "  finish=${row.finish} parsed=$step contentLen=${row.contentLen} " +
                "fence=${row.fence} answerKey=${row.answerKey} passedKey=${row.passedKey} empty=${row.empty}",
        )
        report.appendLine("  raw[0..500]: ${raw.replace("\n", "\\n").take(500)}")
        report.appendLine("  answer[0..200]: ${answer.replace("\n", "\\n").take(200)}")
        report.appendLine("  CritiqueParser(raw)=$rawParse")
        report.appendLine("  CritiqueParser(answer)=$answerParse")
        report.appendLine("  result=$outcomeText")
        report.appendLine("  failureLayer=$layer")
        report.appendLine("  shape=$shape")
        report.appendLine()
    }

    private fun skippedRow(label: String, provider: String, model: String) {
        rows += ProbeRow(
            label = label, provider = provider, model = model, inputLen = 0, finish = "-", contentLen = 0,
            step = "-", fence = false, answerKey = false, passedKey = false, empty = true,
            critiqueRaw = "SKIPPED", critiqueAnswer = "SKIPPED", outcome = "SKIPPED_NO_DRAFT",
            detail = "", layer = "-", skipped = true,
        )
        report.appendLine("=== $label ($provider / $model) SKIPPED_NO_DRAFT ===")
    }

    @Test
    fun `probe critic raw output on both providers`() {
        val appDir = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home")).resolve("Qianyan")
        val credentials = Properties().apply {
            val file = appDir.resolve("credentials.properties").toFile()
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val deepSeekKey = credentials.getProperty("DEEPSEEK").orEmpty().trim()
        val miMoKey = credentials.getProperty("MIMO").orEmpty().trim()

        val s1 = "这是一段尚未发送的草稿，切换作品时应被清除。"
        val s2 = (
            "雪落进雁回关的时候，他想起师父说过的一句话：北境的风，会把人的名字吹散。" +
                "「出关三十里就是黑水驿。」同行的人勒住马，「过了黑水驿，就没有人再叫你公子了。」" +
                "他没有立刻答话。他伸手接住一片雪，看它在掌心化成水——师父的遗言里，也有这样一句话的形状。" +
                "「那就让他们重新认识我。」他说。"
            ).repeat(15)
        val s3 = """
            「你怕吗？」她问。
            他说：「怕。但我更怕留在这里。」
            {"draft":"她问：\"你怕吗？\"","note":"{} 与 [] 都在文中"}
            ```json
            {"passed":true,"issues":[]}
            ```
            「那就走。」她把刀放在桌上，「别回头。」
        """.trimIndent()
        val s4Path = System.getenv("QIANYAN_PROBE_DRAFT_FILE")
        val s4 = if (!s4Path.isNullOrBlank() && Files.isReadable(Path.of(s4Path))) {
            Files.readString(Path.of(s4Path))
        } else {
            null
        }

        val samples = listOf("S1" to s1, "S2" to s2, "S3" to s3)
        val providers = mutableListOf(
            Triple("DeepSeek", ModelProfile.DEEPSEEK_V4_FLASH.id, deepSeekKey),
        )
        if (miMoKey.isNotBlank()) providers += Triple("MiMo", ModelProfile.MIMO_V2_6_FLASH.id, miMoKey)

        assertTrue(deepSeekKey.isNotBlank(), "KEY_MISSING: DEEPSEEK（只记状态，不重试）")

        providers.forEach { (provider, model, key) ->
            val gateway = when (provider) {
                "DeepSeek" -> DeepSeekLLMGateway(apiKey = key, wireModel = model)
                else -> MiMoLLMGateway(apiKey = key, wireModel = model)
            }
            samples.forEach { (name, content) ->
                repeat(2) { i ->
                    probe("$name#${i + 1}", provider, model, gateway, content)
                }
            }
            if (s4 != null) {
                repeat(2) { i -> probe("S4#${i + 1}", provider, model, gateway, s4) }
            } else {
                repeat(2) { i -> skippedRow("S4#${i + 1}", provider, model) }
            }
        }

        report.appendLine("SUMMARY: rows=${rows.size} providers=${providers.size}")
        val shapes = rows.filterNot { it.skipped }.groupBy { r ->
            when {
                r.empty -> "EMPTY"
                r.fence -> "FENCE"
                r.answerKey -> "ANSWER_ENVELOPE"
                else -> "DIRECT_JSON"
            }
        }
        report.appendLine("SHAPE STATS (CritiqueParser on runtime answer):")
        shapes.toSortedMap().forEach { (shape, group) ->
            val ok = group.count { it.critiqueRaw.startsWith("OK") }
            val err = group.size - ok
            report.appendLine("  $shape n=${group.size} parserOK=$ok parserERR=$err")
        }
        rows.forEach { r ->
            report.appendLine(
                "${r.label} | ${r.provider}/${r.model} | input=${r.inputLen} | ${r.layer} | " +
                    "fence=${r.fence} answerKey=${r.answerKey} passedKey=${r.passedKey} empty=${r.empty} | " +
                    "raw=${r.critiqueRaw.take(60)}",
            )
        }

        val out = Path.of("build", "critic-probe.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toString())
        println(report)

        val expected = providers.size * 4 * 2
        assertTrue(rows.size == expected, "预期 $expected 条样本，实际 ${rows.size}")
        assertTrue(
            rows.filterNot { it.skipped }.all { it.contentLen > 0 },
            "所有非 SKIPPED 样本的模型输出必须非空（空输出 = 取证无效）",
        )
    }
}
