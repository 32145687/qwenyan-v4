package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.context.defaultContextSources
import com.qianyan.model.AgentId
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.skill.SkillBinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 · Skill 契约字段的生产读取。
 *
 * 验证 `allowedTools / requiredContext / expectedActions / bindings` 会真正影响执行前校验，
 * 而不是仅存在于数据类中。
 */
class SkillContractConsumptionTest {

    @Test
    fun `executable core skills pass contract validation with their real agents`() {
        val f = novelAgentFixture()
        val guard = SkillExecutionGuard(f.app.productTools, f.app.actionPolicy, f.app.contextEngine)

        val executable = mapOf(
            CoreSkills.STORY_PLANNING to f.app.planner.agentId,
            CoreSkills.WRITING to f.app.writer.agentId,
            CoreSkills.REWRITE_REVISION to f.app.rewriter.agentId,
            CoreSkills.ANALYSIS_CRITIQUE to f.app.critic.agentId,
        )
        executable.forEach { (skill, agentId) ->
            assertEquals(emptyList(), guard.violations(skill, agentId), "Skill ${skill.skillId.value} 应通过契约校验")
        }
        f.close()
    }

    @Test
    fun `unregistered allowed tool is rejected`() {
        val f = novelAgentFixture()
        val guard = SkillExecutionGuard(f.app.productTools, f.app.actionPolicy, f.app.contextEngine)
        val bad = CoreSkills.WRITING.copy(
            allowedTools = CoreSkills.WRITING.allowedTools + ToolName("ghost_tool"),
        )

        assertTrue(guard.violations(bad, f.app.writer.agentId).any { "allowedTools" in it })
        f.close()
    }

    @Test
    fun `required context source must be configured`() {
        val f = novelAgentFixture()
        val engine = ContextEngineUseCases(
            projects = f.app.projects,
            chapters = f.app.chapters,
            sessions = f.app.agentSessions,
            sources = defaultContextSources(
                projects = f.app.projects,
                novels = f.app.novels,
                chapters = f.app.chapters,
                writer = f.app.writerUseCases,
                vocabularyRepository = f.app.vocabularyRepository,
                foundations = f.app.storyFoundationRepository,
            ).filter { it.kind != ContextSourceKind.STORY_FOUNDATION },
            errorMapper = f.app.errorMapper,
        )
        val guard = SkillExecutionGuard(f.app.productTools, f.app.actionPolicy, engine)

        val violations = guard.violations(CoreSkills.WRITING, f.app.writer.agentId)
        assertTrue(violations.any { "requiredContext" in it && "STORY_FOUNDATION" in it }, violations.toString())
        f.close()
    }

    @Test
    fun `expected actions deny and needs human are rejected`() {
        val f = novelAgentFixture()
        val guard = SkillExecutionGuard(f.app.productTools, f.app.actionPolicy, f.app.contextEngine)

        val denied = CoreSkills.WRITING.copy(expectedActions = listOf(AgentActionKind.DELETE))
        val needsHuman = CoreSkills.WRITING.copy(expectedActions = listOf(AgentActionKind.UPDATE_WORLD_MODEL))
        assertTrue(guard.violations(denied, f.app.writer.agentId).any { "被拒绝动作" in it })
        assertTrue(guard.violations(needsHuman, f.app.writer.agentId).any { "需人工确认" in it })
        f.close()
    }

    @Test
    fun `bindings must be non empty and contain the real execution agent`() {
        val f = novelAgentFixture()
        val guard = SkillExecutionGuard(f.app.productTools, f.app.actionPolicy, f.app.contextEngine)

        assertTrue(guard.violations(CoreSkills.WRITING.copy(bindings = emptyList()), f.app.writer.agentId).any { "bindings" in it })
        val wrong = CoreSkills.WRITING.copy(bindings = listOf(SkillBinding(AgentId("other-agent"))))
        assertTrue(guard.violations(wrong, f.app.writer.agentId).any { "实际执行 Agent" in it })
        f.close()
    }

    @Test
    fun `malformed skill fails in the real NovelAgent execution path`() {
        val f = novelAgentFixture()
        val w = seedNovelWorld(f)
        val bad = CoreSkills.WRITING.copy(
            allowedTools = CoreSkills.WRITING.allowedTools + ToolName("ghost_tool"),
        )
        val agent = novelAgentWithRegistry(f, SkillRegistry.of(listOf(bad)))

        val result = agent.run(request(w, "继续写第一章"))

        assertEquals(NovelAgentOutcome.FAILED, result.outcome)
        assertEquals(NovelAgentErrorCodes.SKILL_NOT_EXECUTABLE, result.failure?.code)
        assertTrue(result.failure?.detail.orEmpty().contains("allowedTools"), result.failure?.detail.orEmpty())
        f.close()
    }
}
