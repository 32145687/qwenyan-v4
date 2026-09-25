package com.qianyan.application.usecase.workflow

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.chapter.ChapterWritingUseCases
import com.qianyan.application.usecase.decision.DecisionModelGateway
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlanningSnapshot
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.decision.DecisionOutcome
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionPolicySource
import com.qianyan.model.decision.DecisionType
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P20-P5-fix · 上层编排路径的 DecisionPolicy 闭环测试（FD-4）。
 *
 * 覆盖：
 *  1. WorkflowOrchestrator：Planning → Writing 透传同一份 DecisionPolicy；
 *  2. WorkflowOrchestrator：新 durable plan seam（`PLANJSON2:<PlanningSnapshot>`）plan + policies round-trip；
 *  3. WorkflowOrchestrator：Resume（PLANNING 已持久化）不重新 Decision（invocation count）；
 *  4. WorkflowOrchestrator：legacy `PLANJSON:<ChapterPlan json>` 继续可读且不偷偷重新 Decision；
 *  5. ChapterWritingUseCases：plan() → write() 透传同一份 Policy；
 *  6. ChapterWritingUseCases：链内幂等重入不重新 Decision。
 *
 * 全程 Mock LLM / 内存库，无网络；不修改 P19 Domain Contract。
 */
class DecisionPolicyOrchestrationIntegrationTest {

    // ---------------- helpers ----------------

    /** 已确认 EXPLICIT 偏好驱动的期望政策（Preference > Core > DNA，RULE_MATRIX）。 */
    private fun expectedPolicies() = listOf(
        DecisionPolicy(DecisionType.STORY_DIRECTION, DecisionOutcome.PROGRESSIVE_REVEAL, DecisionPolicySource.RULE_MATRIX),
        DecisionPolicy(DecisionType.WRITING_STYLE, DecisionOutcome.SHORT_DOMINANT, DecisionPolicySource.RULE_MATRIX),
    )

    private fun chapterPlan(chapterId: ChapterId, novelId: NovelId, goal: String) = ChapterPlan(
        chapterPlanId = ChapterPlanId("cp-legacy"),
        chapterId = chapterId,
        arcId = ArcId("arc-legacy"),
        actId = ActId("act-legacy"),
        novelId = novelId,
        scope = VariantScope.ORIGINAL,
        chapterGoal = goal,
        expectedEvents = listOf("闭关"),
        endingHook = "门被敲响",
    )

    /** 计数 [DecisionModelGateway]：委托真实 P19 语义，只统计 decide 调用次数。 */
    private class CountingDecisionGateway(
        private val delegate: DecisionModelGateway,
    ) : DecisionModelGateway {
        var calls: Int = 0
            private set

        override fun decide(authorContext: AuthorContext, vararg types: DecisionType): List<DecisionPolicy> {
            calls += 1
            return delegate.decide(authorContext, *types)
        }
    }

    /** 夹具：内存容器 + 计数 Decision 网关 + 注入计数网关的 Planning/Orchestrator/ChapterWriting。 */
    private class Harness {

        val plannerInputs = mutableListOf<String>()
        val writerInputs = mutableListOf<String>()

        private val gateway = MockLLMGateway { req ->
            val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
            val user = req.messages.last { it.role == ChatRole.USER }.content
            val body = when {
                "StoryPlannerAgent" in system -> { plannerInputs += user; """{"chapterGoal":"g"}""" }
                "StoryWriterAgent" in system -> { writerInputs += user; """{"content":"正文已写就。"}""" }
                "StoryCriticAgent" in system -> """{"passed":true}"""
                else -> """{}"""
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(1, 1, 2),
                finishReason = FinishReason.STOP,
            )
        }

        val app = ApplicationContainer.open(analysisGateway = gateway)
        val decisions = CountingDecisionGateway(app.decisionModelGateway)

        val planning = PlanningExecutionUseCase(
            taskManager = app.tasks,
            assembly = app.planningContextAssembly,
            planner = app.planner,
            chapterRepository = app.chapterRepository,
            continuationResolver = app.continuationResolver,
            errorMapper = app.errorMapper,
            decisionModel = decisions,
        )

