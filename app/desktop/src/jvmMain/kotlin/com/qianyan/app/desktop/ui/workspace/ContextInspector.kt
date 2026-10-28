package com.qianyan.app.desktop.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.ErrorState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.formatCount
import com.qianyan.app.desktop.ui.preview
import com.qianyan.app.desktop.ui.shortTime
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.statusTint
import com.qianyan.app.desktop.ui.taskTypeLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.model.change.DiffLineKind
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.log.AgentLogStatus
import com.qianyan.model.workspace.InspectorActivityView
import com.qianyan.model.workspace.InspectorChangeView
import com.qianyan.model.workspace.InspectorContextView
import com.qianyan.model.workspace.InspectorHistoryView
import com.qianyan.model.workspace.InspectorTab
import com.qianyan.model.workspace.InspectorTaskContextState
import com.qianyan.model.workspace.InspectorTaskContextView
import com.qianyan.model.workspace.InspectorTaskView
import com.qianyan.model.workspace.WorkspaceInspectorView
import kotlinx.coroutines.launch

/**
 * Context Inspector（右栏；**只读投影**）。
 *
 * 桌面 UI 四页：`任务 / 上下文 / 活动 / 修改`。
 * 领域侧三标签（[InspectorTab]）保持 LOCKED 不变；「任务」只是桌面端的只读总览，
 * 不派生不同检查器、不创建任务、不发起 LLM 调用。
 * 展示只使用**普通作者能理解的字段**：内部字段（packId / packVersion / 预算 / 版本号 /
 * 内部 ID / 指纹）一律不显示。
 */
@Composable
fun ContextInspector(
    app: DesktopAppState,
    width: Dp = QianyanDimens.Inspector,
    modifier: Modifier = Modifier,
) {
    val palette = QianyanTheme.palette
    val c = app.workspace
    val scope = rememberCoroutineScope()

    Column(
        modifier.width(width).fillMaxHeight().background(palette.bg),
    ) {
        InspectorHeader()
        TabBar(c)
        HLine()

        val view = c.view
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(QianyanDimens.Gutter)) {
            when {
                c.projectId == null -> EmptyState("暂无内容", "先从顶部打开或新建作品")
                c.loading && view == null -> LoadingState()
                c.message != null -> ErrorState(c.message, onRetry = { scope.launch { c.load() } })
                c.selection == null -> EmptyState("未选择对象", "从上方选择一个对象")
                view == null -> LoadingState()
                else -> when (c.pane) {
                    InspectorPane.TASK -> TaskTab(app, view)
                    InspectorPane.CONTEXT -> ContextTab(view)
                    InspectorPane.ACTIVITY -> ActivityTab(view.activities)
                    InspectorPane.CHANGES -> ChangesTab(view.changes, view.history)
                }
            }
        }
    }
}

/* ---------------- 检查器标题 + 当前对象 ---------------- */

@Composable
private fun InspectorHeader() {
    Row(
        Modifier.fillMaxWidth().height(QianyanDimens.TopBar).padding(start = QianyanDimens.Gutter, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "检查器",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = QianyanTheme.palette.text,
        )
    }
}

/* ---------------- 固定四页（下划线式） ---------------- */

@Composable
private fun TabBar(c: WorkspaceController) {
    val palette = QianyanTheme.palette
    val tabs = listOf(
        InspectorPane.TASK to "任务",
        InspectorPane.CONTEXT to "上下文",
        InspectorPane.ACTIVITY to "活动",
        InspectorPane.CHANGES to "修改",
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = QianyanDimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        tabs.forEach { (pane, label) ->
            val selected = c.pane == pane
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            // 下划线只覆盖标签自身宽度（Apple 式节制），不划满整栏
            Column(
                Modifier.clickable(interactionSource = source, indication = null) { c.selectPane(pane) }.width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected || hovered) palette.text else palette.text3,
                    modifier = Modifier.padding(vertical = 9.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (selected) palette.accent else Color.Transparent),
                )
            }
        }
    }
}

/* ---------------- 任务（只读总览） ---------------- */

