package com.qianyan.application.usecase.commit

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.draft.countRows
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.ActionRisk
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowHumanGateId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I10 · ActionPolicy 复用 + Boundary（§13 / §14 / §22 / §23）。
 *
 * 证明：放行判定**完全复用 I2**（不新建 Policy / Gate）；Commit 只做"这项已被允许提交的变更是否可安全写入"；
 * 源码保持分层（无存储实现 / 无 LLM / 无 Agent / 无 Tool / 无 Context / 无 I11+ / 无 P19 决策 / 无 VCS 过度设计）。
 */
class CommitBoundaryTest {

    @Test
    fun `commit and revert permissions come from existing action policy`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val artifact = artifactFor(f, w, "A 第二章正文（提交稿）")

        // I2 的 ActionDecision（既有类型，非第二套判定结果）
        val commitDecision = f.app.commits.evaluateCommit(artifact)
        assertTrue(commitDecision is ActionDecision.NeedsHuman, "COMMIT_CANONICAL 属高风险 ⇒ 须人工确认")
        assertEquals(ActionRisk.HIGH, commitDecision.risk)

        val commit = f.app.commits.commit(w.projectId, artifact.artifactId, approvedGate(f, w))
        val revertDecision = f.app.commits.evaluateRevert(commit.historyEntry)
        assertTrue(revertDecision is ActionDecision.NeedsHuman, "回退是对 Canonical 的修改 ⇒ 须人工确认")
        assertEquals(ActionRisk.HIGH, revertDecision.risk)

        // 凭据就是既有的 Workflow Human Gate 身份（不新建 Gate 体系）
        val approval = approvedGate(f, w, gateId = "gate-i10-shape")
        assertEquals(WorkflowHumanGateId("gate-i10-shape"), approval.gateId)
        assertNotNull(f.app.workflows.getGate(approval.gateId), "批准凭据必须指向既有 Human Gate 记录")
        f.close()
    }

    @Test
    fun `commit requires approval and never creates or mutates human gates`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val artifact = artifactFor(f, w, "A 第二章正文（提交稿）")

        // 未批准 ⇒ 拒绝（且不改动任何数据）
        val e = assertFailsWith<ApplicationException> { f.app.commits.commit(w.projectId, artifact.artifactId) }
        assertTrue(e.message!!.contains(CommitErrorCodes.HUMAN_APPROVAL_REQUIRED))
        assertEquals(0, countRows(f.handle, "CommitHistory"))

        // 既有 Gate 已 REJECTED ⇒ 仍拒绝
        val rejected = approvedGate(
            f,
            w,
            gateId = "gate-i10-rejected",
            status = HumanGateStatus.RESOLVED,
            decision = com.qianyan.model.workflow.HumanDecision.REJECTED,
        )
        assertFailsWith<ApplicationException> { f.app.commits.commit(w.projectId, artifact.artifactId, rejected) }

        // 批准后提交成功，且 I10 **不创建 / 不修改**任何 Gate 记录
        val gatesBefore = countRows(f.handle, "WorkflowHumanGate")
        val approved = approvedGate(f, w, gateId = "gate-i10-ok")
        val gateBefore = assertNotNull(f.app.workflows.getGate(WorkflowHumanGateId("gate-i10-ok")))
        f.app.commits.commit(w.projectId, artifact.artifactId, approved)
        assertEquals(gatesBefore + 1, countRows(f.handle, "WorkflowHumanGate"), "只应存在测试夹具写入的 Gate")
        assertEquals(gateBefore, f.app.workflows.getGate(WorkflowHumanGateId("gate-i10-ok")), "Commit 不得修改 Gate")
        f.close()
    }

    @Test
    fun `commit reuses existing draft validation and working draft models`() {
        val f = commitFixture()
        val w = commitWorld(f)
        val approval = approvedGate(f, w)
        val artifact = artifactFor(f, w, "A 第二章正文（提交稿）")

        val result = f.app.commits.commit(w.projectId, artifact.artifactId, approval)

        // 复用 I9 Artifact 身份 / I8 Working Draft 身份 / 既有 Draft 模型（无第二套）
        assertEquals("com.qianyan.model.change.ChangeArtifactId", result.artifactId::class.qualifiedName)
        assertEquals("com.qianyan.model.writing.Draft", f.app.writerUseCases.latestDraft(w.chapter2)!!::class.qualifiedName)
        assertEquals("com.qianyan.model.workingdraft.WorkingDraftTarget", result.target::class.qualifiedName)
        assertEquals("com.qianyan.model.commit.CommitHistoryEntry", result.historyEntry::class.qualifiedName)
        f.close()
    }

    @Test
    fun `commit sources stay layered and future stage free`() {
        val code = sourceOf("src/main/kotlin/com/qianyan/application/usecase/commit")
        listOf(
            // 不直连存储实现 / 不写其它 Canonical 表
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "Sqlite", "ChapterDraft", "ProjectState",
            // 不调用 LLM / Provider / Agent runtime
            "LLMGateway", "ModelProfile", "provider.api", "provider.impl", "AgentRuntime", "WriterAgent", "RevisionAgent",
            // 不执行 Tool / 不构建 Context / 不推进 Workflow 生命周期 / 不新建 Gate
            "ToolExecutor", "ToolRegistry", "SkillRegistry", "ContextEngine", "ContextPack", "WorkflowOrchestrator",
            "approveGate", "createPendingGate", "AgentSessionStatus", "WorkflowStatus", "TaskStatus",
            // 不新建第二套权限系统
            "CommitPolicy", "RevertPolicy", "HistoryPolicy", "DecisionPolicy", "DecisionModel",
            // 不是 Git / 版本控制系统
            "Branch", "Merge", "Rebase", "CRDT", "EventSourcing", "Event Sourcing",
            // 不吸收 I11+ 职责
            "Queue", "Inspector", "ProjectIndex", "NovelAgent",
        ).forEach { token -> assertTrue(token !in code, "Commit 层不得出现 '$token'（复用 I2/I8/I9 + 分层）") }

        // 复用证据（正面断言，防止"重新实现一套"）
        listOf("ActionPolicyUseCases", "ChangeUseCases", "WorkingDraftUseCases", "inTransaction", "CommitHistoryRepository")
            .forEach { token -> assertTrue(token in code, "Commit 层必须复用既有能力：'$token'") }
    }

    @Test
    fun `commit models stay pure domain`() {
        val code = sourceOf("../core/model/src/main/kotlin/com/qianyan/model/commit")
        listOf(
            "SqlDriver", "QianyanDb", "storage", "LLMGateway", "AgentRuntime", "DecisionPolicy", "DecisionModel",
            "Queue", "Inspector", "ProjectIndex",
        ).forEach { token -> assertTrue(token !in code, "Commit 领域契约不得依赖 '$token'") }
        listOf("CommitHistoryEntry", "CommitOperation", "CommitErrorCodes").forEach { token ->
            assertTrue(token in code, "缺少 I10 契约：'$token'")
        }
    }

    private fun sourceOf(relativeDir: String): String {
        val dir = File(relativeDir)
        assertTrue(dir.isDirectory, "找不到源码目录：${dir.absolutePath}")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
    }
}