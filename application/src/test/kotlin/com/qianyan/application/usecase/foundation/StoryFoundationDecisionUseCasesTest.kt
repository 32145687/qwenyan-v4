package com.qianyan.application.usecase.foundation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
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
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P14-F.3 · Story Foundation Confirmation Flow 应用测试。
 * 真实 SQLite（不 Mock StoryFoundationRepository / 不 fake WorkflowRepository / 不跳过 Checkpoint / 不直接构造 APPROVED Gate）。
 * 成功路径一律经：Proposal → Checkpoint → Gate → Confirm → StoryFoundationRepository。
 */
class StoryFoundationDecisionUseCasesTest {

    private val now: Clock = Clock.System

    private data class Ctx(val app: ApplicationContainer, val handle: QianyanDbHandle)

    private fun gateway() = MockLLMGateway {
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", "{}") }.toString()),
            usage = Usage(0, 0, 0),
            finishReason = FinishReason.STOP,
        )
    }

    private fun open(url: String = JdbcSqliteDriver.IN_MEMORY, handles: MutableList<QianyanDbHandle>? = null): Ctx {
        val handle = QianyanDbFactory.open(url)
        handles?.add(handle)
        val app = ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())
        return Ctx(app, handle)
    }

    private fun seedNovel(app: ApplicationContainer, novelId: String) {
        app.novelRepository.createOriginal(
            Novel(
                novelId = NovelId(novelId), projectId = ProjectId("proj-$novelId"), title = "T",
                source = ProjectSource.ORIGINAL_NOVEL, scope = VariantScope.ORIGINAL,
                status = ProjectStatus.DRAFT, createdAt = now.now(), updatedAt = now.now(),
            ),
        )
    }

    private fun proposalV1() = FoundationProposal(
        genre = listOf(GenreId("romance")),
        direction = StoryDirection(theme = "成长", conflict = "信念之战", promise = "逆袭", storyType = "东方玄幻"),
        audience = NarrativeProfile(pov = "第三人称", readerTone = "热血"),
        policy = WritingPolicy(listOf("不OOC", "章节紧凑")),
    )

    private fun proposalV2() = proposalV1().copy(
        genre = listOf(GenreId("romance"), GenreId("fantasy_eastern")),
        direction = StoryDirection(theme = "改线", conflict = "家国", promise = "归来", storyType = "历史"),
        policy = WritingPolicy(listOf("不OOC", "黄金三章")),
    )

    /** 统计某 foundation Workflow 的 Gate 数（验证幂等 / 不重复建 Gate）。 */
    private fun gateCount(driver: SqlDriver, workflowId: String): Long =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM WorkflowHumanGate WHERE gate_key LIKE 'FOUNDATION:$workflowId:%' LIMIT 1",
            { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) },
            0,
        ).value

    /* A. prepare proposal */
    @Test
    fun `a prepare stages proposal checkpoint and pending gate`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())

        assertEquals(1L, p.proposal.proposalRevision)
        assertEquals(HumanGateStatus.PENDING, p.gate.status)
        assertEquals(HumanDecision.PENDING, p.gate.decision)
        // Proposal 可从 Checkpoint 恢复
        val r = ctx.app.foundationDecisions.restoreProposal(NovelId("n1"))
        assertEquals(p.proposal, r.proposal)
        assertEquals(1L, r.proposalRevision)
        assertEquals(HumanGateStatus.PENDING, r.gate!!.status)
        assertNull(ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1")), "prepare 不应写入 StoryFoundation")
        closeDriver(ctx)
    }

    /* B. prepare 相同 Proposal 幂等 */
    @Test
    fun `b prepare same proposal is idempotent`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val a = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        val b = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())

        assertEquals(a.gate.gateId, b.gate.gateId, "相同 Proposal 幂等：应返回同一 PENDING Gate")
        assertEquals(1L, b.proposal.proposalRevision)
        assertEquals(1L, gateCount(ctx.handle.driver, "foundation-n1"), "不应重复创建 Gate")
        closeDriver(ctx)
    }

    /* C+D. ACCEPT → APPROVED → StoryFoundation persisted */
    @Test
    fun `cd accept approves gate and persists confirmed story foundation`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        val c = ctx.app.foundationDecisions.confirmFoundation(p.gate.gateId, NovelId("n1"))

        assertEquals(HumanGateStatus.RESOLVED, c.gate.status)
        assertEquals(HumanDecision.APPROVED, c.gate.decision)
        assertTrue(c.canEnterPlanning, "confirm 成功应留下可进入 PLANNING 的结果")
        val f = ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1"))!!
        assertEquals(VariantScope.ORIGINAL, f.scope)
        assertEquals(listOf<GenreId>(GenreId("romance")), f.genre)
        assertEquals("成长", f.direction.theme)
        assertEquals(NarrativeProfile(pov = "第三人称", readerTone = "热血"), f.audience)
        assertEquals(WritingPolicy(listOf("不OOC", "章节紧凑")), f.policy)
        closeDriver(ctx)
    }

    /* E. 重复 ACCEPT → NO-OP */
    @Test
    fun `e duplicate accept is no-op`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        ctx.app.foundationDecisions.confirmFoundation(p.gate.gateId, NovelId("n1"))
        val again = ctx.app.foundationDecisions.confirmFoundation(p.gate.gateId, NovelId("n1"))

        assertEquals(HumanDecision.APPROVED, again.gate.decision)
        val f = ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1"))!!
        assertEquals(listOf<GenreId>(GenreId("romance")), f.genre, "重复 ACCEPT 不应改写 Confirmed Foundation")
        closeDriver(ctx)
    }

    /* F+G. REJECT 幂等 */
    @Test
    fun `fg reject is a normal decision and idempotent`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        ctx.app.foundationDecisions.rejectProposal(p.gate.gateId)

        val g = ctx.app.workflowRepository.getGate(p.gate.gateId)!!
        assertEquals(HumanGateStatus.RESOLVED, g.status)
        assertEquals(HumanDecision.REJECTED, g.decision)
        assertNull(ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1")), "REJECT 不应写入 StoryFoundation")

        ctx.app.foundationDecisions.rejectProposal(p.gate.gateId) // 幂等：不再抛
        val g2 = ctx.app.workflowRepository.getGate(p.gate.gateId)!!
        assertEquals(HumanDecision.REJECTED, g2.decision, "重复 REJECT 决策保持不变")
        closeDriver(ctx)
    }

    /* H. REQUEST_REVISION */
    @Test
    fun `h request revision retains proposal and sets gate`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        ctx.app.foundationDecisions.requestFoundationRevision(p.gate.gateId)

        val g = ctx.app.workflowRepository.getGate(p.gate.gateId)!!
        assertEquals(HumanGateStatus.RESOLVED, g.status)
        assertEquals(HumanDecision.REQUEST_REVISION, g.decision)
        assertNotNull(ctx.app.foundationDecisions.restoreProposal(NovelId("n1")).proposal, "Proposal(v1) 保留")
        assertNull(ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1")))
        closeDriver(ctx)
    }

    /* I. MODIFY → 新 Proposal Revision + 新 PENDING Gate；旧 Gate 保留 */
    @Test
    fun `i modify creates new proposal revision and pending gate`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p1 = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        val p2 = ctx.app.foundationDecisions.modifyProposal(NovelId("n1"), proposalV2())

        assertEquals(2L, p2.proposal.proposalRevision, "MODIFY 应产生 Revision 2")
        assertTrue(p1.gate.gateId != p2.gate.gateId, "MODIFY 应创建新 Gate")
        assertEquals(HumanGateStatus.PENDING, p2.gate.status)
        assertEquals(HumanGateStatus.PENDING, ctx.app.workflowRepository.getGate(p1.gate.gateId)!!.status, "旧 Gate 保留（历史）")
        // 旧 Proposal(v1) 仍可恢复（最新为 v2，但 v1 Checkpoint 不删除）
        closeDriver(ctx)
    }

    /* J. 旧 Gate 不能批准新 Proposal */
    @Test
    fun `j old gate cannot approve new proposal`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p1 = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        ctx.app.foundationDecisions.modifyProposal(NovelId("n1"), proposalV2())

        assertFailsWith<ApplicationException> {
            ctx.app.foundationDecisions.confirmFoundation(p1.gate.gateId, NovelId("n1"))
        }
        assertNull(ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1")), "旧 Gate 不得批准新 Proposal")
        closeDriver(ctx)
    }

    /* K. 新 Gate 可以批准新 Proposal */
    @Test
    fun `k new gate can approve new proposal`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        val p2 = ctx.app.foundationDecisions.modifyProposal(NovelId("n1"), proposalV2())

        val c = ctx.app.foundationDecisions.confirmFoundation(p2.gate.gateId, NovelId("n1"))
        assertEquals(HumanDecision.APPROVED, c.gate.decision)
        val f = ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1"))!!
        assertEquals(listOf<GenreId>(GenreId("romance"), GenreId("fantasy_eastern")), f.genre, "确认的是新 Proposal v2 内容")
        assertEquals("改线", f.direction.theme)
        closeDriver(ctx)
    }

    /* L. 重启后 PENDING Gate 可恢复并继续确认 */
    @Test
    fun `l pending gate recovers across restart and can be confirmed`() {
        val file = Files.createTempFile("qianyan-f3-l", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var ctx = open(url, handles); seedNovel(ctx.app, "n1")
            val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
            val gateId = p.gate.gateId
            closeAll(handles)

            ctx = open(url, handles)
            val r = ctx.app.foundationDecisions.restoreProposal(NovelId("n1"))
            assertNotNull(r.gate, "重启后应能恢复 PENDING Gate")
            assertEquals(HumanGateStatus.PENDING, r.gate!!.status)

            val c = ctx.app.foundationDecisions.confirmFoundation(gateId, NovelId("n1"))
            assertEquals(HumanDecision.APPROVED, c.gate.decision)
            assertNotNull(ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1")))
        } finally {
            closeAll(handles); Files.deleteIfExists(file)
        }
    }

    /* M. Checkpoint 可恢复 Proposal */
    @Test
    fun `m checkpoint recovers proposal across restart`() {
        val file = Files.createTempFile("qianyan-f3-m", ".db").toAbsolutePath()
        val url = "jdbc:sqlite:$file"
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var ctx = open(url, handles); seedNovel(ctx.app, "n1")
            ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
            closeAll(handles)

            ctx = open(url, handles)
            val r = ctx.app.foundationDecisions.restoreProposal(NovelId("n1"))
            assertEquals(1L, r.proposalRevision)
            assertEquals(listOf<GenreId>(GenreId("romance")), r.proposal.genre)
            assertEquals(NarrativeProfile(pov = "第三人称", readerTone = "热血"), r.proposal.audience)
        } finally {
            closeAll(handles); Files.deleteIfExists(file)
        }
    }

    /* N. Confirmed Foundation 不被旧 Checkpoint 覆盖 */
    @Test
    fun `n confirmed foundation not overwritten by stale checkpoint`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        val c = ctx.app.foundationDecisions.confirmFoundation(p.gate.gateId, NovelId("n1"))

        // Confirm 后 Foundation 已落库且 Checkpoint 不被修改（仍为 Proposal v1）
        val r = ctx.app.foundationDecisions.restoreProposal(NovelId("n1"))
        assertEquals(1L, r.proposalRevision, "Confirm 不应改写 Checkpoint")
        val f = ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1"))!!
        assertEquals(c.foundation, f, "Confirmed Foundation 保持，不被旧 Checkpoint 内容覆盖")
        assertTrue(c.foundation.createdAt == f.createdAt)
        closeDriver(ctx)
    }

    /* O. Original Foundation 重复 Confirm 被拒绝 / NO-OP */
    @Test
    fun `o original foundation duplicate confirm rejected or noop`() {
        val ctx = open(); seedNovel(ctx.app, "n1")
        val p1 = ctx.app.foundationDecisions.prepareProposal(NovelId("n1"), proposalV1())
        ctx.app.foundationDecisions.confirmFoundation(p1.gate.gateId, NovelId("n1"))

        // 同 Gate 重复 Confirm → NO-OP（返回既有确认）
        val again = ctx.app.foundationDecisions.confirmFoundation(p1.gate.gateId, NovelId("n1"))
        assertEquals(HumanDecision.APPROVED, again.gate.decision)

        // 新 Proposal + 新 Gate 再 Confirm → 拒绝覆盖（SEALED）
        val p2 = ctx.app.foundationDecisions.modifyProposal(NovelId("n1"), proposalV2())
        assertFailsWith<ApplicationException> {
            ctx.app.foundationDecisions.confirmFoundation(p2.gate.gateId, NovelId("n1"))
        }
        val f = ctx.app.storyFoundationRepository.getStoryFoundation(NovelId("n1"))!!
        assertEquals(listOf<GenreId>(GenreId("romance")), f.genre, "Original Foundation 不能被再次覆盖")
        closeDriver(ctx)
    }

    private fun closeDriver(ctx: Ctx) {
        (ctx.handle.driver as JdbcSqliteDriver?)?.getConnection()?.close()
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }
}