package com.qianyan.application.usecase.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-8-1A · Phase 8 **Partial** E2E：锁住 1–13 + Commit 触发的 World Model Update。
 *
 * 明确不是 Phase 8 验收：本轮不证明 3000 字、CharacterState 进入 prompt、
 * 第二次 NovelAgent、Proposal 回流、O-32 Canonical 基线。
 */
class Phase8PartialLoopE2ETest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")
    private val seedContent = "旧稿（Canonical）。"
    private val writerContent = "新章：主角抵达北境，风雪未停。"
    private val factExcerpt = "主角抵达北境"
    private val userIntent = "继续写这一章 3000 字，保持人物状态和前文一致。"

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fixture(records: MutableList<ProviderRequest>): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, scriptedGateway(records)), handle)
    }

    private fun scriptedGateway(records: MutableList<ProviderRequest>): MockLLMGateway = MockLLMGateway { request ->
        records += request
        val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
        val allText = request.messages.joinToString("\n") { it.content }
        val body = when {
            "StoryPlannerAgent" in system ->
                """{"chapterGoal":"推进北境","mainConflict":"风雪阻拦","expectedEvents":["主角抵达北境"]}"""

            "StoryWriterAgent" in system -> buildJsonObject { put("content", writerContent) }.toString()

            "WorldModelUpdateAgent" in system ->
                if (writerContent in allText) factsJson() else """{"facts":[]}"""

            else -> buildJsonObject { put("content", "占位") }.toString()
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun factsJson(): String =
        """{"facts":[{"factId":"f1","kind":"EVENT","operation":"ADD","name":"$factExcerpt","summary":"主角抵达北境","excerpt":"$factExcerpt","reason":"来自提交稿"}]}"""

    private fun seedCanonical(f: Fixture): Pair<NovelId, ChapterId> {
        val novelId = f.app.novels.createOriginal(title = "书A")
        val chapterId = f.app.chapters.createNextChapter("第一章", novelId).chapterId
        f.app.projects.selectChapter(novelId, chapterId)
        val draftId = DraftId("draft-seed-canonical")
        f.app.draftRepository.save(
            Draft(
                draftId = draftId,
                novelId = novelId,
                chapterId = chapterId,
                content = seedContent,
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.REVISED,
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
        // 正式 Canonical 路径：不是手写 CommitHistory。
        f.app.commits.commitAuthorSave(draftId, seedContent)
        return novelId to chapterId
    }

    private fun request(f: Fixture, novelId: NovelId, chapterId: ChapterId): NovelAgentRequest =
        NovelAgentRequest(
            projectId = f.app.projects.projectOf(novelId).projectId,
            userIntent = userIntent,
            activeChapterId = chapterId,
        )

    private fun promptFor(records: List<ProviderRequest>, agent: String): String =
        records.first { agent in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content }
            .messages.joinToString("\n") { it.content }

    private fun writingRequest(novelId: NovelId, chapterId: ChapterId): UserWritingRequest =
        UserWritingRequest(
            requestId = RequestId("req-second-context"),
            intentType = IntentType.CONTINUE,
            target = TargetRef(TargetKind.CHAPTER, TargetRefId(chapterId.value)),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId(novelId.value),
            variantId = null,
            scope = VariantScope.ORIGINAL,
            rawText = userIntent,
        )

    private fun capturePrompts(context: PlanningContext): List<ProviderRequest> {
        val records = mutableListOf<ProviderRequest>()
        val gateway = MockLLMGateway { request ->
            records += request
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> """{"chapterGoal":"目标","mainConflict":"冲突"}"""
                "StoryWriterAgent" in system -> buildJsonObject { put("content", "第二轮正文") }.toString()
                else -> buildJsonObject { put("content", "占位") }.toString()
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK)
        val writer = WriterAgent(gateway, ErrorMapper, ModelProfile.MOCK)
        val plan = planner.plan(context, emptyList()).copy(
            chapterId = context.request.target.id?.let { ChapterId(it.value) },
            novelId = context.novelId,
            variantId = context.variantId,
            scope = context.scope,
        )
        writer.write(context, plan)
        return records
    }

    @Test
    fun `partial loop reaches commit and triggers world model update but leaves rounds 15 to 17 unproven`() {
        val records = mutableListOf<ProviderRequest>()
        val f = fixture(records)
        try {
            val (novelId, chapterId) = seedCanonical(f)
            val request = request(f, novelId, chapterId)
            val result = f.app.novelAgent.run(request)

            // S2 / S3：原始 NL 入参 + 确定性意图
            assertEquals(userIntent, request.userIntent)
            assertEquals(IntentType.CONTINUE, result.intent.intentType)
            assertNotNull(result.intent.matchedKeyword)

            // S4：选中 writing；首次桥接必须成功，不能是 CANDIDATE_UNBRIDGED
            assertEquals("skill.writing", assertNotNull(result.skillId).value)
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertEquals(NovelAgentApprovalState.BRIDGED, result.approvalState)

            // S5：ContextPack 有 DRAFT item，且 T-8-A 的当前稿节进入两处实际 prompt
            val sessionId = assertNotNull(result.sessionId)
            val projectId = request.projectId
            val pack = assertNotNull(f.app.contextPackStore.find(sessionId, projectId)).pack
            assertNotNull(result.contextPackId)
            val draftItem = assertNotNull(pack.items.firstOrNull { it.source == com.qianyan.model.context.ContextSourceKind.DRAFT })
            assertEquals(seedContent, draftItem.content)
            val plannerPrompt = promptFor(records, "StoryPlannerAgent")
            val writerPrompt = promptFor(records, "StoryWriterAgent")
            assertTrue("【本章当前稿】" in plannerPrompt, "Planner 必须含当前稿节")
            assertTrue("【本章当前稿】" in writerPrompt, "Writer 必须含当前稿节")
            assertTrue(seedContent in plannerPrompt, "Planner 当前稿必须来自 pack")
            assertTrue(seedContent in writerPrompt, "Writer 当前稿必须来自 pack")

            // S6：工具调用留痕
            val toolCalls = result.activityIds.flatMap { f.app.toolCallLogs.listByActivity(it) }
            assertTrue(toolCalls.any { it.toolName.value == "get_latest_draft" }, "必须记录 get_latest_draft 调用")

            // S7：WorkingDraft
            val workingDraftId = assertNotNull(result.workingDraftId)

            // S9 / S10：Artifact + 真实 Diff
            val artifactId = assertNotNull(result.artifactId)
            val artifact = f.app.changes.get(artifactId)
            assertTrue(artifact.change.diff.hasChanges, "Artifact 必须有真实差异")
            assertEquals(workingDraftId, artifact.workingDraftId)

            // S11：正式 pending Gate
            val workflow = assertNotNull(f.app.workflowRepository.getWorkflowByActiveChapter(chapterId))
            val gateId = assertNotNull(workflow.pendingGateId)
            assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(gateId)?.status)

            // S12：正式批准 → CommitHistory +1
            val historyBefore = f.app.commits.history(projectId).size
            f.app.workflowFacade.approve(chapterId)
            val historyAfter = f.app.commits.history(projectId)
            assertEquals(historyBefore + 1, historyAfter.size)
            val mainHistory = assertNotNull(historyAfter.lastOrNull { it.artifactId == artifactId })

            // S13：CanonicalRead 返回与提交事实一致的新稿
            val committed = assertNotNull(f.app.canonicalRead.committedDraft(chapterId))
            assertEquals(mainHistory.resultingDraftId, committed.draftId)
            assertEquals(writerContent, committed.content)

            // S14：Commit 已触发 WM Update；用 replay 证明候选是提交时产生的，而不是本断言现造的
            val outcome = f.app.worldModelUpdate.onCommit(mainHistory)
            assertTrue(outcome.replay, "WM 候选必须已由 Commit 触发（replay=true）")
            val accepted = outcome.accepted
            assertTrue(
                accepted.isNotEmpty(),
                "脚本化 facts 必须产生 ACCEPTED 候选；实际=${outcome.candidates.map { "${it.factName}:${it.status}" }}",
            )
            assertNotNull(accepted.single().resultingFactId)
        } finally {
            f.close()
        }
    }

    @Test
    fun `after commit a fresh context prompt contains the world model event`() {
        val records = mutableListOf<ProviderRequest>()
        val f = fixture(records)
        try {
            val (novelId, chapterId) = seedCanonical(f)
            val firstRequest = request(f, novelId, chapterId)
            val first = f.app.novelAgent.run(firstRequest)
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, first.outcome)
            f.app.workflowFacade.approve(chapterId)

            // 同一 DB、第二轮 Context：Commit 写入的 Event 必须进入新的 PlanningContext prompt。
            val secondPack = f.app.contextEngine.build(
                ContextRequest(
                    projectId = firstRequest.projectId,
                    purpose = IntentType.CONTINUE,
                    focusChapterId = chapterId,
                ),
            )
            val secondContext = f.app.planningContextAssembly.assemble(writingRequest(novelId, chapterId), secondPack)
            val secondPrompts = capturePrompts(secondContext)
            listOf(promptFor(secondPrompts, "StoryPlannerAgent"), promptFor(secondPrompts, "StoryWriterAgent")).forEach { prompt ->
                assertTrue("【近期事件】" in prompt, "第二轮 prompt 必须含近期事件节")
                assertTrue("主角抵达北境: 主角抵达北境 [MAIN_PLOT/CONFIRMED]" in prompt, "第二轮 prompt 必须含上一轮 Commit 的 Event")
            }
        } finally {
            f.close()
        }
    }
}
