package com.qianyan.application

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.txt.TxtCanonicalInitializationUseCases
import com.qianyan.application.usecase.txt.TxtUseCases
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.txt.TxtDocument
import com.qianyan.model.writing.Draft
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.UniqueConflictException
import kotlinx.datetime.Clock
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1 §10.12 + **Follow-up F1–F4** · TXT 导入闭环与数据一致性。
 *
 * 覆盖：
 *  - **F1**：容器装配下 `importTxtAsOriginal` 自动初始化 Canonical；分阶段失败原样抛出且可幂等重试；
 *  - **F2**：目标 Novel 已有其它来源 domain Chapter（或目标确定性 ID 被占用）时，在任何写入之前拒绝；
 *  - **F3**：初始化必须显式指定 `documentId`，并校验存在性与 Novel 归属；
 *  - **F4**：标题/正文分离约定（标题只进 `Chapter.title`；`Draft.content` = 本章段落按 ordinal 空行连接；`format = null`）；
 *  - 既有语义：Chapter 映射、CanonicalRead 可读、不经 AI 变更链、幂等、用户已改 Canonical 不被覆盖、中途失败回滚。
 */
class TxtCanonicalInitializationTest {

    private fun container(): ApplicationContainer = ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun source(text: String, name: String = "novel.txt"): TxtSource =
        TxtSource(text.toByteArray(StandardCharsets.UTF_8), name)

    /** 2 章节 + 3 段落块（第一章：正文一/二；第二章：正文三）。 */
    private val validText = "第一章\n\n正文一。\n\n正文二。\n\n第二章\n\n正文三。"

    private fun projectId(app: ApplicationContainer, novelId: NovelId) = app.projects.projectOf(novelId).projectId

    /**
     * 仅导入、不自动初始化：未注入初始化器的装配形态（同时也是 `TxtImportOutput.canonicalInit = null` 的形态），
     * 用于需要精确控制初始化时机的单元测试。F1 闭环另由 `container import auto initializes...` 系列测试锁定。
     */
    private fun importOnly(app: ApplicationContainer, text: String, title: String): TxtUseCases.TxtImportOutput =
        TxtUseCases(TxtPipeline(), app.txtRepository, app.novelRepository, ErrorMapper)
            .importTxtAsOriginal(source(text), title = title)

    /** 本文档的初始化目标集合（domain ChapterId ← TXT 章节确定性 ID）；不靠 `#c` 字面量拼接。 */
    private fun targetChapterIds(app: ApplicationContainer, documentId: TxtDocumentId): List<ChapterId> =
        app.txtRepository.getChapters(documentId).sortedBy { it.ordinal }.map { ChapterId(it.chapterId.value) }

    /* ============================== 既有语义（改用 F3 新签名） ============================== */

    @Test
    fun `import then initialize creates domain chapters readable via CanonicalRead`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")

        val init = app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        assertEquals(2, init.totalChapterCount)
        assertEquals(2, init.initializedChapterCount)
        assertEquals(0, init.skippedChapterCount)

        val chapters = app.chapterRepository.listByNovel(out.novelId, null)
        assertEquals(2, chapters.size, "应创建 2 个正式 domain Chapter")
        assertEquals(listOf(1, 2), chapters.map { it.order }, "order 应稳定映射（ordinal+1）")
        assertEquals(listOf("第一章", "第二章"), chapters.map { it.title }, "标题应映射")

