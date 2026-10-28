package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.CreationMessage
import com.qianyan.app.desktop.ui.CreationMessageRole
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.Workspace
import com.qianyan.app.desktop.ui.convergenceOutcomeLabel
import com.qianyan.app.desktop.ui.importTxtFromPicker
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.workspace.TaskComposer
import com.qianyan.model.novelagent.NovelAgentApprovalState
import com.qianyan.model.novelagent.NovelAgentErrorCodes
import com.qianyan.model.workspace.WorkspaceSelection

/**
 * 创作工作区：自然语言任务入口 + 连续会话 + 底部固定输入框。
 *
 * 空状态只呈现标题与输入框，不做 Dashboard、作品列表或能力卡片墙；
 * 作品切换与新建放在顶部栏与左栏，TXT 导入放在输入框「添加」菜单。
 */
@Composable
fun AgentPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    // 草稿放在 DesktopAppState：切到正文 / 故事再回来仍保留；切换作品时由 applyNovelSwitch 清空。
    val input = state.creationDraft
    val messages = state.creationMessages
    val scroll = rememberScrollState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }

    fun submit() {
        val text = state.creationDraft.trim()
        if (text.isEmpty() || state.busy) return
        if (state.launchAgentTask(text)) state.creationDraft = ""
    }

    if (messages.isEmpty()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 706.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "开始你的创作",
                    style = MaterialTheme.typography.displaySmall,
                    color = palette.text,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "一句话说出你想写的故事。千言会帮你搭好开头、人物和节奏，你随时可以接手继续写。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.text2,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(26.dp))

                TaskComposer(state, input, { state.creationDraft = it }, ::submit)

                Spacer(Modifier.height(12.dp))
                ComposerHint()

                if (state.novel == null) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "还没有打开作品。先新建一部，或导入 TXT 初稿。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text3,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("新建作品", ActionLevel.SECONDARY, true) { state.createNovelOpen = true }
                        ActionButton("导入 TXT", ActionLevel.TEXT, true) {
                            state.importTxtFromPicker()
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = 706.dp).fillMaxWidth()
                        .verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    messages.forEach { message -> MessageBlock(message) }
                    AgentResultCard(state)
                    Spacer(Modifier.height(12.dp))
                }
            }
            HLine()
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Box(Modifier.align(Alignment.Center).widthIn(max = 706.dp).fillMaxWidth()) {
                    TaskComposer(state, input, { state.creationDraft = it }, ::submit)
                }
            }
        }
    }
}

/* ---------------- 输入提示 ---------------- */

/** 发送键与写入路径的说明（生成内容先进入「修改」，不直接写正文）。 */
@Composable
private fun ComposerHint() {
    Text(
        "${shortcutLabel()} + Enter 发送 · Enter 换行 · 生成内容会先进入「修改」，由你确认后写入正文。",
        style = MaterialTheme.typography.labelSmall,
        color = QianyanTheme.palette.text3,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/* ---------------- 连续会话 ---------------- */

@Composable
private fun MessageBlock(message: CreationMessage) {
    val palette = QianyanTheme.palette
    val isUser = message.role == CreationMessageRole.USER

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            when (message.role) {
                CreationMessageRole.USER -> "你"
                CreationMessageRole.ERROR -> "错误"
                CreationMessageRole.ASSISTANT -> "千言"
            },
            style = MaterialTheme.typography.labelSmall,
            color = palette.text3,
        )
        Spacer(Modifier.height(4.dp))
        when {
            isUser -> Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.soft)
                    .padding(12.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = palette.text)
            }

            message.role == CreationMessageRole.ERROR -> Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.del,
            )

            else -> Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.width(2.dp).heightIn(min = 22.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(palette.accentSoft),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                    message.detail?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = palette.text3)
                    }
                }
            }
        }
    }
}

/* ---------------- 任务状态与后续操作 ---------------- */

