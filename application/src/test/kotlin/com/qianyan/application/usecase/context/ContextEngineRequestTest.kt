package com.qianyan.application.usecase.context

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.AgentSessionId
import com.qianyan.model.IntentType
import com.qianyan.model.ProjectId
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.context.ContextRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * I6 · ContextRequest 合法性测试（§26 ContextRequest）。
 *
 * 覆盖：合法请求、非法 project、非法 budget、非法候选边界、未知尺寸估算器、未知 session。
 */
class ContextEngineRequestTest {

    @Test
    fun `valid request builds a pack for the requested project`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        val pack = f.app.contextEngine.build(
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2),
        )

        assertEquals(w.projectId, pack.request.projectId)
        assertEquals(IntentType.CONTINUE, pack.request.purpose)
        assertEquals(w.novelId, pack.novelId)
        assertEquals(w.chapter2, pack.request.focusChapterId)
        assertTrue(pack.packId.startsWith("ctx-${w.projectId.value}-continue-"), "packId 确定性派生：${pack.packId}")
        f.close()
    }

    @Test
    fun `unknown project is rejected`() {
        val f = contextFixture()

        val ex = assertFailsWith<ApplicationException> {
            f.app.contextEngine.build(ContextRequest(projectId = ProjectId("p-ghost"), purpose = IntentType.CONTINUE))
        }

        assertTrue(ex.error is ApplicationError.EntityNotFound, "非法 project → NOT_FOUND")
        f.close()
    }

    @Test
    fun `non positive budget is rejected`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        listOf(0L, -1L).forEach { bad ->
            val ex = assertFailsWith<ApplicationException>("budget=$bad 必须被拒绝") {
                f.app.contextEngine.build(
                    ContextRequest(
                        projectId = w.projectId,
                        purpose = IntentType.CONTINUE,
                        budget = ContextBudget(maxTokens = bad),
                    ),
                )
            }
            assertTrue(ex.error is ApplicationError.InvalidOperation, "非法 budget($bad) → INVALID_INPUT")
        }
        f.close()
    }

    @Test
    fun `non positive candidate horizon is rejected`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        val ex = assertFailsWith<ApplicationException> {
            f.app.contextEngine.build(
                ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, candidateHorizon = 0),
            )
        }

        assertTrue(ex.error is ApplicationError.InvalidOperation)
        f.close()
    }

    @Test
    fun `unknown size estimator is rejected`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        val ex = assertFailsWith<ApplicationException> {
            f.app.contextEngine.build(
                ContextRequest(
                    projectId = w.projectId,
                    purpose = IntentType.CONTINUE,
                    budget = ContextBudget(estimator = "mimo-tokenizer"),
                ),
            )
        }

        assertTrue(ex.error is ApplicationError.InvalidOperation, "未知估算器不静默降级")
        f.close()
    }

    @Test
    fun `unknown session is rejected`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        val ex = assertFailsWith<ApplicationException> {
            f.app.contextEngine.build(
                ContextRequest(
                    projectId = w.projectId,
                    purpose = IntentType.CONTINUE,
                    sessionId = AgentSessionId("s-ghost"),
                ),
            )
        }

        assertTrue(ex.error is ApplicationError.EntityNotFound)
        f.close()
    }
}