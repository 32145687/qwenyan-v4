package com.qianyan.application.usecase.decision

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.writing.planning.PlanningContext
import com.qianyan.application.usecase.writing.planning.PlanningExecutionUseCase
import com.qianyan.application.usecase.writing.planning.PlanningSnapshot
import com.qianyan.application.usecase.writing.planning.PlannerAgent
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.application.usecase.writing.WritingSnapshot
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.ChapterPlanId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.decision.DecisionOutcome
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionPolicySource
import com.qianyan.model.decision.DecisionType
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.task.TaskType
import com.qianyan.model.writing.Draft
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P20-P5 · P19 Writing Integration 测试（FD-4：DecisionPolicy 生命周期 / Checkpoint 快照 / Resume）。
 *
 * 覆盖：
 *  1. 新 Task → 经 Application orchestration decide 一次并获得 Policy；
 *  2. Planning 收到的 Agent 输入携带该 Policy；
 *  3. Writing 复用 **同一份** Policy（Writer 不重新 decide）；
 *  4. Resume：从 Checkpoint Policy Snapshot 恢复，**不重新调用 DecisionModel**（invocation count 验证）；
 *  5. Snapshot round-trip（RULE_MATRIX / DETERMINISTIC_DEFAULT 均可保存/恢复）；
 *  6. 旧 Checkpoint（无政策键）仍可读，且不静默重新 decide；
 *  7. 非法枚举名安全丢弃（不破坏旧数据读取）；
 *  8. 两个 DecisionType 均通过 orchestration；
 *  9. Agent 层无第二套 Decision 逻辑（只消费传入 Policy，绝不自行合成）。
 *
 * 全程 Mock LLM / 内存库，无网络；不修改 P19 Domain Contract。
 */
class DecisionPolicyWritingIntegrationTest {

