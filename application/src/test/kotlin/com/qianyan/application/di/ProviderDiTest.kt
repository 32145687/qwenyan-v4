package com.qianyan.application.di

import com.qianyan.application.error.ApplicationException
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.VocabularyId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.VariantContext
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.TaskType
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.LlmHttpClient
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P12.1.5 Provider-runtime DI + security（T7–T9、T13–T15 + 端到端密钥安全）。
 * 经配置的 DeepSeek gateway（fake transport）跑 Planning / Writing / Analysis / Knowledge Update，
 * 验证配置的 gateway 确实进入各 Use Case，且 TEST_SECRET_KEY 从不落入任何业务可观测输出。
 */
class ProviderDiTest {

    private val secret = "TEST_SECRET_KEY_XYZ"
    private val validText = "第一章\n\n正文一。\n\n第二章\n\n正文三。"
    private val vocabJson = "{\"vocabulary\":[{\"canonical\":\"灵石\",\"type\":\"WORLD_TERM\",\"aliases\":[]}]}"

    /** fake transport：按请求体中的 Agent 名（system prompt）返回对应内容；analysis 返回词库 JSON。 */
    private inner class RecordingClient : LlmHttpClient {
        var lastUrl: String = ""
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String = ""

        override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            val content = when {
                body.contains("StoryPlannerAgent") -> """{"answer":"{\"chapterGoal\":\"救下女主\"}"}"""
                body.contains("StoryWriterAgent") -> """{"answer":"{\"content\":\"正文被写就。\"}"}"""
                body.contains("KnowledgeUpdateAgent") ->
                    """{"answer":"{\"changes\":[{\"changeId\":\"k1\",\"operation\":\"ADD\",\"target\":\"主角\",\"content\":\"已突破金丹期\"}]}"}"""
                else -> vocabJson
            }
            return HttpResponse(200, completion(content))
        }

        private fun completion(contentJson: String): String = buildJsonObject {
            put("choices", buildJsonArray {
                addJsonObject {
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", contentJson)
                    })
                    put("finish_reason", "stop")
                }
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", 1); put("completion_tokens", 1); put("total_tokens", 2)
            })
        }.toString()
    }

    /** 构建经配置（DeepSeek + 已设密钥）的容器，fake transport 注入。 */
    private fun configuredApp(client: RecordingClient = RecordingClient()): Pair<ApplicationContainer, RecordingClient> {
        val store = InMemoryProviderCredentialStore()
        store.setApiKey(ProviderType.DEEPSEEK, secret)
        val assembler = DefaultProviderAssembler(store, client)
        val app = ApplicationContainer.open(providerAssembler = assembler, configuration = ProviderConfiguration(ProviderType.DEEPSEEK))
        return app to client
    }

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-di"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun plan(novelId: NovelId) = ChapterPlan(
        chapterPlanId = ChapterPlanId("plan-di"),
        arcId = ArcId("arc"), actId = ActId("act"),
        novelId = novelId, scope = VariantScope.ORIGINAL,
        chapterGoal = "揭开秘辛",
    )

    /* T13 — 现有 Planning 使用配置后的 LLM Gateway（DeepSeek，非 Mock） */
    @Test
    fun `t13 planning uses configured deepseek gateway`() {
        val (app, client) = configuredApp()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val plan = app.taskRunner.executePlanning(app.tasks.create(TaskType.PLANNING), request(novelId))

        assertEquals("救下女主", plan.chapterGoal)
        assertTrue(client.lastUrl.startsWith("https://api.deepseek.com"))
        assertEquals("Bearer $secret", client.lastHeaders["Authorization"])
        assertTrue(!client.lastBody.contains(secret)) // body 里只有鉴权 header 带 key，payload 不含 key
    }

    /* T14 — 现有 Writing 使用配置后的 LLM Gateway */
    @Test
    fun `t14 writing uses configured deepseek gateway`() {
        val (app, client) = configuredApp()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val draft = app.taskRunner.executeWriting(app.tasks.create(TaskType.WRITING), request(novelId), plan(novelId))

        assertTrue(draft.content.contains("正文被写就"))
        assertTrue(client.lastUrl.startsWith("https://api.deepseek.com"))
        assertEquals("Bearer $secret", client.lastHeaders["Authorization"])
    }

    /* T15 — 现有 Analysis 使用配置后的 LLM Gateway */
    @Test
    fun `t15 analysis uses configured deepseek gateway`() {
        val (app, client) = configuredApp()
        val out = app.txts.importTxtAsOriginal(TxtSource(validText.toByteArray(StandardCharsets.UTF_8), "t.txt"), title = "小说")
        val novelId = out.novelId.value
        val vocabId = VocabularyId("vocab-di")
        app.vocabularies.saveVocabulary(Vocabulary(vocabularyId = vocabId, novelId = NovelId(novelId), scopeLevel = VocabularyScopeLevel.NOVEL, name = "词库"))

        val result = app.analysis.analyzeTxtOriginal(
            out.documentId, vocabId, VariantContext(baseNovelId = BaseNovelId(novelId)),
        )

        assertEquals(listOf("灵石"), result.analysisResult.vocabularySuggestions.map { it.canonical })
        assertTrue(client.lastUrl.startsWith("https://api.deepseek.com"))
        assertEquals("Bearer $secret", client.lastHeaders["Authorization"])
    }

    /* T7/T8/T9 + security：TEST_SECRET_KEY 不落入 Task/Checkpoint/Draft/Memory/Story State 及异常（但确实到达 provider 鉴权） */
    @Test
    fun `security secret never leaks into observable business outputs`() {
        val (app, client) = configuredApp()
        val novelId = app.novels.createOriginal(title = "仙侠")
        val req = request(novelId)

        // Planning → ChapterPlan + PLANNING checkpoint
        val planTask = app.tasks.create(TaskType.PLANNING)
        val plan = app.taskRunner.executePlanning(planTask, req)
        // Writing → Draft(WRITTEN) + WRITING checkpoint
        val writeTask = app.tasks.create(TaskType.WRITING)
        val draft = app.taskRunner.executeWriting(writeTask, req, plan)
        // finalize + confirm
        app.draftRepository.save(draft.copy(status = DraftStatus.FINAL))
        val confirmed = app.confirmations.confirmFinalDraft(draft.draftId, novelId, null)
        // Knowledge Update → Memory(Story State 可读) + KNOWN_UPDATE checkpoint
        app.taskRunner.executeKnowledgeUpdate(writeTask, confirmed)

        // 汇集全部可观测业务输出，逐一断言无 secret
        val checkpoints = app.tasks.findCheckpoints(planTask) + app.tasks.findCheckpoints(writeTask)
        val observable = buildList {
            add(plan.toString())
            add(plan.chapterGoal)
            add(draft.content)
            add(confirmed.content)
            addAll(checkpoints.flatMap { listOf(it.stage, it.snapshot?.toString() ?: "") })
            addAll(app.tasks.findById(planTask).let { listOf("${it.error}", it.status.toString()) })
            addAll(app.tasks.findById(writeTask).let { listOf("${it.error}", it.status.toString()) })
            addAll(app.storyWorldContextResolver.resolve(novelId).memories)
            addAll(app.storyWorldContextResolver.resolve(novelId).orderedVisible)
        }
        observable.forEach { assertTrue(!it.contains(secret), "secret 泄漏到可观测输出: $it") }

        // 但 Token 确实被用于 provider 鉴权（证明配置的 DeepSeek 被真实使用，而非跳过）
        assertTrue(client.lastHeaders["Authorization"]!!.contains(secret))
        // 重复运行不抛异常
        assertNotNull(draft.draftId)
    }
}