@Composable
private fun AgentResultCard(state: DesktopAppState) {
    val result = state.lastAgentResult ?: return
    val palette = QianyanTheme.palette
    val failureCode = result.failure?.code
    val candidateLost = failureCode == NovelAgentErrorCodes.ARTIFACT_NOT_FOUND
    val approvedNotCommitted = failureCode == NovelAgentErrorCodes.CANDIDATE_APPROVED_NOT_COMMITTED
    val approvalState = result.approvalState
    val commitId = result.commitId
    // 任务卡展示所属章节，避免用户在跨章节操作后误判。
    val taskChapterLabel = state.lastAgentRequest
        ?.activeChapterId
        ?.let { chapterId ->
            state.workspace.candidates
                .firstOrNull { it.selection.chapterId == chapterId }
                ?.label
                ?.takeIf { it.isNotBlank() }
        }
    val visible = candidateLost || approvedNotCommitted || commitId != null ||
        approvalState == NovelAgentApprovalState.BRIDGED || approvalState == NovelAgentApprovalState.UNBRIDGED
    if (!visible) return

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .clip(RoundedCornerShape(QianyanShapes.r3))
            .border(1.dp, palette.lineStrong, RoundedCornerShape(QianyanShapes.r3))
            .padding(14.dp),
    ) {
        Text("任务状态", style = MaterialTheme.typography.titleSmall, color = palette.text2)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                candidateLost -> "候选已失效（可能因进程重启）：请重新发起任务。"
                approvedNotCommitted -> "正式 Gate 已批准，但尚未提交完成；请在「正文」页重试批准收敛。"
                commitId != null ->
                    "已提交 Canonical（commit=${commitId.value}）；可执行只读收敛检查。"
                approvalState == NovelAgentApprovalState.BRIDGED ->
                    "已进入正式审批：请在「正文」页对该章节的待确认提案执行批准或拒绝。"
                else -> "未进入正式审批：${result.failure?.detail ?: "候选已保留"}。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
        )
        if (taskChapterLabel != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "任务章节 · $taskChapterLabel",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                approvedNotCommitted -> ActionButton("刷新任务状态", ActionLevel.SECONDARY, true) {
                    state.refreshAgentCandidate()
                }
                commitId != null -> ActionButton("收敛检查", ActionLevel.SECONDARY, !state.busy) {
                    state.launchConvergenceReview()
                }
                approvalState == NovelAgentApprovalState.UNBRIDGED -> {
                    ActionButton("重试接入审批", ActionLevel.SECONDARY, true) { state.retryAgentBridge() }
                    ActionButton("丢弃候选", ActionLevel.TEXT, true) { state.discardAgentCandidate() }
                }
                approvalState == NovelAgentApprovalState.BRIDGED -> ActionButton(
                    "去正文处理",
                    ActionLevel.SECONDARY,
                    true,
                ) { openTaskChapter(state) }
            }
        }
        state.lastConvergenceReview
            ?.takeIf { it.sourceCommitId == commitId }
            ?.let { review ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "收敛评审 · round ${review.round} · ${convergenceOutcomeLabel(review.outcome)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.text2,
                )
                Text(
                    "ERROR ${review.errorCount} / WARNING ${review.warningCount} · ${review.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text,
                )
            }
        if (approvalState != NovelAgentApprovalState.NOT_REQUIRED && !candidateLost) {
            Spacer(Modifier.height(8.dp))
            Text(
                "正式批准 / 拒绝仅在「正文」页完成；此处只同步任务状态，不会提交正文。",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
            )
        }
    }
}

/**
 * 从任务请求定位到原章节，再进入正文；不依赖用户当前的 Workspace Selection。
 * 若任务所属作品/章节已经不可用，明确提示，不猜测其他章节。
 */
private fun openTaskChapter(state: DesktopAppState) {
    val request = state.lastAgentRequest
    val chapterId = request?.activeChapterId
    val novel = state.novel
    if (request == null || chapterId == null || novel == null || novel.projectId != request.projectId) {
        state.toast("无法定位任务所属章节，请在左侧章节目录中重新选择")
        state.openWorkspace(Workspace.WRITE, 0)
        return
    }
    val chapterExists = state.workspace.candidates.isEmpty() ||
        state.workspace.candidates.any { it.selection.chapterId == chapterId }
    if (!chapterExists) {
        state.toast("任务所属章节已不存在，请在左侧章节目录中重新选择")
        state.openWorkspace(Workspace.WRITE, 0)
        return
    }
    state.workspace.select(WorkspaceSelection.chapter(novel.projectId, novel.novelId, chapterId))
    state.openWorkspace(Workspace.WRITE, 0)
}

/* ---------------- 新建作品 ---------------- */

/**
 * 新建作品（走既有 `novels.createOriginal`）。
 *
 * 只收集标题：题材 / 简介属于 Story Foundation 阶段，不在此处伪造。
 */
@Composable
fun CreateNovelDialog(state: DesktopAppState, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    var title by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        state.modalOpen = true
        onDispose { state.modalOpen = false }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (QianyanTheme.isDark) 0.55f else 0.32f))
                .clickable { if (!working) onClose() },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(420.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.raised)
                    .consumeClicks()
                    .padding(18.dp),
            ) {
                Text("新建作品", style = MaterialTheme.typography.titleLarge, color = palette.text)
                Spacer(Modifier.height(4.dp))
                Text(
                    "创建一本 Original 作品。题材与故事方向在「故事」中确认（不在此处伪造）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                )
                Spacer(Modifier.height(14.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(QianyanShapes.r3))
                        .background(palette.bg)
                        .border(1.dp, palette.lineStrong, RoundedCornerShape(QianyanShapes.r3))
                        .padding(horizontal = 9.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        Box {
                            if (title.isEmpty()) {
                                Text("作品标题", style = MaterialTheme.typography.bodyLarge, color = palette.text3)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton("取消", ActionLevel.TEXT, !working, onClose)
                    Spacer(Modifier.width(8.dp))
                    ActionButton("创建", ActionLevel.PRIMARY, title.isNotBlank() && !working && !state.busy) {
                        val name = title.trim()
                        working = true
                        state.io(
                            then = { novel ->
                                working = false
                                onClose()
                                // 复用与「打开作品」相同的状态重置：新作品不得残留旧作品的会话 / 候选 / 检查器状态
                                state.applyNovelSwitch(novel)
                                state.toast("已创建《$name》")
                            },
                            onFailure = { working = false },
                        ) {
                            val novelId = state.container.novels.createOriginal(title = name)
                            state.container.projects.openProject(novelId)
                            state.container.novels.getNovel(novelId)
                        }
                    }
                }
            }
        }
    }
}
