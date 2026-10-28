package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.agentTaskToast
import com.qianyan.model.IntentType
import com.qianyan.model.ProjectId
import com.qianyan.model.novelagent.NovelAgentFailure
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentResult
import com.qianyan.model.novelagent.NovelIntentAnalysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 新执行链路 · 桌面 NL 任务结果 → 用户可见文案（internal [agentTaskToast]，Common.kt）。
 * 五种结局必须可区分：完成摘要透出；已进入正式审批；候选未桥接；失败带类型化 detail；取消明说。
 */
class AgentTaskToastTest {

    private fun result(
        outcome: NovelAgentOutcome,
        summary: String,
        failure: NovelAgentFailure? = null,
    ): NovelAgentResult = NovelAgentResult(
        outcome = outcome,
        projectId = ProjectId("p-1"),
        intent = NovelIntentAnalysis(IntentType.CONTINUE, "继续", "测试"),
        summary = summary,
        failure = failure,
    )

    @Test
    fun `completed outcome surfaces the orchestration summary as is`() {
        assertEquals(
            "已完成 skill.story-planning：规划就绪",
            agentTaskToast(result(NovelAgentOutcome.COMPLETED, "已完成 skill.story-planning：规划就绪")),
        )
    }

    @Test
    fun `waiting human outcome points to the formal chapter approval`() {
        val msg = agentTaskToast(result(NovelAgentOutcome.WAITING_HUMAN, "变更已就绪，等待人工确认"))
        assertTrue(msg.contains("正式审批"), msg)
        assertTrue(msg.contains("待确认"), msg)
        assertTrue(msg.contains("正文"), msg)
    }

    @Test
    fun `unbridged candidate is not presented as pending approval`() {
        val msg = agentTaskToast(
            result(
                NovelAgentOutcome.CANDIDATE_UNBRIDGED,
                "候选未进入正式审批",
                NovelAgentFailure("BRIDGE_FAILED", "章节已存在待确认的正式审批"),
            ),
        )
        assertTrue(msg.contains("未进入正式审批"), msg)
        assertTrue(msg.contains("章节已存在待确认的正式审批"), msg)
        assertTrue(!msg.contains("已进入正式审批——"), "未桥接不得伪装成待审批：$msg")
    }

    @Test
    fun `failed outcome carries the typed failure detail`() {
        val msg = agentTaskToast(
            result(
                NovelAgentOutcome.FAILED,
                "运行中止（PROJECT_NOT_FOUND）",
                NovelAgentFailure("PROJECT_NOT_FOUND", "Project 不存在"),
            ),
        )
        assertTrue(msg.contains("任务失败"), msg)
        assertTrue(msg.contains("Project 不存在"), msg)
    }

    @Test
    fun `cancelled outcome is named as cancelled`() {
        assertEquals("任务已取消", agentTaskToast(result(NovelAgentOutcome.CANCELLED, "运行被取消")))
    }
}
