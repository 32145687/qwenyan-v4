package com.qianyan.application.usecase.writing.writer

import com.qianyan.agent.runtime.AgentResponseParser
import com.qianyan.agent.runtime.AgentStep
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.planning.CurrentDraftLite
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-O75-D · Writer 真实输出取证（默认关闭；`QIANYAN_REAL_SMOKE=1` 才执行）。
 *
 * 只做取证：用**生产同一份** WriterAgent（AgentRuntime + 同一 system prompt + 同一 renderInput）打真实网关，
 * 记录每轮原始输出前 300 字符、finish_reason、AgentResponseParser 判定，以及"双重解析"两段各自的形态：
 *  - 第 1 段：运行时 Final 协议（`{"answer": "<字符串>"}` → answer 字符串；否则原样）；
 *  - 第 2 段：DraftParser 期望 `{"content":"..."}`。
 *
 * 不修改生产代码、不写库、不打印 Key。输出写 `build/writer-probe.txt`。
 */
@EnabledIfEnvironmentVariable(named = "QIANYAN_REAL_SMOKE", matches = "1")
class WriterRealOutputProbeTest {

    private val report = StringBuilder()
    private val json = Json { ignoreUnknownKeys = true }
    private val rows = mutableListOf<Triple<String, Int, Boolean>>()

    private class RecordingGateway(private val delegate: LLMGateway) : LLMGateway {
        val turns = mutableListOf<String>()
        val finishes = mutableListOf<String>()
        var systemPrompt: String = ""

        override fun chat(request: ProviderRequest): ProviderResponse {
            if (systemPrompt.isBlank()) systemPrompt = request.messages.firstOrNull()?.content.orEmpty()
            val response = delegate.chat(request)
            turns += response.message.content
            finishes += response.finishReason.name
            return response
        }
    }

    private val novelId = NovelId("probe-novel")

    private fun context(draft: String) = PlanningContext(
        request = UserWritingRequest(
            requestId = RequestId("writer-probe"),
            intentType = IntentType.CONTINUE,
            target = TargetRef(TargetKind.CHAPTER, null),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId(novelId.value),
            variantId = null,
            scope = VariantScope.ORIGINAL,
        ),
        novelId = novelId,
        novelTitle = "雨夜",
        novelGenre = listOf("悬疑"),
        novelSynopsis = "少年在雨夜回到旧宅，木箱里藏着一封旧信。",
        currentDraft = CurrentDraftLite(DraftId("probe-draft"), draft),
    )

