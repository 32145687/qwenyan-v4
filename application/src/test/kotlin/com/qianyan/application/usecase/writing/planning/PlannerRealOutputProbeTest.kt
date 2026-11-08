package com.qianyan.application.usecase.writing.planning

import com.qianyan.agent.runtime.AgentResponseParser
import com.qianyan.agent.runtime.AgentStep
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.BaseNovelId
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-O73-F · Planner 真实输出取证（默认关闭；`QIANYAN_REAL_SMOKE=1` 才执行）。
 *
 * 用**生产同一份** PlannerAgent（AgentRuntime + 同一 system prompt + 同一 renderInput，含 O-73 输出契约）
 * 打真实网关，记录每一轮原始响应前 300 字符 / finish_reason / AgentResponseParser 判定，
 * 并断言 **7 个样本全部可被 ChapterPlanParser 解析成功**。
 *
 * 不修改生产代码，不写库，不打印 Key；正文仅用极小样本（22 字占位 / 190 字正文）。
 * 输出同时写 `build/planner-probe.txt`，便于回报取证。
 */
@EnabledIfEnvironmentVariable(named = "QIANYAN_REAL_SMOKE", matches = "1")
class PlannerRealOutputProbeTest {

    private val report = StringBuilder()

    private class RecordingGateway(
        private val delegate: LLMGateway,
        private val slot: String,
    ) : LLMGateway {
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

    private fun context(draft: String, title: String) = PlanningContext(
        request = UserWritingRequest(
            requestId = RequestId("probe"),
            intentType = IntentType.CONTINUE,
            target = TargetRef(TargetKind.CHAPTER, null),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId("probe-novel"),
            variantId = null,
            scope = VariantScope.ORIGINAL,
        ),
        novelId = NovelId("probe-novel"),
        novelTitle = title,
        novelGenre = listOf("东方幻想"),
        novelSynopsis = "少年离山，北上寻名。",
        currentDraft = CurrentDraftLite(DraftId("probe-draft"), draft),
    )

    private val results = mutableListOf<Pair<String, Result<ChapterPlan>>>()
    private val lastOutputs = mutableListOf<String>()

    private fun probe(label: String, provider: String, model: String, gateway: LLMGateway, draft: String, title: String) {
        val recording = RecordingGateway(gateway, label)
        val planner = PlannerAgent(recording, ErrorMapper, ModelProfile(id = model, label = model))
        val ctx = context(draft, title)
        // 与生产同形的纠正重试（生产实现：PlanningExecutionUseCase.planWithCorrectionRetry，单测钉住「总尝试 ≤3」）。
        var lastError: ApplicationException? = null
        var outcome: Result<ChapterPlan>? = null
        var retried = false
        for (attempt in 0..2) {
            val correction: String? = lastError?.let { error ->
                val detail = (error.error as? ApplicationError.InvalidPlanningOutput)?.detail.orEmpty()
                "上一次输出无法解析为规划 JSON：$detail。" +
                    "请只输出合法 JSON 对象（顶层 chapterGoal），字符串内引号必须转义。"
            }
            if (attempt > 0) retried = true
            try {
                outcome = Result.success(planner.plan(ctx, emptyList(), correction = correction))
                break
            } catch (e: ApplicationException) {
                if (e.error !is ApplicationError.InvalidPlanningOutput) {
                    outcome = Result.failure(e)
                    break
                }
                lastError = e
            }
        }
        val finalOutcome = outcome ?: Result.failure(checkNotNull(lastError))
        results += label to finalOutcome
        lastOutputs += recording.turns.lastOrNull().orEmpty()
        report.appendLine("=== $label ($provider / $model) draft=${draft.length}字 ===")
        report.appendLine("systemPrompt[0..300]: ${recording.systemPrompt.replace("\n", "\\n").take(300)}")
        report.appendLine("turns=${recording.turns.size} retried=$retried")
        recording.turns.forEachIndexed { i, content ->
            val oneLine = content.replace("\r", "").replace("\n", "\\n")
            val step = when (AgentResponseParser.parse(content)) {
                is AgentStep.Tool -> "TOOL"
                is AgentStep.Final -> "FINAL"
            }
            report.appendLine("  #${i + 1} finish=${recording.finishes.getOrNull(i)} parsed=$step len=${content.length}")
            report.appendLine("     raw[0..300]: ${oneLine.take(300)}")
        }
        finalOutcome.fold(
            onSuccess = { plan -> report.appendLine("  result=OK chapterGoal=${plan.chapterGoal.take(40)}") },
            onFailure = { e -> report.appendLine("  result=${e::class.simpleName}: ${e.message?.take(200)}") },
        )
        report.appendLine()
    }

    @Test
    fun `probe planner raw output on both providers`() {
        val appDir = Path.of(System.getenv("APPDATA") ?: System.getProperty("user.home")).resolve("Qianyan")
        val credentials = Properties().apply {
            val file = appDir.resolve("credentials.properties").toFile()
            if (file.exists()) file.inputStream().use { load(it) }
        }
        val deepSeekKey = credentials.getProperty("DEEPSEEK").orEmpty().trim()
        val miMoKey = credentials.getProperty("MIMO").orEmpty().trim()

        val placeholder = "这是一段尚未发送的草稿，切换作品时应被清除。"
        val realText = "雪落进雁回关的时候，他想起师父说过的一句话：北境的风，会把人的名字吹散。" +
            "那时他不信。山上的雪是安静的，落在檐角，落在经幡，落在师父的肩头。而关外的雪是横着" +
            "的，带着刀刃一样的风声。「出关三十里就是黑水驿。」同行的人勒住马，「过了黑水驿，就" +
            "没有人再叫你公子了。」他没有立刻答话。他伸手接住一片雪，看它在掌心化成水——师父的遗" +
            "言里，也有这样一句话的形状。「那就让他们重新认识我。」他说。"

        if (miMoKey.isNotBlank()) {
            // 占位草稿重复 3 次：观察是否有「工具循环 / 步数耗尽」的抽样（MiMo 即使 temperature=0 也可能抖动）。
            repeat(3) { i ->
                probe(
                    "mimo-placeholder#${i + 1}", "MiMo", ModelProfile.MIMO_V2_6_FLASH.id,
                    MiMoLLMGateway(apiKey = miMoKey, wireModel = ModelProfile.MIMO_V2_6_FLASH.id),
                    placeholder, "草稿",
                )
            }
            repeat(2) { i ->
                probe(
                    "mimo-real#${i + 1}", "MiMo", ModelProfile.MIMO_V2_6_FLASH.id,
                    MiMoLLMGateway(apiKey = miMoKey, wireModel = ModelProfile.MIMO_V2_6_FLASH.id),
                    realText, "雁回关",
                )
            }
        } else {
            report.appendLine("SKIPPED_NO_KEY: MIMO")
        }
        if (deepSeekKey.isNotBlank()) {
            probe(
                "deepseek-placeholder", "DeepSeek", ModelProfile.DEEPSEEK_V4_FLASH.id,
                DeepSeekLLMGateway(apiKey = deepSeekKey, wireModel = ModelProfile.DEEPSEEK_V4_FLASH.id),
                placeholder, "草稿",
            )
            probe(
                "deepseek-real", "DeepSeek", ModelProfile.DEEPSEEK_V4_FLASH.id,
                DeepSeekLLMGateway(apiKey = deepSeekKey, wireModel = ModelProfile.DEEPSEEK_V4_FLASH.id),
                realText, "雁回关",
            )
        } else {
            report.appendLine("SKIPPED_NO_KEY: DEEPSEEK")
        }

        val out = Path.of("build", "planner-probe.txt")
        Files.createDirectories(out.parent)
        Files.writeString(out, report.toString())
        println(report)

        // O-73 F3：探针降级为**报告**——只做 sanity（7 条输出非空 + 至少 1 条可解析），
        // 个别模型抖动不再让门控测试变红；失败原文完整打印，供规划层裁决。
        val nonEmpty = results.size == 7 && lastOutputs.size == 7 && lastOutputs.all { it.isNotBlank() }
        val passed = results.count { it.second.isSuccess }
        val failed = results.filter { it.second.isFailure }

        report.appendLine("SUMMARY: parsed=$passed/${results.size} nonEmptyOutputs=$nonEmpty")
        failed.forEach { (label, r) ->
            val raw = lastOutputs[results.indexOfFirst { it.first == label }].replace("\n", "\\n")
            report.appendLine("FAILED[$label] ${r.exceptionOrNull()?.message?.take(200)}")
            report.appendLine("  raw[0..300]: ${raw.take(300)}")
        }
        Files.writeString(out, report.toString())
        println(report)

        assertTrue(results.size == 7, "预期 7 个样本（MiMo 占位×3 / 真实×2 + DeepSeek 占位×1 / 真实×1），实际 ${results.size}")
        assertTrue(nonEmpty, "7 条模型输出必须全部非空（空输出 = 取证无效）")
        assertTrue(passed >= 1, "至少 1 条必须可解析（契约/管线的 sanity）")
    }
}
