package com.qianyan.application.usecase.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.agent.agents.CoreSkills
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceOrigin
import com.qianyan.model.author.PreferenceScope
import com.qianyan.model.character.Character
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.worldmodel.CandidateFactStatus
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-8-1 · Phase 8 十七段完整纵切 E2E（S1→S17 顺序断言）。
 *
 * 真实 ApplicationContainer + 内存 SQLite + scripted Mock LLM：
 * Planner / Writer / WorldModelUpdate / CharacterConsistency 四类响应分别脚本化。
 *
 * 本测试**只证明**同章 Canonical 之上的一次 Continue 链路可走通到只读第二轮；
 * 不证明 3000 字、真实 LLM 语义、GUI、进程重启持久化、跨章续写（O-42）。
 */
class Phase8FullLoopE2ETest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")
    private val seedContent = "旧稿（Canonical）：主角在北境边缘。"
    private val writerContent = "新章：主角抵达北境，风雪未停。"
    private val factExcerpt = "主角抵达北境"
    private val userIntent = "继续写这一章 3000 字，保持人物状态和前文一致。"
    private val preferenceStatement = "对话克制，避免煽情"

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private data class Seed(
        val novelId: NovelId,
        val chapterId: ChapterId,
        val seedDraftId: DraftId,
        val historyBaseline: Int,
    )

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

            "StoryCharacterConsistencyAgent" in system ->
                """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[{"characterId":"c1","name":"林岚"}],"missingInputs":[],"summary":"pass"}"""

            else -> buildJsonObject { put("content", "占位") }.toString()
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(10, 10, 20),
            finishReason = FinishReason.STOP,
        )
    }

    private fun factsJson(): String =
        """{"facts":[{"factId":"f1","kind":"EVENT","operation":"ADD","name":"$factExcerpt","summary":"$factExcerpt","excerpt":"$factExcerpt","reason":"来自提交稿"}]}"""

    private fun seedCanonical(f: Fixture): Seed {
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
        return Seed(
            novelId = novelId,
            chapterId = chapterId,
            seedDraftId = draftId,
            historyBaseline = f.app.projects.projectOf(novelId).let { f.app.commits.history(it.projectId).size },
        )
    }

    /** S1：稳定且激活的作者偏好，经 AuthorContext 投影进入 Planner / Writer prompt。 */
    private fun seedAuthorPreference(f: Fixture) {
        f.app.authorPreferenceRepository.upsertAuthorPreference(
            AuthorPreference(
                preferenceId = AuthorPreferenceId("pref-tone-1"),
                scope = PreferenceScope.GLOBAL,
                dimension = PreferenceDimension.TONE,
                statement = preferenceStatement,
                origin = PreferenceOrigin.EXPLICIT,
                confidence = Confidence(0.9),
                revocable = false,
                obtainedAt = fixed,
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
    }

    private fun seedCharacter(f: Fixture, novelId: NovelId) {
        f.app.storyState.saveCharacter(
            Character(
                characterId = CharacterId("c1"),
                novelId = novelId,
                name = "林岚",
                createdAt = fixed,
                updatedAt = fixed,
            ),
        )
    }

    private fun request(f: Fixture, seed: Seed): NovelAgentRequest =
        NovelAgentRequest(
            projectId = f.app.projects.projectOf(seed.novelId).projectId,
            userIntent = userIntent,
            activeChapterId = seed.chapterId,
        )

    private fun promptFor(records: List<ProviderRequest>, agent: String): String =
        records.first { agent in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content }
            .messages.joinToString("\n") { it.content }

    private fun writingRequest(seed: Seed): UserWritingRequest =
        UserWritingRequest(
            requestId = RequestId("req-second-context"),
            intentType = IntentType.CONTINUE,
            target = TargetRef(TargetKind.CHAPTER, TargetRefId(seed.chapterId.value)),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId(seed.novelId.value),
            variantId = null,
            scope = VariantScope.ORIGINAL,
            rawText = userIntent,
        )

    /** S15：用独立脚本化 gateway 真正生成 Planner / Writer prompt（不写 Canonical）。 */
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
    fun `phase 8 full loop asserts S1 to S17 in order`() {
        val records = mutableListOf<ProviderRequest>()
        val f = fixture(records)
        try {
            // ---- 夹具：同章已有正式 Canonical；S1 作者偏好先入仓 ----
            val seed = seedCanonical(f)
            seedAuthorPreference(f)
            seedCharacter(f, seed.novelId)
            val request = request(f, seed)

            val first = f.app.novelAgent.run(request)

            // S1 Author：作者偏好进入 Planner / Writer prompt（AuthorContext 投影生效）
            val plannerPrompt = promptFor(records, "StoryPlannerAgent")
            val writerPrompt = promptFor(records, "StoryWriterAgent")
            assertTrue(preferenceStatement in plannerPrompt, "S1：Planner prompt 必须含作者偏好")
            assertTrue(preferenceStatement in writerPrompt, "S1：Writer prompt 必须含作者偏好")

            // S2 NL Task：userIntent 原样入参（同时进入两处实际 prompt 的 rawText）
            assertEquals(userIntent, request.userIntent, "S2：userIntent 必须原样入参")
            assertTrue("rawText: $userIntent" in plannerPrompt, "S2：Planner prompt 必须携带原始 userIntent")
            assertTrue("rawText: $userIntent" in writerPrompt, "S2：Writer prompt 必须携带原始 userIntent")

            // S3 Intent：CONTINUE + 命中的确定性关键词
            assertEquals(IntentType.CONTINUE, first.intent.intentType, "S3：意图必须是 CONTINUE")
            assertTrue(!first.intent.matchedKeyword.isNullOrBlank(), "S3：matchedKeyword 必须非空")

            // S4 Agent 第一次：writing / 等待人工 / 已桥接 / 有会话
            assertEquals("skill.writing", assertNotNull(first.skillId, "S4：skillId 不能为空").value)
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, first.outcome, "S4：首次必须等待正式人工门")
            assertEquals(NovelAgentApprovalState.BRIDGED, first.approvalState, "S4：候选必须已桥接进正式审批")
            val firstSessionId = assertNotNull(first.sessionId, "S4：第一轮 sessionId 不能为空")

            // S5 Context：pack 有 DRAFT item；Planner/Writer 都含【本章当前稿】+ seed 正文
            val contextPackId = assertNotNull(first.contextPackId, "S5：contextPackId 不能为空")
            val projectId = request.projectId
            val firstPack = assertNotNull(
                f.app.contextPackStore.find(firstSessionId, projectId)?.pack,
                "S5：必须能读回本轮发布过的 ContextPack",
            )
            assertEquals(contextPackId, firstPack.packId, "S5：结果引用的 pack 必须可定位")
            val draftItem = assertNotNull(
                firstPack.items.firstOrNull { it.source == ContextSourceKind.DRAFT },
                "S5：pack 必须含 DRAFT item",
            )
            assertEquals(seedContent, draftItem.content, "S5：pack 当前稿必须来自 Canonical 基线")
            listOf(plannerPrompt, writerPrompt).forEach { prompt ->
                assertTrue("【本章当前稿】" in prompt, "S5：两处 prompt 都必须含当前稿节")
                assertTrue(seedContent in prompt, "S5：两处 prompt 都必须含 seed 正文")
            }

            // S6 Skill/Tool：get_latest_draft 有调用留痕，且属于 WRITING 白名单
            val toolCalls = first.activityIds.flatMap { f.app.toolCallLogs.listByActivity(it) }
            assertTrue(
                toolCalls.any { it.toolName.value == "get_latest_draft" },
                "S6：ToolCallLog 必须包含 get_latest_draft；实际=${toolCalls.map { it.toolName.value }}",
            )
            assertTrue(
                CoreSkills.WRITING.allowedTools.any { it.value == "get_latest_draft" },
                "S6：get_latest_draft 必须在 WRITING.allowedTools 内",
            )

            // S7 Execution：WorkingDraft 已创建
            val workingDraftId = assertNotNull(first.workingDraftId, "S7：workingDraftId 不能为空")

            // S8 Working Draft：可查到且内容 == writer 产出
            val workingDraft = f.app.workingDrafts.get(workingDraftId)
            assertEquals(writerContent, workingDraft.content, "S8：WorkingDraft 内容必须等于 writer 产出")

            // S9 Diff：真实差异
            val artifactId = assertNotNull(first.artifactId, "S10：artifactId 不能为空")
            val artifact = f.app.changes.get(artifactId)
            assertTrue(artifact.change.diff.hasChanges, "S9：Artifact 必须包含真实差异")

            // S10 Change：Artifact 与 WorkingDraft 绑定
            assertEquals(workingDraftId, artifact.workingDraftId, "S10：Artifact 必须绑定同一 WorkingDraft")

            // S11 Human Gate：正式 pending Gate
            val workflow = assertNotNull(
                f.app.workflowRepository.getWorkflowByActiveChapter(seed.chapterId),
                "S11：章节必须存在正式 Workflow",
            )
            val gateId = assertNotNull(workflow.pendingGateId, "S11：必须存在 pendingGateId")
            assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(gateId)?.status, "S11：Gate 必须 PENDING")

            // S12 Commit：正式批准 → CommitHistory +1
            val historyBeforeApprove = f.app.commits.history(projectId).size
            f.app.workflowFacade.approve(seed.chapterId)
            val historyAfterApprove = f.app.commits.history(projectId)
            assertEquals(historyBeforeApprove + 1, historyAfterApprove.size, "S12：批准后 CommitHistory 必须恰好 +1")
            val mainHistory = assertNotNull(
                historyAfterApprove.lastOrNull { it.artifactId == artifactId },
                "S12：必须能按 Artifact 定位本次正式提交",
            )

            // S13 Canonical：读取结果与提交事实一致
            val committed = assertNotNull(f.app.canonicalRead.committedDraft(seed.chapterId), "S13：Canonical 必须可读")
            assertEquals(mainHistory.resultingDraftId, committed.draftId, "S13：Canonical draftId 必须等于 resultingDraftId")
            assertEquals(writerContent, committed.content, "S13：Canonical 内容必须等于 writer 产出")

            // S14 WM Update：Commit 触发；脚本化 facts 落为 ACCEPTED
            val wmOutcome = f.app.worldModelUpdate.onCommit(mainHistory)
            assertTrue(wmOutcome.replay, "S14：WM 候选必须已由 Commit 触发（replay=true），而不是断言现造")
            val candidate = assertNotNull(wmOutcome.candidates.singleOrNull(), "S14：脚本化 facts 必须产生恰好一条候选")
            assertEquals(CandidateFactStatus.ACCEPTED, candidate.status, "S14：候选必须 ACCEPTED")
            assertEquals(factExcerpt, candidate.factName, "S14：候选事实名必须来自脚本化 facts")
            assertNotNull(candidate.resultingFactId, "S14：ACCEPTED 后必须落地 World Model 行")

            // S15 Next Context：重新 assemble 第二个 pack；Planner/Writer prompt 含 S14 的 Event
            val secondPack = f.app.contextEngine.build(
                ContextRequest(
                    projectId = projectId,
                    purpose = IntentType.CONTINUE,
                    focusChapterId = seed.chapterId,
                ),
            )
            val secondContext = f.app.planningContextAssembly.assemble(writingRequest(seed), secondPack)
            val secondPrompts = capturePrompts(secondContext)
            listOf(promptFor(secondPrompts, "StoryPlannerAgent"), promptFor(secondPrompts, "StoryWriterAgent")).forEach { prompt ->
                assertTrue("【近期事件】" in prompt, "S15：第二轮 prompt 必须含近期事件节")
                assertTrue(
                    "$factExcerpt: $factExcerpt [MAIN_PLOT/CONFIRMED]" in prompt,
                    "S15：第二轮 prompt 必须含 S14 写入的 Event",
                )
            }

            // R3：S5 与 S15 是两次不同 assembly（packId 与内容都必须不同）
            assertNotEquals(firstPack.packId, secondPack.packId, "R3：两次 Context 的 packId 必须不同")
            assertNotEquals(firstPack.items, secondPack.items, "R3：两次 Context 的 items 必须不同")

            // R2 / S16：第二轮只读；显式收敛评审不得是 REVIEW_FAILED
            val draftsBeforeReview = f.app.draftRepository.listByChapter(seed.chapterId).size
            val workingBeforeReview = f.app.workingDrafts.list(projectId).size
            val artifactsBeforeReview = f.app.changes.list(projectId).size
            val historyBeforeReview = f.app.commits.history(projectId).size

            val review = f.app.convergenceReview.review(seed.novelId, null, seed.chapterId, artifactId)

            assertTrue(
                review.outcome != ConvergenceOutcome.REVIEW_FAILED,
                "S16：第二轮不得是 REVIEW_FAILED；实际=${review.outcome}，summary=${review.summary}",
            )
            assertEquals(ConvergenceOutcome.CONSISTENT, review.outcome, "S16：脚本化 PASS 必须映射为 CONSISTENT")
            val reviewSessionId = assertNotNull(review.reviewSessionId, "S16：reviewSessionId 不能为空")
            assertNotEquals(firstSessionId, reviewSessionId, "S16：第二轮必须是新会话")

            assertEquals(draftsBeforeReview, f.app.draftRepository.listByChapter(seed.chapterId).size, "R2：Draft 不得变化")
            assertEquals(workingBeforeReview, f.app.workingDrafts.list(projectId).size, "R2：WorkingDraft 不得变化")
            assertEquals(artifactsBeforeReview, f.app.changes.list(projectId).size, "R2：Artifact 不得变化")
            assertEquals(historyBeforeReview, f.app.commits.history(projectId).size, "R2：CommitHistory 不得变化")

            // S17 Result/Proposal：T-8-E 显式字段全部可读
            assertEquals(seed.novelId, review.novelId, "S17：novelId 必须显式可读")
            assertEquals(seed.chapterId, review.chapterId, "S17：chapterId 必须显式可读")
            assertEquals(mainHistory.commitId, review.sourceCommitId, "S17：sourceCommitId 必须绑定本次提交")
            assertEquals(artifactId, review.sourceArtifactId, "S17：sourceArtifactId 必须绑定本次 Artifact")
            assertEquals(2, review.round, "S17：round 固定为 2")
            assertTrue(review.findings.isEmpty(), "S17：PASS 的 findings 必须为空")
            assertEquals(0, review.errorCount, "S17：errorCount 必须为 0")
            assertEquals(0, review.warningCount, "S17：warningCount 必须为 0")
            assertTrue(review.summary.isNotBlank(), "S17：summary 必须非空")

            // R1：全程只提交一次；Canonical 恰好前进一步
            val historyFinal = f.app.commits.history(projectId)
            assertEquals(seed.historyBaseline + 1, historyFinal.size, "R1：全程 CommitHistory 必须恰好 +1")
            val canonicalFinal = assertNotNull(f.app.canonicalRead.committedDraft(seed.chapterId), "R1：Canonical 必须存在")
            assertNotEquals(seed.seedDraftId, canonicalFinal.draftId, "R1：Canonical 必须前进一步")
            assertEquals(mainHistory.resultingDraftId, canonicalFinal.draftId, "R1：Canonical 必须停在唯一一次新提交")
        } finally {
            f.close()
        }
    }
}
