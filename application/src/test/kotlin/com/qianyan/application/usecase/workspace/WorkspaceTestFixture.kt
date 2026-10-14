package com.qianyan.application.usecase.workspace

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.AgentSessionId
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.CharacterId
import com.qianyan.model.DraftId
import com.qianyan.model.ForeshadowingId
import com.qianyan.model.GenreId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.VocabularyId
import com.qianyan.model.WorldId
import com.qianyan.model.WorldRuleId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.character.Character
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.task.TaskType
import com.qianyan.model.taskqueue.TaskKind
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.world.WorldRule
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
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant

/*
 * I14 测试夹具（真实内存 SQLite + ApplicationContainer；数据全真实）。
 *
 * 全部使用固定 ID / 固定时间戳：Workspace / Inspector 的结果不得依赖 UUID / 当前时间。
 */

internal val WORKSPACE_FIXED_INSTANT: Instant = Instant.parse("2026-04-01T00:00:00Z")

internal class WorkspaceFixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
    fun close() = handle.driver.close()
}

internal fun workspaceFixture(): WorkspaceFixture {
    val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
    return WorkspaceFixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
}

/** 一个真实 Project：作品 + 2 章 + 正文 + 故事基础 + 词汇 + 人物 + 世界规则 + 伏笔 + 任务 + 修改。 */
internal class WorkspaceWorld(
    val projectId: ProjectId,
    val novelId: NovelId,
    val chapter1: ChapterId,
    val chapter2: ChapterId,
    val draft1: DraftId,
    val characterId: CharacterId,
    val worldRuleId: WorldRuleId,
    val foreshadowId: ForeshadowingId,
    val taskId: TaskId,
    val sessionId: AgentSessionId,
    val artifactId: ChangeArtifactId,
    val vocabularyCandidateId: VocabularyCandidateId,
)

