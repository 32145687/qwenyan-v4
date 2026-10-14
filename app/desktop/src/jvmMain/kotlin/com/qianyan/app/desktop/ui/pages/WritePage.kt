package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.NavItem
import com.qianyan.app.desktop.ui.ProseSize
import com.qianyan.app.desktop.ui.formatCount
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanChapterStyle
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanFonts
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.qianyanProseStyle
import com.qianyan.app.desktop.ui.wordCount
import com.qianyan.app.desktop.ui.write.RewriteDialog
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterUiState
import com.qianyan.model.story.Chapter
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection

/**
 * 12.2 · 正文（**内容优先**）。
 *
 * 整页只有三件事：章节标题行、稿纸正文、稿纸脚注（字数 / 状态 / 修订）。
 * 正文使用衬线体与宽行距、居中的窄栏（[QianyanDimens.EditorWidth]）——不是 TextArea，也不是工具栏面板。
 *
 * 真实能力：`WriterController`（既有 Writer Gateway + Chapter Workflow Gateway）——
 * 保存 / 继续写 / 让 Qianyan 修改 / 人工通过 全部走既有 UseCase，不新增业务逻辑。
 */
@Composable
fun WritePage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val scope = rememberCoroutineScope()
    val workspace = state.workspace
    val chapterId = workspace.selection?.takeIf { it.kind == WorkspaceObjectKind.CHAPTER }?.chapterId
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var controller by remember { mutableStateOf<WriterController?>(null) }
    var ui by remember { mutableStateOf(WriterUiState()) }
    var menuOpen by remember { mutableStateOf(false) }
    var rewriting by remember { mutableStateOf(false) }
    var allChapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }

    LaunchedEffect(chapterId, state.novel?.novelId, state.refreshKey) {
        val novelId = state.novel?.novelId
        if (novelId == null || chapterId == null) {
            chapter = null
            controller = null
            state.status = state.status.copy(chapter = "—", words = "—", save = "—", task = "空闲")
            return@LaunchedEffect
        }
        allChapters = runCatching { state.load { it.chapters.listByNovel(novelId, null) } }.getOrDefault(emptyList())
        val loaded = runCatching { state.load { it.chapters.findById(chapterId) } }.getOrNull()
        chapter = loaded
        if (loaded == null) {
            controller = null
            return@LaunchedEffect
        }
        val c = WriterController(
            novelId = novelId,
            chapter = loaded,
            gateway = state.container.writerGateway,
            workflow = state.container.workflowFacade,
            scope = scope,
        )
        controller = c
        c.load()
    }

    val s by (controller?.uiState?.collectAsState() ?: remember { mutableStateOf(WriterUiState()) })
    ui = s

    // 稿纸样式（衬线 + 行距；字号来自用户在「设置 · 写作 · 排版」或 ··· 菜单中的选择）
    val prose = qianyanProseStyle(state.proseSize.size.sp)

    LaunchedEffect(ui) {
        state.status = state.status.copy(
            chapter = chapter?.let { "第 ${it.order} 章" } ?: "—",
            words = formatCount(wordCount(ui.draftContent)),
            save = if (ui.dirty) "未保存" else if (ui.hasDraft) "已保存" else "无草稿",
            task = ui.taskStatus.label,
        )
    }

    Column(Modifier.fillMaxSize()) {
        ChapterBar(
            state = state,
            chapter = chapter,
            ui = ui,
            menuOpen = menuOpen,
            setMenuOpen = { menuOpen = it },
            onSave = { controller?.save() },
            onRewrite = { if (ui.canRewrite) rewriting = true },
            onContinue = { controller?.continueWriting() },
            onApprove = { controller?.approveGate() },
            onPrev = {
                val list = allChapters.sortedBy { it.order }
                val idx = list.indexOfFirst { it.chapterId == chapterId }
                val target = list.getOrNull(idx - 1)
                if (idx > 0 && target != null) workspace.select(selectionFor(state, target))
            },
            onNext = {
                val list = allChapters.sortedBy { it.order }
                val idx = list.indexOfFirst { it.chapterId == chapterId }
                val target = list.getOrNull(idx + 1)
                if (idx in 0 until list.size - 1 && target != null) workspace.select(selectionFor(state, target))
            },
        )
        HLine()

        when {
            chapterId == null -> Box(Modifier.fillMaxSize().padding(QianyanDimens.PageX)) {
                EmptyState("未选择章节", "在「章节」里选择一章，或用 Ctrl/⌘+4 打开章节列表")
            }

            chapter == null -> Box(Modifier.fillMaxSize().padding(QianyanDimens.PageX)) { LoadingState() }

            else -> Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(34.dp))

                    Column(Modifier.width(QianyanDimens.EditorWidth).padding(horizontal = 8.dp)) {
                        // 稿纸标题（展示层：章节序号 + 标题；不是正文内容）
                        Text(
                            "第 ${chapter?.order ?: 0} 章",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            chapter?.title?.ifBlank { "未命名" } ?: "",
                            style = QianyanChapterStyle,
                            color = palette.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(26.dp))

                        BasicTextField(
                            value = ui.draftContent,
                            onValueChange = { controller?.onContentChange(it) },
                            textStyle = prose,
                            cursorBrush = SolidColor(palette.accent),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (ui.draftContent.isEmpty()) {
                                    Text(
                                        "本章还没有正文。点「继续写」让 Qianyan 起草，或直接在这里开始写。",
                                        style = prose.copy(color = palette.text3),
                                    )
                                }
                                inner()
                            },
                        )
                    }

                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }

    if (rewriting) {
        RewriteDialog(
            state = ui,
            onDismiss = { rewriting = false },
            onConfirm = {
                rewriting = false
                controller?.rewrite()
            },
        )
    }
}

