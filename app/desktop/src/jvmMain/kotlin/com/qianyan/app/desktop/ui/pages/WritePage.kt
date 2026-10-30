package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import com.qianyan.app.desktop.ui.Workspace
import com.qianyan.app.desktop.ui.ProseSize
import com.qianyan.app.desktop.ui.chapterTitleOnly
import com.qianyan.app.desktop.ui.exportChapterToTxt
import com.qianyan.app.desktop.ui.formatCount
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanChapterStyle
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanFonts
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.qianyanProseStyle
import com.qianyan.app.desktop.ui.wordCount
import com.qianyan.app.desktop.ui.write.CANDIDATE_LOST_MESSAGE
import com.qianyan.app.desktop.ui.write.RewriteDialog
import com.qianyan.app.desktop.ui.write.WriterController
import com.qianyan.app.desktop.ui.write.WriterUiState
import com.qianyan.application.usecase.writing.WriterPendingProposal
import com.qianyan.model.change.DiffLineKind
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
            scope = state.scope,
            autoSave = true,
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
            onReject = { controller?.rejectGate() },
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
            onExport = {
                val loaded = chapter
                if (loaded == null) state.toast("尚未加载章节") else state.exportChapterToTxt(loaded, ui.draftContent)
            },
            onConfirmFinal = {
                val draftId = ui.draftId
                when {
                    draftId == null -> state.toast("尚无草稿：请先生成或保存正文")
                    ui.waitingForUser -> state.toast("当前有待决人工门：请在正文审批区「通过确认」或「拒绝」")
                    ui.draftStatus == "CONFIRMED" -> state.toast("该草稿已确认")
                    ui.draftStatus != "PENDING_CONFIRMATION" -> state.toast(
                        "当前草稿状态为 ${statusLabel(ui.draftStatus)}，需先经人工门「通过确认」进入定稿",
                    )
                    else -> state.io(then = { controller?.load(); state.toast("已定稿确认") }) {
                        state.container.confirmations.confirmFinalDraft(draftId, state.novel!!.novelId, null)
                    }
                }
            },
        )
        HLine()

        when {
            chapterId == null -> Box(Modifier.fillMaxSize().padding(QianyanDimens.PageX)) {
                EmptyState("未选择章节", "在左侧章节目录中选择一章，或打开「故事 · 章节」")
            }

            chapter == null -> Box(Modifier.fillMaxSize().padding(QianyanDimens.PageX)) { LoadingState() }

            else -> Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(28.dp))

                    // 稿纸：白色纸面 + 极轻投影，放在暖白工作台上（视觉焦点是正文本身）
                    Column(
                        Modifier.width(QianyanDimens.EditorWidth)
                            .shadow(2.dp, RoundedCornerShape(QianyanShapes.r3), clip = false)
                            .clip(RoundedCornerShape(QianyanShapes.r3))
                            .background(palette.raised)
                            .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.r3))
                            .padding(start = 62.dp, end = 62.dp, top = 52.dp, bottom = 56.dp),
                    ) {
                        Text(
                            "Chapter ${chapter?.order ?: 0}",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                            letterSpacing = 1.1.sp,
                        )
                        Spacer(Modifier.height(13.dp))
                        Text(
                            chapter?.title?.let { chapterTitleOnly(it) }?.ifBlank { "未命名" } ?: "",
                            style = QianyanChapterStyle,
                            color = palette.text,
                        )
                        Spacer(Modifier.height(26.dp))

                        val proposal = ui.pendingProposal
                        if (ui.waitingForUser && proposal != null) {
                            PendingProposalReview(proposal)
                        } else {
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

                        Spacer(Modifier.height(32.dp))
                        HLine()
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(
                                "本章 ${formatCount(wordCount(ui.draftContent))} 字",
                                style = QianyanCodeStyle,
                                color = palette.text3,
                            )
                            Text(
                                if (ui.dirty) "未保存" else if (ui.hasDraft) "已保存" else "无草稿",
                                style = QianyanCodeStyle,
                                color = palette.text3,
                            )
                        }
                    }

                    Spacer(Modifier.height(56.dp))
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

/**
 * 审批前的只读审查区：原文 / 候选稿 / 行级 Diff。
 *
 * 不提供编辑与保存入口 —— 未批准的 AI 候选只能经「通过确认」进入现有 Commit，或经「拒绝」结束当前 Gate。
 */
