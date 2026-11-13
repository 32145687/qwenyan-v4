package com.qianyan.app.desktop

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterUiState
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O-84（T-O84-F）：空章节作者首稿——桌面层判定与接线。
 *
 * 空章节（latestDraft = null）输入非空正文 → canSave 可用 → save() 创建首稿并写 Canonical；
 * 正文为空 / 纯空白 → canSave 仍禁用，save() 不产生任何行。
 */
class DesktopWriterFirstDraftTest {

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = if ("StoryWriterAgent" in system) """{"content":"AI 生成正文"}""" else """{"chapterGoal":"推进主线"}"""
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, """{"answer":"$body"}"""),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }

    private fun controllerFor(app: ApplicationContainer, novelId: com.qianyan.model.NovelId, chapterId: com.qianyan.model.ChapterId): WriterController {
        val chapter = assertNotNull(app.chapters.findById(chapterId))
        return WriterController(
            novelId = novelId,
            chapter = chapter,
            gateway = app.writerGateway,
            workflow = app.workflowFacade,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `empty chapter with non blank content enables save and creates first draft`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "空章节首稿")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        val controller = controllerFor(app, novelId, chapter.chapterId)
        controller.load()

        assertNull(controller.uiState.value.draftId, "空章节初始无 Draft")
        assertFalse(controller.uiState.value.canSave, "未输入正文时保存应禁用")

        controller.onContentChange("第一章的第一段正文。")

        assertTrue(controller.uiState.value.canSave, "空章节 + 非空正文应可保存")
        controller.save()

        val state = controller.uiState.value
        assertNull(state.error, "首稿保存不应报错")
        assertEquals("${chapter.chapterId.value}#author-first", state.draftId?.value, "首稿使用确定性 DraftId")
        assertEquals("第一章的第一段正文。", state.draftContent)
        assertFalse(state.dirty, "保存后应清除脏标记")
        assertEquals(1, app.draftRepository.listByChapter(chapter.chapterId).size, "首稿只产生一行 Draft")
        assertEquals("第一章的第一段正文。", app.canonicalRead.committedContent(chapter.chapterId), "首稿即 Canonical")
        assertEquals(1, app.commits.history(projectId).size, "保存即批准：一条 CommitHistory")
        assertEquals("作者手动保存（保存即批准）", app.commits.history(projectId).single().summary)
    }

    @Test
    fun `blank content keeps save disabled on empty chapter`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "空章节空白")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val controller = controllerFor(app, novelId, chapter.chapterId)
        controller.load()

        controller.onContentChange("   \n  ")

        assertFalse(controller.uiState.value.canSave, "纯空白正文不得可保存")
        assertEquals(0, app.draftRepository.listByChapter(chapter.chapterId).size, "不得创建 Draft")
    }

    @Test
    fun `pure state projection allows saving without draft when content present`() {
        val empty = WriterUiState(isLoaded = true, draftId = null, draftContent = "")
        assertFalse(empty.canSave, "无内容且无 Draft → 禁用")

        val typed = WriterUiState(isLoaded = true, draftId = null, draftContent = "正文")
        assertTrue(typed.canSave, "有正文且无 Draft → 可保存")

        val pending = WriterUiState(
            isLoaded = true,
            draftId = null,
            draftContent = "正文",
            waitingForUser = true,
        )
        assertFalse(pending.canSave, "待审期间仍禁止普通保存")
    }
}
