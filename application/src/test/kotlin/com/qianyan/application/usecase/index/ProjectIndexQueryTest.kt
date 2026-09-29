package com.qianyan.application.usecase.index

import com.qianyan.application.error.ApplicationException
import com.qianyan.model.DraftId
import com.qianyan.model.projectindex.ProjectIndexEntryType
import com.qianyan.model.projectindex.ProjectIndexErrorCodes
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * I12 · 查询 API + Project 隔离（§8 / §9 / §16 C·E）。
 *
 * 查询只读既有索引（不隐式构建、不改 Canonical、不返回正文），结果与顺序稳定。
 */
class ProjectIndexQueryTest {

    @Test
    fun `query api returns located references with stable ordering`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)
        f.app.projectIndex.rebuild(w.projectId)

        // entries
        assertEquals(f.app.projectIndex.get(w.projectId).entries, f.app.projectIndex.entries(w.projectId))

        // findByType（章节按 order 稳定排序）
        val chapters = f.app.projectIndex.findByType(w.projectId, ProjectIndexEntryType.CHAPTER)
        assertEquals(listOf(w.chapter1, w.chapter2), chapters.map { it.reference.chapterId })
        assertEquals(
            listOf(w.draft1, w.draft2),
            f.app.projectIndex.findByType(w.projectId, ProjectIndexEntryType.DRAFT).map { it.reference.draftId },
        )

        // find：标签 / 可检索词匹配（不区分大小写；不是全文检索）
        assertEquals(
            listOf(w.chapter1),
            f.app.projectIndex.find(w.projectId, "入山").mapNotNull { it.reference.chapterId }.distinct(),
        )
        assertTrue(f.app.projectIndex.find(w.projectId, "入山").any { it.entryType == ProjectIndexEntryType.DRAFT })
        assertEquals(
            w.chapter2,
            f.app.projectIndex.find(w.projectId, "第2章").first().reference.chapterId,
            "章节编号可检索",
        )
        assertEquals(
            ProjectIndexEntryType.VOCABULARY,
            f.app.projectIndex.find(w.projectId, "灵石").single().entryType,
        )
        assertEquals(
            w.vocabularyCandidateId,
            f.app.projectIndex.find(w.projectId, "灵玉").single().reference.vocabularyCandidateId,
            "别名可检索",
        )
        assertTrue(f.app.projectIndex.find(w.projectId, "根本不存在的内容").isEmpty())

        // 空白 query ⇒ 全部条目
        assertEquals(f.app.projectIndex.entries(w.projectId), f.app.projectIndex.find(w.projectId, "  "))

        // 结果稳定（同输入 ⇒ 同结果 / 同顺序）
        assertEquals(
            f.app.projectIndex.find(w.projectId, "入山"),
            f.app.projectIndex.find(w.projectId, "入山"),
        )

        // 不复制正文：结果只含定位信息
        assertTrue(
            f.app.projectIndex.entries(w.projectId).none { it.label.contains("正文") },
            "索引入口不得包含正文",
        )
        f.close()
    }

    @Test
    fun `index is strictly isolated per project`() {
        val f = indexFixture()
        val a = seedIndexWorld(f)

        // Project B：独立 Novel + 章节 + 正文
        val projectB = f.app.projects.createProject(title = "另一本书")
        val chapterB = f.app.chapters.createNextChapter("B 独有章节", projectB.novelId).chapterId
        f.app.draftRepository.save(
            Draft(
                draftId = DraftId("d-index-b"),
                novelId = projectB.novelId,
                chapterId = chapterB,
                content = "B 独有正文。",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.WRITTEN,
                createdAt = INDEX_FIXED_INSTANT,
                updatedAt = INDEX_FIXED_INSTANT,
            ),
        )

        // 只构建 A：B 的查询表现为"未构建"（不泄漏 B 是否存在）
        val aIndex = f.app.projectIndex.rebuild(a.projectId)
        assertTrue(aIndex.entries.all { it.reference.novelId == a.novelId }, "Index(A) 只能含 A 的数据")
        assertTrue(aIndex.entries.none { it.label.contains("B 独有") })
        assertTrue(aIndex.entries.none { it.reference.chapterId == chapterB })
        val bNotBuilt = assertFailsWith<ApplicationException> { f.app.projectIndex.entries(projectB.projectId) }
        assertTrue(bNotBuilt.message!!.contains(ProjectIndexErrorCodes.INDEX_NOT_BUILT), bNotBuilt.message!!)

        // 构建 B：两边互不污染
        val bIndex = f.app.projectIndex.rebuild(projectB.projectId)
        assertTrue(bIndex.entries.all { it.reference.novelId == projectB.novelId })
        assertTrue(bIndex.entries.none { it.reference.chapterId == a.chapter1 })
        assertEquals(listOf(chapterB), bIndex.ofType(ProjectIndexEntryType.CHAPTER).map { it.reference.chapterId })
        assertEquals(aIndex.entries, f.app.projectIndex.entries(a.projectId), "构建 B 不得改动 A 的索引")
        f.close()
    }

    @Test
    fun `queries never build implicitly`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)

        listOf<() -> Unit>(
            { f.app.projectIndex.get(w.projectId) },
            { f.app.projectIndex.entries(w.projectId) },
            { f.app.projectIndex.find(w.projectId, "入山") },
            { f.app.projectIndex.findByType(w.projectId, ProjectIndexEntryType.CHAPTER) },
            { f.app.projectIndex.isStale(w.projectId) },
        ).forEach { call ->
            val e = assertFailsWith<ApplicationException> { call() }
            assertTrue(e.message!!.contains(ProjectIndexErrorCodes.INDEX_NOT_BUILT), e.message!!)
        }
        f.close()
    }
}