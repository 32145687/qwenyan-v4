package com.qianyan.application.usecase.story.worldmodelupdate

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.commit.approvedGate
import com.qianyan.application.usecase.commit.artifactFor
import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.application.usecase.draft.seedWorld
import com.qianyan.model.BaseNovelId
import com.qianyan.model.EventId
import com.qianyan.model.VariantId
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.commit.CommitId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.worldmodel.CandidateFactStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteStoryStateRepository
import com.qianyan.storage.repository.SqliteWorldModelCandidateRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-O06 · World Model 冲突 → 独立人工门（设计验收 1–10 的应用层部分）。
 *
 * 全链路真实：SQLite 内存库 + 真实 Pipeline / Commit / Gate 仓储 + 脚本化 Mock LLM。
 * 门键规则：`wm-conflict:<candidateId>`；三外键 NULL；不参与 workflow.pendingGateId。
 */
class WorldModelConflictGateTest {

    private fun pipe(factsJson: String): WorkingDraftFixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val gateway = MockLLMGateway { req ->
            val agent = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = if ("WorldModelUpdateAgent" in agent) factsJson else """{"facts":[]}"""
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        return WorkingDraftFixture(ApplicationContainer.fromDriver(handle.driver, gateway), handle)
    }

    private fun fact(name: String, summary: String, excerpt: String = "提交稿"): String =
        """{"factId":"f1","kind":"EVENT","operation":"ADD","name":"$name",""" +
            """"summary":"$summary","excerpt":"$excerpt","reason":"正文出现该描述"}"""

    private fun factsJson(vararg facts: String): String = """{"facts":[${facts.joinToString(",")}]}"""

    private fun candidates(f: WorkingDraftFixture) = SqliteWorldModelCandidateRepository(f.handle.db)
    private fun storyState(f: WorkingDraftFixture) = SqliteStoryStateRepository(f.handle.db)