private fun selectionFor(state: DesktopAppState, chapter: Chapter): WorkspaceSelection {
    val current = state.workspace.selection
    return WorkspaceSelection.chapter(
        projectId = current?.projectId ?: state.novel!!.projectId,
        novelId = state.novel!!.novelId,
        chapterId = chapter.chapterId,
    )
}

/**
 * 章节标题行：左侧是"第 N 章 · 标题"，右侧是动作。
 *
 * 一屏内 PRIMARY 至多一个：停在人工门时是「通过确认」（走 [WriterController.approveGate]，
 * 即既有 Human Gate 决议路径），否则是「继续写」。两者**不是同一个回调**：
 * 「通过确认」决议闸门，「继续写」只推进一步；用 advance 去"通过"会命中同一 PENDING gate 而无副作用。
 */
@Composable
private fun ChapterBar(
    state: DesktopAppState,
    chapter: Chapter?,
    ui: WriterUiState,
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
    onSave: () -> Unit,
    onRewrite: () -> Unit,
    onContinue: () -> Unit,
    onApprove: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(QianyanDimens.TopBar).padding(horizontal = QianyanDimens.PageX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                chapter?.let { "第 ${it.order} 章 · ${it.title.ifBlank { "未命名" }}" } ?: "正文",
                style = TextStyle(fontFamily = QianyanFonts.serif, fontSize = 15.sp, color = palette.text),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            if (ui.waitingForUser) {
                ActionButton("通过确认", ActionLevel.PRIMARY, ui.canApprove, onApprove)
            } else {
                ActionButton("保存", ActionLevel.SECONDARY, ui.canSave, onSave)
                Spacer(Modifier.width(8.dp))
                ActionButton("让 Qianyan 修改", ActionLevel.SECONDARY, ui.canRewrite, onRewrite)
                Spacer(Modifier.width(8.dp))
                ActionButton("继续写", ActionLevel.PRIMARY, ui.canGenerate, onContinue)
            }
            if (ui.waitingForUser) {
                Spacer(Modifier.width(8.dp))
                ActionButton("保存", ActionLevel.TEXT, ui.canSave, onSave)
            }
            Box {
                ActionButton("···", ActionLevel.TEXT, true) { setMenuOpen(true) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
                    MenuEntry("上一章") { setMenuOpen(false); onPrev() }
                    MenuEntry("下一章") { setMenuOpen(false); onNext() }
                    MenuSeparator()
                    ProseSize.entries.forEach { p ->
                        MenuEntry("字号 · ${p.label}${if (p == state.proseSize) "  ✓" else ""}") {
                            setMenuOpen(false)
                            state.proseSize = p
                        }
                    }
                    MenuSeparator()
                    MenuEntry("专注模式") { setMenuOpen(false); state.toggle(com.qianyan.app.desktop.ui.PanelToggle.FOCUS) }
                    MenuEntry("历史") { setMenuOpen(false); state.nav = NavItem.HISTORY }
                    MenuEntry("导出") {
                        setMenuOpen(false)
                        state.toast("导出能力将在后续阶段接入（当前不伪造导出结果）")
                    }
                    MenuSeparator()
                    // 未接入的收尾动作：写明接入点，不放假按钮
                    MenuPending("定稿确认 · 未接入", "confirmations.confirmFinalDraft(draftId, novelId, variantId)")
                    MenuPending("知识更新 · 未接入", "knowledgeUpdate.execute(taskId, draft)")
                }
            }
        }
        HLine()

        // 错误 / 人工门提示：安静的一行，不弹窗
        if (ui.error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = QianyanDimens.PageX, vertical = 6.dp)) {
                Text("无法完成操作", style = MaterialTheme.typography.labelSmall, color = palette.del)
                Spacer(Modifier.width(10.dp))
                Text(ui.error ?: "", style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 2)
            }
        } else if (ui.waitingForUser) {
            Row(Modifier.fillMaxWidth().padding(horizontal = QianyanDimens.PageX, vertical = 6.dp)) {
                Text(
                    "已停在人工确认：请阅读上方正文后决定是否通过（不会自动批准）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                )
            }
        }
    }
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = QianyanTheme.palette.text2) },
        onClick = onClick,
    )
}

@Composable
private fun MenuSeparator() {
    HLine(Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
}

/** 菜单里的「未接入」说明行（不可点击，只说明接入点）。 */
@Composable
private fun MenuPending(label: String, useCase: String) {
    val palette = QianyanTheme.palette
    Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.text3)
        Text(useCase, style = QianyanCodeStyle, color = palette.text3)
    }
}
