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
import com.qianyan.model.workflow.HumanDecision
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P15-C · IdeaFirstGateway 测试：验证 Idea-first Application seam 且正确接入既有 FoundationDecisionGateway。
 */
class IdeaFirstGatewayTest {

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
    fun `idea first starts flow and presents idea state`() {
        val app = open(); seedNovel(app, "n1")

        val r = app.ideaFirstGateway.startFromIdea(NovelId("n1"), "少年逆天")
        assertEquals("少年逆天", r.storyIntent.rawIdea)
        val idea = app.ideaFirstGateway.presentIdeaState(NovelId("n1"))
        assertNotNull(idea)
        assertEquals("热血成长，主角逆天改命", idea.aiSummary)
    }

    @Test
    fun `idea proposal is readable via foundation decision gateway and confirmable`() {
        val app = open(); seedNovel(app, "n1")

        val r = app.ideaFirstGateway.startFromIdea(NovelId("n1"), "少年逆天")

        // 决策走 FoundationDecisionGateway（IdeaFirst 上游之后复用）
        val view = app.foundationDecisionGateway.presentProposal(NovelId("n1"))
        assertEquals(1L, view.revision)
        assertEquals(listOf<GenreId>(GenreId("romance")), view.genre)
        assertTrue(view.canConfirm)

        val confirmed = app.foundationDecisionGateway.confirmFoundation(r.proposal.gate.gateId, NovelId("n1"))
        assertEquals(HumanDecision.APPROVED, confirmed.gate.decision)
        val f = assertNotNull(app.storyFoundationRepository.getStoryFoundation(NovelId("n1")))
        assertEquals(listOf<GenreId>(GenreId("romance")), f.genre, "Confirmed StoryFoundation 应经 confirmFoundation 写入")
    }

    @Test
    fun `gateway exposes only application result dto types`() {
        val app = open(); seedNovel(app, "n1")
        val gateway: IdeaFirstGateway = app.ideaFirstGateway
        val r: IdeaFirstResult = gateway.startFromIdea(NovelId("n1"), "少年逆天")
        val idea: com.qianyan.model.foundation.StoryIntent = assertNotNull(gateway.presentIdeaState(NovelId("n1")))
        // 方法签名只暴露 Application DTO / domain 值对象，无 Repository / Storage / Provider / Runtime。
        assertTrue(r.proposal.proposal.proposalRevision >= 1L)
        assertTrue(idea.rawIdea.isNotEmpty())
    }
}