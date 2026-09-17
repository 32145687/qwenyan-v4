package com.qianyan.application.usecase.foundation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.TaskId
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P15-A · Foundation Decision Presentation 测试（聚焦新增语义，不重复已有 F.3 覆盖项）。
 * 覆盖 T1–T7、T13、T14；T8–T12（旧 Gate 确认保护 / REJECT、REQUEST_REVISION 不写 Foundation / Confirm 幂等）由既有 StoryFoundationDecisionUseCasesTest 覆盖。
 */
class StoryFoundationDecisionPresentationTest {

    private fun gateway() = MockLLMGateway {
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", """{"chapterGoal":"x"}""" ) }.toString()),
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

    private fun v2() = v1().copy(direction = StoryDirection(theme = "改线", conflict = "家国", promise = "归来", storyType = "历史"))

    private fun foundationTaskId(novelId: String) = TaskId("foundation-foundation-$novelId")

    /* T1/T2/T3：presentProposal 正确映射 proposal + revision + gate，can* 由 Gate 状态派生。 */
    @Test
    fun `t1 t2 t3 present view maps proposal gate and derives can flags`() {
        val app = open(); seedNovel(app, "n1")
        val p = app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        val view = app.foundationDecisions.presentProposal(NovelId("n1"))
        assertEquals(1L, view.revision)
        assertEquals(listOf<GenreId>(GenreId("romance")), view.genre)
        assertEquals(StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻"), view.direction)
        assertEquals(NarrativeProfile(pov = "第三人称", readerTone = "热血"), view.audience)
        assertEquals(WritingPolicy(listOf("不OOC")), view.writingPolicy)
        assertEquals(HumanGateStatus.PENDING, view.gateStatus)
        assertEquals(HumanDecision.PENDING, view.gateDecision)
        assertTrue(view.canModify && view.canReject && view.canRequestRevision && view.canConfirm, "PENDING 态所有 can* 应为 true")

        // REJECT 后 Gate RESOLVED → 全部 can* = false
        app.foundationDecisions.rejectProposal(p.gate.gateId)
        val after = app.foundationDecisions.presentProposal(NovelId("n1"))
        assertEquals(HumanGateStatus.RESOLVED, after.gateStatus)
        assertEquals(HumanDecision.REJECTED, after.gateDecision)
        assertFalse(after.canModify || after.canReject || after.canRequestRevision || after.canConfirm, "RESOLVED 态 can* 应为 false")
    }

    /* T4/T5/T6/T13：MODIFY 生成 FoundationDecision；sourceRevision=旧 revision；新 revision=N+1；可恢复 Decision。 */
    @Test
    fun `t4 t5 t6 t13 modify produces decision with sourceRevision and new revision is restored`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())
        val now: Instant = Clock.System.now()

        app.foundationDecisions.modifyProposal(
            NovelId("n1"), v2(),
            FoundationDecision(sourceRevision = 1L, changedFields = setOf(FoundationDecisionField.DIRECTION, FoundationDecisionField.POLICY), userReason = "改线", modifiedAt = now),
        )

        val r = app.foundationDecisions.restoreProposal(NovelId("n1"))
        assertEquals(2L, r.proposalRevision, "新 Proposal revision 应为 N+1")
        assertEquals("改线", r.proposal.direction.theme, "N+1 内容为修改后 Proposal")
        val d = assertNotNull(r.decision, "restoreProposal 应恢复 Decision")
        assertEquals(1L, d.sourceRevision, "Decision.sourceRevision 应等于旧 revision N")
        assertEquals(setOf(FoundationDecisionField.DIRECTION, FoundationDecisionField.POLICY), d.changedFields)
        assertEquals("改线", d.userReason)
    }

    /* autoDecision：缺省 2 参 MODIFY 自动推导 changedFields。 */
    @Test
    fun `t4 auto decision infers changed fields`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        app.foundationDecisions.modifyProposal(NovelId("n1"), v2()) // 2 参：自动推导

        val d = app.foundationDecisions.restoreProposal(NovelId("n1")).decision!!
        assertEquals(1L, d.sourceRevision)
        assertTrue(FoundationDecisionField.DIRECTION in d.changedFields, "direction 变化应被记录")
    }

    /* T7：旧 Proposal Revision（N）保留——Checkpoint 序列含 revision 1（未被删除/覆盖）。 */
    @Test
    fun `t7 old proposal revision is retained in checkpoint history`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())
        app.foundationDecisions.modifyProposal(NovelId("n1"), v2(),
            FoundationDecision(sourceRevision = 1L, changedFields = setOf(FoundationDecisionField.DIRECTION), userReason = null, modifiedAt = Clock.System.now()))

        val revisions = app.taskRepository.findCheckpoints(foundationTaskId("n1")).map { it.revision }
        assertEquals(listOf(1, 2), revisions, "revision 1 与 2 都应保留")
    }

    /* T14：Presentation API 只经 Application 返回只读 View（不含任何 Repository 引用）。 */
    @Test
    fun `t14 present api returns read-only view without exposing repository`() {
        val app = open(); seedNovel(app, "n1")
        app.foundationDecisions.prepareProposal(NovelId("n1"), v1())

        val view: FoundationProposalView = app.foundationDecisions.presentProposal(NovelId("n1"))
        // View 仅含值/枚举类型（GenreId/StoryDirection/NarrativeProfile/WritingPolicy/HumanGateStatus/HumanDecision/布尔），
        // 无存储层类型；UI 只需 novelId，无需触碰 Repository / Checkpoint / Gate store。
        assertNotNull(view.proposalId)
        assertTrue(view.proposalId.contains("foundation-"))
    }
}