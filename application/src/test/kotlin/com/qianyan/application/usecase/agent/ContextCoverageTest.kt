package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * O-30（A-33 D4/D5/D6）：Skill 声明来源的覆盖情况进 `NovelAgentResult.contextCoverage`。
 *
 * 语义：
 *  - Pack Skill：present/absent 来自冻结的 `pack.items`，unverified 恒空；
 *  - 非 Pack Skill：只对 DRAFT / VOCABULARY 做真实判定，其余声明来源进 unverified（不得谎报缺失）；
 *  - 新章无 DRAFT / 无 Foundation / 空词库只影响 coverage，不改变任务结局。
 */
class ContextCoverageTest {

    @Test
    fun `writing coverage reflects pack items`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)

            val result = f.app.novelAgent.run(request(w, "继续写第一章"))

            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            val coverage = assertNotNull(result.contextCoverage, "Pack Skill 必须携带 contextCoverage")
            val declared = CoreSkills.WRITING.requiredContext.distinct().sortedBy { it.ordinal }
            assertEquals(declared, coverage.declared)
            // seed 世界：NOVEL / PROJECT_STATE / CHAPTER / DRAFT 有候选；无已确认 Foundation、无词条。
            listOf(
                ContextSourceKind.NOVEL,
                ContextSourceKind.DRAFT,
                ContextSourceKind.CHAPTER,
            ).forEach { assertTrue(it in coverage.present, "present 缺少 $it：${coverage.present}") }
            assertEquals(
                listOf(ContextSourceKind.STORY_FOUNDATION, ContextSourceKind.VOCABULARY),
                coverage.absent,
            )
            assertTrue(coverage.unverified.isEmpty(), "Pack Skill 的 unverified 必须为空：${coverage.unverified}")
            assertEquals(0, coverage.omittedItemCount)
        } finally {
            f.close()
        }
    }

    @Test
    fun `story planning without foundation reports it as absent but still completes`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)

            val result = f.app.novelAgent.run(request(w, "规划第一章"))

            assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
            val coverage = assertNotNull(result.contextCoverage)
            assertEquals(
                CoreSkills.STORY_PLANNING.requiredContext.distinct().sortedBy { it.ordinal },
                coverage.declared,
            )
            assertTrue(
                ContextSourceKind.STORY_FOUNDATION in coverage.absent,
                "无已确认 Foundation 时必须出现在 absent：${coverage.absent}",
            )
            assertTrue(coverage.unverified.isEmpty(), "Pack Skill 的 unverified 必须为空")
        } finally {
            f.close()
        }
    }

    @Test
    fun `tiny budget writing reports missing novel and keeps coverage on failure`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)
            val budget = ContextBudget(maxTokens = 1)

            val result = f.app.novelAgent.run(request(w, "继续写第一章", budget = budget))

            assertEquals(NovelAgentOutcome.FAILED, result.outcome)
            assertEquals(NovelAgentErrorCodes.MISSING_CONTEXT_INPUT, result.failure?.code)
            val coverage = assertNotNull(result.contextCoverage, "失败路径只要 Pack 已构建就必须带 coverage")
            assertTrue(
                ContextSourceKind.NOVEL in coverage.absent,
                "极小预算淘汰 NOVEL 后必须进 absent：${coverage.absent}",
            )
            assertTrue(coverage.omittedItemCount > 0, "预算淘汰数必须可见：${coverage.omittedItemCount}")
        } finally {
            f.close()
        }
    }

    @Test
    fun `rewrite with empty vocabulary separates absent from unverified`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)

            val result = f.app.novelAgent.run(request(w, "重写第一章"))

            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            val coverage = assertNotNull(result.contextCoverage, "非 Pack Skill 也必须携带 contextCoverage")
            assertEquals(
                CoreSkills.REWRITE_REVISION.requiredContext.distinct().sortedBy { it.ordinal },
                coverage.declared,
            )
            assertEquals(listOf(ContextSourceKind.DRAFT), coverage.present)
            assertEquals(listOf(ContextSourceKind.VOCABULARY), coverage.absent)
            assertEquals(
                listOf(ContextSourceKind.PROJECT_STATE, ContextSourceKind.CHAPTER),
                coverage.unverified,
                "未检查来源不得报成 absent：${coverage.unverified}",
            )
            assertEquals(0, coverage.omittedItemCount)
        } finally {
            f.close()
        }
    }
}
