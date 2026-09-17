package com.qianyan.application.usecase.foundation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P15-C · StoryIntentUseCases 测试（真实 SQLite / Checkpoint / Gate；Mock LLM 仅代表 AI 理解输出）。
 */
class StoryIntentUseCasesTest {

    private val IDEA_JSON = """{"aiSummary":"热血成长，主角逆天改命","proposal":{"genre":["romance"],"direction":{"theme":"成长","conflict":"信念之战","promise":"逆袭","storyType":"东方玄幻"},"audience":{"pov":"第三人称","readerTone":"热血"},"policy":{"rules":["不OOC"]}}}"""

    private fun gateway() = MockLLMGateway {
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", IDEA_JSON) }.toString()),
            usage = Usage(0, 0, 0), finishReason = FinishReason.STOP,
        )
    }

    private fun open(): ApplicationContainer {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())
    }

    private fun seedNovel(app: ApplicationContainer, novelId: String) {
        app.novelRepository.createOriginal(
            Novel(NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL, status = ProjectStatus.DRAFT,
                createdAt = Clock.System.now(), updatedAt = Clock.System.now()),
        )
    }

    @Test
    fun `startFromIdea preserves rawIdea and produces proposal with pending gate`() {
        val app = open(); seedNovel(app, "n1")
        val raw = "我想写一个少年逆天改命的故事"

        val r = app.storyIntentUseCases.startFromIdea(NovelId("n1"), raw)

        assertEquals(raw, r.storyIntent.rawIdea, "rawIdea 原样保留")
        assertEquals("热血成长，主角逆天改命", r.storyIntent.aiSummary)
        assertEquals(listOf<GenreId>(GenreId("romance")), r.proposal.proposal.genre)
        assertEquals(1L, r.proposal.proposal.proposalRevision)
        assertEquals(HumanGateStatus.PENDING, r.proposal.gate.status)

        // AI 理解不写 StoryFoundation
        assertNull(app.storyFoundationRepository.getStoryFoundation(NovelId("n1")), "AI Understanding 不得直接写 StoryFoundation")
    }

    @Test
    fun `ai understanding is persisted to workflow-local checkpoint and recoverable`() {
        val app = open(); seedNovel(app, "n1")
        app.storyIntentUseCases.startFromIdea(NovelId("n1"), "少年复仇")

        val idea = app.storyIntentUseCases.presentIdeaState(NovelId("n1"))
        assertNotNull(idea, "Idea 中间态应可从 checkpoint 读取")
        assertEquals("少年复仇", idea.rawIdea)
        assertEquals("热血成长，主角逆天改命", idea.aiSummary)
    }

    @Test
    fun `blank rawIdea is rejected with typed error`() {
        val app = open(); seedNovel(app, "n1")
        val ex = assertFailsWith<com.qianyan.application.error.ApplicationException> {
            app.storyIntentUseCases.startFromIdea(NovelId("n1"), "   ")
        }
        assertTrue(ex.error is com.qianyan.application.error.ApplicationError.InvalidOperation)
    }

    @Test
    fun `understandIdea returns preview without persisting`() {
        val app = open(); seedNovel(app, "n1")
        val u = app.storyIntentUseCases.understandIdea(NovelId("n1"), "少年逆天")
        assertEquals("热血成长，主角逆天改命", u.aiSummary)
        assertNull(app.storyIntentUseCases.presentIdeaState(NovelId("n1")), "understandIdea 不持久化")
    }
}