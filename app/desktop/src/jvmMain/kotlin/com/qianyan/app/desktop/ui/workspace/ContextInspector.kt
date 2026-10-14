package com.qianyan.app.desktop.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.qianyan.model.workspace.InspectorTaskView
import kotlinx.coroutines.launch

/**
 * Context Inspector（右栏 300dp；**只读投影**）。
 *
 * 结构固定（LOCKED）：`上下文 / 活动 / 修改` 三个 Tab，不按页面派生不同 Inspector。
 * 展示只使用**普通作者能理解的字段**：内部字段（packId / packVersion / 预算 / 版本号 /
 * 内部 ID / 指纹）一律不显示。
 */
@Composable
fun ContextInspector(app: DesktopAppState, modifier: Modifier = Modifier) {
    val palette = QianyanTheme.palette
    val c = app.workspace
    val scope = rememberCoroutineScope()

    Column(
        modifier.width(QianyanDimens.Inspector).fillMaxHeight().background(palette.surface),
    ) {
        ObjectSelector(app)
        HLine()
        TabBar(c)
        HLine()

        val view = c.view
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(QianyanDimens.Gutter)) {
            when {
                c.projectId == null -> EmptyState("暂无内容", "先在「Qianyan Agent」里打开或新建作品")
                c.loading && view == null -> LoadingState()
                c.message != null -> ErrorState(c.message, onRetry = { scope.launch { c.load() } })
                c.selection == null -> EmptyState("未选择对象", "从上方选择一个对象")
                view == null -> LoadingState()
                else -> when (c.tab) {
                    InspectorTab.CONTEXT -> ContextTab(view.context, view.task)
                    InspectorTab.ACTIVITY -> ActivityTab(view.activities)
                    InspectorTab.CHANGES -> ChangesTab(view.changes, view.history)
                }
            }
        }
    }
}

/* ---------------- 当前对象（选择器） ---------------- */

@Composable
private fun ObjectSelector(app: DesktopAppState) {
    val palette = QianyanTheme.palette
    val c = app.workspace
    var open by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(start = QianyanDimens.Gutter, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("检查器", style = MaterialTheme.typography.labelSmall, color = palette.text3, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            ActionButton("收起", ActionLevel.TEXT, true) {
                app.inspectorOpen = false
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(QianyanShapes.r2))
                .clickable { open = true }
                .padding(vertical = 4.dp, horizontal = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.view?.title ?: c.selection?.let { "已选择对象" } ?: "未选择对象",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = palette.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", style = QianyanCodeStyle, color = palette.text3)
            }
            Text(
                c.view?.detail ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
            )
            Box {
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    if (c.candidates.isEmpty()) {
                        DropdownMenuItem(text = { Text("暂无可选对象") }, onClick = { open = false })
                    }
                    c.candidates.take(80).forEach { candidate ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(candidate.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    Text(candidate.detail, style = MaterialTheme.typography.labelSmall, color = palette.text3)
                                }
                            },
                            onClick = {
                                c.select(candidate.selection)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/* ---------------- 固定三 Tab（下划线式） ---------------- */

@Composable
private fun TabBar(c: WorkspaceController) {
    val palette = QianyanTheme.palette
    val tabs = listOf(
        InspectorTab.CONTEXT to "上下文",
        InspectorTab.ACTIVITY to "活动",
        InspectorTab.CHANGES to "修改",
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = QianyanDimens.Gutter)) {
        tabs.forEach { (tab, label) ->
            val selected = c.tab == tab
            Column(
                Modifier.weight(1f).clickable { c.tab = tab },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) palette.text else palette.text3,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(2.dp)
                        .background(if (selected) palette.accent else Color.Transparent),
                )
            }
        }
    }
}

/* ---------------- 上下文 ---------------- */

@Composable
private fun ContextTab(context: InspectorContextView?, task: InspectorTaskView?) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        if (task != null) {
            TaskBlock(task)
            Spacer(Modifier.height(14.dp))
        }
        if (context == null) {
            EmptyState()
            return
        }
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
