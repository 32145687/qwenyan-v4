package com.qianyan.app.desktop.ui.write

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.BreathingSparkle
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyHint
import com.qianyan.app.desktop.ui.PulsingDot
import com.qianyan.app.desktop.ui.QianyanCard
import com.qianyan.app.desktop.ui.QianyanChip
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import com.qianyan.app.desktop.ui.StatusTag
import com.qianyan.app.desktop.ui.statusColor
import com.qianyan.app.desktop.ui.theme.ProseStyle
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.model.ChapterId
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * 04 正文创作（数字书房）—— P20-PC2 · Desktop Writer。
 *
 * 接线（**唯一**路径）：
 * ```
 * WriteScreen → WriterController → WriterGateway（loadContext / saveContent / continueWriting / rewrite）
 *                               → ChapterWorkflowGateway（getChapterProgress / approve：HITL）
 * ```
 * 硬约束（与 PC-1 一致）：**不出现** UI → DraftRepository / UI → WorkflowOrchestrator / UI → SQLDelight；
 * 不 Decision（不引用 DecisionModel，不重算 DecisionPolicy）；不伪造数据（无作品/无章节/无草稿都显式空态）。
 *
 * 章节与草稿全部来自 SQLite 中的真实数据；本页不创建演示数据。
 */
@Composable
fun WriteScreen(state: DesktopAppState) {
    val novel = state.novel
    if (novel == null) {
        Column(Modifier.fillMaxSize()) {
            SectionHeader(Section.WRITE)
            EmptyHint("尚未打开作品——回到「首页」打开一本书后再进入正文创作。")
        }
        return
    }

    // Provider 切换会重建 ApplicationContainer；以它为 key 让 Controller 绑定新的 Writer seam。
    val provider by state.graph.provider.collectAsState()

    var chapters by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<Chapter>>(emptyList()) }
    var selectedId by remember(novel.novelId) { mutableStateOf<ChapterId?>(null) }
    var showNewChapter by remember { mutableStateOf(false) }
    var showRewrite by remember { mutableStateOf(false) }

    LaunchedEffect(novel.novelId, state.refreshKey) {
        chapters = state.load { it.chapters.listByNovel(novel.novelId) }.sortedBy { ch -> ch.order }
        if (selectedId == null) selectedId = chapters.lastOrNull()?.chapterId
    }

    val selected = chapters.firstOrNull { it.chapterId == selectedId }
    val controller = remember(selected?.chapterId, provider) {
        selected?.let { chapter ->
            WriterController(
                novelId = novel.novelId,
                chapter = chapter,
                gateway = state.container.writerGateway,
                workflow = state.container.workflowFacade,
                scope = state.scope,
            )
        }
    }
    val noChapterState = remember { MutableStateFlow(WriterUiState()) }
    val writer by (controller?.uiState ?: noChapterState).collectAsState()

    LaunchedEffect(controller) { controller?.load() }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.WRITE) {
            Text("《${novel.title}》", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            OutlinedButton(onClick = { showNewChapter = true }, enabled = !state.busy) { Text("新建章节") }
        }

        if (chapters.isEmpty()) {
            EmptyHint("这部作品还没有章节——点右上「新建章节」创建第一章（走既有 chapters.createNextChapter）。")
            return@Column
        }

        Row(Modifier.fillMaxSize().padding(start = 34.dp, end = 34.dp, bottom = 24.dp)) {
            ChapterList(
                chapters = chapters,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                modifier = Modifier.width(250.dp).fillMaxHeight(),
            )
            Spacer(Modifier.width(16.dp))

            val chapter = selected
            if (chapter == null || controller == null) {
                EmptyHint("选择左侧一章开始写作。", Modifier.weight(1f))
            } else {
                ChapterWorkbench(
                    state = writer,
                    chapter = chapter,
                    busy = state.busy,
                    onContentChange = controller::onContentChange,
                    onSave = controller::save,
                    onContinueWriting = controller::continueWriting,
                    onRewrite = { showRewrite = true },
                    onApproveGate = controller::approveGate,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showNewChapter) {
        NewChapterDialog(
            novel = novel,
            appState = state,
            onDismiss = { showNewChapter = false },
            onCreated = { id ->
                showNewChapter = false
                selectedId = id
            },
        )
    }

    if (showRewrite) {
        RewriteDialog(
            state = writer,
            onDismiss = { showRewrite = false },
            onConfirm = {
                showRewrite = false
                controller?.rewrite()
            },
        )
    }
}

/** 章节列表（真实数据；仅支持选择 / 上一章 / 下一章——不做拖拽排序、批量重排、批量删除）。 */
@Composable
private fun ChapterList(
    chapters: List<Chapter>,
    selectedId: ChapterId?,
    onSelect: (ChapterId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
    ) {
        Text("章节（${chapters.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(6.dp))
        LazyColumn {
            items(chapters, key = { it.chapterId.value }) { ch ->
                val sel = ch.chapterId == selectedId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSelect(ch.chapterId) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ch.title.ifBlank { "第 ${ch.order} 章" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text("第 ${ch.order} 章", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    StatusTag(ch.status.name, statusColor(ch.status.name))
                }
            }
        }
    }
}

/**
 * 章节工作台：章节信息 + 正文编辑区 + 操作区 + 状态 / 错误显示。
 * 正文编辑为**纯文本（受控 Markdown v1）**承载：不引入 RichText / HTML / WebView 编辑器。
 */
@Composable
private fun ChapterWorkbench(
    state: WriterUiState,
    chapter: Chapter,
    busy: Boolean,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onContinueWriting: () -> Unit,
    onRewrite: () -> Unit,
    onApproveGate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // 1) 章节信息区：作品 / 章节 / 章节号 / Draft 状态 / 工作流阶段 / HITL / 格式
        QianyanCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        chapter.title.ifBlank { "第 ${chapter.order} 章" },
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Serif,
                    )
                    Text(
                        "《${state.novelTitle}》· 第 ${state.chapterOrder} 章",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.waitingForUser) PulsingDot(QianyanColors.AmberLight, 8.dp)
                    StatusTag(state.taskStatus.label, statusColor(chapterPhaseName(state.taskStatus)))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QianyanChip(
                    if (state.hasDraft) "草稿 ${state.draftStatus ?: "-"}" else "尚无草稿",
                    if (state.hasDraft) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                )
                QianyanChip("修订 ${state.revisionCount}/3", QianyanColors.AmberSoftLight)
                QianyanChip(state.formatLabel)
                if (state.waitingForUser) QianyanChip("需要确认（人工门）", QianyanColors.AmberSoftLight)
                if (state.dirty) QianyanChip("未保存修改", QianyanColors.AmberSoftLight)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "流程状态由 durable Workflow 实时投影（Task / Checkpoint 是唯一事实来源）。" +
                    "「继续写作」每次推进一个流程步骤，人工门不会被自动通过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // 2) 正文编辑区（真实内容来自 WriterGateway.loadContext 的 Draft）
        QianyanCard(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BreathingSparkle(fontSize = 12.sp)
                    Text("正文", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "${state.draftContent.count { !it.isWhitespace() }} 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (!state.hasDraft) {
                Text(
                    "本章还没有草稿。草稿只能由真实写作流程产生：点「继续写作」推进 durable Workflow" +
                        "（规划 → 写作 → 审校 → …）。本页不伪造草稿。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                OutlinedTextField(
                    value = state.draftContent,
                    onValueChange = onContentChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    enabled = !state.busy && !busy,
                    textStyle = ProseStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, lineHeight = 28.sp),
                    placeholder = { Text("在此编辑正文（受控 Markdown v1 纯文本）") },
                )
            }
        }

        // 3) 错误显示（只展示既有错误；PC-2 不实现 Error Recovery UX，也不自建重试机制）
        state.error?.let { message ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text("写作失败", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                Text("原因：$message", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(
                    "可重新执行「继续写作」/「AI 改写」；重复失败请检查 Provider 设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 4) 操作区
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onSave,
                enabled = state.canSave && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text(if (state.isSaving) "保存中…" else if (state.dirty) "保存 *" else "保存") }

            OutlinedButton(onClick = onContinueWriting, enabled = state.canGenerate && !busy) {
                Text(if (state.isGenerating) "执行中…" else "继续写作")
            }

            OutlinedButton(onClick = onRewrite, enabled = state.canRewrite && !busy) { Text("AI 改写") }

            if (state.waitingForUser) {
                Button(
                    onClick = onApproveGate,
                    enabled = state.canApprove && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = QianyanColors.AmberLight),
                ) { Text(if (state.isApproving) "审批中…" else "通过闸门（人工确认）") }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "当前阶段：${state.taskStatus.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Desktop 状态 → 既有语义色标签用的阶段名（复用 [statusColor] 的既有键，不新建色板）。 */
private fun chapterPhaseName(status: WriterTaskStatus): String = when (status) {
    WriterTaskStatus.IDLE -> "NOT_STARTED"
    WriterTaskStatus.PLANNING -> "PLANNING"
    WriterTaskStatus.WRITING -> "WRITING"
    WriterTaskStatus.CRITIQUE -> "REVIEWING"
    WriterTaskStatus.REVISION -> "REVISING"
    WriterTaskStatus.WAITING_CONFIRMATION -> "WAITING_CONFIRMATION"
    WriterTaskStatus.UPDATING_STORY -> "UPDATING_STORY"
    WriterTaskStatus.COMPLETED -> "COMPLETED"
    WriterTaskStatus.FAILED -> "FAILED"
}

/** 新建章节（既有 chapters.createNextChapter UseCase；不做批量生成 / 重排 / 删除）。 */
@Composable
private fun NewChapterDialog(
    novel: Novel,
    appState: DesktopAppState,
    onDismiss: () -> Unit,
    onCreated: (ChapterId) -> Unit,
) {
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建章节", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("《${novel.title}》", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("章节标题（可留空，按序号自动命名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !appState.busy,
                onClick = {
                    val t = title.trim()
                    appState.launchWork {
                        val created = appState.container.chapters.createNextChapter(t, novel.novelId)
                        withContext(Dispatchers.Main) { onCreated(created.chapterId) }
                    }
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}