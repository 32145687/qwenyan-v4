package com.qianyan.application.usecase.draft

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.workingdraft.WorkingDraftBase
import com.qianyan.model.workingdraft.WorkingDraftLimits
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workingdraft.WorkingDraftValidationCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I8 · Working Draft Validation 测试（§23 Validation / Determinism）。
 *
 * 覆盖：合法 / 空正文 / 超长 / target 不存在 / project 与 chapter 作用域 /
 * session 与 activity 归属 / 基线 / 终态 / 受控 Markdown 降级 / 与基线相同 / 确定性。
 *
 * 校验复用既有 `ValidationResult` / `ValidationIssue` / `IssueSeverity`（不新建第二套 Validation 模型）。
 */
class WorkingDraftValidationTest {

    private fun codes(result: ValidationResult): List<String> = result.issues.map { it.field }

    private fun severityOf(result: ValidationResult, code: String): IssueSeverity? =
        result.issues.firstOrNull { it.field == code }?.severity

    @Test
    fun `a well formed working draft passes without findings`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val validator = validatorOf(f)

        val result = validator.validate(
            workingDraftOf("wd-ok", w.projectId, w.novelId, w.chapter2, "第一段。\n\n第二段。"),
        )

        assertTrue(result.passed, "合法草稿应通过：${result.issues}")
        assertTrue(result.issues.isEmpty(), "无 finding")
        f.close()
    }

    @Test
    fun `empty content is an error`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val result = validatorOf(f).validate(
            workingDraftOf("wd-empty", w.projectId, w.novelId, w.chapter2, "   "),
        )

        assertFalse(result.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(result, WorkingDraftValidationCodes.EMPTY_CONTENT))
        f.close()
    }

    @Test
    fun `extremely long content is an error`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val result = validatorOf(f).validate(
            workingDraftOf(
                "wd-long",
                w.projectId,
                w.novelId,
                w.chapter2,
                "x".repeat(WorkingDraftLimits.MAX_CONTENT_CHARS + 1),
            ),
        )

        assertFalse(result.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(result, WorkingDraftValidationCodes.CONTENT_TOO_LONG))
        f.close()
    }

    @Test
    fun `unknown target chapter is an error`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val result = validatorOf(f).validate(
            workingDraftOf("wd-ghost", w.projectId, w.novelId, ChapterId("c-ghost"), "正文"),
        )

        assertFalse(result.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(result, WorkingDraftValidationCodes.TARGET_NOT_FOUND))
        f.close()
    }

    @Test
    fun `project scope mismatch is an error`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val b = seedWorld(f, "书B", "B")
        val validator = validatorOf(f)

        // Project A 的 id + Project B 的 novel
        val crossNovel = validator.validate(workingDraftOf("wd-x1", a.projectId, b.novelId, b.chapter2, "正文"))
        assertFalse(crossNovel.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(crossNovel, WorkingDraftValidationCodes.PROJECT_SCOPE_MISMATCH))

        // Project 不存在
        val ghost = validator.validate(
            workingDraftOf("wd-x2", ProjectId("p-ghost"), a.novelId, a.chapter2, "正文"),
        )
        assertFalse(ghost.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(ghost, WorkingDraftValidationCodes.PROJECT_SCOPE_MISMATCH))
        f.close()
    }

    @Test
    fun `target chapter from another novel is an error`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val b = seedWorld(f, "书B", "B")

        // 声称 target.novelId = A，但 chapter 属于 B
        val result = validatorOf(f).validate(workingDraftOf("wd-y1", a.projectId, a.novelId, b.chapter2, "正文"))

        assertFalse(result.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(result, WorkingDraftValidationCodes.TARGET_NOT_FOUND))
        f.close()
    }

    @Test
    fun `chapter variant scope mismatch is an error`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val result = validatorOf(f).validate(
            workingDraftOf(
                "wd-v1",
                w.projectId,
                w.novelId,
                w.chapter2,
                "正文",
                variantId = VariantId("v-ghost"),
            ),
        )

        assertFalse(result.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(result, WorkingDraftValidationCodes.CHAPTER_SCOPE_MISMATCH))
        f.close()
    }

    @Test
    fun `session and activity scope mismatches are errors`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val b = seedWorld(f, "书B", "B")
        val validator = validatorOf(f)

        // Session 属于另一个 Project
        val foreignSession = validator.validate(
            workingDraftOf("wd-s1", a.projectId, a.novelId, a.chapter2, "正文", sessionId = b.sessionId),
        )
        assertFalse(foreignSession.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(foreignSession, WorkingDraftValidationCodes.SESSION_SCOPE_MISMATCH))

        // Activity 不属于本 Project
        val foreignActivity = validator.validate(
            workingDraftOf("wd-a1", a.projectId, a.novelId, a.chapter2, "正文", activityId = b.activityId),
        )
        assertFalse(foreignActivity.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(foreignActivity, WorkingDraftValidationCodes.ACTIVITY_SCOPE_MISMATCH))

        // Activity 存在但属于另一个 Session（本 Project 内）
        val otherSession = f.app.agentSessions.startSession(a.novelId)
        val mismatched = validator.validate(
            workingDraftOf(
                "wd-a2",
                a.projectId,
                a.novelId,
                a.chapter2,
                "正文",
                sessionId = otherSession.sessionId,
                activityId = a.activityId,
            ),
        )
        assertFalse(mismatched.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(mismatched, WorkingDraftValidationCodes.ACTIVITY_SCOPE_MISMATCH))
        f.close()
    }

    @Test
    fun `base draft must exist and belong to the target chapter`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val validator = validatorOf(f)

        val missing = validator.validate(
            workingDraftOf(
                "wd-b1",
                a.projectId,
                a.novelId,
                a.chapter2,
                "正文",
                base = WorkingDraftBase(baseDraftId = DraftId("d-ghost")),
            ),
        )
        assertFalse(missing.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(missing, WorkingDraftValidationCodes.BASE_DRAFT_NOT_FOUND))

        // 基线 Draft 属于 chapter2，但 target 指向 chapter1
        val wrongTarget = validator.validate(
            workingDraftOf(
                "wd-b2",
                a.projectId,
                a.novelId,
                a.chapter1,
                "正文",
                base = WorkingDraftBase(baseDraftId = a.baseDraftId),
            ),
        )
        assertFalse(wrongTarget.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(wrongTarget, WorkingDraftValidationCodes.BASE_DRAFT_NOT_FOUND))
        f.close()
    }

    @Test
    fun `discarded draft has an invalid state finding`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val created = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "第一段。")
        workspaces.discard(created.workingDraftId)

        val result = workspaces.validate(created.workingDraftId)

        assertFalse(result.passed)
        assertEquals(IssueSeverity.ERROR, severityOf(result, WorkingDraftValidationCodes.INVALID_STATE))
        assertEquals(
            WorkingDraftStatus.DISCARDED,
            workspaces.get(created.workingDraftId).status,
            "终态不得被校验改写",
        )
        f.close()
    }

    @Test
    fun `illegal controlled markdown structure is a warning not an error`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val result = validatorOf(f).validate(
            workingDraftOf("wd-md", w.projectId, w.novelId, w.chapter2, "| 列A | 列B |\n| --- | --- |"),
        )

        assertTrue(result.passed, "WARNING 不影响 passed（§13）")
        assertEquals(IssueSeverity.WARNING, severityOf(result, WorkingDraftValidationCodes.CONTROLLED_MARKDOWN_DEGRADED))
        f.close()
    }

    @Test
    fun `content identical to the base draft is an info finding`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val baseContent = f.app.writerUseCases.draft(w.baseDraftId)?.content

        val result = validatorOf(f).validate(
            workingDraftOf(
                "wd-same",
                w.projectId,
                w.novelId,
                w.chapter2,
                baseContent!!,
                base = WorkingDraftBase(baseDraftId = w.baseDraftId),
            ),
        )

        assertTrue(result.passed, "INFO 不影响 passed")
        assertEquals(IssueSeverity.INFO, severityOf(result, WorkingDraftValidationCodes.UNCHANGED_FROM_BASE))
        f.close()
    }

    @Test
    fun `validation is deterministic across repeated runs`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val validator = validatorOf(f)
        val draft = workingDraftOf("wd-det", w.projectId, w.novelId, w.chapter2, "第一段。\n\n第二段。")

        val first = validator.validate(draft)
        val second = validator.validate(draft)
        val third = validator.validate(draft)

        assertEquals(first, second, "同输入 ⇒ 同结果（无 LLM / 无时间 / 无随机）")
        assertEquals(second, third)
        f.close()
    }

    @Test
    fun `use case level validation is repeatable and idempotent`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val created = workspaces.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "第一段。")

        val first = workspaces.validate(created.workingDraftId)
        val afterFirst = workspaces.get(created.workingDraftId)
        val second = workspaces.validate(created.workingDraftId)

        assertEquals(first, second, "重复校验返回同一结果")
        assertEquals(afterFirst, workspaces.get(created.workingDraftId), "状态已收敛 ⇒ 不再变动（幂等）")
        f.close()
    }

    @Test
    fun `finding order is stable`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        // 同时命中：空正文 + 不存在的 target + 不存在的基线
        val result = validatorOf(f).validate(
            workingDraftOf(
                "wd-multi",
                w.projectId,
                w.novelId,
                ChapterId("c-ghost"),
                "  ",
                base = WorkingDraftBase(baseDraftId = DraftId("d-ghost")),
            ),
        )

        assertEquals(
            listOf(
                WorkingDraftValidationCodes.TARGET_NOT_FOUND,
                WorkingDraftValidationCodes.BASE_DRAFT_NOT_FOUND,
                WorkingDraftValidationCodes.EMPTY_CONTENT,
            ),
            codes(result),
            "findings 按固定检查序列输出（状态 → target → 归属 → 基线 → 内容）",
        )
        f.close()
    }
}