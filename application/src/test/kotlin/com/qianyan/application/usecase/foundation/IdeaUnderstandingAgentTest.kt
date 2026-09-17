package com.qianyan.application.usecase.foundation

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.foundation.StoryIntent
import com.qianyan.model.foundation.StoryIntentId
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P15-C · IdeaUnderstandingAgent 测试：验证真实 parser / contract / output mapping（使用 Mock LLM）。
 */
class IdeaUnderstandingAgentTest {

    private val rawIdea = "我想写一个少年逆天改命的故事"
    private val IDEA_JSON = """{"aiSummary":"热血成长，主角逆天改命","proposal":{"genre":["romance"],"direction":{"theme":"成长","conflict":"信念之战","promise":"逆袭","storyType":"东方玄幻"},"audience":{"pov":"第三人称","readerTone":"热血"},"policy":{"rules":["不OOC"]}}}"""

    private fun intent() = StoryIntent(
        id = StoryIntentId("i-1"), novelId = NovelId("n1"), rawIdea = rawIdea,
        aiSummary = null, createdAt = Clock.System.now(), updatedAt = Clock.System.now(),
    )

    private fun gateway(output: String) = MockLLMGateway {
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", output) }.toString()),
            usage = Usage(0, 0, 0), finishReason = FinishReason.STOP,
        )
    }

    @Test
    fun `agent parses aiSummary and foundationProposal`() {
        val agent = IdeaUnderstandingAgent(gateway(IDEA_JSON), ErrorMapper)

        val u = agent.understand(intent())

        assertEquals("热血成长，主角逆天改命", u.aiSummary)
        assertEquals(listOf<GenreId>(GenreId("romance")), u.proposal.genre)
        assertEquals("成长", u.proposal.direction.theme)
        assertEquals("信念之战", u.proposal.direction.conflict)
        assertEquals("逆袭", u.proposal.direction.promise)
        assertEquals("东方玄幻", u.proposal.direction.storyType)
        assertEquals("第三人称", u.proposal.audience.pov)
        assertEquals("热血", u.proposal.audience.readerTone)
        assertEquals(WritingPolicy(listOf("不OOC")), u.proposal.policy)
    }

    @Test
    fun `agent rejects malformed output with typed error`() {
        val agent = IdeaUnderstandingAgent(gateway("""{"chapterGoal":"x"}"""), ErrorMapper)

        val ex = assertFailsWith<ApplicationException> { agent.understand(intent()) }
        assertTrue(ex.error is ApplicationError.InvalidAnalysisOutput, "解析失败应归一到 InvalidAnalysisOutput，实际=${ex.error}")
    }

    @Test
    fun `agent handles empty answer`() {
        val agent = IdeaUnderstandingAgent(gateway(""), ErrorMapper)
        val ex = assertFailsWith<ApplicationException> { agent.understand(intent()) }
        assertTrue(ex.error is ApplicationError.InvalidAnalysisOutput)
    }
}