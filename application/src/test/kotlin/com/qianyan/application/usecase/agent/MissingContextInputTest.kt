package com.qianyan.application.usecase.agent

import com.qianyan.model.IntentType
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O-34（A-33 D1/D2）：空 Pack / 缺 NOVEL payload 的错误语义。
 *
 * 语义：Skill 声明需要 NOVEL 来源但本次 Pack 未包含时，报类型化 `MISSING_CONTEXT_INPUT`，
 * 不得落成 `EXECUTION_FAILED`（"缺输入"≠"执行失败"）；非 Pack Skill 与正常 Pack 不受影响。
 */
class MissingContextInputTest {

    @Test
    fun `empty pack for writing reports missing context input`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)
            val budget = tinyBudget(f, w.projectId, w.chapterId, IntentType.CONTINUE)

            val result = f.app.novelAgent.run(request(w, "继续写第一章", budget = budget))

            assertEquals(NovelAgentOutcome.FAILED, result.outcome)
            assertEquals(NovelAgentErrorCodes.MISSING_CONTEXT_INPUT, result.failure?.code)
        } finally {
            f.close()
        }
    }

    @Test
    fun `empty pack for story planning reports missing context input`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)
            val budget = tinyBudget(f, w.projectId, w.chapterId, IntentType.PLAN)

            val result = f.app.novelAgent.run(request(w, "规划第一章", budget = budget))

            assertEquals(NovelAgentOutcome.FAILED, result.outcome)
            assertEquals(NovelAgentErrorCodes.MISSING_CONTEXT_INPUT, result.failure?.code)
        } finally {
            f.close()
        }
    }

    @Test
    fun `normal pack keeps existing behavior`() {
        val f = novelAgentFixture()
        try {
            val w = seedNovelWorld(f)

            val result = f.app.novelAgent.run(request(w, "继续写第一章"))

            assertNull(result.failure)
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
        } finally {
            f.close()
        }
    }

    /**
     * 极小预算：关键来源（含 NOVEL）也会按预算淘汰（ContextSelector 阶段 1）。
     * 断言前置条件成立，避免"以为空 Pack、实际含 NOVEL"的假绿。
     */
    private fun tinyBudget(
        f: NovelAgentFixture,
        projectId: com.qianyan.model.ProjectId,
        chapterId: com.qianyan.model.ChapterId,
        purpose: IntentType,
    ): ContextBudget {
        val budget = ContextBudget(maxTokens = 1)
        val pack = f.app.contextEngine.build(
            ContextRequest(
                projectId = projectId,
                purpose = purpose,
                focusChapterId = chapterId,
                budget = budget,
            ),
        )
        assertTrue(
            pack.items.none { it.source == ContextSourceKind.NOVEL },
            "前置：极小预算应淘汰 NOVEL 条目，实际 items=${pack.items.map { it.source }}",
        )
        return budget
    }
}
