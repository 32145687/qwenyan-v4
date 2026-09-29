package com.qianyan.application.usecase.index

import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.projectindex.ProjectIndexEntries
import com.qianyan.model.projectindex.ProjectIndexEntryType
import com.qianyan.model.projectindex.ProjectIndexErrorCodes
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I12 · 构建 / 重建 / 派生性（§2 / §5 / §6 / §14 / §16 A·B·D·F·G·H·I）。
 */
class ProjectIndexBuildTest {

    @Test
    fun `rebuild collects canonical structure into index entries`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)

        val index = f.app.projectIndex.rebuild(w.projectId)

        assertEquals(w.projectId, index.projectId)
        assertEquals(w.novelId, index.novelId)
        assertEquals(null, index.scopeVariantId, "默认索引 Original 基座（由调用方显式指定作用域）")

        val types = index.entries.groupingBy { it.entryType }.eachCount()
        assertEquals(1, types[ProjectIndexEntryType.NOVEL])
        assertEquals(2, types[ProjectIndexEntryType.CHAPTER])
        assertEquals(2, types[ProjectIndexEntryType.DRAFT])
        assertEquals(1, types[ProjectIndexEntryType.STORY_FOUNDATION])
        assertEquals(1, types[ProjectIndexEntryType.VOCABULARY])

        // 稳定引用（不是 Canonical Entity 副本）
        val chapter = index.ofType(ProjectIndexEntryType.CHAPTER).first { it.reference.chapterId == w.chapter1 }
        assertEquals(w.novelId, chapter.reference.novelId)
        assertEquals(w.novelId, chapter.parentReference?.novelId, "章节归属作品")
        assertEquals("入山", chapter.label)
        assertEquals(1, chapter.ordinal)
        assertTrue(chapter.keywords.contains("第1章"), "章节编号可检索：${chapter.keywords}")

        val draft = index.ofType(ProjectIndexEntryType.DRAFT).first { it.reference.draftId == w.draft1 }
        assertEquals(w.chapter1, draft.reference.chapterId)
        assertEquals(w.chapter1, draft.parentReference?.chapterId, "正文归属章节")
        assertTrue(
            draft.sourceVersion.contains("chars="),
            "来源版本含元数据（含正文字数），但不含正文：${draft.sourceVersion}",
        )
        assertTrue(
            index.entries.none { it.label.contains("第一章正文") },
            "索引不得复制正文（只做定位）",
        )

        val foundation = index.ofType(ProjectIndexEntryType.STORY_FOUNDATION).single()
        assertEquals(w.novelId, foundation.reference.novelId)
        assertEquals("version=3", foundation.sourceVersion)

        val vocabulary = index.ofType(ProjectIndexEntryType.VOCABULARY).single()
        assertEquals(w.vocabularyCandidateId, vocabulary.reference.vocabularyCandidateId)
        assertEquals("灵石", vocabulary.label)
        assertTrue(vocabulary.keywords.contains("灵玉"), "别名参与候选发现：${vocabulary.keywords}")
        f.close()
    }

    @Test
    fun `rebuild is deterministic for identical canonical data`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)

        val first = f.app.projectIndex.rebuild(w.projectId)
        val second = f.app.projectIndex.rebuild(w.projectId)

        assertEquals(first.entries, second.entries, "相同 Canonical 数据 ⇒ 相同 entries")
        assertEquals(first.version, second.version, "相同输入 ⇒ 相同版本指纹")
        // 重复 rebuild 不产生重复条目
        assertEquals(first.entries.size, first.entries.map { it.entryId.value }.distinct().size)
        val third = f.app.projectIndex.rebuild(w.projectId)
        assertEquals(first.entries.size, third.entries.size)

        // 与其他 Project 的构建互不影响（同一实例内的派生存储按 Project 隔离）
        val other = f.app.projects.createProject(title = "另一本书")
        val otherIndex = f.app.projectIndex.rebuild(other.projectId)
        assertEquals(1, otherIndex.entries.size, "空 Project 只有作品条目")
        assertEquals(first.entries, f.app.projectIndex.entries(w.projectId))
        f.close()
    }

    @Test
    fun `empty project builds a valid index without chapters or drafts`() {
        val f = indexFixture()
        val world = seedIndexWorld(f, withFoundation = false, withVocabulary = false, secondChapterDraft = false)
        // 只有作品条目：删除章节不现实，改为断言"无 Foundation / 无 Vocabulary / 第二章无 Draft"的形态
        val index = f.app.projectIndex.rebuild(world.projectId)

        assertEquals(0, index.ofType(ProjectIndexEntryType.STORY_FOUNDATION).size)
        assertEquals(0, index.ofType(ProjectIndexEntryType.VOCABULARY).size)
        assertEquals(1, index.ofType(ProjectIndexEntryType.DRAFT).size, "仅第一章有当前稿")
        assertEquals(2, index.ofType(ProjectIndexEntryType.CHAPTER).size)

        // 完全没有章节的项目：只有作品条目，且不抛异常
        val bare = f.app.projects.createProject(title = "空书")
        val bareIndex = f.app.projectIndex.rebuild(bare.projectId)
        assertEquals(listOf(ProjectIndexEntryType.NOVEL), bareIndex.entries.map { it.entryType })
        assertTrue(bareIndex.entries.single().label.isNotBlank())
        f.close()
    }

    @Test
    fun `rebuild reflects canonical changes and detects staleness`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)

        val before = f.app.projectIndex.rebuild(w.projectId)
        assertEquals(false, f.app.projectIndex.isStale(w.projectId), "刚重建 ⇒ 未过期")

        // Canonical 变化 1：新增章节
        val chapter3 = f.app.chapters.createNextChapter("出关", w.novelId).chapterId
        // Canonical 变化 2：既有正文被改写（字数变化 ⇒ 来源版本变化）
        val longer = "第一章正文（被改写得长了许多）。"
        val current = f.app.draftRepository.getById(w.draft1)!!
        f.app.draftRepository.save(current.copy(content = longer, updatedAt = Instant.parse("2026-02-01T00:00:00Z")))

        assertEquals(true, f.app.projectIndex.isStale(w.projectId), "Canonical 变化 ⇒ 可识别为过期")
        assertEquals(before.entries, f.app.projectIndex.entries(w.projectId), "过期判定不得悄悄改动既有索引")

        val after = f.app.projectIndex.rebuild(w.projectId)
        assertNotEquals(before.version, after.version)
        assertTrue(after.ofType(ProjectIndexEntryType.CHAPTER).any { it.reference.chapterId == chapter3 })
        val draftEntry = after.ofType(ProjectIndexEntryType.DRAFT).first { it.reference.draftId == w.draft1 }
        assertTrue(draftEntry.sourceVersion.contains("chars=${longer.length}"), draftEntry.sourceVersion)
        assertEquals(false, f.app.projectIndex.isStale(w.projectId))
        f.close()
    }

    @Test
    fun `index can be discarded and rebuilt from canonical data`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)
        val before = f.app.projectIndex.rebuild(w.projectId)

        assertTrue(f.app.projectIndex.discard(w.projectId), "派生索引可整体丢弃")
        assertEquals(false, f.app.projectIndex.discard(w.projectId))

        // 索引丢失 → 查询表现为"未构建"；Canonical 数据不受影响
        val missing = assertFailsWith<ApplicationException> { f.app.projectIndex.get(w.projectId) }
        assertTrue(missing.message!!.contains(ProjectIndexErrorCodes.INDEX_NOT_BUILT), missing.message!!)
        assertNotNull(f.app.draftRepository.getById(w.draft1), "索引丢失不影响 Canonical 数据")

        val rebuilt = f.app.projectIndex.rebuild(w.projectId)
        assertEquals(before.entries, rebuilt.entries, "Canonical 未变 ⇒ 重建结果一致")
        assertEquals(before.version, rebuilt.version)
        f.close()
    }

    @Test
    fun `rebuild is read only for canonical and derived-adjacent data`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)
        val tables = listOf(
            "Chapter", "ChapterDraft", "StoryFoundation", "ProjectState",
            "CommitHistory", "Novel", "Vocabulary", "VocabularyCandidate",
        )
        val before = tables.associateWith { countRows(f.handle, it) }
        val chapterBefore = f.app.chapters.findById(w.chapter1)
        val draftBefore = f.app.draftRepository.getById(w.draft1)
        val foundationBefore = f.app.storyFoundationRepository.getStoryFoundation(w.novelId)

        f.app.projectIndex.rebuild(w.projectId)
        f.app.projectIndex.rebuild(w.projectId)
        f.app.projectIndex.entries(w.projectId)
        f.app.projectIndex.find(w.projectId, "入山")
        f.app.projectIndex.findByType(w.projectId, ProjectIndexEntryType.DRAFT)
        f.app.projectIndex.isStale(w.projectId)

        tables.forEach { table -> assertEquals(before[table], countRows(f.handle, table), "$table 行数不得变化") }
        assertEquals(chapterBefore, f.app.chapters.findById(w.chapter1), "Chapter 不得被修改")
        assertEquals(draftBefore, f.app.draftRepository.getById(w.draft1), "ChapterDraft 不得被修改")
        assertEquals(foundationBefore, f.app.storyFoundationRepository.getStoryFoundation(w.novelId), "StoryFoundation 不得被修改")
        f.close()
    }

    @Test
    fun `unknown project and foreign scope fail with stable codes`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)

        val unknown = assertFailsWith<ApplicationException> { f.app.projectIndex.rebuild(ProjectId("p-missing")) }
        assertTrue(unknown.message!!.contains(ProjectIndexErrorCodes.PROJECT_NOT_FOUND), unknown.message!!)

        // 不存在 / 不属于本 Novel 的作用域
        val foreignScope = assertFailsWith<ApplicationException> {
            f.app.projectIndex.rebuild(w.projectId, VariantId("v-missing"))
        }
        assertTrue(foreignScope.message!!.contains(ProjectIndexErrorCodes.SCOPE_MISMATCH), foreignScope.message!!)
        assertEquals(0, countRows(f.handle, "CommitHistory"))
        f.close()
    }

    @Test
    fun `entry ids and ordering are stable and content free`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)
        val index = f.app.projectIndex.rebuild(w.projectId)

        assertEquals(
            index.entries.sortedWith(ProjectIndexEntries.ORDER),
            index.entries,
            "条目必须按固定顺序（类型 → 位次 → entryId）",
        )
        // entryId 确定性：形态固定为 `<entryType>:<主键>`，不含 UUID / 时间
        index.entries.forEach { entry ->
            assertTrue(
                entry.entryId.value.startsWith("${entry.entryType.name.lowercase()}:"),
                "entryId 形态固定：${entry.entryId.value}",
            )
        }
        assertEquals(
            "chapter:${w.chapter1.value}",
            index.ofType(ProjectIndexEntryType.CHAPTER).first { it.reference.chapterId == w.chapter1 }.entryId.value,
        )
        assertEquals(
            "draft:${w.draft1.value}",
            index.ofType(ProjectIndexEntryType.DRAFT).first { it.reference.draftId == w.draft1 }.entryId.value,
        )
        // 二次构建的 entryId 集合完全一致
        val again = f.app.projectIndex.rebuild(w.projectId)
        assertEquals(index.entries.map { it.entryId.value }, again.entries.map { it.entryId.value })
        f.close()
    }
}