@Composable
private fun TaskTab(app: DesktopAppState, view: WorkspaceInspectorView) {
    val palette = QianyanTheme.palette
    val model by app.graph.effectiveModel.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        val task = view.task
        if (task != null) {
            TaskBlock(task)
            Spacer(Modifier.height(14.dp))
        } else {
            EmptyState("暂无任务", "在「创作」页下达任务后，这里会显示任务与活动")
            Spacer(Modifier.height(10.dp))
        }

        view.taskContext?.let {
            TaskContextBlock(it)
            Spacer(Modifier.height(14.dp))
        }

        SectionLabel("活动记录")
        if (view.activities.isEmpty()) {
            Text("暂无活动", style = MaterialTheme.typography.bodySmall, color = palette.text3)
        } else {
            view.activities.reversed().take(4).forEach { activity ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        activityTime(activity.startedAt),
                        style = QianyanCodeStyle,
                        color = palette.text3,
                        modifier = Modifier.width(38.dp),
                    )
                    Box(
                        Modifier.padding(top = 6.dp).size(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (activity.status == AgentLogStatus.RUNNING) palette.accent else palette.text3.copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(activity.title, style = MaterialTheme.typography.bodySmall, color = palette.text, maxLines = 2)
                        activity.summary?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(preview(it, 60), style = MaterialTheme.typography.labelSmall, color = palette.text2, maxLines = 2)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        SectionLabel("待审批")
        val pending = view.changes
        if (pending.isEmpty()) {
            Text("没有待审批的修改", style = MaterialTheme.typography.bodySmall, color = palette.text3)
        } else {
            val first = pending.last()
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.raised)
                    .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.r3))
                    .padding(13.dp),
            ) {
                Text(first.title.ifBlank { first.summary }, style = MaterialTheme.typography.bodyMedium, color = palette.text, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    statusLabel(first.status.name) + " · " + shortTime(first.createdAt),
                    style = QianyanCodeStyle,
                    color = palette.text3,
                )
                Spacer(Modifier.height(8.dp))
                // 正式批准 / 拒绝只在「正文」页完成；这里只负责把作者带到那里
                Text(
                    "请在正文页处理 →",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { app.openWorkspace(com.qianyan.app.desktop.ui.Workspace.WRITE, 0) },
                )
                if (pending.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text("另有 ${pending.size - 1} 项待确认", style = MaterialTheme.typography.labelSmall, color = palette.text3)
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        SectionLabel("任务信息")
        FieldLine("发起方式", "手动")
        FieldLine("模型", model)
        val taskChapter = app.lastAgentRequest
            ?.takeIf { it.projectId == app.projectId }
            ?.activeChapterId
            ?.let { chapterId ->
                app.workspace.candidates.firstOrNull { it.selection.chapterId == chapterId }?.label
            }
        taskChapter?.let { FieldLine("任务章节", it) }
        FieldLine(
            "状态",
            when {
                app.busy -> "运行中"
                task != null -> statusLabel(task.taskStatus)
                app.lastAgentResult != null -> "最近任务已结束"
                else -> "暂无任务"
            },
            if (app.busy) palette.accent else palette.text2,
        )
    }
}

/** 活动时间只显示时:分（来自真实记录，不做推测）。 */
private fun activityTime(instant: kotlinx.datetime.Instant): String =
    shortTime(instant).substringAfter(' ', shortTime(instant)).take(5)


/* ---------------- 上下文 ---------------- */

@Composable
private fun ContextTab(view: WorkspaceInspectorView) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        if (view.currentFacts.isNotEmpty()) {
            SectionLabel("当前事实 · 只读")
            view.currentFacts.forEach { fact -> FieldLine(fact.label, fact.value) }
            Spacer(Modifier.height(10.dp))
            HLine()
            Spacer(Modifier.height(10.dp))
        }
        // A2 才会提供真实任务 Pack；4B.1a 固定为 null。
        view.context?.let { ContextPackBlock(it) }
        if (view.context == null && view.currentFacts.isEmpty()) {
            EmptyState("暂无内容", "当前对象没有可展示的当前事实或任务上下文")
        }
    }
}

