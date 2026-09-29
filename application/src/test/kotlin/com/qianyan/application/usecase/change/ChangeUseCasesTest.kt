package com.qianyan.application.usecase.change

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.draft.fixedClockWorkingDrafts
import com.qianyan.application.usecase.draft.seedWorld
import com.qianyan.application.usecase.draft.workingDraftFixture
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.change.ChangeArtifactStatus
import com.qianyan.model.change.ChangeKind
import com.qianyan.model.change.DiffLineKind
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I9 · Change / Artifact 测试（§12 Change / Artifact / Canonical Isolation 前置）。
 *
 * 覆盖：Artifact 与 Working Draft / target / base 的关联、change kind、status 派生、
 * 校验复用、get / list / scope、同输入确定性、冻结与"被取代"判定、不推进 I8 状态、容器 seam 共享。
 */
class ChangeUseCasesTest {

    @Test
    fun `prepare binds the artifact to working draft target and base`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(
            projectId = w.projectId,
            target = WorkingDraftTarget(w.novelId, w.chapter2),
            content = "A 第二章初稿（改）",
            baseDraftId = w.baseDraftId,
        )

        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertEquals(w.projectId, artifact.projectId)
        assertEquals(draft.workingDraftId, artifact.workingDraftId)
        assertEquals(w.novelId, artifact.change.target.novelId)
        assertEquals(w.chapter2, artifact.change.target.chapterId)
        assertEquals(draft.target, artifact.change.target)
        assertEquals(w.baseDraftId, artifact.change.base.baseDraftId, "复用 I8 base reference")
        assertEquals(DraftStatus.WRITTEN, artifact.change.base.baseDraftStatus)
        assertEquals(ChangeKind.MODIFIED, artifact.change.kind)
        assertTrue(artifact.change.summary.isNotBlank())
        assertTrue(artifact.change.validation.passed, "校验复用 I8 确定性 Validator")
        assertEquals(ChangeArtifactStatus.READY, artifact.status)
        assertTrue(artifact.readyForReview)
        f.close()
    }

    @Test
    fun `change kind follows the diff and a missing base means added`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "全新正文\n第二行",
        )

        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertEquals(ChangeKind.ADDED, artifact.change.kind, "无基线 ⇒ 新增")
        assertTrue(artifact.change.diff.hunks.any { it.kind == DiffLineKind.ADDED })
        assertEquals(2, artifact.change.diff.addedLines)
        assertEquals(ChangeArtifactStatus.READY, artifact.status)
        f.close()
    }

    @Test
    fun `content identical to base yields no changes status`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val canonical = f.app.writerUseCases.draft(w.canonicalDraftId)!!
        val draft = f.app.workingDrafts.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            canonical.content,
            baseDraftId = w.canonicalDraftId,
        )

        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertEquals(ChangeKind.UNCHANGED, artifact.change.kind)
        assertEquals(ChangeArtifactStatus.NO_CHANGES, artifact.status)
        assertFalse(artifact.readyForReview, "无变化 ⇒ 不可进入后续流程")
        f.close()
    }

    @Test
    fun `validation errors make the artifact invalid`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "   ",
            baseDraftId = w.baseDraftId,
        )

        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertFalse(artifact.change.validation.passed)
        assertEquals(ChangeArtifactStatus.INVALID, artifact.status)
        assertFalse(artifact.readyForReview)
        f.close()
    }

    @Test
    fun `get returns the stored artifact and rejects unknown`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(w.projectId, WorkingDraftTarget(w.novelId, w.chapter2), "正文")
        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertEquals(artifact, f.app.changes.get(artifact.artifactId))
        val ex = assertFailsWith<ApplicationException> { f.app.changes.get(ChangeArtifactId("artifact-ghost")) }
        assertTrue(ex.error is ApplicationError.EntityNotFound)
        f.close()
    }

    @Test
    fun `list is sorted by artifact id and scoped to the project`() {
        val f = workingDraftFixture()
        val a = seedWorld(f, "书A", "A")
        val b = seedWorld(f, "书B", "B")
        val draftsA1 = f.app.workingDrafts.create(a.projectId, WorkingDraftTarget(a.novelId, a.chapter2), "A 正文一", baseDraftId = a.baseDraftId)
        val draftsA2 = f.app.workingDrafts.create(a.projectId, WorkingDraftTarget(a.novelId, a.chapter1), "A 正文二")
        val draftsB = f.app.workingDrafts.create(b.projectId, WorkingDraftTarget(b.novelId, b.chapter2), "B 正文", baseDraftId = b.baseDraftId)
        val artifactA1 = f.app.changes.prepare(draftsA1.workingDraftId)
        val artifactA2 = f.app.changes.prepare(draftsA2.workingDraftId)
        val artifactB = f.app.changes.prepare(draftsB.workingDraftId)

        val listedA = f.app.changes.list(a.projectId)

        assertEquals(2, listedA.size, "只返回本 Project 的 Artifact")
        assertEquals(setOf(artifactA1.artifactId, artifactA2.artifactId), listedA.map { it.artifactId }.toSet())
        assertEquals(
            listedA.map { it.artifactId.value }.sorted(),
            listedA.map { it.artifactId.value },
            "按 artifactId 升序（确定性）",
        )
        assertEquals(listOf(artifactB.artifactId), f.app.changes.list(b.projectId).map { it.artifactId })
        f.close()
    }

    @Test
    fun `same input yields the same artifact content status and fingerprint`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val changes = fixedClockChanges(f, workspaces)
        val draft = workspaces.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "A 第二章初稿（改）",
            baseDraftId = w.baseDraftId,
        )

        val first = changes.prepare(draft.workingDraftId)
        val second = changes.prepare(draft.workingDraftId)

        assertEquals(first.change, second.change, "同输入 ⇒ 同 Change")
        assertEquals(first.status, second.status)
        assertEquals(first.fingerprint, second.fingerprint, "同输入 ⇒ 同指纹")
        assertEquals(first.createdAt, second.createdAt, "固定时钟 ⇒ 同时间戳")
        assertEquals(first.copy(artifactId = second.artifactId), second, "除身份外完全一致")
        f.close()
    }

    @Test
    fun `artifact is frozen and superseded detection follows working draft changes`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val workspaces = fixedClockWorkingDrafts(f)
        val changes = fixedClockChanges(f, workspaces)
        val draft = workspaces.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "A 第二章初稿（改）",
            baseDraftId = w.baseDraftId,
        )
        val artifact = changes.prepare(draft.workingDraftId)

        assertFalse(changes.isSuperseded(artifact.artifactId), "刚生成 ⇒ 未被取代")

        workspaces.replace(draft.workingDraftId, "A 第二章初稿（再次改）")

        assertTrue(changes.isSuperseded(artifact.artifactId), "Working Draft 变化 ⇒ 旧 Artifact 被取代")
        assertEquals(artifact, changes.get(artifact.artifactId), "Artifact 冻结：自身数据不变")
        assertEquals(
            listOf("A 第二章初稿（改）"),
            artifact.change.diff.hunks.single { it.kind == DiffLineKind.ADDED }.lines,
            "冻结的 Diff 仍描述生成时的内容，不伪装成最新结果",
        )

        val refreshed = changes.prepare(draft.workingDraftId)
        assertFalse(changes.isSuperseded(refreshed.artifactId), "按最新内容重新生成 ⇒ 未被取代")
        assertTrue(refreshed.fingerprint != artifact.fingerprint, "内容变化 ⇒ 指纹变化")
        f.close()
    }

    @Test
    fun `prepare does not advance the working draft status`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "A 第二章初稿（改）",
            baseDraftId = w.baseDraftId,
        )
        assertEquals(WorkingDraftStatus.WORKING, draft.status)

        f.app.changes.prepare(draft.workingDraftId)

        assertEquals(
            WorkingDraftStatus.WORKING,
            f.app.workingDrafts.get(draft.workingDraftId).status,
            "I9 不推进 I8 的 Working Draft 状态",
        )
        f.close()
    }

    @Test
    fun `container seam shares the same working draft workspace`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "正文",
            baseDraftId = w.baseDraftId,
        )

        // 若 changes 未共享同一 Working Draft 工作区，这里会 EntityNotFound
        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertEquals(draft.workingDraftId, artifact.workingDraftId)
        f.close()
    }
}