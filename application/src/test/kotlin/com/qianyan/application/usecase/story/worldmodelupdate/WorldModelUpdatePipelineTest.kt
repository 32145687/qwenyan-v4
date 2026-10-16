package com.qianyan.application.usecase.story.worldmodelupdate

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.usecase.commit.approvedGate
import com.qianyan.application.usecase.commit.artifactFor
import com.qianyan.application.usecase.draft.SeededWorld
import com.qianyan.application.usecase.draft.WorkingDraftFixture
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.application.usecase.draft.seedWorld
import com.qianyan.model.BaseNovelId
import com.qianyan.model.EventId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.commit.CommitHistoryId
import com.qianyan.model.commit.CommitId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.worldmodel.CandidateFact
import com.qianyan.model.worldmodel.CandidateFactStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteStoryStateRepository
import com.qianyan.storage.repository.SqliteWorldModelCandidateRepository
import com.qianyan.storage.repository.WorldModelCandidateRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3 · World Model Update Pipeline 端到端测试（v3.1 §12.10）。
 *
 * 全程走真实 UseCase / Repository / SQLite（内存库）+ 脚本化 Mock LLM，无网络。
 * 覆盖：Commit 触发 / 草稿阶段零变化（边界二）/ 无差异零写入 / 候选抽取 /
 * 伪造来源被拒且不污染 World Model / 非 ADD 被拒 / 冲突零静默覆盖 /
 * 幂等重放 / 失败不留半状态 / Variant 隔离 / Original 既有事实不被改写。
 */
class WorldModelUpdatePipelineTest {

    private var llmCalls = 0

    private val previousContent = "A 第二章正文"
    private val newContent = "A 第二章正文（提交稿）"

    /* ---------------- fixtures ---------------- */