/** 4B.1a：任务上下文占位。只显示契约文案，不显示伪造的 Pack / 版本号 / 条目。 */
@Composable
private fun TaskContextBlock(context: InspectorTaskContextView) {
    val palette = QianyanTheme.palette
    SectionLabel("任务上下文")
    Text(
        context.message,
        style = MaterialTheme.typography.bodySmall,
        color = if (context.state == InspectorTaskContextState.PACK_AVAILABLE) palette.text2 else palette.text3,
        modifier = Modifier.padding(vertical = 3.dp),
    )
    context.taskId?.let { FieldLine("任务", preview(it.value, 18)) }
    context.sessionId?.let { FieldLine("会话", preview(it.value, 18)) }
}

/** A2 的真实 Pack 投影；4B.1a 不产生该分支。 */
@Composable
private fun ContextPackBlock(context: InspectorContextView) {
    val palette = QianyanTheme.palette
    context.notices.forEach { notice ->
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text3,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    if (context.groups.isEmpty()) {
        EmptyState("暂无内容", "当前对象没有可进入 Context 的来源")
        return
    }
    context.groups.forEach { group ->
        SectionLabel("${group.title} · ${group.entries.size}")
        // Context 条目的内容可能是**结构化引用**（内部标识 / key=value 记录）。
        // 这类内容不展示（§15：不把内部字段与 ID 暴露给作者）；散文内容照常展示。
        val prose = group.entries.filterNot { looksStructured(it.preview) }
        if (group.source in STRUCTURAL_SOURCES || prose.isEmpty()) {
            Text(
                "结构化引用 · 内部标识不展示",
                style = MaterialTheme.typography.bodySmall,
                color = palette.text3,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        } else {
            prose.forEach { entry ->
                Text(
                    entry.preview.ifBlank { "（无预览）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text2,
                    maxLines = 5,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * 判断一段 Context 预览是否为**结构化记录**（而非散文）。
 *
 * 判定：包含 `标识符=` 形式，或包含 GUID 形态的长标识。
 * 只影响展示（不改变 Context 内容），用于隐藏内部字段与 ID。
 */
private fun looksStructured(text: String): Boolean {
    if (text.isBlank()) return true
    if (Regex("""[A-Za-z_][A-Za-z0-9_]*=""").containsMatchIn(text)) return true
    if (Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}""").containsMatchIn(text)) return true
    return false
}

/** 内容是结构化元数据（而非散文）的来源：不把内部字段展示给作者。 */
private val STRUCTURAL_SOURCES = setOf(
    ContextSourceKind.PROJECT,
    ContextSourceKind.PROJECT_STATE,
    ContextSourceKind.NOVEL,
)

/** 任务投影（真实 `InspectorTaskView`；只显示用户可理解的字段）。 */
@Composable
private fun TaskBlock(task: InspectorTaskView) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("任务")
        FieldLine("类型", taskTypeLabel(task.taskType))
        FieldLine("状态", statusLabel(task.taskStatus), statusTint(task.taskStatus))
        FieldLine("进度", "${(task.progress * 100).toInt()}%")
        if (task.queueStatus != null) FieldLine("队列", statusLabel(task.queueStatus))
        if (task.attempt != null) FieldLine("尝试", "${task.attempt}/${task.maxAttempts ?: 0}")
        task.lastError?.takeIf { it.isNotBlank() }?.let { FieldLine("错误", preview(it, 80), palette.del) }
        Spacer(Modifier.height(6.dp))
        HLine()
    }
}

@Composable
private fun FieldLine(label: String, value: String, tint: Color = QianyanTheme.palette.text2) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = QianyanTheme.palette.text3,
            modifier = Modifier.width(48.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

/* ---------------- 活动 ---------------- */

@Composable
private fun ActivityTab(activities: List<InspectorActivityView>) {
    val palette = QianyanTheme.palette
    if (activities.isEmpty()) {
        EmptyState("暂无内容", "该项目还没有 Agent / Tool 行为记录")
        return
    }
    Column(Modifier.fillMaxWidth()) {
        activities.reversed().forEach { activity ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                // 状态点（进行中为强调色；其余为结构灰）
                Box(
                    Modifier.padding(top = 6.dp).width(5.dp).height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (activity.status == AgentLogStatus.RUNNING) palette.accent else palette.lineStrong),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(activity.title, style = MaterialTheme.typography.bodySmall, color = palette.text, maxLines = 2)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        shortTime(activity.startedAt) + (activity.completedAt?.let { " → ${shortTime(it)}" } ?: ""),
                        style = QianyanCodeStyle,
                        color = palette.text3,
                    )
                    activity.summary?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(preview(it, 90), style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 3)
                    }
                    activity.toolCalls.forEach { call ->
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "工具 · ${call.toolName} · ${statusLabel(call.status.name)}",
                            style = QianyanCodeStyle,
                            color = palette.text3,
                            maxLines = 1,
                        )
                    }
                    activity.error?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(preview(it, 90), style = MaterialTheme.typography.bodySmall, color = palette.del, maxLines = 3)
                    }
                }
            }
            HLine()
        }
    }
}

/* ---------------- 修改（I9 待确认 + I10 历史） ---------------- */

@Composable
private fun ChangesTab(changes: List<InspectorChangeView>, history: List<InspectorHistoryView>) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("待确认修改 · ${changes.size}")
        if (changes.isEmpty()) {
            EmptyState("暂无内容", "还没有待确认的修改")
        }
        changes.reversed().forEach { change ->
            ChangeBlock(change)
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.height(2.dp))
        SectionLabel("已提交历史 · ${history.size}")
        if (history.isEmpty()) {
            EmptyState("暂无内容", "还没有提交记录")
        }
        history.reversed().forEach { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                Text(
                    statusLabel(entry.operation.name) + "  ·  " + shortTime(entry.createdAt) +
                        (entry.revertedHistoryId?.let { "  ·  回退提交" } ?: ""),
                    style = QianyanCodeStyle,
                    color = palette.text3,
                )
            }
            HLine()
        }
    }
}

/**
 * 一处待确认修改：标题行 + 摘要行 + Diff。
 *
 * Diff 为**只读呈现**（行号是本次修改内的序号，不是文件行号——投影里没有文件行号）。
 * 接受 / 拒绝需要 `ChangeArtifact → Commit` 的真实写链路与一个尚不存在的丢弃入口，
 * 属行为新增，本次未接线（不伪造按钮）。
 */
@Composable
private fun ChangeBlock(change: InspectorChangeView) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(statusLabel(change.kind.name), palette.text2)
            Spacer(Modifier.width(6.dp))
            Text(change.title.ifBlank { change.summary }, style = MaterialTheme.typography.bodySmall, color = palette.text, maxLines = 2)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(
                statusLabel(change.status.name),
                "新增 ${formatCount(change.addedLines)}",
                "删除 ${formatCount(change.removedLines)}",
                shortTime(change.createdAt),
            ).joinToString("  ·  "),
            style = QianyanCodeStyle,
            color = palette.text3,
        )
        if (!change.validationPassed) {
            Spacer(Modifier.height(3.dp))
            Text("校验未通过", style = MaterialTheme.typography.bodySmall, color = palette.del)
        }
        if (change.diffLines.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            DiffBlock(change.diffLines)
        }
    }
}

@Composable
private fun DiffBlock(lines: List<com.qianyan.model.workspace.InspectorDiffLineView>) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(QianyanShapes.r2))) {
        lines.forEachIndexed { index, line ->
            val (marker, bg, fg) = when (line.kind) {
                DiffLineKind.ADDED -> Triple("+", palette.addSoft, palette.add)
                DiffLineKind.REMOVED -> Triple("-", palette.delSoft, palette.del)
                else -> Triple("", Color.Transparent, palette.text2)
            }
            Row(Modifier.fillMaxWidth().background(bg)) {
                Text(
                    (index + 1).toString(),
                    style = QianyanCodeStyle.copy(fontSize = 10.sp),
                    color = palette.text3,
                    modifier = Modifier.width(24.dp).padding(start = 6.dp, top = 3.dp),
                )
                Text(
                    marker,
                    style = QianyanCodeStyle,
                    color = fg,
                    modifier = Modifier.width(12.dp).padding(top = 3.dp),
                )
                Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.kind == DiffLineKind.UNCHANGED) palette.text3 else palette.text,
                    modifier = Modifier.weight(1f).padding(end = 8.dp, top = 3.dp, bottom = 3.dp),
                )
            }
        }
    }
}
