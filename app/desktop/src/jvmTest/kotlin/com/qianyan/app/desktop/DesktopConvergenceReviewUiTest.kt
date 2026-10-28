package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V-5/V-6 · Desktop 最小 caller 与四态文案的源码结构断言。
 *
 * 选择结构断言而非 Compose 行为测试：本仓 Desktop UI 测试没有可复用的 Compose 点击/组合测试夹具，
 * 现有 UI 契约测试同样以源码结构防回归；本次验证"入口存在且由 commitId 门控"，
 * 以及"摘要读 T-8-E 显式字段、四种 outcome 文案可区分、失败态不冒充一致"。
 */
class DesktopConvergenceReviewUiTest {

    @Test
    fun `convergence review action exists and is gated by commit id`() {
        val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        val agent = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt").readText()

        assertTrue("fun launchConvergenceReview()" in common, "Common 必须提供显式收敛检查动作")
        val actionBody = common.substringAfter("fun launchConvergenceReview()")
            .substringBefore("\n    /**")
        assertTrue("result.commitId ?: return" in actionBody || "commitId ?: return" in actionBody, "动作必须由 commitId 门控")
        assertTrue("convergenceReview.review" in actionBody, "动作必须调用收敛评审用例")
        assertTrue("lastConvergenceReview" in common, "必须保留结构化复审摘要")

        assertTrue("val commitId = result.commitId" in agent, "任务卡必须读取结果中的 commitId")
        assertTrue("commitId != null" in agent, "任务卡必须仅在 commitId 非空时展示收敛按钮")
        assertTrue("收敛检查" in agent, "任务卡必须提供收敛检查入口")
        assertTrue("launchConvergenceReview" in agent, "任务卡必须调用收敛动作")
        assertTrue("lastConvergenceReview" in agent, "任务卡必须呈现复审摘要")
    }

    @Test
    fun `convergence summary reads explicit outcome fields with four distinct labels`() {
        val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        val agent = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt").readText()

        // 四态文案集中在一处，UI 只消费 outcome，不再从嵌套 result 里猜。
        assertTrue("ConvergenceOutcome.CONSISTENT -> \"人物一致（无问题）\"" in common, "缺少 CONSISTENT 文案")
        assertTrue("ConvergenceOutcome.ISSUES_FOUND -> \"发现人物一致性问题\"" in common, "缺少 ISSUES_FOUND 文案")
        assertTrue("ConvergenceOutcome.INSUFFICIENT_EVIDENCE -> \"证据不足，无法完成检查\"" in common, "缺少 INSUFFICIENT_EVIDENCE 文案")
        assertTrue("ConvergenceOutcome.REVIEW_FAILED -> \"复审失败，未完成检查\"" in common, "缺少 REVIEW_FAILED 文案")
        assertFalse("ConvergenceOutcome.REVIEW_FAILED -> \"人物一致" in common, "失败态不得与 PASS 共用文案")

        assertTrue("convergenceOutcomeLabel(review.outcome)" in agent, "任务卡必须按显式 outcome 渲染")
        assertTrue("review.summary" in agent, "任务卡必须直接读扁平化 summary")
        assertTrue("review.errorCount" in agent && "review.warningCount" in agent, "任务卡必须展示扁平化计数")
        assertFalse("review.result.characterConsistency" in agent, "摘要不得再钻嵌套 result")
        assertFalse("review.result.characterConsistency" in common, "toast 不得再钻嵌套 result")
    }
}
