package com.qianyan.application.usecase.draft

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.ProjectId
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I8 · Working Draft 工作区测试（§23 Working Draft）。
 *
 * 覆盖：create / get / list / replace / edit / discard / unknown draft /
 * project scope / chapter scope / target scope / 基线引用。
 */
class WorkingDraftUseCasesTest {

    @Test
    fun `create binds the draft to project target base and attribution`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)

        val draft = workspaces.create(
            projectId = w.projectId,
            target = WorkingDraftTarget(w.novelId, w.chapter2),
            content = "第一段。\n\n第二段。",
            sessionId = w.sessionId,
            activityId = w.activityId,
            baseDraftId = w.baseDraftId,
        )

        assertEquals(w.projectId, draft.projectId)
        assertEquals(w.novelId, draft.target.novelId)
        assertEquals(w.chapter2, draft.target.chapterId)
        assertEquals("第一段。\n\n第二段。", draft.content)
        assertEquals(WorkingDraftStatus.WORKING, draft.status)
        assertEquals(w.sessionId, draft.sessionId)
        assertEquals(w.activityId, draft.activityId)
        // base reference 复用既有 Draft 标识 / 状态 / 时间，不新建版本体系
        assertEquals(w.baseDraftId, draft.base.baseDraftId)
        assertEquals(DraftStatus.WRITTEN, draft.base.baseDraftStatus)
        assertEquals(f.app.writerUseCases.draft(w.baseDraftId)?.updatedAt, draft.base.baseDraftUpdatedAt)
        assertNotNull(draft.base.baseChapterStatus)
        assertEquals(FIXED_INSTANT, draft.createdAt, "固定时钟 ⇒ 确定性时间戳")
        assertEquals(FIXED_INSTANT, draft.updatedAt)
        f.close()
    }

    @Test
    fun `create rejects unknown or out of scope project chapter and base`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val b = seedWorld(f, "书B", "B")
        val workspaces = fixedClockWorkingDrafts(f)
        val content = "正文"

        // Project 不存在
        assertEntityNotFound {
            workspaces.create(ProjectId("p-ghost"), WorkingDraftTarget(a.novelId, a.chapter2), content)
        }
        // Project 与 target.novelId 不一致（跨 Project）
        assertEntityNotFound {
            workspaces.create(a.projectId, WorkingDraftTarget(b.novelId, b.chapter2), content)
        }
        // Chapter 不存在
        assertEntityNotFound {
            workspaces.create(a.projectId, WorkingDraftTarget(a.novelId, ChapterId("c-ghost")), content)
        }
        // Chapter 属于另一个 Project
        assertEntityNotFound {
            workspaces.create(a.projectId, WorkingDraftTarget(a.novelId, b.chapter2), content)
        }
        // 基线 Draft 不存在
        assertEntityNotFound {
            workspaces.create(
                a.projectId,
                WorkingDraftTarget(a.novelId, a.chapter2),
                content,
                baseDraftId = DraftId("d-ghost"),
            )
        }
        // 基线 Draft 不属于 target 章节
        assertEntityNotFound {
            workspaces.create(
                a.projectId,
                WorkingDraftTarget(a.novelId, a.chapter1),
                content,
                baseDraftId = a.baseDraftId,
            )
        }
        f.close()
    }

    @Test
    fun `get returns the stored draft and rejects unknown`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val created = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "正文")

        assertEquals(created, workspaces.get(created.workingDraftId))
        assertEntityNotFound { workspaces.get(WorkingDraftId("wd-ghost")) }
        f.close()
    }

    @Test
    fun `list returns project drafts sorted by id and excludes other projects`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val b = seedWorld(f, "书B", "B")
        val workspaces = fixedClockWorkingDrafts(f)

        val first = workspaces.create(a.projectId, WorkingDraftTarget(a.novelId, a.chapter2), "A 正文 1")
        val second = workspaces.create(a.projectId, WorkingDraftTarget(a.novelId, a.chapter1), "A 正文 2")
        workspaces.create(b.projectId, WorkingDraftTarget(b.novelId, b.chapter2), "B 正文")

        val listed = workspaces.list(a.projectId)
        assertEquals(2, listed.size, "只返回本 Project 的 Working Draft")
        assertEquals(
            listed.map { it.workingDraftId.value }.sorted(),
            listed.map { it.workingDraftId.value },
            "按 workingDraftId 升序（确定性）",
        )
        assertEquals(setOf(first.workingDraftId, second.workingDraftId), listed.map { it.workingDraftId }.toSet())
        f.close()
    }

    @Test
    fun `replace and edit only change content and return to working`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val created = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "旧正文")
        workspaces.validate(created.workingDraftId)

        val replaced = workspaces.replace(created.workingDraftId, "新正文")
        assertEquals("新正文", replaced.content)
        assertEquals(WorkingDraftStatus.WORKING, replaced.status, "内容变化 ⇒ 回到 WORKING 待重校验")
        assertEquals(created.workingDraftId, replaced.workingDraftId, "身份不变")
        assertEquals(created.createdAt, replaced.createdAt, "创建时间不变")

        val edited = workspaces.edit(created.workingDraftId) { current -> "$current（编辑后）" }
        assertEquals("新正文（编辑后）", edited.content)
        assertEquals(WorkingDraftStatus.WORKING, edited.status)
        assertEquals(edited, workspaces.get(created.workingDraftId))
        f.close()
    }

    @Test
    fun `discard is terminal and idempotent`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val created = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "正文")

        val discarded = workspaces.discard(created.workingDraftId)
        assertEquals(WorkingDraftStatus.DISCARDED, discarded.status)
        assertEquals(discarded, workspaces.discard(created.workingDraftId), "重复丢弃幂等")
        assertEquals(discarded, workspaces.get(created.workingDraftId), "丢弃后仍可审计读取")

        val ex = assertFailsWith<ApplicationException> { workspaces.replace(created.workingDraftId, "改") }
        assertTrue(ex.error is ApplicationError.InvalidOperation, "终态不可修改")
        val ex2 = assertFailsWith<ApplicationException> {
            workspaces.edit(created.workingDraftId) { it + "改" }
        }
        assertTrue(ex2.error is ApplicationError.InvalidOperation)
        f.close()
    }

    @Test
    fun `validate records the working draft status`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)

        val valid = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "第一段。\n\n第二段。")
        assertTrue(workspaces.validate(valid.workingDraftId).passed)
        assertEquals(WorkingDraftStatus.VALIDATED, workspaces.get(valid.workingDraftId).status)

        val invalid = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "   ")
        assertTrue(!workspaces.validate(invalid.workingDraftId).passed)
        assertEquals(WorkingDraftStatus.INVALID, workspaces.get(invalid.workingDraftId).status)
        f.close()
    }

    @Test
    fun `a validated draft becomes working again after replace`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val created = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "第一段。")
        workspaces.validate(created.workingDraftId)
        assertEquals(WorkingDraftStatus.VALIDATED, workspaces.get(created.workingDraftId).status)

        workspaces.replace(created.workingDraftId, "第二段。")

        assertEquals(WorkingDraftStatus.WORKING, workspaces.get(created.workingDraftId).status)
        f.close()
    }

    @Test
    fun `create requires a resolvable project and chapter even without base`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)

        val draft = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "正文", baseDraftId = null)

        assertEquals(null, draft.base.baseDraftId, "无基线时 base 只记录章节状态")
        assertNotNull(draft.base.baseChapterStatus)
        assertEquals(1, workspaces.list(w.projectId).size)
        f.close()
    }

    private fun assertEntityNotFound(block: () -> Unit) {
        val ex = assertFailsWith<ApplicationException>(block = block)
        assertTrue(
            ex.error is ApplicationError.EntityNotFound,
            "越界 / 不存在一律按 NOT_FOUND 拒绝，不泄漏存在性：${ex.error}",
        )
    }
}