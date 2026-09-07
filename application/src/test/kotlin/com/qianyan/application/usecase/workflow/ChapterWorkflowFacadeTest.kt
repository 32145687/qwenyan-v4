package com.qianyan.application.usecase.workflow

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.NovelId
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * P12.2 M1-M2 · ChapterWorkflowFacade（FACADE-1..8）。
 * 验证用户层 Facade：启动/推进/进度投影/approve(HITL)/resume(recovery)/continueToNextChapter，
 * 且全部结果只暴露用户层类型（不暴露 Workflow 内部）。
 */
class ChapterWorkflowFacadeTest {

    private fun okGateway(): LLMGateway = MockLLMGateway { req ->
        val sys = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in sys -> """{"content":"正文被写就。"}"""
            "StoryCriticAgent" in sys -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in sys || "StoryKnowledgeUpdateAgent" in sys -> """{"changes":[{"changeId":"k1","operation":"ADD","target":"主角","content":"已突破金丹期"}]}"""
            "StoryPlannerAgent" in sys -> """{"chapterGoal":"揭开秘辛","mainConflict":"c","expectedEvents":["遇险"]}"""
            else -> """{"content":"占位"}"""
        }
        ProviderResponse(message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()), usage = Usage(10, 10, 20), finishReason = FinishReason.STOP)
    }

    private fun open(): ApplicationContainer = ApplicationContainer.open(analysisGateway = okGateway())

    private fun openFile(url: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val h = QianyanDbFactory.open(url)
        handles += h
        return ApplicationContainer.fromDriver(h.driver, analysisGateway = okGateway())
    }

    private fun closeAll(handles: MutableList<QianyanDbHandle>) {
        handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
        handles.clear()
    }

    /** 建 novel + chapter（经既有 ChapterUseCases）。 */
    private fun ch(container: ApplicationContainer, title: String = "章"): Pair<NovelId, com.qianyan.model.ChapterId> {
        val novel = container.novels.createOriginal(title = "仙侠")
        val chapter = container.chapters.createNextChapter(title = title, novelId = novel)
        return novel to chapter.chapterId
    }

    /** FACADE-1：startChapter 创建 Workflow（幂等），阶段 NOT_STARTED。 */
    @Test
    fun `FACADE-1 start chapter workflow`() {
        val c = open()
        val (novel, chapter) = ch(c)
        val p1 = c.workflowFacade.startChapter(novel, null, chapter)
        assertEquals(ChapterPhase.NOT_STARTED, p1.phase)
        assertTrue(c.workflowRepository.getWorkflowByActiveChapter(chapter) != null)
        val p2 = c.workflowFacade.startChapter(novel, null, chapter) // 幂等
        assertEquals(p1, p2)
    }

    /** FACADE-2 / FACADE-3：advance 推进到 WAITING_HUMAN，getChapterProgress 读取用户层进度。 */
    @Test
    fun `FACADE-2 advance and FACADE-3 progress`() {
        val c = open()
        val (novel, chapter) = ch(c)
        c.workflowFacade.startChapter(novel, null, chapter)
        val after = c.workflowFacade.advance(chapter)
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, after.phase)
        assertTrue(after.waitingForUser)
        assertEquals(0, after.revisionCount)
        assertTrue(after.draftId != null)

        val read = c.workflowFacade.getChapterProgress(chapter)
        assertEquals(after, read) // 只读投影一致
    }

    /** FACADE-4 / FACADE-5：approve 闸门 → 再 advance → COMPLETED。 */
    @Test
    fun `FACADE-4 approve and FACADE-5 completes`() {
        val c = open()
        val (novel, chapter) = ch(c)
        c.workflowFacade.startChapter(novel, null, chapter)
        c.workflowFacade.advance(chapter)
        assertEquals(ChapterPhase.WAITING_CONFIRMATION, c.workflowFacade.getChapterProgress(chapter).phase)

        // approve 后：不再等待用户，但尚未完成（等 KU）
        val approved = c.workflowFacade.approve(chapter)
        assertFalse(approved.waitingForUser)
        assertNotEquals(ChapterPhase.COMPLETED, approved.phase)

        // 再 advance → COMPLETED；KU 已沉淀事实
        val done = c.workflowFacade.advance(chapter)
        assertEquals(ChapterPhase.COMPLETED, done.phase)
        assertTrue(c.storyWorldContextResolver.resolve(novel).memories.any { it.contains("已突破金丹期") })
    }

    /** approve 幂等：approve×3 后 advance，仅一次 KU（不重复）。 */
    @Test
    fun `approve idempotency no duplicate ku`() {
        val c = open()
        val (novel, chapter) = ch(c)
        c.workflowFacade.startChapter(novel, null, chapter)
        c.workflowFacade.advance(chapter)
        c.workflowFacade.approve(chapter)
        c.workflowFacade.approve(chapter) // 已 APPROVED → NO-OP
        c.workflowFacade.approve(chapter)
        val done = c.workflowFacade.advance(chapter)
        assertEquals(ChapterPhase.COMPLETED, done.phase)
        val facts = c.storyWorldContextResolver.resolve(novel).memories.filter { it.contains("已突破金丹期") }
        assertEquals(1, facts.size) // KU 只有一次沉淀
    }

    /** FACADE-6：resume 建立在 durable recovery（file-backed：推进到 gate → 关闭 → 重建 → resume 不重跑已完成步骤）。 */
    @Test
    fun `FACADE-6 resume after restart`() {
        val tmp = Files.createTempFile("qianyan-facade-6", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var c = openFile("jdbc:sqlite:$tmp", handles)
            val (novel, chapter) = ch(c)
            c.workflowFacade.startChapter(novel, null, chapter)
            c.workflowFacade.advance(chapter) // → WAITING_HUMAN（已完成 planning/writing）
            assertEquals(ChapterPhase.WAITING_CONFIRMATION, c.workflowFacade.getChapterProgress(chapter).phase)
            val draftBefore = c.draftRepository.latestByChapter(chapter)!!.draftId
            closeAll(handles)

            c = openFile("jdbc:sqlite:$tmp", handles)
            val resumed = c.workflowFacade.resume(chapter) // durable 恢复，不重跑已完成步骤
            assertEquals(ChapterPhase.WAITING_CONFIRMATION, resumed.phase)
            assertEquals(draftBefore, c.draftRepository.latestByChapter(chapter)!!.draftId)
            assertEquals(1, c.draftRepository.listByChapter(chapter).size) // 未因恢复产生重复 Draft
        } finally {
            closeAll(handles)
            Files.deleteIfExists(Path(tmp))
        }
    }

    /** FACADE-7：complete Ch1 → continueToNextChapter 返回 Ch2 progress；重复 continue 幂等。 */
    @Test
    fun `FACADE-7 continue to next chapter idempotent`() {
        val c = open()
        val (novel, chapter1) = ch(c)
        c.workflowFacade.startChapter(novel, null, chapter1)
        c.workflowFacade.advance(chapter1)
        c.workflowFacade.approve(chapter1)
        assertEquals(ChapterPhase.COMPLETED, c.workflowFacade.advance(chapter1).phase)

        val next1 = c.workflowFacade.continueToNextChapter(chapter1)
        val next2 = c.workflowFacade.continueToNextChapter(chapter1) // 幂等
        assertEquals(next1.chapterId, next2.chapterId)
        assertNotEquals(chapter1, next1.chapterId)
        // 同一 source 只一个 continuation
        val srcDraft = c.draftRepository.latestByChapter(chapter1)!!
        assertEquals(1, c.workflowRepository.getContinuationBySourceDraft(srcDraft.draftId)?.let { listOf(it) }?.size ?: 0)
        // 全 novel 仅 Ch1 + Ch2
        assertEquals(2, c.chapters.listByNovel(novel).size)
    }

    /** FACADE-8：Facade 结果只暴露用户层类型（不是 Workflow 内部），且不含 workflow 内部字段泄漏。 */
    @Test
    fun `FACADE-8 facade exposes only user layer types`() {
        val c = open()
        val (novel, chapter) = ch(c)
        val p = c.workflowFacade.startChapter(novel, null, chapter)
        val any: Any = p
        // 类型是用户层 DTO，绝非 Workflow 内部对象（经 Any 做运行时检查，反证不暴露内部类型）
        assertFalse(any is Workflow)
        assertFalse(any is com.qianyan.model.workflow.WorkflowStep)
        assertTrue(any is ChapterWorkflowProgress)
        // 用户层字段仅含：chapterId / novelId / variantId / phase / waitingForUser / revisionCount / draftId
        assertEquals(chapter, p.chapterId)
        assertEquals(ChapterPhase.NOT_STARTED, p.phase) // start 后未推进
    }
}