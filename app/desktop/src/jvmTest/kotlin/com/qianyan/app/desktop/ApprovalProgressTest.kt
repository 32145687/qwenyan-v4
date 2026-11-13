package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.approvalProgressLabel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-O51-F（O-108 / 方案 D）· 审批进行态可见化 + Main 线程 DB 收尾。
 *
 * 28 号诊断：批准调用内联 KNOWLEDGE_UPDATE 同步 LLM（实测 T1 中位数 64.2s），期间界面只有按钮被禁用，
 * 没有任何阶段文案。本用例锁定两件事：
 *   (a) 进行态文案的秒/分档位；
 *   (b) `readContext` / `compensateApprovedWorkflow` 已改为 suspend + `withContext(ioDispatcher)`，
 *       且 WritePage 在 `ui.isApproving` 时渲染该文案（源码守卫）。
 */
class ApprovalProgressTest {

    /** (a) 0–59 秒：直接显示秒。 */
    @Test
    fun `label under one minute shows seconds`() {
        assertEquals("已通过 · 正在更新故事状态（0s）…", approvalProgressLabel(0))
        assertEquals("已通过 · 正在更新故事状态（5s）…", approvalProgressLabel(5))
        assertEquals("已通过 · 正在更新故事状态（59s）…", approvalProgressLabel(59))
    }

    /** (a) ≥60 秒：显示 m+s。 */
    @Test
    fun `label from one minute shows minutes and seconds`() {
        assertEquals("已通过 · 正在更新故事状态（1m0s）…", approvalProgressLabel(60))
        assertEquals("已通过 · 正在更新故事状态（2m5s）…", approvalProgressLabel(125))
    }

    /** (b) 源码守卫：控制器不再在 Main 线程做同步 DB。 */
    @Test
    fun `writer controller reads context off the main thread`() {
        val src = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/write/WriterController.kt").readText()

        assertTrue(
            "private suspend fun readContext(" in src,
            "readContext 必须声明为 suspend（调用点在协程内）",
        )
        val readBody = src.substringAfter("private suspend fun readContext(").take(900)
        assertTrue(
            "withContext(ioDispatcher)" in readBody,
            "readContext 内的 loadContext / getChapterProgress 必须切到 ioDispatcher",
        )
        assertTrue("gateway.loadContext(" in readBody, "readContext 仍读取章节上下文")
        assertTrue("workflow.getChapterProgress(" in readBody, "readContext 仍读取进度投影（去重需证据，见报告）")

        assertTrue(
            "private suspend fun compensateApprovedWorkflow()" in src,
            "compensateApprovedWorkflow 必须声明为 suspend",
        )
        val compensateBody = src.substringAfter("private suspend fun compensateApprovedWorkflow()").take(600)
        assertTrue(
            "withContext(ioDispatcher)" in compensateBody,
            "compensateApprovedWorkflow 的进度查询 / 补偿 approve 必须切到 ioDispatcher",
        )
    }

    /** (b) 源码守卫：WritePage 在批准进行中渲染阶段文案与递增秒数。 */
    @Test
    fun `write page shows approval progress label while approving`() {
        val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt").readText()

        assertTrue("approvalProgressLabel(approvalSeconds)" in page, "动作区必须渲染阶段文案")
        assertTrue("LaunchedEffect(ui.isApproving)" in page, "秒数必须由 ui.isApproving 驱动的本地状态维护")
        assertTrue("if (ui.isApproving)" in page, "进行态分支必须存在（优先于通过/拒绝按钮）")
        assertTrue("import com.qianyan.app.desktop.ui.write.approvalProgressLabel" in page, "必须导入文案函数")
    }
}
