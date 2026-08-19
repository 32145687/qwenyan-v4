package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.BaseNovelId
import com.qianyan.model.VocabularyId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.impl.DeepSeekLLMGateway
import com.qianyan.provider.impl.MiMoLLMGateway
import com.qianyan.provider.impl.transport.HttpResponse
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P9 Application 集成测试：真实 Provider（DeepSeek / MiMo）经 fake transport 走完整
 * AnalysisUseCases → LLMGateway → ProviderResponse 链路，普通测试不依赖真实网络。
 *
 * 验证：装配方可把真实网关 + 模型注入 ApplicationContainer，且 Analysis 全链路仍能
 * 产出并持久化 PENDING 词汇候选；模型 Profile 正确传到 wire 请求。
 */
class RealProviderApplicationIntegrationTest {

    private fun source(text: String): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), "novel.txt")

    private val validText = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"

    /** 与 AnalysisUseCases 解析契约一致的词汇 JSON，作为 assistant content。 */
    private val vocabJson =
        "{\"vocabulary\":[" +
            "{\"canonical\":\"灵石\",\"type\":\"WORLD_TERM\",\"aliases\":[]}," +
            "{\"canonical\":\"丹田\",\"type\":\"REALM\",\"aliases\":[\"气海\"]}" +
            "]}"

    /** 把词汇 JSON 包装为 OpenAI 兼容 chat.completion 响应体。 */
    private fun completionBody(content: String): String =
        """{"id":"chatcmpl-test","choices":[{"index":0,"message":{"role":"assistant","content":${
            "\"" + content.replace("\"", "\\\"") + "\""
        }},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

    private fun import(app: ApplicationContainer): Pair<String, String> {
        val out = app.txts.importTxtAsOriginal(source(validText), title = "我的小说")
        return out.documentId.value to out.novelId.value
    }

    private fun vocab(app: ApplicationContainer, novelId: String): VocabularyId {
        val id = VocabularyId("novel-vocab-$novelId")
        app.vocabularies.saveVocabulary(
            Vocabulary(vocabularyId = id, novelId = com.qianyan.model.NovelId(novelId), scopeLevel = VocabularyScopeLevel.NOVEL, name = "NOVEL词库"),
        )
        return id
    }

    private fun originalContext(novelId: String): VariantContext =
        VariantContext(baseNovelId = BaseNovelId(novelId))

    @Test
    fun `deepseek gateway through application completes analysis and persists candidates`() {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val transport = LlmHttpClient { url, _, body ->
            capturedUrl = url
            capturedBody = body
            HttpResponse(200, completionBody(vocabJson))
        }
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = transport)
        val app = ApplicationContainer.open(
            analysisGateway = gateway,
            analysisModel = ModelProfile.DEEPSEEK_V4_FLASH,
        )
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val out = app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))

        assertEquals(2, out.candidateIds.size)
        assertEquals(listOf("灵石", "丹田"), out.analysisResult.vocabularySuggestions.map { it.canonical })
        assertEquals("https://api.deepseek.com/chat/completions", capturedUrl)
        assertTrue(capturedBody!!.contains("\"model\":\"deepseek-v4-flash\""))

        val persisted = app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId))
        assertEquals(listOf("灵石", "丹田"), persisted.map { it.suggested.canonical })
    }

    @Test
    fun `mimo gateway through application completes analysis and persists candidates`() {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val transport = LlmHttpClient { url, _, body ->
            capturedUrl = url
            capturedBody = body
            HttpResponse(200, completionBody(vocabJson))
        }
        val gateway = MiMoLLMGateway(apiKey = "test-key", client = transport)
        val app = ApplicationContainer.open(
            analysisGateway = gateway,
            analysisModel = ModelProfile.MIMO_V2_5,
        )
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val out = app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))

        assertEquals(2, out.candidateIds.size)
        assertEquals(listOf("灵石", "丹田"), out.analysisResult.vocabularySuggestions.map { it.canonical })
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", capturedUrl)
        assertTrue(capturedBody!!.contains("\"model\":\"mimo-v2.5-pro\""))
    }

    @Test
    fun `real gateway provider failure surfaces as application provider unavailable error`() {
        val transport = LlmHttpClient { _, _, _ -> HttpResponse(429, """{"error":{"code":"rate_limit_exceeded"}}""") }
        val gateway = DeepSeekLLMGateway(apiKey = "test-key", client = transport)
        val app = ApplicationContainer.open(analysisGateway = gateway, analysisModel = ModelProfile.DEEPSEEK_V4_FLASH)
        val (docId, novelId) = import(app)
        val vocabId = vocab(app, novelId)

        val ex = kotlin.test.assertFailsWith<com.qianyan.application.error.ApplicationException> {
            app.analysis.analyzeTxtOriginal(com.qianyan.model.TxtDocumentId(docId), vocabId, originalContext(novelId))
        }
        assertTrue(ex.error is com.qianyan.application.error.ApplicationError.ProviderUnavailable)
        assertTrue(app.vocabularyRepository.findCandidatesByNovel(com.qianyan.model.NovelId(novelId)).isEmpty())
    }
}