    // ---------------- helpers ----------------

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-p5"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun chapterPlan(novelId: NovelId) = ChapterPlan(
        chapterPlanId = ChapterPlanId("cp-p5"),
        chapterId = ChapterId("ch-p5"),
        arcId = ArcId("arc-p5"),
        actId = ActId("act-p5"),
        novelId = novelId,
        scope = VariantScope.ORIGINAL,
        chapterGoal = "突破到筑基期并结识道友",
        expectedEvents = listOf("闭关"),
        endingHook = "门被敲响",
    )

    private fun draft(novelId: NovelId) = Draft(
        draftId = DraftId("d-p5"),
        novelId = novelId,
        chapterId = ChapterId("ch-p5"),
        planId = ChapterPlanId("cp-p5"),
        content = "女主推开古碑，雷劫降临。",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun planningContext() = PlanningContext(
        request = request(NovelId("novel-plain")),
        novelId = NovelId("novel-plain"),
        scope = VariantScope.ORIGINAL,
        novelTitle = "测试小说",
    )

    private fun policy(
        type: DecisionType,
        outcome: DecisionOutcome,
        source: DecisionPolicySource,
    ) = DecisionPolicy(decisionType = type, outcome = outcome, source = source)

    /** 计数 [DecisionModelGateway]：委托真实 P19 语义，只统计 decide 调用次数与类型。 */
    private class CountingDecisionGateway(
        private val delegate: DecisionModelGateway,
    ) : DecisionModelGateway {

        var calls: Int = 0
            private set
        var lastTypes: List<DecisionType> = emptyList()
            private set

        override fun decide(authorContext: AuthorContext, vararg types: DecisionType): List<DecisionPolicy> {
            calls += 1
            lastTypes = types.toList()
            return delegate.decide(authorContext, *types)
        }
    }

    /** 按 Agent 渲染输入分派 Planner / Writer 响应，并记录每次 LLM 的 USER 输入（= Agent 渲染文本）。 */
    private class ScriptedGateway(
        private val planJson: String,
        private val draftJson: String,
    ) : LLMGateway {

        val agentInputs: MutableList<String> = mutableListOf()

        override fun chat(request: ProviderRequest): ProviderResponse {
            val user = request.messages.last { it.role == ChatRole.USER }.content
            agentInputs += user
            // Writer 的渲染输入含【本章规划】段，Planner 不含 → 确定性分派（测试专用）
            val body = if ("【本章规划】" in user) draftJson else planJson
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    /** 测试夹具：内存容器 + 计数 Decision 网关 + 直接构造的 PlanningExecutionUseCase（注入计数网关）。 */
    private class Harness {

        val gateway = ScriptedGateway(PLAN_JSON, DRAFT_JSON)
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

        /** 以已确认的 EXPLICIT 偏好驱动 RULE_MATRIX（Preference 优先于 Core / DNA）。 */
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
    }

    // ---------------- 1. 新 Task Decision ----------------

    @Test
    fun `new planning task decides once and snapshots both policy types`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "测试仙侠")
        val taskId = h.app.tasks.create(TaskType.PLANNING)

        h.planning.execute(taskId, request(novelId))

        assertEquals(1, h.decisions.calls, "新 Task 应恰好决定一次")
        assertEquals(
            listOf(DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE),
            h.decisions.lastTypes,
            "两个 DecisionType 均须经 Application orchestration",
        )
        val policies = h.planning.decisionPoliciesFrom(h.app.tasks.restoreCheckpoint(taskId))
        assertEquals(
            listOf(
                policy(DecisionType.STORY_DIRECTION, DecisionOutcome.PROGRESSIVE_REVEAL, DecisionPolicySource.RULE_MATRIX),
                policy(DecisionType.WRITING_STYLE, DecisionOutcome.SHORT_DOMINANT, DecisionPolicySource.RULE_MATRIX),
            ),
            policies,
            "Checkpoint 必须保存可恢复的 Policy 快照（decisionType/outcome/source）",
        )
    }

    // ---------------- 2. Planning 使用 Policy ----------------

    @Test
    fun `planner agent input carries the decided policy`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "测试仙侠")
        val taskId = h.app.tasks.create(TaskType.PLANNING)

        h.planning.execute(taskId, request(novelId))

        val plannerInput = h.gateway.agentInputs.first { "【本章规划】" !in it }
        assertTrue(plannerInput.contains("【创作决策 Decision Policy】"), "Planner 输入应含决策政策段")
        assertTrue(plannerInput.contains("- STORY_DIRECTION → PROGRESSIVE_REVEAL (source=RULE_MATRIX)"))
        assertTrue(plannerInput.contains("- WRITING_STYLE → SHORT_DOMINANT (source=RULE_MATRIX)"))
    }

    // ---------------- 3. Writing 使用同一 Policy ----------------

    @Test
    fun `writing reuses the same policy snapshot without deciding again`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "测试仙侠")
        val planTask = h.app.tasks.create(TaskType.PLANNING)
        val plan = h.planning.execute(planTask, request(novelId))
        val policies = h.planning.decisionPoliciesFrom(h.app.tasks.restoreCheckpoint(planTask))
        assertNotNull(policies)

        val writeTask = h.app.tasks.create(TaskType.WRITING)
        h.app.writingExecution.execute(writeTask, request(novelId), plan, policies)

        assertEquals(1, h.decisions.calls, "Writing 不得重新 Decision（仍为 Planning 的一次）")
        val writerInput = h.gateway.agentInputs.first { "【本章规划】" in it }
        assertTrue(
            writerInput.contains("- STORY_DIRECTION → PROGRESSIVE_REVEAL (source=RULE_MATRIX)"),
            "Writer 必须消费与 Planning 相同的 Policy",
        )
        assertEquals(
            policies,
            h.app.writingExecution.decisionPoliciesFrom(h.app.tasks.restoreCheckpoint(writeTask)),
            "WRITING Checkpoint 应保存同一份 Policy 快照",
        )
    }

    // ---------------- 4. Resume 不重新 Decision ----------------

    @Test
    fun `resume restores policy from checkpoint without re-deciding`() {
        val h = Harness()
        h.seedAuthorPreferences()
        val novelId = h.app.novels.createOriginal(title = "测试仙侠")

        // 新 Task → decide() == 1
        val first = h.app.tasks.create(TaskType.PLANNING)
        h.planning.execute(first, request(novelId))
        assertEquals(1, h.decisions.calls)

        // Resume：Checkpoint → 恢复 policy snapshot → 继续（复用，不重 decide）
        val restored = h.planning.decisionPoliciesFrom(h.app.tasks.restoreCheckpoint(first))
        assertNotNull(restored, "Resume 必须能从 Checkpoint 恢复 Policy")
        val resumed = h.app.tasks.create(TaskType.PLANNING)
        h.planning.execute(
            taskId = resumed,
            request = request(novelId),
            continuationReference = null,
            targetChapterId = null,
            decisionPolicies = restored,
        )

        assertEquals(1, h.decisions.calls, "Resume 后 decide() 必须仍为 1（不是 2）")

        // 对照：不提供 snapshot 即视为新 Attempt → 真的会 decide（证明计数生效）
        val fresh = h.app.tasks.create(TaskType.PLANNING)
        h.planning.execute(fresh, request(novelId))
        assertEquals(2, h.decisions.calls, "新 Attempt 应决定一次（对照组）")
    }

    // ---------------- 5. Snapshot round-trip（含两种 source） ----------------

    @Test
    fun `policy snapshot round trips both sources`() {
        val policies = listOf(
            policy(DecisionType.STORY_DIRECTION, DecisionOutcome.PROGRESSIVE_REVEAL, DecisionPolicySource.RULE_MATRIX),
            policy(DecisionType.WRITING_STYLE, DecisionOutcome.SHORT_DOMINANT, DecisionPolicySource.RULE_MATRIX),
            policy(DecisionType.STORY_DIRECTION, DecisionOutcome.NEUTRAL, DecisionPolicySource.DETERMINISTIC_DEFAULT),
            policy(DecisionType.WRITING_STYLE, DecisionOutcome.NEUTRAL, DecisionPolicySource.DETERMINISTIC_DEFAULT),
        )

        // DecisionPolicy → Snapshot → DecisionPolicy
        assertEquals(policies, DecisionPolicySnapshot.decode(DecisionPolicySnapshot.encodeAsObject(policies)))

        // 嵌入 PLANNING / WRITING 快照后仍可恢复，且不破坏既有快照内容
        val novelId = NovelId("novel-p5")
        val planSnapshot = PlanningSnapshot.encode(chapterPlan(novelId), policies)
        assertEquals(policies, PlanningSnapshot.decodePolicies(planSnapshot))
        assertEquals("突破到筑基期并结识道友", PlanningSnapshot.decode(planSnapshot)?.chapterGoal)

        val draftSnapshot = WritingSnapshot.encode(draft(novelId), policies)
        assertEquals(policies, WritingSnapshot.decodePolicies(draftSnapshot))
        assertEquals(DraftId("d-p5"), WritingSnapshot.decodeReference(draftSnapshot))
    }

    // ---------------- 6. 旧 Checkpoint 兼容 ----------------

    @Test
    fun `legacy checkpoints without policy snapshot stay readable`() {
        val novelId = NovelId("novel-p5")
        val legacyPlan = PlanningSnapshot.encode(chapterPlan(novelId))
        // 旧 Checkpoint 无政策键 → null（调用方不得据此静默重新 decide）
        assertNull(PlanningSnapshot.decodePolicies(legacyPlan))
        // 但旧数据本身仍可正常读取（不破坏历史流程）
        assertEquals("突破到筑基期并结识道友", PlanningSnapshot.decode(legacyPlan)?.chapterGoal)

        val legacyWriting = WritingSnapshot.encode(draft(novelId))
        assertNull(WritingSnapshot.decodePolicies(legacyWriting))
        assertEquals(DraftId("d-p5"), WritingSnapshot.decodeReference(legacyWriting))
    }

    // ---------------- 7. 非法枚举名安全丢弃 ----------------

    @Test
    fun `unknown enum names are dropped without breaking decode`() {
        val snapshot = buildJsonObject {
            put(
                DecisionPolicySnapshot.KEY_TYPE,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("decisionType", "STORY_DIRECTION")
                            put("outcome", "PROGRESSIVE_REVEAL")
                            put("source", "RULE_MATRIX")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("decisionType", "NOT_A_DECISION_TYPE")
                            put("outcome", "NEUTRAL")
                            put("source", "RULE_MATRIX")
                        },
                    )
                },
            )
        }

        val restored = DecisionPolicySnapshot.decode(snapshot)
        assertEquals(1, restored?.size, "非法枚举名条目应被丢弃而非抛错")
        assertEquals(DecisionType.STORY_DIRECTION, restored?.first()?.decisionType)
    }

    // ---------------- 8. 空 AuthorContext → Deterministic Neutral（经 orchestration） ----------------

    @Test
    fun `empty author context falls back to deterministic neutral through orchestration`() {
        val h = Harness()
        val novelId = h.app.novels.createOriginal(title = "无偏好小说")
        val taskId = h.app.tasks.create(TaskType.PLANNING)

        h.planning.execute(taskId, request(novelId))

        assertEquals(1, h.decisions.calls)
        assertEquals(
            listOf(
                policy(DecisionType.STORY_DIRECTION, DecisionOutcome.NEUTRAL, DecisionPolicySource.DETERMINISTIC_DEFAULT),
                policy(DecisionType.WRITING_STYLE, DecisionOutcome.NEUTRAL, DecisionPolicySource.DETERMINISTIC_DEFAULT),
            ),
            h.planning.decisionPoliciesFrom(h.app.tasks.restoreCheckpoint(taskId)),
        )
    }

    // ---------------- 9. Agent 层无第二套 Decision 逻辑 ----------------

    @Test
    fun `agents never synthesize their own policy`() {
        val plannerInputs = mutableListOf<String>()
        val plannerGateway = MockLLMGateway { req ->
            plannerInputs += req.messages.last { it.role == ChatRole.USER }.content
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", PLAN_JSON) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val planner = PlannerAgent(plannerGateway, ErrorMapper, ModelProfile.MOCK)
        val policies = listOf(policy(DecisionType.STORY_DIRECTION, DecisionOutcome.PROGRESSIVE_REVEAL, DecisionPolicySource.RULE_MATRIX))

        planner.plan(planningContext())
        assertFalse(plannerInputs.last().contains("Decision Policy"), "未传入时 Planner 不得自行产生 Policy")

        planner.plan(planningContext(), policies)
        assertTrue(plannerInputs.last().contains("Decision Policy"), "传入时 Planner 只做消费（渲染）")

        val writerInputs = mutableListOf<String>()
        val writerGateway = MockLLMGateway { req ->
            writerInputs += req.messages.last { it.role == ChatRole.USER }.content
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", DRAFT_JSON) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val writer = WriterAgent(writerGateway, ErrorMapper, ModelProfile.MOCK)
        val plan = chapterPlan(NovelId("novel-plain"))

        writer.write(planningContext(), plan)
        assertFalse(writerInputs.last().contains("Decision Policy"), "未传入时 Writer 不得自行产生 Policy")

        writer.write(planningContext(), plan, policies)
        assertTrue(writerInputs.last().contains("Decision Policy"), "传入时 Writer 只做消费（渲染）")
    }

    private companion object {
        const val PLAN_JSON: String =
            "{\"chapterGoal\":\"突破到筑基期并结识道友\",\"mainConflict\":\"conf-c\"," +
                "\"characterGoals\":{\"c1\":\"变强\"},\"expectedEvents\":[\"闭关\",\"遇险\"]," +
                "\"endingHook\":\"门被敲响\",\"constraints\":[\"无\"],\"forbiddenEvents\":[\"死亡\"]}"

        const val DRAFT_JSON: String = "{\"content\":\"女主推开古碑，雷劫降临。\"}"
    }
}