    private fun plan() = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-probe"),
        arcId = ArcId("default-arc"),
        actId = ActId("default-act"),
        novelId = novelId,
        chapterGoal = "少年在雨夜敲开旧宅的门，接续木箱里旧信的线索，完成本章的情绪转折",
        expectedEvents = listOf("敲门", "旧信展读"),
        emotionalDirection = "压抑、克制",
        endingHook = "信末落款是一个不该出现的名字",
    )

    /** 第 1 段：运行时如何把原始 content 变成 answer。 */
    private fun runtimeAnswer(raw: String): String = when (val step = AgentResponseParser.parse(raw)) {
        is AgentStep.Final -> step.answer
        is AgentStep.Tool -> "<TOOL:${step.request.toolName.value}>"
    }

    /** 第 2 段：DraftParser 的输入形态（是否含 content 字段 / 是否是散文）。 */
    private fun draftShape(text: String): String {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return "PROSE(非JSON)"
        val content = root["content"]
        if (content is JsonObject) return "OBJECT"
        val value = content?.jsonPrimitive?.contentOrNull
        if (root.containsKey("answer")) {
            val nested = runCatching { json.parseToJsonElement(root.getValue("answer").jsonPrimitive.content).jsonObject }.getOrNull()
            return if (nested?.get("content") != null) "answerString→JSON(content)" else "answerString→非content"
        }
        return if (value != null) "直接JSON(content)" else "JSON无content"
    }

    private fun probe(label: String, provider: String, model: String, gateway: LLMGateway, draft: String) {
        val recording = RecordingGateway(gateway)
        val agent = WriterAgent(recording, ErrorMapper, ModelProfile(id = model, label = model))
        val outcome = runCatching { agent.write(context(draft), plan()) }

        report.appendLine("=== $label ($provider / $model) currentDraft=${draft.length}字 ===")
        report.appendLine("systemPrompt[0..300]: ${recording.systemPrompt.replace("\n", "\\n").take(300)}")
        report.appendLine("turns=${recording.turns.size}")
        recording.turns.forEachIndexed { i, raw ->
            val step = when (AgentResponseParser.parse(raw)) {
                is AgentStep.Tool -> "TOOL"
                is AgentStep.Final -> "FINAL"
            }
            val answer = runtimeAnswer(raw)
            report.appendLine("  #${i + 1} finish=${recording.finishes.getOrNull(i)} parsed=$step rawLen=${raw.length}")
            report.appendLine("     raw[0..300]: ${raw.replace("\n", "\\n").take(300)}")
            report.appendLine("     answer[0..120]: ${answer.replace("\n", "\\n").take(120)}")
            report.appendLine("     draftInput=${draftShape(answer)}")
        }
        outcome.fold(
            onSuccess = { d ->
                report.appendLine("  result=OK contentLen=${d.content.length} head=${d.content.take(40)}")
                rows += Triple(label, recording.turns.size, true)
            },
            onFailure = { e ->
                report.appendLine("  result=${e::class.simpleName}: ${e.message?.take(200)}")
                rows += Triple(label, recording.turns.size, false)
            },
        )
        report.appendLine()
    }

    @Test
    fun `probe writer raw output on both providers`() {
        val appDir = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home")).resolve("Qianyan")
        val credentials = Properties().apply {
            val file = appDir.resolve("credentials.properties").toFile()
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val miMoKey = credentials.getProperty("MIMO").orEmpty().trim()
        val deepSeekKey = credentials.getProperty("DEEPSEEK").orEmpty().trim()

        val realText = "雨落在青石板上，巷口的灯一盏一盏亮起来。少年提着一只旧木箱，站在门槛外，没有敲门。" +
            "门内传来翻书的声音，停了一下，又继续。少年把木箱放在台阶上，箱角磨得发白，铜扣上刻着一个模糊的「归」字。"

        if (miMoKey.isNotBlank()) {
            repeat(2) { i ->
                probe(
                    "mimo-writer#${i + 1}", "MiMo", ModelProfile.MIMO_V2_6_FLASH.id,
                    MiMoLLMGateway(apiKey = miMoKey, wireModel = ModelProfile.MIMO_V2_6_FLASH.id),
                    realText,
                )
            }
        } else {
            report.appendLine("SKIPPED_NO_KEY: MIMO")
        }
        if (deepSeekKey.isNotBlank()) {
            probe(
                "deepseek-writer", "DeepSeek", ModelProfile.DEEPSEEK_V4_FLASH.id,
                DeepSeekLLMGateway(apiKey = deepSeekKey, wireModel = ModelProfile.DEEPSEEK_V4_FLASH.id),
                realText,
            )
        } else {
            report.appendLine("SKIPPED_NO_KEY: DEEPSEEK")
        }

        report.appendLine("SUMMARY: samples=${rows.size} ok=${rows.count { it.third }}")
        rows.forEach { (label, turns, ok) -> report.appendLine("  $label turns=$turns ok=$ok") }
        val out = Path.of("build", "writer-probe.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toString())
        println(report)

        assertTrue(rows.size == 3, "预期 3 个真实样本（MiMo ×2 + DeepSeek ×1），实际 ${rows.size}")
        assertTrue(rows.all { it.second >= 1 }, "每个样本都必须拿到模型输出（turns ≥ 1）")
        assertTrue(
            rows.all { it.third },
            "3 个样本必须全部可被 DraftParser 解析成功；失败：" +
                rows.filterNot { it.third }.joinToString("; ") { it.first },
        )
    }
}