    private fun pipe(factsJson: String): WorkingDraftFixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val gateway = MockLLMGateway { req ->
            llmCalls++
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

    /** 一次真实提交：CommitHistory 已持久化，返回其条目与提交后的正文。 */
    private class Committed(
        val f: WorkingDraftFixture,
        val w: SeededWorld,
        val historyId: String,
        val entry: com.qianyan.model.commit.CommitHistoryEntry,
    )

    private fun committed(factsJson: String): Committed {
        val f = pipe(factsJson)
        val w = seedWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, newContent)
        val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)
        return Committed(f, w, result.historyEntry.historyId.value, result.historyEntry)
    }

    private fun candidates(f: WorkingDraftFixture) = SqliteWorldModelCandidateRepository(f.handle.db)

    private fun storyState(f: WorkingDraftFixture) = SqliteStoryStateRepository(f.handle.db)

    private fun fact(
        id: String,
        name: String,
        summary: String,
        excerpt: String,
        kind: String = "EVENT",
        operation: String = "ADD",
    ): String = """{"factId":"$id","kind":"$kind","operation":"$operation","name":"$name",""" +
        """"summary":"$summary","excerpt":"$excerpt","reason":"正文出现该描述"}"""

    private fun factsJson(vararg facts: String): String = """{"facts":[${facts.joinToString(",")}]}"""

    /* ---------------- §12.10：Commit 触发 + 来源可追溯 ---------------- */

    @Test
    fun `commit triggers world model update and accepted fact traces back to the commit`() {
        val excerpt = "（提交稿）"
        val c = committed(factsJson(fact("f1", "主角抵达北境", "主角抵达北境并入城", excerpt)))
        val rows = candidates(c.f).listByHistory(c.historyId)

        assertEquals(1, rows.size, "Commit 之后必须产生候选行")
        val row = rows.single()
        assertEquals(CandidateFactStatus.ACCEPTED, row.status)
        assertEquals(c.historyId, row.historyId, "候选必须关联触发它的 Commit history")
        assertEquals(c.entry.commitId.value, row.commitId, "候选必须关联 Commit 身份")
        assertEquals(c.w.novelId, row.novelId)
        assertEquals(c.w.chapter2, row.chapterId)
        assertEquals(VariantScope.ORIGINAL, row.scope)
        assertEquals(excerpt, row.sourceExcerpt, "来源正文片段必须原样持久化")

        val factId = assertNotNull(row.resultingFactId, "ACCEPTED 候选必须回写落地事实 id")
        val event = storyState(c.f).getEventById(EventId(factId))
        assertNotNull(event, "落地事实必须真实写入六表（Event）")
        assertEquals("主角抵达北境", event.name)
        assertEquals("主角抵达北境并入城", event.what, "候选内容必须写入 v27 what 列")
        assertEquals(c.w.novelId, event.novelId)
        assertEquals(c.w.chapter2, event.chapterId)
        assertEquals(VariantScope.ORIGINAL, event.scope)
        assertNull(event.variantId)
        assertEquals(FactLevel.GENERATED, event.factLevel, "提取生成的事实必须标记 factLevel=GENERATED")
        assertEquals(1, llmCalls, "一次 Commit 只抽取一次")
    }

    @Test
    fun `draft and change preparation without commit leave world model untouched`() {
        val f = pipe(factsJson(fact("f1", "未提交的事实", "不该落地", "（提交稿）")))
        val w = seedWorld(f)
        approvedGate(f, w)
        // 走到 Change Layer 为止：Working Draft + Artifact 已产出，但**没有 Commit**。
        f.app.changes.prepare(
            f.app.workingDrafts.create(
                projectId = w.projectId,
                target = WorkingDraftTarget(w.novelId, w.chapter2),
                content = newContent,
                sessionId = w.sessionId,
                activityId = w.activityId,
                baseDraftId = w.canonicalDraftId,
            ).workingDraftId,
        )

        assertEquals(0L, countRows(f.handle, "CandidateFact"), "未提交的草稿变化不得产生候选")
        assertEquals(0L, countRows(f.handle, "Event"), "未提交的草稿变化不得写入 World Model")
        assertEquals(0, llmCalls, "未 Commit 不得调用抽取 Agent（边界二）")
    }

    @Test
    fun `commit without content difference produces no candidate and no llm call`() {
        val c = committed(factsJson(fact("f1", "主角抵达北境", "主角抵达北境", "（提交稿）")))
        val callsBefore = llmCalls
        val noDiff = c.entry.copy(
            historyId = CommitHistoryId("h-no-diff"),
            commitId = CommitId("c-no-diff"),
            previousContent = c.entry.resultingContent, // 正文无差异
        )

        val outcome = c.f.app.worldModelUpdate.onCommit(noDiff)

        assertFalse(outcome.replay)
        assertTrue(outcome.candidates.isEmpty(), "无差异提交不得产生候选")
        assertEquals(candidates(c.f).listByHistory("h-no-diff").size, 0)
        assertEquals(callsBefore, llmCalls, "无差异不得调用 LLM（不做无意义更新）")
    }

    /* ---------------- §12.10：校验拒绝 / 冲突 / 阶段边界 ---------------- */

    @Test
    fun `forged source excerpt is rejected and never enters world model`() {
        val forged = "这句话从未出现在正文里"
        val c = committed(
            factsJson(
                fact("f1", "主角抵达北境", "真实来源", "（提交稿）"),
                fact("f2", "伪造来源的事实", "LLM 臆造", forged),
            ),
        )
        val rows = candidates(c.f).listByHistory(c.historyId)
        val accepted = rows.filter { it.status == CandidateFactStatus.ACCEPTED }
        val rejected = rows.filter { it.status == CandidateFactStatus.REJECTED }

        assertEquals(1, accepted.size)
        assertEquals("主角抵达北境", accepted.single().factName)
        assertEquals(1, rejected.size, "伪造来源候选必须被确定性拒绝")
        assertEquals("伪造来源的事实", rejected.single().factName)
        assertTrue(
            rejected.single().decisionReason?.contains("逐字") == true,
            "拒绝理由必须说明来源片段规则，实际=${rejected.single().decisionReason}",
        )
        assertNull(rejected.single().resultingFactId, "被拒候选不得落地任何事实 id")

        val events = storyState(c.f).listEvents(c.w.novelId, null)
        assertEquals(listOf("主角抵达北境"), events.map { it.name }, "被拒事实不得污染 World Model")
    }

    @Test
    fun `non add operation is rejected at validation stage without writing`() {
        val c = committed(factsJson(fact("f1", "主角离开北境", "改写既有事实", "（提交稿）", operation = "UPDATE")))
        val row = candidates(c.f).listByHistory(c.historyId).single()

        assertEquals(CandidateFactStatus.REJECTED, row.status, "UPDATE 落地语义 §12 未定义（超出当前实现范围）")
        assertTrue(row.decisionReason!!.contains("operation"), "拒绝理由必须指出被拒规则")
        assertNull(row.resultingFactId)
        assertEquals(0L, countRows(c.f.handle, "Event"), "被拒候选不得写入六表")
    }

    @Test
    fun `failed commit produces no world model update at all`() {
        // A 类语义：小说 Commit 本身失败 ⇒ 不得产生任何知识更新记录（不伪造成功提交的更新）。
        val f = pipe(factsJson(fact("f1", "主角抵达北境", "第一次落地", "（提交稿）")))
        val w = seedWorld(f)
        val stale = artifactFor(f, w, newContent) // 基于提交前 Canonical 的 Artifact
        f.app.commits.commit(w.projectId, stale.artifactId, approvedGate(f, w))
        val historyBefore = f.app.commits.history(w.projectId).size
        val candidatesBefore = countRows(f.handle, "CandidateFact")
        val eventsBefore = countRows(f.handle, "Event")

        // 同一 Artifact 再次提交：Canonical 已前进 ⇒ STALE_ARTIFACT，Commit 必须失败。
        val second = runCatching { f.app.commits.commit(w.projectId, stale.artifactId, approvedGate(f, w, gateId = "gate-stale")) }

        assertTrue(second.isFailure, "过期 Artifact 的提交必须失败")
        assertEquals(historyBefore, f.app.commits.history(w.projectId).size, "失败的提交不得写历史")
        assertEquals(candidatesBefore, countRows(f.handle, "CandidateFact"), "失败的提交不得产生候选")
        assertEquals(eventsBefore, countRows(f.handle, "Event"), "失败的提交不得写入任何事实")
    }

    @Test
    fun `event persistence failure rolls back candidates and never fakes accepted`() {
        // D 类语义：不得出现"标记 ACCEPTED 但事实未写入"的假成功。
        val f = pipe(factsJson(fact("f1", "主角抵达北境", "落地", "（提交稿）")))
        val w = seedWorld(f)
        val entry = f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, newContent).artifactId,
            approvedGate(f, w),
        ).historyEntry
        val eventsBefore = countRows(f.handle, "Event")
        // 幽灵 novel：Event.novel_id 的 FK 使其写入失败（PRAGMA foreign_keys = ON）。
        val ghost = entry.copy(
            historyId = CommitHistoryId("h-ghost"),
            commitId = CommitId("c-ghost"),
            target = WorkingDraftTarget(NovelId("ghost-novel"), w.chapter2),
        )

        val outcome = runCatching { f.app.worldModelUpdate.onCommit(ghost) }

        assertTrue(outcome.isFailure, "真实持久化故障必须上抛，不得被吞掉（实际: ${outcome.exceptionOrNull()}）")
        assertTrue(
            candidates(f).listByHistory("h-ghost").isEmpty(),
            "事实写入失败时候选必须整体回滚，不留半状态",
        )
        assertEquals(eventsBefore, countRows(f.handle, "Event"), "不得写入任何事实行")
    }

    @Test
    fun `conflict with existing fact becomes conflict and never overwrites it`() {
        // 同名候选：第一次 Commit 落地为既有事实，第二次真实 Commit 再提出同名候选 ⇒ 必须判冲突。
        val f = pipe(factsJson(fact("f1", "主角抵达北境", "第一次提交的描述", "正文")))
        val w = seedWorld(f)
        val first = f.app.commits.commit(w.projectId, artifactFor(f, w, newContent).artifactId, approvedGate(f, w))
        val existingId = candidates(f).listByHistory(first.historyEntry.historyId.value)
            .single().resultingFactId
        assertNotNull(existingId, "前置：第一次提交应落地事实")

        val secondContent = "A 第二章正文（二次提交稿）"
        val second = f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, secondContent, baseDraftId = first.resultingDraftId).artifactId,
            approvedGate(f, w, gateId = "gate-worldmodel-second"),
        )
        val rows = candidates(f).listByHistory(second.historyEntry.historyId.value)

        assertEquals(1, rows.count { it.status == CandidateFactStatus.CONFLICT }, "同名候选必须判定为冲突")
        val conflict = rows.single { it.status == CandidateFactStatus.CONFLICT }
        assertNotNull(conflict.conflictWith, "冲突候选必须指向既有事实 id")
        assertEquals(existingId, conflict.conflictWith)
        assertNull(conflict.resultingFactId, "冲突候选不得落地新事实")
        assertTrue(conflict.decisionReason!!.contains("不得覆盖"), "拒绝理由必须写明不得覆盖")

        val events = storyState(f).listEvents(w.novelId, null)
        assertEquals(1, events.size, "冲突不得新增事实行")
        assertEquals("第一次提交的描述", events.single().what, "既有事实内容必须原样保留（零静默覆盖）")
    }

    /* ---------------- §12.10：幂等 / 事务 / Variant 隔离 ---------------- */

    @Test
    fun `replaying the same commit is idempotent`() {
        val c = committed(factsJson(fact("f1", "主角抵达北境", "主角抵达北境", "（提交稿）")))
        val callsBefore = llmCalls

        val outcome = c.f.app.worldModelUpdate.onCommit(c.entry)

        assertTrue(outcome.replay, "同一 historyId 重放必须识别为幂等")
        assertEquals(1, outcome.candidates.size, "重放不得追加候选")
        assertEquals(1L, countRows(c.f.handle, "Event"), "重放不得重复写入事实")
        assertEquals(callsBefore, llmCalls, "重放不得再次抽取")
    }

    @Test
    fun `commit survives extraction failure and records it instead of failing the commit`() {
        // 网关对 WorldModelUpdateAgent 返回不可解析文本：挂载点必须"记录失败"，不得把已成功的提交报告为失败。
        val f = pipe("这不是 JSON")
        val w = seedWorld(f)
        val result = f.app.commits.commit(
            w.projectId,
            artifactFor(f, w, newContent).artifactId,
            approvedGate(f, w),
        )

        val rows = candidates(f).listByHistory(result.historyEntry.historyId.value)
        assertEquals(1, rows.size, "旁路失败必须留下可追溯记录（不静默）")
        val row = rows.single()
        assertEquals(CandidateFactStatus.REJECTED, row.status)
        assertTrue(row.decisionReason!!.contains("World Model Update 流程失败"), row.decisionReason)
        assertEquals("(非候选事实：抽取流程失败标记)", row.factName, "失败记录必须在数据层显式声明非候选事实")
        assertNull(row.resultingFactId, "失败记录不得指向任何落地事实")
        assertEquals(result.historyEntry.commitId.value, row.commitId, "失败记录同样必须指向来源 Commit")
        assertEquals(0L, countRows(f.handle, "Event"), "失败不得写入任何 World Model 事实")
        assertEquals(1, f.app.commits.history(w.projectId).size, "Commit 本身必须已成功持久化")
    }

    @Test
    fun `author manual save path also triggers the pipeline`() {
        // 四条提交路径之一：作者手改保存（same-row 载体，不经 Artifact / Gate）同样是 Commit 事件。
        val f = pipe(factsJson(fact("f1", "主角抵达北境", "作者手改后落地", "北境")))
        val w = seedWorld(f)
        val entry = f.app.commits.commitAuthorSave(w.canonicalDraftId, "A 第二章正文：主角抵达北境")

        val rows = candidates(f).listByHistory(entry.historyId.value)
        assertEquals(1, rows.size, "手改保存必须同样触发流水线")
        assertEquals(CandidateFactStatus.ACCEPTED, rows.single().status)
        assertEquals(entry.commitId.value, rows.single().commitId)
        assertNotNull(storyState(f).getEventById(EventId(rows.single().resultingFactId!!)))
    }

    @Test
    fun `pipeline failure leaves no half state while commit stays durable`() {
        val c = committed(factsJson(fact("f1", "占位", "占位", "（提交稿）")))
        val delegate = SqliteWorldModelCandidateRepository(c.f.handle.db)
        val flaky = FlakyCandidateRepository(delegate, failOnSave = 2)
        val pipeline = WorldModelUpdateUseCases(
            agent = WorldModelUpdateAgent(
                FixedGateway(
                    factsJson(
                        fact("f1", "主角抵达北境", "第一条", "（提交稿）"),
                        fact("f2", "主角进入王城", "第二条", "正文"),
                    ),
                ),
            ),
            candidates = flaky,
            storyState = storyState(c.f),
            errorMapper = c.f.app.errorMapper,
        )

        val candidatesBefore = countRows(c.f.handle, "CandidateFact")
        val eventsBefore = countRows(c.f.handle, "Event")
        // 全新 historyId（避开幂等重放分支），两条候选均合法 ⇒ 第二条 save 抛错。
        val flakyEntry = c.entry.copy(
            historyId = CommitHistoryId("h-flaky"),
            commitId = CommitId("c-flaky"),
        )

        assertFailsWith<IllegalStateException> { pipeline.onCommit(flakyEntry) }

        assertEquals(candidatesBefore, countRows(c.f.handle, "CandidateFact"), "失败必须整体回滚，不留候选半状态")
        assertEquals(eventsBefore, countRows(c.f.handle, "Event"), "失败必须整体回滚，不留事实半状态")
        assertTrue(
            c.f.app.commits.history(c.entry.projectId).any { it.historyId.value == c.historyId },
            "Pipeline 失败不得回滚已持久化的 Commit",
        )
    }

    @Test
    fun `variant scoped update is isolated from original facts and keeps both scopes`() {
        val c = committed(factsJson(fact("f1", "主角抵达北境", "Original 既有事实", "（提交稿）")))
        val plannedVariantId = com.qianyan.model.VariantId("var-worldmodel")
        val variantId = c.f.app.novels.createVariant(
            context = VariantContext(baseNovelId = BaseNovelId(c.w.novelId.value), variantId = plannedVariantId),
            name = "改线",
            variantId = plannedVariantId,
        )
        val variantEntry = c.entry.copy(
            historyId = CommitHistoryId("h-variant"),
            commitId = CommitId("c-variant"),
            target = WorkingDraftTarget(c.w.novelId, c.w.chapter2, variantId),
        )

        val outcome = c.f.app.worldModelUpdate.onCommit(variantEntry)

        assertEquals(
            1,
            outcome.accepted.size,
            "Variant 作用域不得与 Original 同名事实判为冲突（隔离语义按 novel + variant 判定）",
        )
        val variantEvent = storyState(c.f).getEventById(EventId(outcome.accepted.single().resultingFactId!!))
        assertNotNull(variantEvent)
        assertEquals(variantId, variantEvent.variantId)
        assertEquals(VariantScope.VARIANT, variantEvent.scope)

        val originalEvents = storyState(c.f).listEvents(c.w.novelId, null)
        assertEquals(listOf("Original 既有事实"), originalEvents.map { it.what }, "Original 事实不得被 Variant 写入影响")
        val variantEvents = storyState(c.f).listEvents(c.w.novelId, variantId)
        assertEquals(1, variantEvents.size, "Variant 读取只见 Variant 事实")
    }

    /* ---------------- helpers ---------------- */

    /** 第 [failOnSave] 次 save 抛错的候选仓储装饰器（真实事务回滚验证用）。 */
    private class FlakyCandidateRepository(
        private val delegate: WorldModelCandidateRepository,
        private val failOnSave: Int,
    ) : WorldModelCandidateRepository {
        private var saves = 0

        override fun save(candidate: CandidateFact) {
            saves += 1
            if (saves == failOnSave) throw IllegalStateException("injected candidate save failure")
            delegate.save(candidate)
        }

        override fun getById(candidateId: String) = delegate.getById(candidateId)

        override fun listByHistory(historyId: String) = delegate.listByHistory(historyId)

        override fun listByStatus(status: CandidateFactStatus) = delegate.listByStatus(status)

        override fun updateStatus(
            candidateId: String,
            status: CandidateFactStatus,
            decisionReason: String?,
            conflictWith: String?,
            resultingFactId: String?,
        ) = delegate.updateStatus(candidateId, status, decisionReason, conflictWith, resultingFactId)
    }

    /** 只为 [WorldModelUpdateAgent] 返回固定候选 JSON 的网关（其余 Agent 返回空批次）。 */
    private class FixedGateway(
        private val factsJson: String,
    ) : com.qianyan.provider.LLMGateway {
        override fun chat(request: com.qianyan.provider.ProviderRequest) = ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", factsJson) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }
}