internal fun seedWorkspaceWorld(f: WorkspaceFixture): WorkspaceWorld {
    val novelId = f.app.novels.createOriginal(title = "长夜行舟", genre = listOf("仙侠"))
    val chapter1 = f.app.chapters.createNextChapter("雨夜", novelId).chapterId
    val chapter2 = f.app.chapters.createNextChapter("出关", novelId).chapterId

    val draft1 = DraftId("d-ws-1")
    f.app.draftRepository.save(draft(draft1, novelId, chapter1, "第一章正文（短）。"))

    // 运行态（ProjectState 是 projectOf(projectId) 的前提；Workspace 选择本身不写它）
    f.app.projects.selectChapter(novelId, chapter1)
    val projectId = f.app.projects.projectOf(novelId).projectId

    // 故事基础（I12 / I6 的真实来源）
    f.app.storyFoundationRepository.upsertStoryFoundation(
        StoryFoundation(
            novelId = novelId,
            baseNovelId = BaseNovelId(novelId.value),
            version = 1L,
            genre = listOf(GenreId("xianxia")),
            createdAt = WORKSPACE_FIXED_INSTANT,
            updatedAt = WORKSPACE_FIXED_INSTANT,
        ),
    )

    // 词汇候选
    val vocabularyId = f.app.vocabularies.getOrCreateNovelVocabulary(novelId)
    val candidateId = VocabularyCandidateId("vc-ws-1")
    f.app.vocabularyRepository.saveCandidate(
        VocabularyCandidate(
            candidateId = candidateId,
            vocabularyId = vocabularyId,
            novelId = novelId,
            scopeLevel = VocabularyScopeLevel.NOVEL,
            suggested = VocabularyEntry(
                entryId = VocabularyEntryId("ve-ws-1"),
                vocabularyId = vocabularyId,
                novelId = novelId,
                canonical = "灵石",
                aliases = listOf("灵玉"),
            ),
            source = VocabularyCandidateSource.AUTO_EXTRACT,
            status = VocabularyCandidateStatus.PENDING,
            createdAt = WORKSPACE_FIXED_INSTANT,
        ),
    )

    // Story State：人物 / 世界规则 / 伏笔（既有 Canonical 事实；只读投影来源）
    val characterId = CharacterId("c-ws-1")
    f.app.storyState.saveCharacter(
        Character(
            characterId = characterId,
            novelId = novelId,
            name = "沈昭",
            description = "寡言、克制",
            createdAt = WORKSPACE_FIXED_INSTANT,
            updatedAt = WORKSPACE_FIXED_INSTANT,
        ),
    )
    val worldRuleId = WorldRuleId("wr-ws-1")
    f.app.storyState.saveWorldRule(
        WorldRule(
            ruleId = worldRuleId,
            worldId = WorldId("w-ws-1"),
            novelId = novelId,
            content = "灵气守恒：施法必有代价",
        ),
    )
    val foreshadowId = ForeshadowingId("f-ws-1")
    f.app.storyState.saveForeshadow(
        Foreshadow(
            foreshadowId = foreshadowId,
            novelId = novelId,
            chapterId = chapter1,
            content = "顾宁的旧布包",
            createdAt = WORKSPACE_FIXED_INSTANT,
        ),
    )

    // 后台任务（I13）+ 会话 / 活动（I3 / I4，经真实 UseCase 写入）
    val taskId: TaskId = f.app.tasks.create(TaskType.BACKGROUND)
    f.app.taskQueue.enqueue(taskId, projectId, TaskKind.PROJECT_INDEX_REBUILD)
    val sessionId = f.app.agentSessions.startSession(novelId, taskId = taskId).sessionId
    val activity = f.app.activities.start(sessionId, kind = "WRITING", summary = "Qianyan 修改了第 1 章")
    f.app.toolCallLogs.start(activity.activityId, ToolName("read_file"), "第 1 章")

    // 待确认修改（I8 → I9 真实链路）
    val workingDraft = f.app.workingDrafts.create(
        projectId = projectId,
        target = WorkingDraftTarget(novelId = novelId, chapterId = chapter1, variantId = null),
        content = "第一章正文（改）。雨下得更大了。",
        baseDraftId = draft1,
    )
    val artifact = f.app.changes.prepare(workingDraft.workingDraftId)

    return WorkspaceWorld(
        projectId = projectId,
        novelId = novelId,
        chapter1 = chapter1,
        chapter2 = chapter2,
        draft1 = draft1,
        characterId = characterId,
        worldRuleId = worldRuleId,
        foreshadowId = foreshadowId,
        taskId = taskId,
        sessionId = sessionId,
        artifactId = artifact.artifactId,
        vocabularyCandidateId = candidateId,
    )
}

private fun draft(id: DraftId, novelId: NovelId, chapterId: ChapterId, content: String): Draft = Draft(
    draftId = id,
    novelId = novelId,
    chapterId = chapterId,
    content = content,
    format = DraftFormat.CONTROLLED_MARKDOWN,
    status = DraftStatus.WRITTEN,
    createdAt = WORKSPACE_FIXED_INSTANT,
    updatedAt = WORKSPACE_FIXED_INSTANT,
)

/** 行数统计（Canonical 隔离断言；不改动任何数据）。 */
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

/**
 * 经**既有** I2 Human Gate 造一个已批准的提交凭据（I14 不参与提交，只读取 I10 历史）。
 */
internal fun approvedGate(f: WorkspaceFixture, w: WorkspaceWorld, gateId: String = "gate-i14-approved"): CommitApproval {
    val workflowId = WorkflowId("wf-i14-$gateId")
    f.app.workflows.createWorkflow(
        Workflow(
            workflowId = workflowId,
            novelId = w.novelId,
            kind = WorkflowKind.WRITE_NOVEL,
            status = WorkflowStatus.RUNNING,
            createdAt = WORKSPACE_FIXED_INSTANT,
            updatedAt = WORKSPACE_FIXED_INSTANT,
        ),
    )
    f.app.workflows.createGate(
        WorkflowHumanGate(
            gateId = WorkflowHumanGateId(gateId),
            workflowId = workflowId,
            gateKey = "i14:$gateId",
            status = HumanGateStatus.RESOLVED,
            decision = HumanDecision.APPROVED,
            createdAt = WORKSPACE_FIXED_INSTANT,
            resolvedAt = WORKSPACE_FIXED_INSTANT,
            resolvedBy = "user",
        ),
    )
    return CommitApproval(WorkflowHumanGateId(gateId))
}