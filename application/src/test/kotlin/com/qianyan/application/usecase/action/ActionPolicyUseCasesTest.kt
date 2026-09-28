package com.qianyan.application.usecase.action

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.NovelId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.ActionRisk
import com.qianyan.model.action.AgentAction
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I2 · Action Policy 测试。
 *
 * 覆盖（对应 I2 要求）：
 *  1. 允许动作：低/中风险可通过；
 *  2. 拒绝动作：明确排除的动作不会执行（类型化拒绝，且不开启 Gate）；
 *  3. Human Gate：NEEDS_HUMAN → 既有 Workflow Human Gate（PENDING）→ 既有 `WAITING_HUMAN` →
 *     复用既有 `APPROVED / REJECTED / REQUEST_REVISION` 语义；
 *  4. 不创建第二套状态机：仅判断不写任何 Workflow / Task / Gate 行；
 *  5. P19 隔离：本层不引用任何创作决策契约（源码结构守卫）；
 *  6. Deterministic：同输入同输出，且判断过程不调用 LLM。
 */
class ActionPolicyUseCasesTest {

    private class Fixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    /** 章节上下文（既有链路产出的真实 Chapter / Draft / Workflow / Step）。 */
    private class ChapterCtx(
        val novelId: NovelId,
        val draft: Draft,
        val draftId: com.qianyan.model.DraftId,
        val workflowId: WorkflowId,
        val stepId: WorkflowStepId,
    )

    /** 默认 Mock 网关（服务既有创作链；ActionPolicy 判断本身不得调用它）。 */
    private fun flowGateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in system -> """{"content":"AI 生成的正文"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in system -> """{"changes":[]}"""
            else -> """{"chapterGoal":"g"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }

    private fun fixture(): Fixture {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return Fixture(ApplicationContainer.fromDriver(handle.driver, flowGateway()), handle)
    }

    private fun action(kind: AgentActionKind, target: String = "chapter-1") = AgentAction(kind, target)

    /** 经**既有** Writer / Workflow 链路准备一个带 Draft 的章节上下文（无替身、无假数据）。 */
    private fun prepareChapter(f: Fixture, title: String): ChapterCtx {
        val novelId = f.app.novels.listOriginals().firstOrNull()?.novelId
            ?: f.app.novels.createOriginal(title = "I2 书")
        val chapter = f.app.chapters.createNextChapter(title, novelId)

        var guard = 0
        while (guard++ < 12 && f.app.draftRepository.latestByChapter(chapter.chapterId) == null) {
            if (f.app.workflowFacade.getChapterProgress(chapter.chapterId).waitingForUser) {
                f.app.workflowFacade.approve(chapter.chapterId)
            } else {
                f.app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
            }
        }
        val draft = assertNotNull(f.app.draftRepository.latestByChapter(chapter.chapterId), "既有 Writer 链路应产出 Draft")
        val wf = assertNotNull(f.app.workflows.getWorkflowByActiveChapter(chapter.chapterId), "应存在既有 Workflow")
        val stepId = assertNotNull(f.app.workflows.listSteps(wf.workflowId).firstOrNull(), "应存在既有 Workflow Step").stepId
        return ChapterCtx(novelId, draft, draft.draftId, wf.workflowId, stepId)
    }

    // ---------- 1. 允许动作 ----------

    @Test
    fun `low and medium risk actions are allowed`() {
        val f = fixture()
        val low = listOf(AgentActionKind.READ, AgentActionKind.SEARCH, AgentActionKind.ANALYZE, AgentActionKind.VALIDATE)
        val medium = listOf(
            AgentActionKind.CREATE_WORKING_DRAFT, AgentActionKind.EDIT_WORKING_DRAFT, AgentActionKind.PROPOSE_CHANGE,
        )

        low.forEach { kind ->
            val d = f.app.actionPolicy.evaluate(action(kind))
            assertTrue(d is ActionDecision.Allowed, "$kind 应为 Allowed，实际=$d")
            assertEquals(ActionRisk.LOW, d.risk)
        }
        medium.forEach { kind ->
            val d = f.app.actionPolicy.evaluate(action(kind))
            assertTrue(d is ActionDecision.Allowed, "$kind 应为 Allowed，实际=$d")
            assertEquals(ActionRisk.MEDIUM, d.risk)
        }
        assertEquals(ActionRisk.LOW, f.app.actionPolicy.requireAllowed(action(AgentActionKind.READ)).risk)
        f.close()
    }

