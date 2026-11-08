package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T-O73-F2 · Planner 纠正重试（P1）的有界性。
 *
 * 规则：仅对 [ApplicationError.InvalidPlanningOutput] 重试 **1 次**（总计 ≤2 次调用），
 * 第二次输入追加「【修正】」指令；仍失败 → 原类型上抛。
 */
class PlannerCorrectionRetryTest {

    private class ScriptedGateway(private val replies: List<String>) : LLMGateway {
        var calls = 0
        val userInputs = mutableListOf<String>()

        override fun chat(request: ProviderRequest): ProviderResponse {
            userInputs += request.messages.last { it.role == ChatRole.USER }.content
            val content = replies[minOf(calls, replies.lastIndex)]
            calls += 1
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, content),
                usage = Usage(5, 5, 10),
                finishReason = FinishReason.STOP,
            )
        }
    }

    private fun answer(planJson: String): String =
        buildJsonObject { put("answer", planJson) }.toString()

    private fun validPlanJson(): String =
        """{"chapterGoal":"突破到筑基期并结识道友","mainConflict":"conf-c","expectedEvents":["闭关","遇险"]}"""

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-retry"),
        intentType = IntentType.PLAN,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    private fun container(gateway: LLMGateway): ApplicationContainer =
        ApplicationContainer.open(analysisGateway = gateway)

    /** 第一次非法、第二次合法 → 成功，且恰好 2 次调用，第二次输入带修正指令。 */
    @Test
    fun `invalid then valid plan retries once and succeeds`() {
        val gateway = ScriptedGateway(listOf(answer("这不是 JSON"), answer(validPlanJson())))
        val app = container(gateway)
        val novelId = app.novels.createOriginal(title = "重试成功")
        val taskId = app.tasks.create(TaskType.PLANNING)

        val plan = app.taskRunner.executePlanning(taskId, request(novelId))

        assertEquals("突破到筑基期并结识道友", plan.chapterGoal)
        assertEquals(2, gateway.calls, "首次失败 + 一次纠正重试 = 恰好 2 次调用")
        assertTrue(gateway.userInputs[1].contains("【修正】"), "第二次输入必须带【修正】指令")
        assertTrue(gateway.userInputs[1].contains("顶层 chapterGoal"), "修正指令必须要求顶层 chapterGoal")
        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(taskId).status)
    }

    /** 前两次都非法、第三次合法 → 成功，且恰 3 次调用（第 2 次重试生效）。 */
    @Test
    fun `invalid twice then valid succeeds on the second retry`() {
        val gateway = ScriptedGateway(
            listOf(answer("这不是 JSON"), answer("仍然不是 JSON"), answer(validPlanJson())),
        )
        val app = container(gateway)
        val novelId = app.novels.createOriginal(title = "二次重试成功")
        val taskId = app.tasks.create(TaskType.PLANNING)

        val plan = app.taskRunner.executePlanning(taskId, request(novelId))

        assertEquals("突破到筑基期并结识道友", plan.chapterGoal)
        assertEquals(3, gateway.calls, "1 次首次 + 2 次纠正重试 = 恰好 3 次调用")
        assertTrue(gateway.userInputs[2].contains("【修正】"), "第三次输入仍必须带【修正】指令")
        assertEquals(TaskStatus.COMPLETED, app.tasks.findById(taskId).status)
    }

    /** 三次都非法 → 原类型错误上抛，且恰好 3 次调用（绝不出现第 4 次）。 */
    @Test
    fun `invalid three times fails with original error and never calls a fourth time`() {
        val gateway = ScriptedGateway(
            listOf(answer("这不是 JSON"), answer("仍然不是 JSON"), answer("第三次仍不是 JSON")),
        )
        val app = container(gateway)
        val novelId = app.novels.createOriginal(title = "重试失败")
        val taskId = app.tasks.create(TaskType.PLANNING)

        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executePlanning(taskId, request(novelId))
        }

        assertIs<ApplicationError.InvalidPlanningOutput>(ex.error, "必须保持原类型化错误")
        assertEquals(3, gateway.calls, "重试必须有界：总尝试 ≤3，不得出现第 4 次调用")
        assertEquals(TaskStatus.FAILED, app.tasks.findById(taskId).status)
    }
}