        val orchestrator = WorkflowOrchestrator(
            workflowRepository = app.workflowRepository,
            taskManager = app.tasks,
            writing = app.writingExecution,
            planning = planning,
            critique = app.critique,
            revision = app.revision,
            confirmation = app.confirmations,
            knowledgeUpdate = app.knowledgeUpdate,
            draftRepository = app.draftRepository,
            chapterRepository = app.chapterRepository,
            errorMapper = app.errorMapper,
        )

        val chapterWriting = ChapterWritingUseCases(
            taskManager = app.tasks,
            planning = planning,
            writing = app.writingExecution,
            critique = app.critique,
            revision = app.revision,
            confirmation = app.confirmations,
            knowledgeUpdate = app.knowledgeUpdate,
            draftRepository = app.draftRepository,
            errorMapper = app.errorMapper,
        )

        fun seedAuthorPreferences() {
            app.authorPreferenceUseCases.addExplicitPreference(
                dimension = PreferenceDimension.STORY_DIRECTION,
                statement = "叙事上偏好 progressive 渐进揭示",
            )
            app.authorPreferenceUseCases.addExplicitPreference(
                dimension = PreferenceDimension.VOICE,
                statement = "句式偏好 short 短句为主",
            )
        }

        fun newChapter(novelId: NovelId): ChapterId =
            app.chapters.createNextChapter(title = "章", novelId = novelId).chapterId

        fun newWorkflow(wfId: WorkflowId, novelId: NovelId, chapterId: ChapterId) {
            val now = Clock.System.now()
            app.workflowRepository.createWorkflow(
                Workflow(
                    wfId, novelId, null,
                    kind = WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.CREATED,
                    activeChapterId = chapterId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        /** 模拟「先前进程已完成 PLANNING」的 Resume 起点：预置 COMPLETED PLANNING step（含持久化 payload）。 */
        fun seedCompletedPlanningStep(wfId: WorkflowId, chapterId: ChapterId, resultReference: String) {
            val now = Clock.System.now()
            app.workflowRepository.createStep(
                WorkflowStep(
                    stepId = WorkflowStepId("s-plan-${wfId.value}"),
                    workflowId = wfId,
                    chapterId = chapterId,
                    phase = WorkflowStepPhase.PLANNING,
                    logicalStepKey = "${wfId.value}:${chapterId.value}:${WorkflowStepPhase.PLANNING.name}",
                    status = WorkflowStepStatus.COMPLETED,
                    resultReference = resultReference,
                    createdAt = now,
                    completedAt = now,
                ),
            )
        }

        fun planStepRef(wfId: WorkflowId): String =
            app.workflowRepository.listSteps(wfId).first { it.phase == WorkflowStepPhase.PLANNING }.resultReference!!
    }

    // ---------------- 1. Workflow：Planning → Writing 透传 ----------------

    @Test
    fun `workflow planning to writing passes the same policy`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "T")
        val ch = h.newChapter(novelId)
        val wfId = WorkflowId("W-P5-a")
        h.newWorkflow(wfId, novelId, ch)

        val r = h.orchestrator.runForward(wfId)

        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)
        assertEquals(1, h.decisions.calls, "新 Workflow 应恰好决定一次（PLANNING）")
        // Planning 与 Writing 的 Agent 输入都携带同一份 Policy
        val expectedLine = "- STORY_DIRECTION → PROGRESSIVE_REVEAL (source=RULE_MATRIX)"
        assertTrue(h.plannerInputs.last().contains(expectedLine), "Planner 输入应携带 Policy")
        assertTrue(h.writerInputs.last().contains(expectedLine), "Writer 输入应携带与 Planning 同一份 Policy")
        assertTrue(h.writerInputs.last().contains("- WRITING_STYLE → SHORT_DOMINANT (source=RULE_MATRIX)"))
    }

    // ---------------- 2. Workflow：新 payload round-trip ----------------