    // ---------- 2. 拒绝动作 ----------

    @Test
    fun `excluded action is denied and never opens a gate`() {
        val f = fixture()
        val decision = f.app.actionPolicy.evaluate(action(AgentActionKind.DELETE))
        assertTrue(decision is ActionDecision.Denied, "DELETE 应被拒绝，实际=$decision")

        val ex = assertFailsWith<ApplicationException> { f.app.actionPolicy.requireAllowed(action(AgentActionKind.DELETE)) }
        assertTrue(ex.error is ApplicationError.InvalidOperation)

        val ctx = prepareChapter(f, "第 1 章")
        val gatesBefore = count(f.handle, "WorkflowHumanGate")
        val exGate = assertFailsWith<ApplicationException> {
            f.app.actionPolicy.openHumanGate(ctx.workflowId, ctx.stepId, ctx.draftId, action(AgentActionKind.DELETE))
        }
        assertTrue(exGate.error is ApplicationError.InvalidOperation, "DENY 动作不得开启 Gate")
        assertEquals(gatesBefore, count(f.handle, "WorkflowHumanGate"), "被拒绝的动作不得写 Gate")
        f.close()
    }

    // ---------- 3. Human Gate（复用既有语义） ----------

    @Test
    fun `high risk action uses existing gate and reuses approve semantics`() {
        val f = fixture()
        val ctx = prepareChapter(f, "第 1 章")

        assertTrue(
            f.app.actionPolicy.evaluate(action(AgentActionKind.COMMIT_CANONICAL)) is ActionDecision.NeedsHuman,
            "COMMIT_CANONICAL 应需人工确认",
        )
        // 非 NEEDS_HUMAN 的动作不得开启 Gate
        val exLow = assertFailsWith<ApplicationException> {
            f.app.actionPolicy.openHumanGate(ctx.workflowId, ctx.stepId, ctx.draftId, action(AgentActionKind.READ))
        }
        assertTrue(exLow.error is ApplicationError.InvalidOperation)

        // 开启既有 Gate → PENDING + 既有 WAITING_HUMAN + 既有 pendingGateId 绑定
        val gate = f.app.actionPolicy.openHumanGate(
            ctx.workflowId, ctx.stepId, ctx.draftId, action(AgentActionKind.COMMIT_CANONICAL),
        )
        assertEquals(HumanGateStatus.PENDING, gate.status)
        assertEquals(HumanDecision.PENDING, gate.decision)
        assertEquals(ctx.workflowId, gate.workflowId)
        assertEquals(ctx.draftId, gate.draftId)
        val waiting = assertNotNull(f.app.workflows.getWorkflow(ctx.workflowId))
        assertEquals(WorkflowStatus.WAITING_HUMAN, waiting.status, "复用既有 WAITING_HUMAN 语义")
        assertEquals(gate.gateId, waiting.pendingGateId, "复用既有 pendingGateId 绑定")

        // 幂等：同 (workflow, step, draft) 复用既有 gate
        val again = f.app.actionPolicy.openHumanGate(
            ctx.workflowId, ctx.stepId, ctx.draftId, action(AgentActionKind.COMMIT_CANONICAL),
        )
        assertEquals(gate.gateId, again.gateId, "既有 gateKey 方案保证幂等")

        // 既有 APPROVED 语义（确认 Draft + 既有 Knowledge Update 链）
        val approved = f.app.actionPolicy.resolveHumanGate(gate.gateId, HumanDecision.APPROVED)
        assertEquals(HumanGateStatus.RESOLVED, approved.status)
        assertEquals(HumanDecision.APPROVED, approved.decision)
        assertEquals(
            DraftStatus.CONFIRMED,
            f.app.draftRepository.getById(ctx.draftId)?.status,
            "APPROVED 复用既有确认链（Draft → CONFIRMED）",
        )
        f.close()
    }