@Composable
private fun PendingProposalReview(proposal: WriterPendingProposal) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Text(
            "AI 候选稿 · 尚未批准",
            style = MaterialTheme.typography.labelSmall,
            color = palette.accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "批准前不会写入正典。下面是原文、AI 候选稿与行级变更；编辑器在决议前不可用。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.text3,
        )
        Spacer(Modifier.height(20.dp))

        ReviewSection("原文（已提交）") {
            Text(
                proposal.baseContent.ifBlank { "（本章尚无已提交正文）" },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text2,
            )
        }
        Spacer(Modifier.height(18.dp))

        ReviewSection("候选稿（AI 提议，不是已保存正文）") {
            Text(
                proposal.candidateContent,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
        }
        Spacer(Modifier.height(18.dp))

        ReviewSection("变更（TextDiffer）") {
            if (!proposal.diff.hasChanges) {
                Text("（与原文没有差异）", style = MaterialTheme.typography.bodySmall, color = palette.text3)
            } else {
                proposal.diff.hunks.forEach { hunk ->
                    val prefix = when (hunk.kind) {
                        DiffLineKind.ADDED -> "+ "
                        DiffLineKind.REMOVED -> "- "
                        DiffLineKind.UNCHANGED -> "  "
                    }
                    val color = when (hunk.kind) {
                        DiffLineKind.ADDED -> palette.accent
                        DiffLineKind.REMOVED -> palette.del
                        DiffLineKind.UNCHANGED -> palette.text3
                    }
                    Text(
                        hunk.lines.joinToString("\n") { prefix + it },
                        style = QianyanCodeStyle,
                        color = color,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ReviewSection(title: String, content: @Composable () -> Unit) {
    val palette = QianyanTheme.palette
    Text(title, style = MaterialTheme.typography.labelSmall, color = palette.text3)
    Spacer(Modifier.height(6.dp))
    content()
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
    onReject: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onExport: () -> Unit,
    onConfirmFinal: () -> Unit,
) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(QianyanDimens.TopBar).padding(horizontal = QianyanDimens.PageX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                chapter?.let { "第 ${it.order} 章 · ${it.title.ifBlank { "未命名" }}" } ?: "正文",
                style = TextStyle(fontFamily = QianyanFonts.serif, fontSize = 16.sp, color = palette.text),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            if (ui.waitingForUser) {
                if (ui.candidateLost) {
                    // 候选已失效：批准没有对象；拒绝只结束当前 Gate、不依赖候选，仍可用。
                    ActionButton("拒绝失效提案", ActionLevel.SECONDARY, ui.canReject, onReject)
                } else {
                    ActionButton("通过确认", ActionLevel.PRIMARY, ui.canApprove, onApprove)
                    Spacer(Modifier.width(8.dp))
                    // 拒绝只结束当前 Gate；Canonical 不变，旧 Gate 不复用。
                    ActionButton("拒绝", ActionLevel.TEXT, ui.canReject, onReject)
                }
            } else {
                ActionButton("保存", ActionLevel.SECONDARY, ui.canSave, onSave)
                Spacer(Modifier.width(8.dp))
                ActionButton("让 Qianyan 修改", ActionLevel.SECONDARY, ui.canRewrite, onRewrite)
                Spacer(Modifier.width(8.dp))
                ActionButton("继续写", ActionLevel.PRIMARY, ui.canGenerate, onContinue)
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
                    MenuEntry("历史") { setMenuOpen(false); state.nav = Workspace.HISTORY }
                    MenuEntry("导出当前章节为 TXT…") { setMenuOpen(false); onExport() }
                    MenuEntry("定稿确认") { setMenuOpen(false); onConfirmFinal() }
                    // 只有 Gate 已关闭时才引导去 Agent 重新发起；PENDING Gate 未关闭时会被 Gate 阻塞。
                    if (!ui.waitingForUser) {
                        MenuEntry("去 Agent 重新发起") { setMenuOpen(false); state.nav = Workspace.CREATE }
                    }
                    MenuPending("自动保存 · 已开启", "停止输入 1.2 秒后写入当前 Draft（待审候选不会自动保存）")
                    MenuSeparator()
                    // 未接入的收尾动作：写明接入点，不放假按钮
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
        } else if (ui.candidateLost) {
            Row(Modifier.fillMaxWidth().padding(horizontal = QianyanDimens.PageX, vertical = 6.dp)) {
                Text(
                    CANDIDATE_LOST_MESSAGE,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text2,
                )
            }
        } else if (ui.waitingForUser) {
            Row(Modifier.fillMaxWidth().padding(horizontal = QianyanDimens.PageX, vertical = 6.dp)) {
                Text(
                    "已停在人工确认：请阅读原文与 AI 候选稿后决定「通过确认」或「拒绝」（不会自动批准）。",
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
