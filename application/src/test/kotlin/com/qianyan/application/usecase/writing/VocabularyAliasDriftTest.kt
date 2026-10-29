package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.agent.NovelAgentFixture
import com.qianyan.application.usecase.agent.NovelWorld
import com.qianyan.application.usecase.agent.novelAgentFixture
import com.qianyan.application.usecase.agent.request
import com.qianyan.application.usecase.agent.seedNovelWorld
import com.qianyan.application.usecase.writing.critique.CritiqueAgent
import com.qianyan.application.usecase.writing.planning.VocabularyLite
import com.qianyan.application.usecase.writing.revision.RevisionAgent
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-O38 · D-38：Critic/Revision 别名漂移检查（只读、WARNING、不自动替换）。
 */
class VocabularyAliasDriftTest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")
    private val vocabulary = listOf(
        VocabularyLite(canonical = "灵石", aliases = listOf("灵晶", "石"), replacement = "灵石"),
    )

    private fun response(body: String): ProviderResponse = ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
        usage = Usage(10, 10, 20),
        finishReason = FinishReason.STOP,
    )

    private fun userOf(request: ProviderRequest): String =
        request.messages.first { it.role == ChatRole.USER }.content

    private fun systemOf(request: ProviderRequest): String =
        request.messages.first { it.role == ChatRole.SYSTEM }.content

    private fun draft(content: String): Draft = Draft(
        draftId = DraftId("d-vocab-1"),
        novelId = NovelId("novel-vocab"),
        content = content,
        status = DraftStatus.WRITTEN,
        createdAt = fixed,
        updatedAt = fixed,
    )

    /* ---------------- Critic ---------------- */

    @Test
    fun `critic warns on alias and keeps passed true`() {
        val prompts = mutableListOf<String>()
        val gateway = MockLLMGateway { req ->
            prompts += userOf(req)
            response("""{"passed":true,"issues":[]}""")
        }
        val result = CritiqueAgent(gateway, ErrorMapper, ModelProfile.MOCK)
            .critique(draft("主角握紧灵晶，走进风雪。"), vocabulary)

        assertTrue(result.passed, "别名 WARNING 不得改变 passed")
        val warning = result.issues.single { it.field == "词库别名" }
        assertEquals(IssueSeverity.WARNING, warning.severity)
        assertTrue("灵石" in warning.message, warning.message)
        assertTrue("灵晶" in warning.message, warning.message)

        val prompt = prompts.single()
        assertTrue("【词库规范】" in prompt, prompt)
        assertTrue("灵石" in prompt && "灵晶" in prompt, prompt)
    }

    @Test
    fun `critic reports no alias warning when canonical is used`() {
        val gateway = MockLLMGateway { response("""{"passed":true,"issues":[]}""") }
        val result = CritiqueAgent(gateway, ErrorMapper, ModelProfile.MOCK)
            .critique(draft("主角握紧灵石，走进风雪。"), vocabulary)

        assertTrue(result.passed)
        assertTrue(result.issues.none { it.field == "词库别名" })
    }

    @Test
    fun `critic with empty vocabulary keeps prompt and issues unchanged`() {
        val prompts = mutableListOf<String>()
        val gateway = MockLLMGateway { req ->
            prompts += userOf(req)
            response("""{"passed":false,"issues":[{"field":"逻辑","severity":"WARNING","message":"前后矛盾"}]}""")
        }
        val result = CritiqueAgent(gateway, ErrorMapper, ModelProfile.MOCK)
            .critique(draft("主角握紧灵晶。"))

        assertFalse(result.passed)
        assertEquals(1, result.issues.size)
        assertEquals("前后矛盾", result.issues.single().message)
        assertTrue("【词库规范】" !in prompts.single(), prompts.single())
    }

    /* ---------------- Revision ---------------- */

    @Test
    fun `revision prompt includes vocabulary and canonical preference instruction`() {
        val prompts = mutableListOf<String>()
        val gateway = MockLLMGateway { req ->
            prompts += userOf(req)
            response("""{"content":"修订后的完整正文"}""")
        }
        val original = draft("主角握紧灵晶。")
        val revised = RevisionAgent(gateway, ErrorMapper, ModelProfile.MOCK)
            .revise(original, ValidationResult(passed = true), vocabulary)

        assertEquals("修订后的完整正文", revised.content)
        assertNotEquals(original.draftId, revised.draftId)
        val prompt = prompts.single()
        assertTrue("【词库规范】" in prompt, prompt)
        assertTrue("灵石" in prompt && "灵晶" in prompt, prompt)
        assertTrue("优先使用规范词" in prompt, prompt)
        assertTrue("不得机械替换作者原句" in prompt, prompt)
    }

    @Test
    fun `revision with empty vocabulary keeps previous prompt`() {
        val prompts = mutableListOf<String>()
        val gateway = MockLLMGateway { req ->
            prompts += userOf(req)
            response("""{"content":"修订后的完整正文"}""")
        }
        RevisionAgent(gateway, ErrorMapper, ModelProfile.MOCK)
            .revise(draft("主角握紧灵晶。"), ValidationResult(passed = true))

        val prompt = prompts.single()
        assertTrue("【词库规范】" !in prompt, prompt)
        assertFalse("优先使用规范词" in prompt, prompt)
        assertTrue("请依据上述评审意见对【原草稿】进行修订，按 JSON 输出新正文" in prompt, prompt)
    }

    /* ---------------- NovelAgent 接线 ---------------- */

    private fun seedApprovedVocabulary(f: NovelAgentFixture, w: NovelWorld) {
        val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(w.novelId)
        f.app.vocabularyRepository.saveEntry(
            VocabularyEntry(
                entryId = VocabularyEntryId("ve-vocab-drift"),
                vocabularyId = vocabularyId,
                novelId = w.novelId,
                scopeLevel = VocabularyScopeLevel.NOVEL,
                canonical = "灵石",
                aliases = listOf("灵晶", "石"),
                type = VocabularyEntryType.PROPER_NOUN,
                status = VocabularyEntryStatus.APPROVED,
            ),
        )
    }

    private fun gateway(records: MutableList<ProviderRequest>): MockLLMGateway = MockLLMGateway { request ->
        records += request
        val system = systemOf(request)
        val body = when {
            "StoryCriticAgent" in system -> """{"passed":true,"issues":[]}"""
            "StoryRevisionAgent" in system -> buildJsonObject { put("content", "修订后的完整正文") }.toString()
            else -> buildJsonObject { put("content", "占位") }.toString()
        }
        response(body)
    }

    @Test
    fun `novel agent passes approved vocabulary to critic and revision`() {
        val records = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(gateway(records))
        try {
            val w = seedNovelWorld(f)
            seedApprovedVocabulary(f, w)

            val analysis = f.app.novelAgent.run(request(w, "分析第一章"))
            assertEquals(NovelAgentOutcome.COMPLETED, analysis.outcome)

            val rewrite = f.app.novelAgent.run(request(w, "重写第一章"))
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, rewrite.outcome)
            val pendingGate = assertNotNull(
                f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId)?.pendingGateId,
            )
            assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(pendingGate)?.status)

            val criticPrompts = records.filter { "StoryCriticAgent" in systemOf(it) }.map { userOf(it) }
            val revisionPrompts = records.filter { "StoryRevisionAgent" in systemOf(it) }.map { userOf(it) }
            assertEquals(2, criticPrompts.size, "分析 + 改写各一次 critique")
            assertEquals(1, revisionPrompts.size)
            (criticPrompts + revisionPrompts).forEach { prompt ->
                assertTrue("【词库规范】" in prompt, prompt)
                assertTrue("灵石" in prompt, prompt)
                assertTrue("灵晶" in prompt, prompt)
            }
        } finally {
            f.close()
        }
    }

    @Test
    fun `novel agent without vocabulary keeps prompts free of vocabulary section`() {
        val records = mutableListOf<ProviderRequest>()
        val f = novelAgentFixture(gateway(records))
        try {
            val w = seedNovelWorld(f)

            val analysis = f.app.novelAgent.run(request(w, "分析第一章"))
            assertEquals(NovelAgentOutcome.COMPLETED, analysis.outcome)
            val rewrite = f.app.novelAgent.run(request(w, "重写第一章"))
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, rewrite.outcome)

            val criticPrompts = records.filter { "StoryCriticAgent" in systemOf(it) }.map { userOf(it) }
            val revisionPrompts = records.filter { "StoryRevisionAgent" in systemOf(it) }.map { userOf(it) }
            assertEquals(2, criticPrompts.size)
            assertEquals(1, revisionPrompts.size)
            (criticPrompts + revisionPrompts).forEach { prompt ->
                assertFalse("【词库规范】" in prompt, prompt)
            }
        } finally {
            f.close()
        }
    }
}
