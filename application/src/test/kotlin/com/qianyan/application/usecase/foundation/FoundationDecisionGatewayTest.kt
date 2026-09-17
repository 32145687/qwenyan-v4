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
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.workflow.HumanDecision
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P15-B · FoundationDecisionGateway 测试：验证 Gateway 是 Android/Desktop 共用的极薄 Application seam。
 * 纯委托到底层 foundationDecisions；不暴露 Repository / Checkpoint / Gate storage / SQLDelight；T7/T8 结构保证由编译期类型与行为一致共同约束。
 */
class FoundationDecisionGatewayTest {

    private fun gateway() = MockLLMGateway {
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", """{"chapterGoal":"x"}""") }.toString()),
            usage = Usage(0, 0, 0),
            finishReason = FinishReason.STOP,
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

    private fun v1() = FoundationProposal(
        genre = listOf(GenreId("romance")),
        direction = StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻"),
        audience = NarrativeProfile(pov = "第三人称", readerTone = "热血"),
        policy = WritingPolicy(listOf("不OOC")),
    )

    /* T1：presentProposal 经 Gateway 返回 FoundationProposalView。 */
    @Test
    fun `t1 gateway present returns view`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        val view = app.foundationDecisionGateway.presentProposal(NovelId("n1"))
        assertEquals(1L, view.revision)
        assertEquals(listOf<GenreId>(GenreId("romance")), view.genre)
        assertEquals(HumanGateStatus.PENDING, view.gateStatus)
        assertTrue(view.canConfirm)
    }

    /* T2：modifyProposal 经 Gateway 进入 P15-A revision flow（N+1 + Decision + 新 Gate）。 */
    @Test
    fun `t2 gateway modify enters revision flow`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        val modified = v1().copy(direction = StoryDirection(theme = "改线"))
        app.foundationDecisionGateway.modifyProposal(
            NovelId("n1"), modified,
            FoundationDecision(sourceRevision = 1L, changedFields = setOf(FoundationDecisionField.DIRECTION), userReason = "改线", modifiedAt = Clock.System.now()),
        )

        val r = app.foundationDecisionGateway.restoreProposal(NovelId("n1"))
        assertEquals(2L, r.proposalRevision)
        assertEquals("改线", r.proposal.direction.theme)
        assertEquals(1L, r.decision!!.sourceRevision)
    }

    /* T3：rejectProposal 经 Gateway 委托（不写 Foundation）。 */
    @Test
    fun `t3 gateway reject delegates without writing foundation`() {
        val app = open(); seedNovel(app, "n1")
        val p = app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        app.foundationDecisionGateway.rejectProposal(p.gate.gateId)

        val view = app.foundationDecisionGateway.presentProposal(NovelId("n1"))
        assertEquals(HumanDecision.REJECTED, view.gateDecision)
        assertTrue(app.storyFoundationRepository.getStoryFoundation(NovelId("n1")) == null, "REJECT 不应写 Foundation")
    }

    /* T4：requestFoundationRevision 经 Gateway 委托（不写 Foundation）。 */
    @Test
    fun `t4 gateway request revision delegates without writing foundation`() {
        val app = open(); seedNovel(app, "n1")
        val p = app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        app.foundationDecisionGateway.requestFoundationRevision(p.gate.gateId)

        val view = app.foundationDecisionGateway.presentProposal(NovelId("n1"))
        assertEquals(HumanDecision.REQUEST_REVISION, view.gateDecision)
        assertTrue(app.storyFoundationRepository.getStoryFoundation(NovelId("n1")) == null)
    }

    /* T5：confirmFoundation 经 Gateway 写 Confirmed，且保持幂等。 */
    @Test
    fun `t5 gateway confirm persists foundation idempotently`() {
        val app = open(); seedNovel(app, "n1")
        val p = app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        val confirmed = app.foundationDecisionGateway.confirmFoundation(p.gate.gateId, NovelId("n1"))
        assertEquals(HumanDecision.APPROVED, confirmed.gate.decision)
        val f = assertNotNull(app.storyFoundationRepository.getStoryFoundation(NovelId("n1")))
        assertEquals(listOf<GenreId>(GenreId("romance")), f.genre)

        // 幂等：重复 Confirm 不产生新写入（同内容）
        val again = app.foundationDecisionGateway.confirmFoundation(p.gate.gateId, NovelId("n1"))
        assertEquals(HumanDecision.APPROVED, again.gate.decision)
        assertEquals(f, app.storyFoundationRepository.getStoryFoundation(NovelId("n1")))
    }

    /* T6：restoreProposal 经 Gateway 恢复 Proposal / revision / gate / decision。 */
    @Test
    fun `t6 gateway restore returns complete state`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())
        app.foundationDecisions.modifyProposal(NovelId("n1"), v1().copy(policy = WritingPolicy(listOf("不OOC", "紧凑"))),
            FoundationDecision(sourceRevision = 1L, changedFields = setOf(FoundationDecisionField.POLICY), userReason = null, modifiedAt = Clock.System.now()))

        val r = app.foundationDecisionGateway.restoreProposal(NovelId("n1"))
        assertEquals(2L, r.proposalRevision)
        assertNotNull(r.gate)
        assertNotNull(r.decision)
        assertEquals(HumanGateStatus.PENDING, r.gate!!.status)
    }

    /* T7：Gateway 类型不暴露任何仓库/存储类型（编译期保证：接口仅返回 Presentation/Result DTO）。 */
    @Test
    fun `t7 gateway exposes no repository storage types`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        val gateway: FoundationDecisionGateway = app.foundationDecisionGateway
        val view: FoundationProposalView = gateway.presentProposal(NovelId("n1"))
        val restored: RestoredFoundation = gateway.restoreProposal(NovelId("n1"))
        // 该方法签名只返回 Application DTO / domain 值对象，不含 Repository / Checkpoint / SQLDelight。
        assertTrue(view.proposalId.isNotEmpty())
        assertNotNull(restored.workflow)
    }

    /* T8：Gateway 不产生第二套业务逻辑——行为与底层 foundationDecisions 一致（同源委托）。 */
    @Test
    fun `t8 gateway is pure delegation with identical behavior`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        // 经 Gateway 修改与直接经 foundationDecisions 修改产出一致的状态。
        app.foundationDecisionGateway.modifyProposal(NovelId("n1"), v1().copy(genre = listOf(GenreId("romance"), GenreId("fantasy_eastern"))))
        val viaGateway = app.foundationDecisionGateway.restoreProposal(NovelId("n1"))
        val viaCore = app.foundationDecisions.restoreProposal(NovelId("n1"))
        assertEquals(viaCore.proposalRevision, viaGateway.proposalRevision)
        assertEquals(viaCore.proposal, viaGateway.proposal)
        assertEquals(viaCore.gate!!.gateId, viaGateway.gate!!.gateId)
    }
}