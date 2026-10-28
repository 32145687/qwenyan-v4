package com.qianyan.application.usecase.agent

import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentPostRevisionFailureCodes
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-43 · 续步后对修订稿再评审一次（补齐 D-36 的“再评审”半句）。
 *
 * 有界：critique ≤ 2（初次 + 复评）、revision ≤ 1；复评仍 ERROR 不再修订；
 * 复评失败不升级为任务失败，但写入 `postRevisionCheckFailure`。
 */
class PostRevisionReviewTest {

    private val userIntent = "分析第一章"
    private val revisionContent = "第一章修订正文（来自 Revision）。"

    /** 按调用顺序返回 critique 结果，并记录 critic / revision 次数。 */
    private class Scripted(val critiqueBodies: List<String>, val revisionContent: String) {
        val records: MutableList<ProviderRequest> = mutableListOf()
        var criticCalls = 0
        var revisionCalls = 0

        val gateway: MockLLMGateway = MockLLMGateway { request ->
            records += request
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryCriticAgent" in system -> {
                    val index = criticCalls
                    criticCalls += 1
                    critiqueBodies.getOrElse(index) { critiqueBodies.last() }
                }

                "StoryRevisionAgent" in system -> {
                    revisionCalls += 1
                    buildJsonObject { put("content", revisionContent) }.toString()
                }

                else -> buildJsonObject { put("content", "占位") }.toString()
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    private fun run(scripted: Scripted, assertions: (NovelAgentFixture, NovelWorld, com.qianyan.model.novelagent.NovelAgentResult) -> Unit) {
        val f = novelAgentFixture(scripted.gateway)
        try {
            val w = seedNovelWorld(f)
            val result = f.app.novelAgent.run(request(w, userIntent))
            assertions(f, w, result)
        } finally {
            f.close()
        }
    }

    /* ====================================================================
     * V-1 无续步（PASS）：critique 恰 1 次；复评字段全为 null
     * ==================================================================== */
    @Test
    fun `v1 pass ends with exactly one critique and no post revision fields`() {
        val s = Scripted(listOf(PASS_JSON), revisionContent)
        run(s) { _, _, result ->
            assertEquals(NovelAgentOutcome.COMPLETED, result.outcome)
            assertEquals(0, assertNotNull(result.analysis).errorCount)
            assertNull(result.postRevisionAnalysis)
            assertNull(result.postRevisionCheckFailure)
            assertEquals(1, s.criticCalls, "无续步时只允许初次 critique")
            assertEquals(0, s.revisionCalls, "无续步时不得 Revision")
        }
    }

    /* ====================================================================
     * V-2 续步后复评通过：初始 ERROR=1 → 复评 PASS；critique=2、revision=1
     * ==================================================================== */
    @Test
    fun `v2 post revision pass is recorded separately and task still waits for human`() {
        val s = Scripted(listOf(ERROR_JSON, PASS_JSON), revisionContent)
        run(s) { f, w, result ->
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertEquals(NovelAgentApprovalState.BRIDGED, result.approvalState)

            val initial = assertNotNull(result.analysis)
            assertEquals(1, initial.errorCount)
            assertFalse(initial.passed)

            val post = assertNotNull(result.postRevisionAnalysis, "续步后必须记录复评结论")
            assertTrue(post.passed)
            assertEquals(0, post.errorCount)
            assertNull(result.postRevisionCheckFailure)

            assertEquals(2, s.criticCalls, "critique 恰好 2 次（初次 + 复评）")
            assertEquals(1, s.revisionCalls, "revision 恰好 1 次")

            val workingDraftId = assertNotNull(result.workingDraftId)
            assertEquals(revisionContent, f.app.workingDrafts.get(workingDraftId).content)
            val gateId = assertNotNull(
                f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId)?.pendingGateId,
                "复评后仍须进入正式人工 Gate",
            )
            assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(gateId)?.status)
        }
    }

    /* ====================================================================
     * V-3 复评仍 ERROR：如实记录，不再触发第三次 critique / 第二次 revision
     * ==================================================================== */
    @Test
    fun `v3 post revision still error stays bounded and does not loop`() {
        val s = Scripted(listOf(ERROR_JSON, ERROR2_JSON), revisionContent)
        run(s) { f, w, result ->
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, result.outcome)
            assertEquals(1, assertNotNull(result.analysis).errorCount)

            val post = assertNotNull(result.postRevisionAnalysis, "复评仍 ERROR 也必须记录")
            assertFalse(post.passed)
            assertTrue(post.errorCount > 0, "未解决 ERROR 必须如实显示")
            assertEquals(2, post.errorCount)
            assertNull(result.postRevisionCheckFailure)

            assertEquals(2, s.criticCalls, "不得出现第 3 次 critique")
            assertEquals(1, s.revisionCalls, "不得出现第 2 次 revision")
            assertEquals(0, f.app.commits.history(w.projectId).size, "续步不得自动提交")
            assertNotNull(result.artifactId)
        }
    }

    /* ====================================================================
     * V-4 复评失败：任务仍产出（WAITING_HUMAN），显式 INVALID_OUTPUT
     * ==================================================================== */
    @Test
    fun `v4 post revision invalid output is marked without failing the task`() {
        val s = Scripted(listOf(ERROR_JSON, "这不是评审 JSON"), revisionContent)
        run(s) { f, w, result ->
            assertEquals(
                NovelAgentOutcome.WAITING_HUMAN,
                result.outcome,
                "复评失败不得让已产出正文的任务失败",
            )
            assertNull(result.postRevisionAnalysis)
            assertEquals(NovelAgentPostRevisionFailureCodes.INVALID_OUTPUT, result.postRevisionCheckFailure)

            assertEquals(2, s.criticCalls, "失败复评也只调用 2 次 critique")
            assertEquals(1, s.revisionCalls)
            assertNotNull(result.workingDraftId)
            assertNotNull(result.artifactId)
            val gateId = assertNotNull(
                f.app.workflowRepository.getWorkflowByActiveChapter(w.chapterId)?.pendingGateId,
                "复评失败仍须进入人工 Gate",
            )
            assertEquals(HumanGateStatus.PENDING, f.app.workflowRepository.getGate(gateId)?.status)
        }
    }

    private companion object {
        const val PASS_JSON: String = """{"passed":true,"issues":[]}"""

        const val ERROR_JSON: String =
            """{"passed":false,"issues":[{"field":"正文","severity":"ERROR","message":"需要修改"}]}"""

        const val ERROR2_JSON: String =
            """{"passed":false,"issues":[""" +
                """{"field":"结构","severity":"ERROR","message":"仍未解决"},""" +
                """{"field":"语言","severity":"ERROR","message":"仍需修改"}]}"""
    }
}
