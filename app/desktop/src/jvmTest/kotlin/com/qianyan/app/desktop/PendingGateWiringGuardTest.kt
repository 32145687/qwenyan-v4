package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * O-50 桌面守卫（源码级）：状态栏与 Inspector「待审批」都必须读持久化 PENDING Gate
 * （经 Application 层 Workspace 能力，不直连 Repository），不再以内存 ChangeArtifact 为待确认来源。
 */
class PendingGateWiringGuardTest {

    private val main = File("src/jvmMain/kotlin/com/qianyan/app/desktop/Main.kt").readText()
    private val inspector =
        File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/workspace/ContextInspector.kt").readText()
    private val writer =
        File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/write/WriterController.kt").readText()
    private val writePage =
        File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt").readText()

    @Test
    fun `status bar pending count comes from persisted gates`() {
        assertTrue(
            "workspace.pendingGateCount(pid)" in main,
            "状态栏「待确认」必须取自持久化 PENDING Gate 计数（经 Workspace 能力）",
        )
        assertFalse(
            "changes.list(pid).size" in main,
            "状态栏不得再以内存 ChangeArtifact 数量作为待确认",
        )
    }

    @Test
    fun `inspector pending section reads persisted gates and labels memory changes`() {
        assertTrue("view.pendingGateCount" in inspector, "Inspector「待审批」必须读持久化 Gate 计数")
        assertTrue("view.pendingGates" in inspector, "Inspector 必须读持久化 Gate 明细（与状态栏同源）")
        assertTrue("共 \$count 项" in inspector, "Inspector 必须显示待审批数量")
        assertTrue("本会话提案（内存，重启清空）" in inspector, "内存 Change 列表必须标注重启清空")
        assertTrue("chapterLabel" in inspector && "phaseLabel" in inspector, "Gate 条目必须能看出章节/阶段")
        assertFalse("workflowRepository" in inspector, "桌面 UI 不得直连 Repository（分层守卫）")
    }

    @Test
    fun `gate decision refreshes the pending count`() {
        assertTrue("onDecision()" in writer, "Gate 决议成功后必须触发刷新回调")
        assertTrue(
            "onDecision = { state.refreshKey++ }" in writePage,
            "写页必须把刷新回调接到 refreshKey（状态栏 / Inspector 同步更新）",
        )
    }
}
