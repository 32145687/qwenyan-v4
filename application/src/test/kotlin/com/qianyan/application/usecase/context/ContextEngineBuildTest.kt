package com.qianyan.application.usecase.context

import com.qianyan.model.IntentType
import com.qianyan.model.context.ContextPriority
import com.qianyan.model.context.ContextRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I6 · Context Engine 构建测试（真实数据；§26 Selection / Snapshot / Source / Determinism）。
 *
 * 覆盖：ContextSource 逐来源产出、"继续写章节"场景、确定性（同输入 ⇒ 同 Pack）、
 * Snapshot 冻结与失效判定、只读守卫。
 */
class ContextEngineBuildTest {

    @Test
    fun `continue writing chapter builds a meaningful pack`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        val pack = f.app.contextEngine.build(
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2),
        )
        val byId = pack.items.associateBy { it.itemId }

        // §22：当前章节 / 前置章节 / 最新草稿为 HIGH
        assertEquals(ContextPriority.HIGH, byId.getValue("chapter:${w.chapter2.value}").priority, "焦点章节 HIGH")
        assertEquals(ContextPriority.HIGH, byId.getValue("chapter:${w.chapter1.value}").priority, "前置章节 HIGH")
        assertEquals(ContextPriority.HIGH, byId.getValue("draft:${w.draftId.value}").priority, "当前稿 HIGH")
        assertEquals("A 第二章正文", byId.getValue("draft:${w.draftId.value}").content, "正文原文保留（不摘要）")

        // 项目 / 运行态 / 作品 / 故事基础 / 词汇都可入选
        assertNotNull(byId["project:${w.projectId.value}"])
        assertNotNull(byId["project_state:${w.projectId.value}"])
        assertNotNull(byId["novel:${w.novelId.value}"])
        assertNotNull(byId["story_foundation:${w.novelId.value}@v0"])
        assertNotNull(byId["vocabulary:${w.candidateId.value}"])

        // 入选顺序：优先级降序
        val ranks = pack.items.map { it.priority.rank }
        assertEquals(ranks.sorted(), ranks, "入选条目按优先级降序排列")
        f.close()
    }

    @Test
    fun `chapter source marks focus and predecessor high and others low`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        f.app.chapters.createNextChapter("A-3", w.novelId)
        val scope = ContextBuildScope(w.projectId, w.novelId, null, f.app.projects.state(w.projectId), w.chapter2)

        val items = ChapterSource(f.app.chapters).candidates(
            scope,
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2),
        )

        val byId = items.associateBy { it.itemId }
        assertEquals(ContextPriority.HIGH, byId.getValue("chapter:${w.chapter2.value}").priority)
        assertEquals(ContextPriority.HIGH, byId.getValue("chapter:${w.chapter1.value}").priority)
        assertEquals(2, items.size, "候选生成边界只覆盖焦点章节及其之前若干章")
        f.close()
    }

    @Test
    fun `vocabulary source only reads the resolved novel`() {
        val f = contextFixture()
        val a = seedProject(f, "书A", "A")
        seedProject(f, "书B", "B")
        val scope = ContextBuildScope(a.projectId, a.novelId, null, f.app.projects.state(a.projectId), a.chapter2)

        val items = VocabularySource(f.app.vocabularies).candidates(
            scope,
            ContextRequest(projectId = a.projectId, purpose = IntentType.CONTINUE),
        )

        assertEquals(listOf("vocabulary:${a.candidateId.value}"), items.map { it.itemId }, "只产出本次作用域 Novel 的词汇")
        f.close()
    }

    @Test
    fun `draft source is empty when the focus chapter has no draft`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val scope = ContextBuildScope(w.projectId, w.novelId, null, f.app.projects.state(w.projectId), w.chapter1)

        val items = DraftSource(f.app.chapters, f.app.writerUseCases).candidates(
            scope,
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter1),
        )

        assertTrue(items.isEmpty(), "无草稿 → 无可入 Context 的草稿条目（不伪造）")
        f.close()
    }

    @Test
    fun `same request and unchanged data produce identical packs`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val engine = fixedClockEngine(f)
        val request = ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2)

        val first = engine.build(request)
        val second = engine.build(request)
        val third = engine.build(request)

        assertEquals(first, second, "同输入 ⇒ 同 ContextPack")
        assertEquals(second, third, "连续运行结果一致（无随机 / 无真实时间参与）")
        assertEquals(first.packId, third.packId, "packId 确定性")
        assertFalse(engine.isStale(first), "数据未变化 ⇒ 未过期")
        f.close()
    }

    @Test
    fun `frozen pack does not change when underlying data changes`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val engine = fixedClockEngine(f)
        val request = ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2)
        val before = engine.build(request)

        // 底层数据变化：改写草稿 + 新增章节
        val draft = assertNotNull(f.app.draftRepository.getById(w.draftId))
        f.app.draftRepository.save(draft.copy(content = "A 第二章正文（改写）"))
        f.app.chapters.createNextChapter("A-3", w.novelId)

        assertEquals(
            "A 第二章正文",
            before.items.single { it.itemId == "draft:${w.draftId.value}" }.content,
            "已生成的 Snapshot 不得因底层数据变化而悄悄改变",
        )
        assertTrue(engine.isStale(before), "底层数据变化 ⇒ 版本变化 ⇒ 判定过期")

        val after = engine.build(request)
        assertTrue(after.packVersion != before.packVersion, "变化后版本不同")
        assertEquals("A 第二章正文（改写）", after.items.single { it.itemId == "draft:${w.draftId.value}" }.content)
        assertFalse(engine.isStale(after), "重建后不再过期")
        f.close()
    }

    @Test
    fun `budget limits the pack while high priority content survives`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        // 预算仅够极小片段：焦点章节（HIGH）必须保留，低优先项被淘汰并记录
        val pack = f.app.contextEngine.build(
            ContextRequest(
                projectId = w.projectId,
                purpose = IntentType.CONTINUE,
                focusChapterId = w.chapter2,
                budget = com.qianyan.model.context.ContextBudget(maxTokens = 4),
            ),
        )

        assertTrue(pack.budgetGuard.isTruncated, "预算不足 ⇒ 标记裁剪")
        assertTrue(pack.budgetGuard.omittedCount > 0, "淘汰必须被记录（可解释）")
        assertTrue(
            pack.items.all { it.priority == ContextPriority.HIGH },
            "预算紧时只保留高优先级内容：${pack.items.map { it.itemId }}",
        )
        f.close()
    }

    @Test
    fun `building a pack leaves canonical data and agent logs untouched`() {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")

        val novelBefore = f.app.novels.getNovel(w.novelId)
        val chaptersBefore = f.app.chapters.listByNovel(w.novelId)
        val draftBefore = f.app.draftRepository.getById(w.draftId)
        val stateBefore = f.app.projects.state(w.projectId)
        val foundationBefore = f.app.storyFoundationRepository.getStoryFoundation(w.novelId)
        val vocabularyBefore = f.app.vocabularyRepository.findCandidatesByNovel(w.novelId)
        val activitiesBefore = countRows(f.handle, "Activity")
        val toolCallsBefore = countRows(f.handle, "ToolCallLog")
        val sessionsBefore = countRows(f.handle, "AgentSession")

        f.app.contextEngine.build(
            ContextRequest(projectId = w.projectId, purpose = IntentType.CONTINUE, focusChapterId = w.chapter2),
        )

        assertEquals(novelBefore, f.app.novels.getNovel(w.novelId), "Novel 不得被修改")
        assertEquals(chaptersBefore, f.app.chapters.listByNovel(w.novelId), "Chapter 不得被修改")
        assertEquals(draftBefore, f.app.draftRepository.getById(w.draftId), "Draft 不得被修改")
        assertEquals(stateBefore, f.app.projects.state(w.projectId), "ProjectState 不得被修改")
        assertEquals(foundationBefore, f.app.storyFoundationRepository.getStoryFoundation(w.novelId), "StoryFoundation 不得被修改")
        assertEquals(vocabularyBefore, f.app.vocabularyRepository.findCandidatesByNovel(w.novelId), "Vocabulary 不得被修改")
        assertEquals(activitiesBefore, countRows(f.handle, "Activity"), "Context 构建不写 Activity")
        assertEquals(toolCallsBefore, countRows(f.handle, "ToolCallLog"), "Context 构建不写 ToolCallLog")
        assertEquals(sessionsBefore, countRows(f.handle, "AgentSession"), "Context 构建不创建 AgentSession")
        f.close()
    }
}