package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.ActId
import com.qianyan.model.ArcId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.CharacterId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ChapterPlan
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * P11.2 Planner Agent 单元测试。
 *
 * 用注入的 Mock LLM（以 `{"answer":"<正文>"}` 协议返回受控输出）验证 Planner：
 *  - 合法计划 → ChapterPlan；
 *  - 非法 JSON / 缺 chapterGoal / 类型错误 / 空输出 → 类型化 [ApplicationError.InvalidPlanningOutput]；
 *  - AgentRuntime / Provider / Tool 失败 → 类型化 [ApplicationError.PlanningFailed]。
 * 全程无网络，不触碰 Storage / Provider 具体实现。
 */
class PlannerAgentTest {

    private fun gateway(content: String): MockGateway = MockGateway { ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, content),
        usage = Usage(10, 10, 20),
        finishReason = FinishReason.STOP,
    ) }

    private fun planner(content: String) = PlannerAgent(gateway(content), ErrorMapper, ModelProfile.MOCK)

    /** 把 PlanDto 正文按真实协议 `{"answer":"<json 字符串>"}` 封装后交给 Planner。 */
    private fun plannerWithAnswer(planJson: String): PlannerAgent =
        planner(buildJsonObject { put("answer", planJson) }.toString())

    private fun context(): PlanningContext {
        val request = UserWritingRequest(
            requestId = RequestId("req-1"),
            intentType = IntentType.PLAN,
            target = TargetRef(TargetKind.CHAPTER, null),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId("novel-1"),
        )
        return PlanningContext(
            request = request,
            novelId = NovelId("novel-1"),
            scope = VariantScope.ORIGINAL,
            novelTitle = "测试小说",
            novelGenre = listOf("玄幻"),
            novelSynopsis = "一个少年从平凡走向巅峰的故事。",
            characters = listOf(CharacterLite(CharacterId("c1"), name = "主角", goals = listOf("登顶"))),
            memories = listOf("主角已获得第一块灵石"),
            vocabulary = listOf(VocabularyLite(canonical = "灵石", aliases = listOf("灵晶"), replacement = "星石")),
        )
    }

    /* 合法计划：Mock LLM 返回有效 ChapterPlan JSON → 映射结构 ID 来自上下文，创作字段来自 LLM */
    @Test
    fun `valid plan output maps to chapter plan`() {
        val planJson = "{\"chapterGoal\":\"救下女主并揭开家族秘辛\",\"mainConflict\":\"conflict-a\"," +
            "\"characterGoals\":{\"c1\":\"守住秘密\"},\"expectedEvents\":[\"发现古碑\",\"遭遇敌人\"]," +
            "\"emotionalDirection\":\"紧张\",\"endingHook\":\"门后传来脚步声\"," +
            "\"constraints\":[\"不剧透\"],\"forbiddenEvents\":[\"主角死亡\"]}"
        val plan = plannerWithAnswer(planJson).plan(context())

        assertEquals("novel-1", plan.novelId.value)
        assertEquals("default-arc", plan.arcId.value)
        assertEquals("default-act", plan.actId.value)
        assertEquals(VariantScope.ORIGINAL, plan.scope)
        assertEquals("救下女主并揭开家族秘辛", plan.chapterGoal)
        assertEquals("conflict-a", plan.mainConflict?.value)
        assertEquals("守住秘密", plan.characterGoals[CharacterId("c1")])
        assertEquals(listOf("发现古碑", "遭遇敌人"), plan.expectedEvents)
        assertEquals("紧张", plan.emotionalDirection)
        assertEquals("门后传来脚步声", plan.endingHook)
        assertEquals(listOf("不剧透"), plan.constraints)
        assertEquals(listOf("主角死亡"), plan.forbiddenEvents)
    }

    /* 非法 JSON：正文不是合法 JSON → InvalidPlanningOutput（类型化，不经 String.contains） */
    @Test
    fun `illegal json output fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            planner("""{"answer":"这不是 JSON 计划" }""").plan(context())
        }
        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error)
    }

    /* 缺失 chapterGoal：JSON 合法但无必需创作字段 → InvalidPlanningOutput（空计划被拒绝） */
    @Test
    fun `plan missing chapterGoal fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            planner("""{"answer":"{\"chapterGoal\":\"\"}" }""").plan(context())
        }
        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error)
    }

    /* 空输出：planner 返回空 answer → InvalidPlanningOutput */
    @Test
    fun `empty answer output fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            planner("""{"answer":"" }""").plan(context())
        }
        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error)
    }

    /* 类型错误：chapterGoal 传成数字 → 解码失败 → InvalidPlanningOutput */
    @Test
    fun `wrong field type output fails with typed error`() {
        val ex = assertFailsWith<ApplicationException> {
            planner("""{"answer":"{\"chapterGoal\":123}" }""").plan(context())
        }
        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error)
    }

    /* 兜底：任何非 Final / 无 answer 的正文视为 Final（含正文），随后按 plans 解析失败归一 */
    @Test
    fun `planner rejects non plan payload`() {
        val ex = assertFailsWith<ApplicationException> {
            planner("""{"vocabulary":[] }""").plan(context())
        }
        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error)
    }

    /** 注入的 fake LLM：对每次请求返回固定正文。 */
    private class MockGateway(private val handler: (ProviderRequest) -> ProviderResponse) : com.qianyan.provider.LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse = handler(request)
    }

    /* 验证 Planner 输出的结构 ID 由 Structure 承载，创作字段由 LLM 提供 */
    @Test
    fun `structure ids come from context not from llm`() {
        val s = ChapterPlanStructure(
            novelId = NovelId("novel-1"),
            arcId = ArcId("arc-1"),
            actId = ActId("act-1"),
            scope = VariantScope.ORIGINAL,
            chapterPlanId = "cp-1",
        )
        val parsed = ChapterPlanParser.parse("{\"chapterGoal\":\"抵达终点\"}", s)
        assertEquals("novel-1", parsed.novelId.value)
        assertEquals("arc-1", parsed.arcId.value)
        assertEquals("act-1", parsed.actId.value)
        assertEquals("cp-1", parsed.chapterPlanId.value)
        assertEquals("抵达终点", parsed.chapterGoal)
    }
}