    /** 第一次提交：同名事实落地（ACCEPTED）。 */
    private fun seedAccepted(name: String = "主角抵达北境"): Pair<WorkingDraftFixture, SeededWorld> {
        val f = pipe(factsJson(fact(name, "第一次提交的描述")))
        val w = seedWorld(f)
        f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, "A 第二章正文（提交稿）").artifactId,
            approvedGate(f, w),
        )
        return f to w
    }

    /** 第二次提交（同名）⇒ CONFLICT + 独立门。 */
    private fun seedConflict(
        name: String = "主角抵达北境",
    ): Triple<WorkingDraftFixture, SeededWorld, com.qianyan.model.worldmodel.CandidateFact> {
        val (f, w) = seedAccepted(name)
        val firstRows = candidates(f).listByStatus(CandidateFactStatus.ACCEPTED)
        val firstDraft = f.app.draftRepository.latestByChapter(w.chapter2)!!.draftId
        f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, "A 第二章正文（二次提交稿）", baseDraftId = firstDraft).artifactId,
            approvedGate(f, w, gateId = "gate-wm-second"),
        )
        assertTrue(firstRows.isNotEmpty(), "前置：第一次提交应落地事实")
        val conflict = candidates(f).listByStatus(CandidateFactStatus.CONFLICT).single()
        return Triple(f, w, conflict)
    }

    /* 1. 冲突产生 → 1 CONFLICT + 1 PENDING 独立门（三外键 NULL、gate_key 规则） */
    @Test
    fun `conflict opens exactly one pending independent gate`() {
        val (f, w, conflict) = seedConflict()

        assertEquals(CandidateFactStatus.CONFLICT, conflict.status)
        val gate = assertNotNull(f.app.workflowRepository.getGateByKey(WorldModelConflictUseCases.gateKey(conflict.candidateId)))
        assertEquals(HumanGateStatus.PENDING, gate.status)
        assertEquals(HumanDecision.PENDING, gate.decision)
        assertNull(gate.workflowId, "独立门不得绑定 Workflow")
        assertNull(gate.stepId)
        assertNull(gate.draftId)
        assertEquals("wm-conflict:${conflict.candidateId}", gate.gateKey)
        assertEquals(1L, conflictGateCount(f), "一条冲突只开一个门")
        assertEquals(1, f.app.worldModelConflicts.listForNovel(w.novelId).size)
    }

    /* 2. 同 historyId 重放 → 候选/门数不变 */
    @Test
    fun `replaying the same commit does not duplicate candidates or gates`() {
        val (f, w, conflict) = seedConflict()
        val entry = f.app.commits.history(w.projectId).first()
        val candidatesBefore = countRows(f.handle, "CandidateFact")
        val gatesBefore = conflictGateCount(f)

        val outcome = f.app.worldModelUpdate.onCommit(entry)

        assertTrue(outcome.replay, "同 historyId 必须判为幂等重放")
        assertEquals(candidatesBefore, countRows(f.handle, "CandidateFact"))
        assertEquals(gatesBefore, conflictGateCount(f))
        assertNotNull(f.app.workflowRepository.getGateByKey(WorldModelConflictUseCases.gateKey(conflict.candidateId)))
    }

    /* 3. 采纳 → 新 Event(USER_CONFIRMED) + 候选 ACCEPTED + 门 APPROVED；既有 Event 逐字段不变 */
    @Test
    fun `approve inserts a confirmed event and keeps the existing one untouched`() {
        val (f, w, conflict) = seedConflict()
        val existingBefore = storyState(f).listEvents(w.novelId, null).single()

        val result = f.app.worldModelConflicts.resolve(conflict.candidateId, approve = true)

        val created = assertNotNull(result.createdEventId)
        val event = assertNotNull(storyState(f).getEventById(EventId(created)))
        assertEquals(conflict.factName, event.name)
        assertEquals(FactLevel.USER_CONFIRMED, event.factLevel)
        assertEquals(w.novelId, event.novelId)
        assertNull(event.variantId)
        assertEquals(w.chapter2, event.chapterId)

        val accepted = assertNotNull(candidates(f).getById(conflict.candidateId))
        assertEquals(CandidateFactStatus.ACCEPTED, accepted.status)
        assertEquals(created, accepted.resultingFactId)
        assertEquals(conflict.conflictWith, accepted.conflictWith)
        assertTrue(accepted.decisionReason!!.contains("同名并存"))

        val gate = assertNotNull(f.app.workflowRepository.getGate(gateOf(f, conflict.candidateId)!!.gateId))
        assertEquals(HumanGateStatus.RESOLVED, gate.status)
        assertEquals(HumanDecision.APPROVED, gate.decision)
        assertEquals("user", gate.resolvedBy)

        val events = storyState(f).listEvents(w.novelId, null)
        assertEquals(2, events.size, "采纳 = 新增一条独立事实（同名并存）")
        val existingAfter = events.single { it.id == existingBefore.id }
        assertEquals(existingBefore, existingAfter, "既有事实行必须逐字段不变（零覆盖）")
    }

    /* 4. 拒绝 → 无新 Event + REJECTED + 门 REJECTED */
    @Test
    fun `reject closes the gate without writing any fact`() {
        val (f, w, conflict) = seedConflict()
        val eventsBefore = countRows(f.handle, "Event")

        val result = f.app.worldModelConflicts.resolve(conflict.candidateId, approve = false)

        assertNull(result.createdEventId)
        assertEquals(eventsBefore, countRows(f.handle, "Event"), "拒绝不得写任何事实")
        val rejected = assertNotNull(candidates(f).getById(conflict.candidateId))
        assertEquals(CandidateFactStatus.REJECTED, rejected.status)
        assertTrue(rejected.decisionReason!!.contains("人审拒绝"))
        assertNull(rejected.resultingFactId)
        val gate = gateOf(f, conflict.candidateId)!!
        assertEquals(HumanDecision.REJECTED, gate.decision)
        assertEquals(HumanGateStatus.RESOLVED, gate.status)
    }

    /* 5. 重复采纳 / 双击 → 仍 1 条新事实、1 个门 */
    @Test
    fun `approving twice is idempotent`() {
        val (f, w, conflict) = seedConflict()
        f.app.worldModelConflicts.resolve(conflict.candidateId, approve = true)
        val eventsAfterFirst = countRows(f.handle, "Event")

        val second = f.app.worldModelConflicts.resolve(conflict.candidateId, approve = true)

        assertTrue(second.alreadyResolved)
        assertNull(second.createdEventId)
        assertEquals(eventsAfterFirst, countRows(f.handle, "Event"), "重复采纳不得再写事实")
        assertEquals(1L, conflictGateCount(f), "不得新建第 2 个门")
    }

    /* 6. 先拒后批 → InvalidOperation、无写入 */
    @Test
    fun `approving after reject is a typed invalid operation`() {
        val (f, _, conflict) = seedConflict()
        f.app.worldModelConflicts.resolve(conflict.candidateId, approve = false)
        val eventsBefore = countRows(f.handle, "Event")

        val ex = assertFailsWith<ApplicationException> {
            f.app.worldModelConflicts.resolve(conflict.candidateId, approve = true)
        }
        assertTrue(ex.message!!.contains("不能反向决议"))
        assertEquals(eventsBefore, countRows(f.handle, "Event"))
        assertEquals(CandidateFactStatus.REJECTED, candidates(f).getById(conflict.candidateId)!!.status)
    }

    /* 7. saveEvent 抛错 → 三者全回滚（候选仍 CONFLICT、门仍 PENDING、无事实） */
    @Test
    fun `fact write failure rolls back candidate and gate`() {
        val (f, _, conflict) = seedConflict()
        // 故障注入（仅本测试的内存库）：移除 Event 表让 saveEvent 必失败。
        f.handle.driver.execute(null, "DROP TABLE Event", 0)

        assertFailsWith<Throwable> {
            f.app.worldModelConflicts.resolve(conflict.candidateId, approve = true)
        }

        assertEquals(CandidateFactStatus.CONFLICT, candidates(f).getById(conflict.candidateId)!!.status, "候选必须回滚为 CONFLICT")
        val gate = gateOf(f, conflict.candidateId)!!
        assertEquals(HumanGateStatus.PENDING, gate.status, "门必须回滚为 PENDING")
        assertEquals(HumanDecision.PENDING, gate.decision)
    }

    /* 8. Variant 冲突 → 落在该 Variant；Original 不受影响 */
    @Test
    fun `variant conflict resolves into the variant scope only`() {
        val (f, w) = seedAccepted() // Original 事实
        val variantId = VariantId("var-wm-conflict")
        f.app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(w.novelId.value), variantId = variantId),
            name = "改线",
            variantId = variantId,
        )
        val variantCommit = { history: String, commit: String ->
            f.app.commits.history(w.projectId).first().copy(
                historyId = CommitHistoryId(history),
                commitId = CommitId(commit),
                target = WorkingDraftTarget(w.novelId, w.chapter2, variantId),
            )
        }
        // 第一次 Variant 提交：Variant 内落地（与 Original 隔离）
        f.app.worldModelUpdate.onCommit(variantCommit("h-var-1", "c-var-1"))
        // 第二次 Variant 提交：Variant 内同名 ⇒ 冲突
        f.app.worldModelUpdate.onCommit(variantCommit("h-var-2", "c-var-2"))
        val variantConflict = candidates(f).listByStatus(CandidateFactStatus.CONFLICT).single()
        assertEquals(variantId, variantConflict.variantId)

        val originalBefore = storyState(f).listEvents(w.novelId, null)

        val result = f.app.worldModelConflicts.resolve(variantConflict.candidateId, approve = true)

        val created = assertNotNull(result.createdEventId)
        val variantEvent = assertNotNull(storyState(f).getEventById(EventId(created)))
        assertEquals(variantId, variantEvent.variantId)
        assertEquals(com.qianyan.model.VariantScope.VARIANT, variantEvent.scope)
        assertEquals(originalBefore, storyState(f).listEvents(w.novelId, null), "Original 事实不得变化")
        assertEquals(2, storyState(f).listEvents(w.novelId, variantId).size, "Variant 内新增一条同名事实")
    }

    /* 9. Original 采纳 → 插入成功；对既有 Original 行 UPDATE/DELETE 仍被触发器 ABORT */
    @Test
    fun `original immutability triggers still reject update and delete`() {
        val (f, w, conflict) = seedConflict()
        val existing = storyState(f).listEvents(w.novelId, null).single()
        f.app.worldModelConflicts.resolve(conflict.candidateId, approve = true)

        assertFailsWith<Throwable> {
            f.handle.driver.execute(null, "UPDATE Event SET name = 'x' WHERE event_id = '${existing.id.value}'", 0)
        }
        assertFailsWith<Throwable> {
            f.handle.driver.execute(null, "DELETE FROM Event WHERE event_id = '${existing.id.value}'", 0)
        }
        assertEquals(existing, storyState(f).getEventById(existing.id), "触发器拒绝后原行必须保持原样")
    }

    private fun conflictGateCount(f: WorkingDraftFixture): Long =
        f.handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM WorkflowHumanGate WHERE gate_key LIKE 'wm-conflict:%'",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
            0,
        ).value

    private fun gateOf(f: WorkingDraftFixture, candidateId: String) =
        f.app.workflowRepository.getGateByKey(WorldModelConflictUseCases.gateKey(candidateId))
}
