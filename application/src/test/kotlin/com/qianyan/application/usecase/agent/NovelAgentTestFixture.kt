package com.qianyan.application.usecase.agent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.agent.agents.SkillRegistry
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.context.ContextBudget
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.skill.SkillId
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * I11 测试夹具（真实内存 SQLite + ApplicationContainer；LLM 全 Mock，数据全真实）。
 *
 * Mock 网关按既有五 Agent 的 system prompt 路由（与 P12 写作链测试同一手法），返回各 Agent 的合法 JSON；
 * 因此 NovelAgent 走的是**真实** Planner / Writer / Critic / Revision 代码路径（无网络）。
 */

internal val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal class NovelAgentFixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
    fun close() = handle.driver.close()
}

private fun jsonBody(key: String, value: String): String = buildJsonObject { put(key, value) }.toString()

/** 按 system prompt 路由的 Mock 网关（写作 / 修订正文与规划目标可注入）。 */
internal fun routingGateway(
    writerContent: String = "第一章正文（新写）。",
    revisionContent: String = "第一章正文（修订）。",
    planGoal: String = "揭开秘辛",
): MockLLMGateway = MockLLMGateway { req ->
    val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
    val body = when {
        "StoryPlannerAgent" in system ->
            """{"chapterGoal":"$planGoal","mainConflict":"主线冲突","expectedEvents":["遇险"]}"""

        "StoryWriterAgent" in system -> jsonBody("content", writerContent)
        "StoryCriticAgent" in system -> """{"passed":true}"""
        "StoryRevisionAgent" in system -> jsonBody("content", revisionContent)
        else -> jsonBody("content", "占位")
    }
    ProviderResponse(
        message = ChatMessage(ChatRole.ASSISTANT, jsonBody("answer", body)),
        usage = Usage(10, 10, 20),
        finishReason = FinishReason.STOP,
    )
}

internal fun novelAgentFixture(gateway: LLMGateway = routingGateway()): NovelAgentFixture {
    val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
    return NovelAgentFixture(ApplicationContainer.fromDriver(handle.driver, gateway), handle)
}

/** 一个真实 Project：Novel + 2 章 + 既有 Canonical 正文（chapter1）。 */
internal class NovelWorld(
    val novelId: NovelId,
    val projectId: ProjectId,
    val chapterId: ChapterId,
    val secondChapterId: ChapterId,
    val canonicalDraftId: DraftId,
)

internal fun seedNovelWorld(f: NovelAgentFixture, canonicalContent: String = "第一章旧正文。"): NovelWorld {
    val novelId = f.app.novels.createOriginal(title = "书A")
    val chapter1 = f.app.chapters.createNextChapter("第一章", novelId).chapterId
    val chapter2 = f.app.chapters.createNextChapter("第二章", novelId).chapterId
    val draftId = DraftId("draft-canonical-1")
    f.app.draftRepository.save(
        Draft(
            draftId = draftId,
            novelId = novelId,
            chapterId = chapter1,
            content = canonicalContent,
            format = DraftFormat.CONTROLLED_MARKDOWN,
            status = DraftStatus.REVISED,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
        ),
    )
    f.app.projects.selectChapter(novelId, chapter1)
    return NovelWorld(
        novelId = novelId,
        projectId = f.app.projects.projectOf(novelId).projectId,
        chapterId = chapter1,
        secondChapterId = chapter2,
        canonicalDraftId = draftId,
    )
}

internal fun request(
    w: NovelWorld,
    userIntent: String,
    sessionId: AgentSessionId? = null,
    activeChapterId: ChapterId? = w.chapterId,
    activeVariantId: VariantId? = null,
    budget: ContextBudget = ContextBudget(),
    preferredSkillId: SkillId? = null,
): NovelAgentRequest = NovelAgentRequest(
    projectId = w.projectId,
    userIntent = userIntent,
    sessionId = sessionId,
    activeChapterId = activeChapterId,
    activeVariantId = activeVariantId,
    budget = budget,
    preferredSkillId = preferredSkillId,
)

/** 既有 Human Gate 的批准凭据（复用 I2 既有记录形状；**不新建 Gate 体系**）。 */
internal fun approvedCommitGate(
    f: NovelAgentFixture,
    w: NovelWorld,
    gateId: String = "gate-i11-approved",
    status: HumanGateStatus = HumanGateStatus.RESOLVED,
    decision: HumanDecision = HumanDecision.APPROVED,
): CommitApproval {
    val workflowId = WorkflowId("wf-i11-$gateId")
    f.app.workflows.createWorkflow(
        Workflow(
            workflowId = workflowId,
            novelId = w.novelId,
            kind = WorkflowKind.WRITE_NOVEL,
            status = WorkflowStatus.RUNNING,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
        ),
    )
    f.app.workflows.createGate(
        WorkflowHumanGate(
            gateId = WorkflowHumanGateId(gateId),
            workflowId = workflowId,
            gateKey = "i11:$gateId",
            status = status,
            decision = decision,
            createdAt = FIXED_INSTANT,
            resolvedAt = if (status == HumanGateStatus.RESOLVED) FIXED_INSTANT else null,
            resolvedBy = if (status == HumanGateStatus.RESOLVED) "user" else null,
        ),
    )
    return CommitApproval(WorkflowHumanGateId(gateId))
}

/** 用自定义 SkillRegistry 装配 NovelAgent（用于 disabled Skill / 无匹配 / allowedTools 边界测试）。 */
internal fun novelAgentWithRegistry(f: NovelAgentFixture, registry: SkillRegistry): NovelAgent = NovelAgent(
    projects = f.app.projects,
    chapters = f.app.chapters,
    sessions = f.app.agentSessions,
    activities = f.app.activities,
    skillRegistry = registry,
    contextEngine = f.app.contextEngine,
    productTools = f.app.productTools,
    workingDrafts = f.app.workingDrafts,
    changes = f.app.changes,
    commits = f.app.commits,
    actionPolicy = f.app.actionPolicy,
    planner = f.app.planner,
    writer = f.app.writer,
    critic = f.app.critic,
    rewriter = f.app.rewriter,
    planningContexts = f.app.planningContextAssembly,
    reader = f.app.writerUseCases,
    errorMapper = f.app.errorMapper,
)

internal fun countRows(handle: QianyanDbHandle, table: String): Long =
    handle.driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM $table",
        { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value