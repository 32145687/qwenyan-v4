package com.qianyan.application.usecase.draft

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftBase
import com.qianyan.model.workingdraft.WorkingDraftId
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/*
 * I8 测试夹具（真实内存 SQLite + ApplicationContainer，与 I5–I7 同构）。
 *
 * 全部使用**固定时间戳 / 固定 ID**：I8 测试不依赖 System.currentTimeMillis / Instant.now / UUID 决定结果（§24）。
 */

internal val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal class WorkingDraftFixture(val app: ApplicationContainer, val handle: QianyanDbHandle) {
    fun close() = handle.driver.close()
}

internal fun workingDraftFixture(): WorkingDraftFixture {
    val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
    return WorkingDraftFixture(ApplicationContainer.fromDriver(handle.driver, MockLLMGateway()), handle)
}

/** 一个真实 Project：Novel + 2 章 + 既有 Canonical Draft（含 lineage）+ Session + Activity。 */
internal class SeededWorld(
    val novelId: NovelId,
    val projectId: ProjectId,
    val chapter1: ChapterId,
    val chapter2: ChapterId,
    val canonicalDraftId: DraftId,
    val baseDraftId: DraftId,
    val sessionId: AgentSessionId,
    val activityId: ActivityId,
)

internal fun seedWorld(f: WorkingDraftFixture, title: String = "书A", key: String = "A"): SeededWorld {
    val novelId = f.app.novels.createOriginal(title = title)
    val chapter1 = f.app.chapters.createNextChapter("$key-1", novelId).chapterId
    val chapter2 = f.app.chapters.createNextChapter("$key-2", novelId).chapterId

    // 既有 Draft lineage：base（chapter2 的第一稿）→ canonical（修订稿，previous 指向 base）
    val baseDraftId = DraftId("draft-$key-base")
    f.app.draftRepository.save(
        Draft(
            draftId = baseDraftId,
            novelId = novelId,
            chapterId = chapter2,
            content = "$key 第二章初稿",
            format = DraftFormat.CONTROLLED_MARKDOWN,
            status = DraftStatus.WRITTEN,
            createdAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
        ),
    )
    val canonicalDraftId = DraftId("draft-$key-latest")
    f.app.draftRepository.save(
        Draft(
            draftId = canonicalDraftId,
            novelId = novelId,
            chapterId = chapter2,
            previousDraftId = baseDraftId,
            content = "$key 第二章正文",
            format = DraftFormat.CONTROLLED_MARKDOWN,
            status = DraftStatus.REVISED,
            createdAt = Instant.parse("2026-01-01T00:01:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:01:00Z"),
        ),
    )

    val sessionId = f.app.agentSessions.startSession(novelId).sessionId
    val activityId = f.app.activities.start(sessionId, "I8_TEST").activityId
    f.app.projects.selectChapter(novelId, chapter2)
    return SeededWorld(
        novelId = novelId,
        projectId = f.app.projects.projectOf(novelId).projectId,
        chapter1 = chapter1,
        chapter2 = chapter2,
        canonicalDraftId = canonicalDraftId,
        baseDraftId = baseDraftId,
        sessionId = sessionId,
        activityId = activityId,
    )
}

/** 固定时钟的 Working Draft 工作区（确定性测试用）。 */
internal fun fixedClockWorkingDrafts(
    f: WorkingDraftFixture,
    instant: Instant = FIXED_INSTANT,
): WorkingDraftUseCases = WorkingDraftUseCases(
    projects = f.app.projects,
    chapters = f.app.chapters,
    drafts = f.app.writerUseCases,
    validator = validatorOf(f),
    clock = object : Clock {
        override fun now(): Instant = instant
    },
    errorMapper = f.app.errorMapper,
)

internal fun validatorOf(f: WorkingDraftFixture): WorkingDraftValidator = WorkingDraftValidator(
    projects = f.app.projects,
    chapters = f.app.chapters,
    sessions = f.app.agentSessions,
    activities = f.app.activities,
    drafts = f.app.writerUseCases,
    errorMapper = f.app.errorMapper,
)

/** 构造任意 Working Draft（用于校验规则测试；绕过 create 的硬校验以覆盖异常组合）。 */
internal fun workingDraftOf(
    id: String,
    projectId: ProjectId,
    novelId: NovelId,
    chapterId: ChapterId,
    content: String,
    variantId: VariantId? = null,
    base: WorkingDraftBase = WorkingDraftBase(),
    status: WorkingDraftStatus = WorkingDraftStatus.WORKING,
    sessionId: AgentSessionId? = null,
    activityId: ActivityId? = null,
): WorkingDraft = WorkingDraft(
    workingDraftId = WorkingDraftId(id),
    projectId = projectId,
    target = WorkingDraftTarget(novelId = novelId, chapterId = chapterId, variantId = variantId),
    base = base,
    content = content,
    status = status,
    sessionId = sessionId,
    activityId = activityId,
    createdAt = FIXED_INSTANT,
    updatedAt = FIXED_INSTANT,
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