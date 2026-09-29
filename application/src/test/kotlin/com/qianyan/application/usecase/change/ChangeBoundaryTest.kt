package com.qianyan.application.usecase.change

import com.qianyan.application.usecase.draft.countRows
import com.qianyan.application.usecase.draft.seedWorld
import com.qianyan.application.usecase.draft.workingDraftFixture
import com.qianyan.model.workingdraft.WorkingDraftTarget
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I9 · Canonical 隔离 + 边界测试（§4 / §5 / §12 Canonical Isolation / Boundary）。
 *
 * 证明：
 *  - 生成 / 读取 / 列出 / 判定 Artifact **不修改**任何 Canonical 或记录数据，也不落库；
 *  - 不创建 Canonical Draft（既有 `ChapterDraft` 行数与集合不变）；
 *  - 复用既有模型（Validation / Working Draft）而非新建第二套；
 *  - 源码边界：无存储实现访问、无 LLM / Agent / Tool / Context / 权限 / 流程编排，且不含 Commit / History / Revert。
 */
class ChangeBoundaryTest {

    @Test
    fun `artifact operations leave canonical and log data untouched`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)

        val chapterBefore = f.app.chapters.findById(w.chapter2)
        val canonicalDraftBefore = f.app.draftRepository.getById(w.canonicalDraftId)
        val stateBefore = f.app.projects.state(w.projectId)
        val foundationBefore = f.app.storyFoundationRepository.getStoryFoundation(w.novelId)
        val tables = listOf(
            "Chapter", "ChapterDraft", "ProjectState", "StoryFoundation",
            "AgentSession", "Activity", "ToolCallLog", "Workflow", "Task",
        )
        val countsBefore = tables.associateWith { countRows(f.handle, it) }

        val draft = f.app.workingDrafts.create(
            projectId = w.projectId,
            target = WorkingDraftTarget(w.novelId, w.chapter2),
            content = "A 第二章初稿（改）",
            sessionId = w.sessionId,
            activityId = w.activityId,
            baseDraftId = w.baseDraftId,
        )
        val artifact = f.app.changes.prepare(draft.workingDraftId)
        f.app.changes.get(artifact.artifactId)
        f.app.changes.list(w.projectId)
        f.app.changes.isSuperseded(artifact.artifactId)

        assertEquals(chapterBefore, f.app.chapters.findById(w.chapter2), "Chapter 不得被修改")
        assertEquals(canonicalDraftBefore, f.app.draftRepository.getById(w.canonicalDraftId), "既有 Draft 不得被修改")
        assertEquals(stateBefore, f.app.projects.state(w.projectId), "ProjectState 不得被修改")
        assertEquals(
            foundationBefore,
            f.app.storyFoundationRepository.getStoryFoundation(w.novelId),
            "StoryFoundation 不得被修改",
        )
        assertEquals(
            setOf(w.baseDraftId, w.canonicalDraftId),
            f.app.draftRepository.listByChapter(w.chapter2).map { it.draftId }.toSet(),
            "不得创建新的 Canonical Draft",
        )
        countsBefore.forEach { (table, count) ->
            assertEquals(count, countRows(f.handle, table), "$table 行数不得变化（Change Review 不落库）")
        }
        f.close()
    }

    @Test
    fun `change review reuses existing validation and working draft models`() {
        val f = workingDraftFixture()
        val w = seedWorld(f)
        val draft = f.app.workingDrafts.create(
            w.projectId,
            WorkingDraftTarget(w.novelId, w.chapter2),
            "A 第二章初稿（改）",
            baseDraftId = w.baseDraftId,
        )
        val artifact = f.app.changes.prepare(draft.workingDraftId)

        assertEquals(
            "com.qianyan.model.spec.ValidationResult",
            artifact.change.validation::class.qualifiedName,
            "复用既有 ValidationResult（不新建第二套 Validation）",
        )
        assertEquals(
            "com.qianyan.model.workingdraft.WorkingDraftId",
            artifact.workingDraftId::class.qualifiedName,
            "复用 I8 Working Draft 身份（不新建第二套 Draft）",
        )
        assertEquals(
            "com.qianyan.model.workingdraft.WorkingDraftTarget",
            artifact.change.target::class.qualifiedName,
            "复用 I8 target",
        )
        f.close()
    }

    @Test
    fun `change review sources stay layered and future stage free`() {
        val dir = File("src/main/kotlin/com/qianyan/application/usecase/change")
        assertTrue(dir.isDirectory, "找不到 Change Review 源码目录：${dir.absolutePath}")
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
            "LLMGateway", "ModelProfile", "provider.api", "provider.impl", "AgentRuntime", "WriterAgent", "RevisionAgent",
            // 不执行 Tool / 不构建 Context / 不判权限 / 不推进 Workflow
            "ToolExecutor", "ToolRegistry", "SkillRegistry", "ContextEngine", "ContextPack", "ActionPolicy",
            "WorkflowOrchestrator", "approveGate", "AgentSessionStatus",
            // 不吸收 I10 / I11 / I12 / I14 职责
            "Commit", "Revert", "Undo", "Rollback", "History", "Queue", "Inspector", "ProjectIndex", "NovelAgent",
        ).forEach { token -> assertTrue(token !in code, "Change Review 不得出现 '$token'（只读 + 分层）") }
    }
}