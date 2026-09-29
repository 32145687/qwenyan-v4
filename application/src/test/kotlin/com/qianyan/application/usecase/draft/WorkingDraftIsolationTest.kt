package com.qianyan.application.usecase.draft

import com.qianyan.model.workingdraft.WorkingDraftTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I8 · Canonical 隔离 + 边界测试（§20 / §23 Canonical Isolation / Boundary）。
 *
 * 证明：
 *  - Working Draft 的 create / replace / edit / validate / discard **不修改** Chapter / 既有 Draft /
 *    StoryFoundation / ProjectState（行数不变 ⇒ 也不落库）；
 *  - 既有 Draft lineage（`previousDraftId`）与 `latestByChapter` 结果不受影响；
 *  - I8 源码边界：不访问存储实现、不调用 LLM / Agent、不执行 Tool、不构建 Context、不判权限、不 Commit。
 */
class WorkingDraftIsolationTest {

    @Test
    fun `working draft operations leave canonical data untouched`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val chapterBefore = f.app.chapters.findById(w.chapter2)
        val draftBefore = f.app.draftRepository.getById(w.canonicalDraftId)
        val stateBefore = f.app.projects.state(w.projectId)
        val tables = listOf("Chapter", "ChapterDraft", "ProjectState", "StoryFoundation", "Activity", "ToolCallLog", "AgentSession", "Workflow", "Task")
        val countsBefore = tables.associateWith { countRows(f.handle, it) }

        val workspaces = fixedClockWorkingDrafts(f)
        val draft = workspaces.create(
            projectId = w.projectId,
            target = WorkingDraftTarget(w.novelId, w.chapter2),
            content = "临时正文",
            sessionId = w.sessionId,
            activityId = w.activityId,
            baseDraftId = w.baseDraftId,
        )
        workspaces.replace(draft.workingDraftId, "临时正文（替换）")
        workspaces.edit(draft.workingDraftId) { "$it（编辑）" }
        workspaces.validate(draft.workingDraftId)
        workspaces.discard(draft.workingDraftId)

        // Canonical 数据逐一不变
        assertEquals(chapterBefore, f.app.chapters.findById(w.chapter2), "Chapter 不得被修改")
        assertEquals(draftBefore, f.app.draftRepository.getById(w.canonicalDraftId), "既有 Draft 不得被修改")
        assertEquals(stateBefore, f.app.projects.state(w.projectId), "ProjectState 不得被修改")
        // 行数不变 ⇒ Working Draft 未写入任何表（本阶段无表 / 无迁移）
        countsBefore.forEach { (table, count) ->
            assertEquals(count, countRows(f.handle, table), "$table 行数不得变化（Working Draft 不落库）")
        }
        f.close()
    }

    @Test
    fun `existing draft lineage and latest draft are preserved`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val latestBefore = f.app.writerUseCases.latestDraft(w.chapter2)
        val baseBefore = f.app.draftRepository.getById(w.baseDraftId)
        assertNotNull(latestBefore)
        assertEquals(w.canonicalDraftId, latestBefore.draftId)
        assertEquals(w.baseDraftId, latestBefore.previousDraftId, "既有 lineage 基线")

        val workspaces = fixedClockWorkingDrafts(f)
        val draft = workspaces.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "临时正文",
            baseDraftId = w.canonicalDraftId,
        )
        workspaces.validate(draft.workingDraftId)

        assertEquals(latestBefore, f.app.writerUseCases.latestDraft(w.chapter2), "latestByChapter 不得被 Working Draft 影响")
        assertEquals(baseBefore, f.app.draftRepository.getById(w.baseDraftId), "lineage 基线 Draft 不得被修改")
        assertEquals(
            w.baseDraftId,
            f.app.writerUseCases.latestDraft(w.chapter2)?.previousDraftId,
            "既有 previousDraftId 链路不得被改写",
        )
        assertEquals(
            setOf(w.baseDraftId, w.canonicalDraftId),
            f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId }.toSet(),
            "Working Draft 不得进入既有 Draft 集合（不落 ChapterDraft）",
        )
        f.close()
    }

    @Test
    fun `working draft sources stay layered and future stage free`() {
        val dir = File("src/main/kotlin/com/qianyan/application/usecase/draft")
        assertTrue(dir.isDirectory, "找不到 Working Draft 源码目录：${dir.absolutePath}")
        val code = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
        listOf(
            // 不直连存储实现 / 不写 Canonical 表
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "storage.repository", "Repository", "ChapterDraft",
            // 不调用 LLM / Provider / Agent runtime
            "LLMGateway", "ModelProfile", "provider.api", "provider.impl", "AgentRuntime", "RevisionAgent", "WriterAgent",
            // 不执行 Tool / 不构建 Context / 不判权限 / 不推进 Workflow
            "ToolExecutor", "ToolRegistry", "SkillRegistry", "ContextEngine", "ContextPack", "ActionPolicy",
            "WorkflowOrchestrator", "approveGate", "AgentSessionStatus",
            // 不吸收后续阶段职责（I9 / I10 / I14）
            "Artifact", "Diff", "Commit", "Revert", "History", "Queue", "Inspector", "ProjectIndex",
        ).forEach { token -> assertTrue(token !in code, "Working Draft 不得出现 '$token'（只读 + 分层）") }
    }

    @Test
    fun `working draft is not a second agent lifecycle`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val sessionBefore = f.app.agentSessions.sessionOf(w.sessionId)

        val workspaces = fixedClockWorkingDrafts(f)
        val draft = workspaces.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "临时正文",
            sessionId = w.sessionId,
            activityId = w.activityId,
        )
        workspaces.validate(draft.workingDraftId)

        assertEquals(
            sessionBefore,
            f.app.agentSessions.sessionOf(w.sessionId),
            "Working Draft 不推进 AgentSession 状态",
        )
        assertEquals(
            sessionBefore.sessionId,
            f.app.activities.get(w.activityId).sessionId,
            "Working Draft 不改写 Activity 归属",
        )
        f.close()
    }
}