    @Test
    fun `human gate reuses rejected and request revision semantics`() {
        val f = fixture()
        val first = prepareChapter(f, "第 1 章")
        val second = prepareChapter(f, "第 2 章")
        val gatesBefore = count(f.handle, "WorkflowHumanGate")

        val rejected = f.app.actionPolicy.resolveHumanGate(
            f.app.actionPolicy.openHumanGate(first.workflowId, first.stepId, first.draftId, action(AgentActionKind.MODIFY_CANONICAL)).gateId,
            HumanDecision.REJECTED,
        )
        assertEquals(HumanGateStatus.RESOLVED, rejected.status)
        assertEquals(HumanDecision.REJECTED, rejected.decision)
        assertEquals(DraftStatus.FINAL, f.app.draftRepository.getById(first.draftId)?.status, "REJECTED 不改动 Draft")

        // 不同 (workflow, step, draft) → 独立 gate，可表达 REQUEST_REVISION
        val revision = f.app.actionPolicy.resolveHumanGate(
            f.app.actionPolicy
                .openHumanGate(second.workflowId, second.stepId, second.draftId, action(AgentActionKind.UPDATE_WORLD_MODEL)).gateId,
            HumanDecision.REQUEST_REVISION,
        )
        assertEquals(HumanGateStatus.RESOLVED, revision.status)
        assertEquals(HumanDecision.REQUEST_REVISION, revision.decision)
        assertEquals(
            gatesBefore + 2,
            count(f.handle, "WorkflowHumanGate"),
            "每次 openHumanGate 一行（复用既有 gate 表，不新建第二套）",
        )

        // PENDING 不是决议
        val ex = assertFailsWith<ApplicationException> {
            f.app.actionPolicy.resolveHumanGate(rejected.gateId, HumanDecision.PENDING)
        }
        assertTrue(ex.error is ApplicationError.InvalidOperation)
        f.close()
    }

    // ---------- 4. 不创建第二套状态机 ----------

    @Test
    fun `policy evaluation writes nothing and adds no lifecycle rows`() {
        val f = fixture()
        prepareChapter(f, "第 1 章")
        val workflows = count(f.handle, "Workflow")
        val gates = count(f.handle, "WorkflowHumanGate")
        val tasks = count(f.handle, "Task")
        val steps = count(f.handle, "WorkflowStep")

        AgentActionKind.entries.forEach { f.app.actionPolicy.evaluate(action(it)) }
        assertFailsWith<ApplicationException> { f.app.actionPolicy.requireAllowed(action(AgentActionKind.COMMIT_CANONICAL)) }

        assertEquals(workflows, count(f.handle, "Workflow"), "判断不创建 Workflow")
        assertEquals(gates, count(f.handle, "WorkflowHumanGate"), "判断不创建 Gate")
        assertEquals(tasks, count(f.handle, "Task"), "判断不创建 Task")
        assertEquals(steps, count(f.handle, "WorkflowStep"), "判断不创建 WorkflowStep")
        f.close()
    }

    // ---------- 5. P19 隔离（结构守卫） ----------

    @Test
    fun `action policy has no dependency on creation decision contracts`() {
        val dir = File("src/main/kotlin/com/qianyan/application/usecase/action")
        assertTrue(dir.isDirectory, "找不到 Action Policy 源码目录：${dir.absolutePath}")
        val sources = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())

        // 只扫描代码行（去掉注释行），避免注释中的说明被误判
        val code = sources.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
        listOf("DecisionPolicy", "DecisionType", "DecisionOutcome", "decisionModel", "AuthorContext", "StoryFoundation")
            .forEach { token -> assertTrue(token !in code, "Action Policy 不得引用创作决策契约 '$token'（与 P19 严格分离）") }
        assertTrue("LLMGateway" !in code, "Action Policy 不得依赖 LLMGateway（判断必须确定性）")
    }

    // ---------- 6. Deterministic ----------

    @Test
    fun `evaluation is deterministic for the same input`() {
        val f = fixture()
        AgentActionKind.entries.forEach { kind ->
            val a = action(kind)
            val first = f.app.actionPolicy.evaluate(a)
            repeat(5) { assertEquals(first, f.app.actionPolicy.evaluate(a), "$kind 的判断必须确定（同输入同输出）") }
        }
        f.close()
    }

    private fun count(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}