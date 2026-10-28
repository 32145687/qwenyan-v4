package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.application.usecase.tool.ProductToolService
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-2B-3 · CharacterConsistency Skill 路由与执行守卫（V-1/V-2/V-3/V-5）。 */
class CharacterConsistencySkillRoutingTest {

    private class ScriptedGateway {
        var answer: String = INSUFFICIENT_PLAN
        val prompts = mutableListOf<String>()

        val gateway: LLMGateway = MockLLMGateway { request ->
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val user = request.messages.first { it.role == ChatRole.USER }.content
            val body = when {
                "StoryCharacterConsistencyAgent" in system -> {
                    prompts += user
                    answer
                }
                "StoryCriticAgent" in system -> """{"passed":true}"""
                else -> """{"content":"占位"}"""
            }
            ProviderResponse(
                message = ChatMessage(
                    ChatRole.ASSISTANT,
                    buildJsonObject { put("answer", body) }.toString(),
                ),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    @Test
    fun `V1 routing selects character consistency by preferred and keyword without hijacking analysis`() {
        val script = ScriptedGateway()
        val f = novelAgentFixture(script.gateway)
        val w = seedNovelWorld(f)

        val preferred = f.app.novelAgent.run(
            request(
                w,
                "检查人物一致性",
                preferredSkillId = CoreSkills.CHARACTER_CONSISTENCY.skillId,
            ),
        )
        assertEquals(CoreSkills.CHARACTER_CONSISTENCY.skillId, preferred.skillId)
        assertEquals(NovelAgentOutcome.COMPLETED, preferred.outcome)
        assertNotNull(preferred.characterConsistency)

        val keyword = f.app.novelAgent.run(request(w, "检查人物一致性"))
        assertEquals(CoreSkills.CHARACTER_CONSISTENCY.skillId, keyword.skillId, "keyword 应命中人物一致性")
        assertNotNull(keyword.characterConsistency)

        // 普通分析仍走 ANALYSIS_CRITIQUE（keyword 不得泛化劫持）
        val ordinary = f.app.novelAgent.run(request(w, "分析第一章"))
        assertEquals(CoreSkills.ANALYSIS_CRITIQUE.skillId, ordinary.skillId)
        assertNull(ordinary.characterConsistency)
        f.close()
    }

    @Test
    fun `V2 character consistency skill is executable and consumes context pack`() {
        val script = ScriptedGateway()
        val f = novelAgentFixture(script.gateway)
        val w = seedNovelWorld(f)
        val skill = CoreSkills.CHARACTER_CONSISTENCY
        val noTools = ProductToolService(
            tools = emptyList(),
            activities = f.app.activities,
            toolCallLogs = f.app.toolCallLogs,
            actionPolicy = f.app.actionPolicy,
        )
        val guard = SkillExecutionGuard(noTools, f.app.actionPolicy, f.app.contextEngine)

        assertEquals(
            emptyList(),
            guard.violations(skill, f.app.characterConsistencyAgent.agentId),
            "CharacterConsistency 无工具注册时也应通过执行前契约校验",
        )
        assertEquals("story-character-consistency", skill.bindings.single().agentId.value)
        assertEquals(listOf(AgentActionKind.READ), skill.expectedActions)
        assertTrue(ContextSourceKind.DRAFT in skill.requiredContext)
        assertEquals(emptyList(), skill.allowedTools, "CharacterConsistency 不声明工具")

        val result = f.app.novelAgent.run(request(w, "检查人物一致性"))
        assertNotNull(result.contextPackId, "skillConsumesContext 应为 true（本次实际构建并产出 ContextPack）")
        assertNotNull(result.characterConsistency)
        f.close()
    }

    @Test
    fun `V3 dispatch folds result into NovelAgentResult and V5 keeps validator summary`() {
        val script = ScriptedGateway()
        val f = novelAgentFixture(script.gateway)
        val w = seedNovelWorld(f)

        val result = f.app.novelAgent.run(
            request(
                w,
                "检查人物一致性",
                preferredSkillId = CoreSkills.CHARACTER_CONSISTENCY.skillId,
            ),
        )

        assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
        assertEquals(CoreSkills.CHARACTER_CONSISTENCY.skillId, result.skillId)
        val consistency = assertNotNull(result.characterConsistency)
        assertEquals("plan missing", consistency.summary, "summary 必须等于 Validator 通过的原始 summary")
        assertTrue(consistency.summary.isNotBlank())
        assertTrue(result.summary.contains("skill.character-consistency"))
        f.close()
    }

    private companion object {
        const val INSUFFICIENT_PLAN =
            """{"status":"INSUFFICIENT_EVIDENCE","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":["PLAN"],"summary":"plan missing"}"""
    }
}