    @Test
    fun `workflow plan payload round trips plan and policies`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "T")
        val ch = h.newChapter(novelId)
        val wfId = WorkflowId("W-P5-b")
        h.newWorkflow(wfId, novelId, ch)

        h.orchestrator.runForward(wfId)

        val ref = h.planStepRef(wfId)
        assertTrue(ref.startsWith("PLANJSON2:"), "新 durable plan seam 前缀")
        val snapshot = Json.parseToJsonElement(ref.removePrefix("PLANJSON2:")).jsonObject
        // Plan 可恢复
        assertEquals("g", PlanningSnapshot.decode(snapshot)?.chapterGoal)
        // DecisionPolicy 快照可恢复（复用既有 codec，未复制第二套）
        assertEquals(expectedPolicies(), PlanningSnapshot.decodePolicies(snapshot))
    }

    // ---------------- 3. Workflow：Resume 不重新 Decision ----------------

    @Test
    fun `workflow resume reuses persisted policy without re-deciding`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "T")
        val ch = h.newChapter(novelId)

        // (a) 新 Task / 新 Workflow → decide() == 1（并落库真实 payload）
        val wfA = WorkflowId("W-P5-resume-a")
        h.newWorkflow(wfA, novelId, ch)
        h.orchestrator.runForward(wfA)
        assertEquals(1, h.decisions.calls)
        val persistedRef = h.planStepRef(wfA)

        // (b) Resume：另一 Workflow 的 PLANNING 已在先前进程 COMPLETED（复用同一份真实落库 payload）
        val wfB = WorkflowId("W-P5-resume-b")
        h.newWorkflow(wfB, novelId, ch)
        h.seedCompletedPlanningStep(wfB, ch, persistedRef)
        val writerCallsBefore = h.writerInputs.size
        val r = h.orchestrator.runForward(wfB)

        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status)
        assertEquals(1, h.decisions.calls, "Resume 不得重新 Decision（仍为 1，不是 2）")
        assertTrue(h.writerInputs.size > writerCallsBefore, "Resume 后 Writing 应真实执行")
        assertTrue(h.writerInputs.last().contains("PROGRESSIVE_REVEAL"), "Writing 消费的是持久化的 Policy")
    }

    // ---------------- 4. Workflow：legacy PLANJSON 兼容 ----------------

    @Test
    fun `workflow legacy planjson payload stays readable without deciding`() {
        val h = Harness()
        h.seedAuthorPreferences() // 即便当前存在作者偏好，旧 payload 也不得被偷偷重新 Decision
        val novelId = h.app.novels.createOriginal(title = "T")
        val ch = h.newChapter(novelId)
        val wfId = WorkflowId("W-P5-legacy")
        h.newWorkflow(wfId, novelId, ch)

        val legacyPlan = chapterPlan(ch, novelId, goal = "旧格式计划")
        h.seedCompletedPlanningStep(
            wfId,
            ch,
            "PLANJSON:" + Json.encodeToString(ChapterPlan.serializer(), legacyPlan),
        )

        val r = h.orchestrator.runForward(wfId)

        assertEquals(WorkflowStatus.WAITING_HUMAN, r.status, "旧 PLANJSON payload 必须继续可读（不崩溃）")
        assertEquals(0, h.decisions.calls, "旧 payload 无政策快照 → 不得偷偷重新 Decision")
        assertTrue(h.writerInputs.isNotEmpty())
        assertFalse(
            h.writerInputs.last().contains("决策 Decision Policy"),
            "旧 payload → 空政策（既不重新决定，也不伪造）",
        )
        assertNotNull(h.app.draftRepository.latestByChapter(ch))
    }

    // ---------------- 5. ChapterWriting：plan → write 透传 ----------------

    @Test
    fun `chapter writing passes the same policy from plan to write`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "T")
        val ch = h.newChapter(novelId)
        val session = h.chapterWriting.open(ch, novelId, null)

        session.plan()
        assertEquals(1, h.decisions.calls, "plan() 应恰好决定一次")

        session.write()
        assertEquals(1, h.decisions.calls, "write() 必须复用 plan() 的同一份 Policy（不重新 Decision）")
        assertTrue(
            h.writerInputs.last().contains("- STORY_DIRECTION → PROGRESSIVE_REVEAL (source=RULE_MATRIX)"),
            "Writer 输入应携带 plan() 阶段决定的 Policy",
        )
    }

    // ---------------- 6. ChapterWriting：幂等重入不重新 Decision ----------------

    @Test
    fun `chapter writing idempotent re-entry does not decide again`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "T")
        val ch = h.newChapter(novelId)
        val session = h.chapterWriting.open(ch, novelId, null)

        session.plan()
        session.plan()
        session.write()
        session.write()

        assertEquals(1, h.decisions.calls, "链内重复推进不得重新 Decision")
        assertEquals(1, h.app.draftRepository.listByChapter(ch).size)
    }
}