        val c1 = chapters.first { it.order == 1 }
        val c2 = chapters.first { it.order == 2 }
        assertEquals("正文一。\n\n正文二。", app.canonicalRead.committedContent(c1.chapterId), "正文应经 CanonicalRead 可读")
        assertEquals("正文三。", app.canonicalRead.committedContent(c2.chapterId))
        // 不迁移格式：导入正文保持 legacy plain text（format = null）
        assertNull(app.draftRepository.latestByChapter(c1.chapterId)?.format, "TXT 导入不得擅自标记受控 Markdown")
        assertEquals(2, app.commits.history(projectId(app, out.novelId)).size, "每章一条 CommitHistory")
    }

    @Test
    fun `untitled chapter keeps its body`() {
        val app = container()
        val out = importOnly(app, "正文甲。\n\n正文乙。", "无标题书")

        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        val chapters = app.chapterRepository.listByNovel(out.novelId, null)
        assertEquals(1, chapters.size, "无标题应生成单个隐式章节")
        assertEquals("", chapters.single().title)
        assertEquals("正文甲。\n\n正文乙。", app.canonicalRead.committedContent(chapters.single().chapterId), "无标题不得丢正文")
    }

    @Test
    fun `initialization does not create ai change proposal or human gate`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")

        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        val pid = projectId(app, out.novelId)
        assertTrue(app.workingDrafts.list(pid).isEmpty(), "初始化不得创建 WorkingDraft")
        assertTrue(app.changes.list(pid).isEmpty(), "初始化不得创建 Change Artifact")
        for (chapter in app.chapterRepository.listByNovel(out.novelId, null)) {
            assertNull(app.workflowRepository.getWorkflowByActiveChapter(chapter.chapterId), "初始化不得创建 Workflow / Human Gate")
        }
    }

    @Test
    fun `repeated initialization does not duplicate chapters or history`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)
        val pid = projectId(app, out.novelId)
        val historyAfterFirst = app.commits.history(pid).size

        val second = app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        assertEquals(0, second.initializedChapterCount, "第二次不应新建章节")
        assertEquals(2, second.skippedChapterCount, "第二次应幂等跳过全部章节")
        assertEquals(2, app.chapterRepository.listByNovel(out.novelId, null).size, "不得重复创建 Chapter")
        assertEquals(historyAfterFirst, app.commits.history(pid).size, "不得重复写 CommitHistory")
    }

    @Test
    fun `user modified canonical is not overwritten by re initialization`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)
        val c1 = app.chapterRepository.listByNovel(out.novelId, null).first { it.order == 1 }
        val draftId = assertNotNull(app.draftRepository.latestByChapter(c1.chapterId)).draftId

        // 用户（作者）改稿并保存（保存即批准）
        app.commits.commitAuthorSave(draftId, "作者改稿后正文")

        // 再次初始化：章节已存在 ⇒ 跳过，不得覆盖用户内容
        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        assertEquals("作者改稿后正文", app.canonicalRead.committedContent(c1.chapterId), "已修改的 Canonical 不得被原始 TXT 覆盖")
    }

    @Test
    fun `mid document failure rolls back and retry succeeds`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        val pid = projectId(app, out.novelId)

        // 注入：第 2 次 Draft 写入失败（模拟中途失败）
        val useCase = TxtCanonicalInitializationUseCases(
            app.txtRepository, app.chapterRepository, failingDraftRepository(app, 2),
            app.commits, app.workflowRepository, ErrorMapper,
        )

        assertFailsWith<Exception> { useCase.initializeCanonical(out.novelId, out.documentId) }

        // 整体回滚：不得留下部分章节已初始化的状态
        assertEquals(0, app.chapterRepository.listByNovel(out.novelId, null).size, "失败必须整体回滚（无 Chapter）")
        assertTrue(app.commits.history(pid).isEmpty(), "失败必须整体回滚（无 CommitHistory）")
        assertNull(app.chapterRepository.findById(targetChapterIds(app, out.documentId).first()), "失败不得留下该 Chapter")

        // 重试（真实仓储）应成功
        val retry = app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)
        assertEquals(2, retry.initializedChapterCount, "重试应能安全完成")
        assertEquals(2, app.chapterRepository.listByNovel(out.novelId, null).size)
    }

    /* ============================== F1：导入即初始化（分阶段闭环） ============================== */

    @Test
    fun `container import auto initializes canonical for the imported document`() {
        val app = container()

        val out = app.txts.importTxtAsOriginal(source(validText), title = "导入书")

        val init = assertNotNull(out.canonicalInit, "容器装配下导入应带回 Canonical 初始化结果")
        assertEquals(out.documentId, init.documentId, "初始化的必须就是本次导入的文档（F3 显式 documentId）")
        assertEquals(2, init.initializedChapterCount)
        assertEquals(0, init.skippedChapterCount)

        val chapters = app.chapterRepository.listByNovel(out.novelId, null)
        assertEquals(2, chapters.size, "导入后 domain Chapter 应已存在，无需再手工初始化")
        val c1 = chapters.first { it.order == 1 }
        assertEquals("正文一。\n\n正文二。", app.canonicalRead.committedContent(c1.chapterId), "导入后即可读 Canonical")
        assertEquals(2, app.commits.history(projectId(app, out.novelId)).size)
    }

    @Test
    fun `import without an initializer leaves canonical uninitialized and reports it`() {
        val app = container()

        val out = importOnly(app, validText, "导入书")

        assertNull(out.canonicalInit, "未注入初始化器时必须显式报告未初始化，不得伪装成功")
        assertEquals(0, app.chapterRepository.listByNovel(out.novelId, null).size, "未注入初始化器则只完成导入")
    }

    @Test
    fun `duplicate import retries the failed initialization idempotently`() {
        val app = container()

        // 装配：导入会落库，但其 Canonical 初始化在中途失败
        val brokenInit = TxtCanonicalInitializationUseCases(
            app.txtRepository, app.chapterRepository, failingDraftRepository(app, 2),
            app.commits, app.workflowRepository, ErrorMapper,
        )
        val txts = TxtUseCases(TxtPipeline(), app.txtRepository, app.novelRepository, ErrorMapper, brokenInit)
        assertFailsWith<Exception> { txts.importTxtAsOriginal(source(validText), title = "导入书") }

        // 分阶段语义：导入已持久化（可识别的已导入未初始化状态），初始化整体回滚
        val novelId = app.novelRepository.listOriginals().single().novelId
        val document: TxtDocument = app.txts.findDocumentsByNovel(novelId).single()
        assertEquals(0, app.chapterRepository.listByNovel(novelId, null).size, "初始化失败已整体回滚")
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty(), "初始化失败不得留下 CommitHistory")

        // 幂等重试：容器装配（已注入初始化器）再导入同一内容 → 命中去重分支 → 补做初始化
        val retry = app.txts.importTxtAsOriginal(source(validText), title = "导入书")

        assertTrue(retry.isDuplicate, "重试应命中去重，不产生第二个 Novel/文档")
        assertEquals(novelId, retry.novelId)
        val retryInit = assertNotNull(retry.canonicalInit, "去重分支也必须执行闭环初始化")
        assertEquals(document.documentId, retryInit.documentId)
        assertEquals(2, retryInit.initializedChapterCount, "上次未初始化的章节应在重试中补齐")
        assertEquals(2, app.chapterRepository.listByNovel(novelId, null).size)
        assertEquals(
            "正文三。",
            app.canonicalRead.committedContent(targetChapterIds(app, document.documentId).last()),
            "重试后可读 Canonical",
        )

        // 再导入一次：全部幂等跳过，不重复写入
        val again = app.txts.importTxtAsOriginal(source(validText), title = "导入书")
        val againInit = assertNotNull(again.canonicalInit)
        assertEquals(0, againInit.initializedChapterCount)
        assertEquals(2, againInit.skippedChapterCount)
        assertEquals(2, app.chapterRepository.listByNovel(novelId, null).size)
        assertEquals(2, app.commits.history(projectId(app, novelId)).size, "幂等重试不得重复写 CommitHistory")
    }

    /* ============================== F2：跨来源章节混排拒绝 ============================== */

    @Test
    fun `initialization is refused before any write when the novel has chapters from another source`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        // 另一来源的既有章节（AI 写作链 / 手工创建：UUID id，不属于本文档目标集合）
        val foreign = app.chapters.createNextChapter("手工创建的章节", out.novelId)

        val ex = assertFailsWith<ApplicationException> {
            app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)
        }
        assertIs<ApplicationError.ChapterSourceConflict>(ex.error, "必须返回可识别的领域错误")

        val chapters = app.chapterRepository.listByNovel(out.novelId, null)
        assertEquals(listOf(foreign.chapterId), chapters.map { it.chapterId }, "拒绝必须是零写入：不追加、不覆盖")
        assertEquals(1, chapters.single().order, "既有章节的 order 不得被重排，也不得静默 max+1 追加")
        targetChapterIds(app, out.documentId).forEach {
            assertNull(app.chapterRepository.findById(it), "一致性检查必须先于任何持久化写入")
        }
        assertTrue(app.commits.history(projectId(app, out.novelId)).isEmpty(), "拒绝时不得写 CommitHistory")
        assertTrue(app.draftRepository.listByNovel(out.novelId).isEmpty(), "拒绝时不得写 Draft")

        // 确定性：重复调用仍是同一拒绝结果，不会因状态漂移而放行
        val again = assertFailsWith<ApplicationException> {
            app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)
        }
        assertIs<ApplicationError.ChapterSourceConflict>(again.error)
    }

    @Test
    fun `initialization is refused when a target chapter id is occupied by another novel`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        val otherNovel = app.novels.createOriginal(title = "另一部作品")
        // 目标确定性 ID 已被其它 Novel 的章节占用：幂等跳过会静默缺章，覆盖会改写他人章节 ⇒ 必须拒绝
        val stolenId = targetChapterIds(app, out.documentId).first()
        app.chapterRepository.save(
            Chapter(
                chapterId = stolenId,
                novelId = otherNovel,
                variantId = null,
                scope = VariantScope.ORIGINAL,
                title = "别人的章节",
                order = 1,
                status = ChapterStatus.WRITTEN,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )

        val ex = assertFailsWith<ApplicationException> {
            app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)
        }
        assertIs<ApplicationError.ChapterSourceConflict>(ex.error)
        assertEquals("别人的章节", app.chapterRepository.findById(stolenId)?.title, "不得覆盖其它来源章节")
        assertEquals(1, app.chapterRepository.listByNovel(otherNovel, null).size)
        assertEquals(0, app.chapterRepository.listByNovel(out.novelId, null).size, "拒绝时不得写入目标 Novel")
    }

    /* ============================== F3：显式 documentId + 存在性/归属校验 ============================== */

    @Test
    fun `initialize requires an existing document id and never picks one implicitly`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")

        val ex = assertFailsWith<ApplicationException> {
            app.txtCanonicalInitialization.initializeCanonical(out.novelId, TxtDocumentId("no-such-doc"))
        }
        assertIs<ApplicationError.EntityNotFound>(ex.error)
        assertEquals(0, app.chapterRepository.listByNovel(out.novelId, null).size, "文档不存在时不得有任何写入")
    }

    @Test
    fun `initialize refuses a document bound to a different novel`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        val otherNovel = app.novels.createOriginal(title = "另一部作品")

        val ex = assertFailsWith<ApplicationException> {
            app.txtCanonicalInitialization.initializeCanonical(otherNovel, out.documentId)
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error, "归属不符必须是可识别错误，而非隐式挑一个文档")
        assertEquals(0, app.chapterRepository.listByNovel(otherNovel, null).size)
        assertEquals(0, app.chapterRepository.listByNovel(out.novelId, null).size)

        // 正确归属仍可初始化
        assertEquals(2, app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId).initializedChapterCount)
    }

    @Test
    fun `multiple documents on one novel initialize only when explicitly named`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        // 同一 Novel 上再挂第二份 TXT 文档（两份文档的章节 ID 形态相同，只有 documentId 能区分）
        val secondDocId = TxtDocumentId("second-doc")
        val parsed = TxtPipeline().import(
            source("上游篇\n\n上游正文。\n\n下游篇\n\n下游末尾。"),
            secondDocId,
            novelId = out.novelId,
            title = "第二份",
        )
        app.txtRepository.saveImport(
            parsed.document,
            parsed.parse.chapters.map { it.copy(novelId = out.novelId) },
            parsed.parse.blocks.map { it.copy(novelId = out.novelId) },
        )
        assertEquals(2, app.txts.findDocumentsByNovel(out.novelId).size, "前置条件：同一 Novel 绑定两份文档")

        val init = app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        assertEquals(out.documentId, init.documentId)
        assertEquals(2, init.totalChapterCount, "只统计显式指定文档的章节，不得静默取第一份或合并两份")
        val expected = targetChapterIds(app, out.documentId)
        assertEquals(expected.sortedBy { it.value }, app.chapterRepository.listByNovel(out.novelId, null).map { it.chapterId }.sortedBy { it.value })
        targetChapterIds(app, secondDocId).forEach {
            assertNull(app.chapterRepository.findById(it), "未被点名的文档不得被隐式初始化")
        }

        // 点名第二份也不会与第一份混排：F2 规则下同一 Novel 的第二份文档被拒绝
        // （导入链路每份 TXT 各建一个 Novel，同 Novel 挂两份文档只可能来自非正常路径）
        val conflict = assertFailsWith<ApplicationException> {
            app.txtCanonicalInitialization.initializeCanonical(out.novelId, secondDocId)
        }
        assertIs<ApplicationError.ChapterSourceConflict>(conflict.error, "第二份文档不得与第一份混排")
        targetChapterIds(app, secondDocId).forEach {
            assertNull(app.chapterRepository.findById(it), "被拒绝的第二份文档不得有任何写入")
        }
        assertEquals(2, app.chapterRepository.listByNovel(out.novelId, null).size, "第一份的初始化结果不受影响")
    }

    /* ============================== F4：标题/正文分离约定 ============================== */

    @Test
    fun `title goes only to chapter title while draft body holds paragraphs only`() {
        val app = container()
        val out = importOnly(app, validText, "导入书")
        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        val chapter = app.chapterRepository.listByNovel(out.novelId, null).first { it.order == 1 }
        val draft = assertNotNull(app.draftRepository.latestByChapter(chapter.chapterId))

        assertEquals("第一章", chapter.title, "标题归 Chapter.title")
        assertEquals("正文一。\n\n正文二。", draft.content, "正文归 Draft.content（段落以空行分隔）")
        assertFalse(draft.content.contains("第一章"), "标题不得混入正文")
        assertFalse(draft.content.contains(chapter.chapterId.value), "正文不得夹带 ID 或定位串")
        assertNull(draft.format, "约定：导入正文保持 legacy plain text（format = null）")
        assertEquals("正文一。\n\n正文二。", app.canonicalRead.committedContent(chapter.chapterId), "CanonicalRead 读到的即该正文")
    }

    @Test
    fun `body is derived from ordered paragraph blocks not from source offsets`() {
        val app = container()
        val text = "第一章\n\n段一。\n\n段二。\n\n段三。\n\n第二章\n\n段四。"
        val out = importOnly(app, text, "偏移无关书")

        app.txtCanonicalInitialization.initializeCanonical(out.novelId, out.documentId)

        val txtChapters = app.txtRepository.getChapters(out.documentId).sortedBy { it.ordinal }
        val chapters = app.chapterRepository.listByNovel(out.novelId, null)
        assertEquals(2, chapters.size)
        txtChapters.forEach { tc ->
            val chapterId = ChapterId(tc.chapterId.value)
            val chapter = chapters.first { it.chapterId == chapterId }
            val expected = app.txtRepository.getBlocksOfChapter(tc.chapterId)
                .sortedBy { it.ordinal }
                .joinToString("\n\n") { it.text }
            val draft = assertNotNull(app.draftRepository.getById(DraftId("${chapterId.value}#canonical")))

            assertEquals(expected, draft.content, "正文必须来自段落块序列（非按偏移切片、非重排）")
            assertFalse(draft.content.contains(tc.title), "标题只进 Chapter.title，不进 Draft.content")
            assertEquals(tc.ordinal + 1, chapter.order, "章节序由 TXT ordinal 稳定映射，不受既有章节影响")
            assertNull(draft.planId, "导入初始化不建立规划血缘")
            assertNull(draft.previousDraftId, "导入初始化不伪造 Draft 链")
            assertEquals("", draft.sourceModel, "导入正文不是 AI 产出")
            assertEquals(VariantScope.ORIGINAL, draft.scope)
            assertNull(draft.variantId)
        }
    }

    /* ============================== U2：导入失败 vs 初始化失败的用户可见状态 ============================== */

    @Test
    fun `init failure is reported as TxtCanonicalInitIncomplete and keeps the import fact`() {
        val app = container()
        // 注入：Draft 保存抛 StorageException ⇒ 初始化器 guard 归一为 DuplicateTarget ⇒ U2 包装保留 cause
        val brokenInit = TxtCanonicalInitializationUseCases(
            app.txtRepository, app.chapterRepository, conflictingDraftRepository(app),
            app.commits, app.workflowRepository, ErrorMapper,
        )
        val txts = TxtUseCases(TxtPipeline(), app.txtRepository, app.novelRepository, ErrorMapper, brokenInit)

        val ex = assertFailsWith<ApplicationException> { txts.importTxtAsOriginal(source(validText), title = "导入书") }

        val incomplete = assertIs<ApplicationError.TxtCanonicalInitIncomplete>(ex.error, "初始化失败必须是可识别的独立状态")
        assertIs<ApplicationError.DuplicateTarget>(incomplete.cause, "原始领域原因必须以类型保留，不得退化成字符串")
        // 分阶段事实：TXT 已导入（文档 + Novel 均落库），章节未初始化
        val novelId = app.novelRepository.listOriginals().single().novelId
        val documents = app.txts.findDocumentsByNovel(novelId)
        assertEquals(1, documents.size, "TXT 文档必须已落库（否则无法幂等重试）")
        assertEquals(documents.single().documentId, incomplete.documentId, "错误必须携带 documentId 供重试定位")
        assertEquals(0, app.chapterRepository.listByNovel(novelId, null).size)
        assertTrue(app.commits.history(projectId(app, novelId)).isEmpty())
    }

    @Test
    fun `re-import hitting the source conflict reports the concrete conflict reason`() {
        val app = container()
        val out = app.txts.importTxtAsOriginal(source(validText), title = "导入书")
        // 导入后作者/AI 又新增了另一来源章节 ⇒ 重导同一文件的去重分支初始化时命中 F2
        app.chapters.createNextChapter("导入后新增的章节", out.novelId)

        val again = assertFailsWith<ApplicationException> { app.txts.importTxtAsOriginal(source(validText), title = "导入书") }

        val incomplete = assertIs<ApplicationError.TxtCanonicalInitIncomplete>(again.error)
        assertEquals(out.documentId, incomplete.documentId)
        assertIs<ApplicationError.ChapterSourceConflict>(incomplete.cause, "F2 冲突原因必须保持类型化，不得显示成普通未知错误")
        // 已有章节与 Canonical 均未被触碰（2 章导入 + 1 章新增）
        val chapters = app.chapterRepository.listByNovel(out.novelId, null)
        assertEquals(3, chapters.size)
        assertEquals(2, app.commits.history(projectId(app, out.novelId)).size)
        assertEquals("正文三。", app.canonicalRead.committedContent(targetChapterIds(app, out.documentId).last()))
    }

    /* ============================== 辅助 ============================== */

    /** 每次执行 `save` 操作时抛出 `StorageException`（经 `guard` 归一化后的领域错误路径，仅供测试注入使用）。 */
    private fun conflictingDraftRepository(app: ApplicationContainer): DraftRepository {
        var saves = 0
        val delegate = app.draftRepository
        return object : DraftRepository {
            override fun save(draft: Draft) {
                saves++
                throw UniqueConflictException("injected unique conflict on save #$saves")
            }

            override fun getById(draftId: DraftId): Draft? = delegate.getById(draftId)
            override fun listByNovel(novelId: NovelId): List<Draft> = delegate.listByNovel(novelId)
            override fun listByChapter(chapterId: ChapterId): List<Draft> = delegate.listByChapter(chapterId)
            override fun latestByChapter(chapterId: ChapterId): Draft? = delegate.latestByChapter(chapterId)
        }
    }

    /** 第 [failOnCall] 次 `save` 抛异常，其余委托真实仓储（仅测试注入用）。 */
    private fun failingDraftRepository(app: ApplicationContainer, failOnCall: Int): DraftRepository {
        var saves = 0
        val delegate = app.draftRepository
        return object : DraftRepository {
            override fun save(draft: Draft) {
                saves++
                if (saves == failOnCall) throw IllegalStateException("injected draft-save failure")
                delegate.save(draft)
            }

            override fun getById(draftId: DraftId): Draft? = delegate.getById(draftId)
            override fun listByNovel(novelId: NovelId): List<Draft> = delegate.listByNovel(novelId)
            override fun listByChapter(chapterId: ChapterId): List<Draft> = delegate.listByChapter(chapterId)
            override fun latestByChapter(chapterId: ChapterId): Draft? = delegate.latestByChapter(chapterId)
        }
    }
}
