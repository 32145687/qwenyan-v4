package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.DesktopOfflineLlmGateway
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.storage.db.QianyanDbFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P0-3B · Desktop UI 契约守卫（§17.2）。
 *
 * Compose 渲染无法在无 GUI 的 JVM 测试中驱动，因此沿用仓库既有的“读源码断言接线”范式：
 *  - AgentPage 必须传章节上下文、保留候选/会话身份；
 *  - 正式批准 / 拒绝唯一入口仍在 WritePage；
 *  - AgentPage 只提供重试桥接 / 刷新状态 / 丢弃未桥接候选，不调用 resume/approve。
 */
class DesktopAgentApprovalUiTest {

    private val common = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt")
    private val agentPage = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/AgentPage.kt")
    private val writePage = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/WritePage.kt")

    @Test
    fun `agent task passes the focused chapter and keeps candidate identity`() {
        assertTrue(common.isFile, "找不到 Common.kt: ${common.absolutePath}")
        val code = common.readText()

        assertTrue("activeChapterId = chapterId" in code, "Agent 任务必须携带焦点章节")
        assertTrue(
            "lastAgentRequest = result.sessionId?.let { request.copy(sessionId = it) } ?: request" in code,
            "必须保留请求并把返回的 sessionId 写回 lastAgentRequest",
        )
        assertTrue("lastAgentResult = result" in code, "必须保留 artifactId / workingDraftId / sessionId")
        assertTrue("bridgeCandidate(request, artifactId)" in code, "失败候选必须支持显式重试桥接")
        assertTrue("refreshCandidate(request, artifactId)" in code, "正式决议后必须支持状态收敛")
        assertTrue("NovelAgentOutcome.CANDIDATE_UNBRIDGED" in code, "未桥接必须是独立可见结局")
        assertFalse("novelAgent.resume(" in code, "Desktop 不得经 resume 完成正式提交")
    }

    @Test
    fun `agent page coordinates but never performs formal approval`() {
        assertTrue(agentPage.isFile, "找不到 AgentPage.kt: ${agentPage.absolutePath}")
        val code = agentPage.readText()

        assertTrue("state.retryAgentBridge()" in code, "未桥接候选应提供显式重试")
        assertTrue("state.refreshAgentCandidate()" in code, "已桥接候选应提供状态刷新")
        assertTrue("state.discardAgentCandidate()" in code, "未桥接/已拒绝候选应可显式丢弃")
        assertTrue("NovelAgentApprovalState.BRIDGED" in code)
        assertTrue("NovelAgentApprovalState.UNBRIDGED" in code)
        assertTrue("ARTIFACT_NOT_FOUND" in code, "候选失效必须有显式展示分支")
        assertFalse("novelAgent.resume(" in code, "AgentPage 不得调用 resume")
        assertFalse(".approve(" in code, "AgentPage 不得成为正式批准入口")
        assertFalse(".reject(" in code, "AgentPage 不得成为正式拒绝入口")
    }

    @Test
    fun `go to write selects the task chapter`() {
        val code = agentPage.readText()
        assertTrue("openTaskChapter(state)" in code, "去正文处理必须调用章节定位")
        assertTrue("activeChapterId" in code, "必须读取任务请求的章节")
        assertTrue("WorkspaceSelection.chapter" in code, "必须把任务章节设为当前对象")
    }

    @Test
    fun `write page remains the unique formal approve and reject entry`() {
        assertTrue(writePage.isFile, "找不到 WritePage.kt: ${writePage.absolutePath}")
        val code = writePage.readText()
        assertTrue("onApprove = { controller?.approveGate() }" in code)
        assertTrue("onReject = { controller?.rejectGate() }" in code)
    }

    @Test
    fun `successful run stores result session id for candidate follow ups`() {
        val compact = common.readText().replace(Regex("\\s+"), " ")
        assertTrue(
            "lastAgentRequest = result.sessionId?.let { request.copy(sessionId = it) } ?: request" in compact,
            "run 成功后必须把 result.sessionId 写回 lastAgentRequest；null 时保持原样",
        )
        assertFalse(
            "lastAgentRequest = request " in compact,
            "不得保存不含 sessionId 的原始请求",
        )

        val dir = Files.createTempDirectory("qianyan-agent-session")
        val handle = QianyanDbFactory.open("jdbc:sqlite:${dir.resolve("qianyan.db").toAbsolutePath()}")
        try {
            val app = ApplicationContainer.fromDriver(handle.driver, DesktopOfflineLlmGateway())
            val novelId = app.novels.createOriginal("session-id")
            val chapter = app.chapters.createNextChapter("第一章", novelId)
            app.projects.selectChapter(novelId, chapter.chapterId)
            val projectId = app.projects.projectOf(novelId).projectId
            val request = NovelAgentRequest(
                projectId = projectId,
                userIntent = "继续写第一章",
                activeChapterId = chapter.chapterId,
            )

            val run = app.novelAgent.run(request)
            assertEquals(NovelAgentOutcome.WAITING_HUMAN, run.outcome)
            val sessionId = assertNotNull(run.sessionId, "run 必须返回 sessionId")
            val artifactId = assertNotNull(run.artifactId, "run 必须产出候选")
            val stored = request.copy(sessionId = sessionId)
            assertEquals(sessionId, stored.sessionId)

            assertNotEquals(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                app.novelAgent.bridgeCandidate(stored, artifactId).failure?.code,
            )
            assertNotEquals(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                app.novelAgent.refreshCandidate(stored, artifactId).failure?.code,
            )
            assertNotEquals(
                NovelAgentErrorCodes.SESSION_REQUIRED,
                app.novelAgent.reject(stored, artifactId, reason = "session-id regression").failure?.code,
            )
        } finally {
            handle.driver.close()
            dir.toFile().deleteRecursively()
        }
    }
}
