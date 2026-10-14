package com.qianyan.application.usecase.workspace

import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.log.Activity
import com.qianyan.model.log.ToolCallLog
import com.qianyan.model.task.Task
import com.qianyan.model.taskqueue.TaskQueueItem
import com.qianyan.model.workspace.InspectorActivityView
import com.qianyan.model.workspace.InspectorBudgetView
import com.qianyan.model.workspace.InspectorChangeView
import com.qianyan.model.workspace.InspectorContextEntry
import com.qianyan.model.workspace.InspectorContextGroup
import com.qianyan.model.workspace.InspectorContextView
import com.qianyan.model.workspace.InspectorDiffLineView
import com.qianyan.model.workspace.InspectorHistoryView
import com.qianyan.model.workspace.InspectorTaskView
import com.qianyan.model.workspace.InspectorToolCallView
import com.qianyan.model.workspace.WorkspaceSelection
import com.qianyan.model.change.ChangeArtifact
import com.qianyan.model.commit.CommitHistoryEntry

/**
 * I14 · Inspector 的**纯投影**（无副作用、无 IO、无状态）。
 *
 * 只把既有 I6 / I4 / I9 / I10 / I13 的领域对象**映射**为 Inspector 展示模型：
 * 不重新选择 Context（条目与预算来自 `ContextPack` / `ContextBudgetGuard`），
 * 不重新判定 Change 状态、不重算 Diff、不修改 Task。
 */
object WorkspaceProjections {

    /** 展示用预览长度（**只影响展示**；不改变 pack 内容，也不参与任何选择）。 */
    const val PREVIEW_LIMIT: Int = 140

    /* ---------------- I6 ContextPack → Context Tab ---------------- */

    fun contextView(
        pack: ContextPack,
        selection: WorkspaceSelection,
        focusLabel: String,
        novelTitle: String,
        notices: List<String> = emptyList(),
    ): InspectorContextView {
        val groups = ContextSourceKind.values().mapNotNull { source ->
            val entries = pack.items.filter { it.source == source }
            if (entries.isEmpty()) null else InspectorContextGroup(source, sourceTitle(source), entries.map(::contextEntry))
        }
        return InspectorContextView(
            selection = selection,
            focusLabel = focusLabel,
            purpose = pack.request.purpose,
            packId = pack.packId,
            packVersion = pack.packVersion,
            novelTitle = novelTitle,
            activeVariantId = pack.activeVariantId,
            groups = groups,
            budget = InspectorBudgetView(
                budgetTokens = pack.budgetGuard.budgetTokens,
                estimatedTokens = pack.budgetGuard.estimatedTokens,
                isTruncated = pack.budgetGuard.isTruncated,
                omittedCount = pack.budgetGuard.omittedCount,
                omittedItemIds = pack.budgetGuard.omittedItemIds,
                estimator = pack.request.budget.estimator,
            ),
            notices = notices,
        )
    }

    fun contextEntry(item: ContextItem): InspectorContextEntry = InspectorContextEntry(
        itemId = item.itemId,
        source = item.source,
        title = sourceTitle(item.source),
        preview = preview(item.content),
        priority = item.priority,
        estimatedTokens = item.estimatedSize.toLong(),
        sourceVersion = item.sourceVersion,
        reason = item.reason,
        ref = item.ref,
    )

    /** 来源分组标题（展示文案；不是第二套 Context 模型）。 */
    fun sourceTitle(source: ContextSourceKind): String = when (source) {
        ContextSourceKind.PROJECT -> "项目"
        ContextSourceKind.PROJECT_STATE -> "运行态"
        ContextSourceKind.NOVEL -> "作品"
        ContextSourceKind.CHAPTER -> "章节"
        ContextSourceKind.DRAFT -> "正文"
        ContextSourceKind.STORY_FOUNDATION -> "故事基础"
        ContextSourceKind.VOCABULARY -> "词汇"
    }

    private fun preview(content: String): String =
        if (content.length <= PREVIEW_LIMIT) content else content.take(PREVIEW_LIMIT) + "…"

    /* ---------------- I4 Activity / ToolCallLog → Activity Tab ---------------- */

    fun activityView(activity: Activity, toolCalls: List<ToolCallLog>, title: String): InspectorActivityView =
        InspectorActivityView(
            activityId = activity.activityId,
            sessionId = activity.sessionId,
            kind = activity.kind,
            title = title,
            status = activity.status,
            startedAt = activity.startedAt,
            completedAt = activity.completedAt,
            summary = activity.summary,
            error = activity.error,
            toolCalls = toolCalls.map(::toolCallView),
        )

    fun toolCallView(log: ToolCallLog): InspectorToolCallView = InspectorToolCallView(
        toolCallId = log.toolCallId,
        toolName = log.toolName.value,
        status = log.status,
        startedAt = log.startedAt,
        completedAt = log.completedAt,
        inputSummary = log.inputSummary,
        outputSummary = log.outputSummary,
        error = log.error,
    )

    /* ---------------- I9 ChangeArtifact → Changes Tab ---------------- */

    fun changeView(artifact: ChangeArtifact): InspectorChangeView {
        val change = artifact.change
        val diffLines = change.diff.hunks.flatMap { hunk ->
            hunk.lines.map { InspectorDiffLineView(kind = hunk.kind, text = it) }
        }
        return InspectorChangeView(
            artifactId = artifact.artifactId,
            kind = change.kind,
            title = change.summary,
            summary = change.summary,
            status = artifact.status,
            addedLines = change.diff.addedLines,
            removedLines = change.diff.removedLines,
            createdAt = artifact.createdAt,
            targetChapterId = change.target.chapterId,
            validationPassed = change.validation.passed,
            diffLines = diffLines,
            note = change.validation.issues.firstOrNull()?.message,
        )
    }

    /* ---------------- I10 CommitHistoryEntry → Changes Tab（历史） ---------------- */

    fun historyView(entry: CommitHistoryEntry): InspectorHistoryView = InspectorHistoryView(
        historyId = entry.historyId,
        operation = entry.operation,
        title = entry.summary,
        summary = entry.summary,
        chapterId = entry.target.chapterId,
        createdAt = entry.createdAt,
        revertedHistoryId = entry.revertedHistoryId,
    )

    /* ---------------- I13 Task / Queue → Task 投影（状态仍以既有模型为准） ---------------- */

    fun taskView(task: Task, queueItem: TaskQueueItem?): InspectorTaskView = InspectorTaskView(
        taskId = task.taskId,
        taskType = task.type.name,
        taskStatus = task.status.name,
        progress = task.progress,
        queueStatus = queueItem?.status?.name,
        attempt = queueItem?.attempt,
        maxAttempts = queueItem?.maxAttempts,
        claimedBy = queueItem?.claimedBy,
        lastError = queueItem?.lastError ?: task.error,
    )
}