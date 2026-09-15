package com.qianyan.app.desktop.ui.write
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.adapter.DesktopFilePicker
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.BreathingSparkle
import com.qianyan.app.desktop.ui.EmptyHint
import com.qianyan.app.desktop.ui.PulsingDot
import com.qianyan.app.desktop.ui.QianyanCard
import com.qianyan.app.desktop.ui.StatusTag
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import com.qianyan.app.desktop.ui.statusColor
import com.qianyan.app.desktop.ui.theme.ProseStyle
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.model.ChapterId
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 04 正文创作（数字书房）。
 * 真实能力：chapters.listByNovel / createNextChapter / findById；
 * workflowFacade.startChapter / advance / approve / getChapterProgress（Durable Workflow + HITL 门）；
 * draftRepository.latestByChapter（正文读取）；confirmations.confirmFinalDraft（定稿确认闸门）。
 * AI 产出当前为 Mock Provider——流程与持久化全部真实。
 */
@Composable
fun WriteScreen(state: DesktopAppState) {
    val novel = state.novel
    if (novel == null) {
        Column(Modifier.fillMaxSize()) {
            SectionHeader(Section.WRITE)
            EmptyHint("尚未打开作品——回到「首页」选择或创建一本书。")
        }
        return
    }

    var chapters by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<Chapter>>(emptyList()) }
    var selected by remember(novel.novelId) { mutableStateOf<Chapter?>(null) }
    var showNew by remember { mutableStateOf(false) }

    LaunchedEffect(state.refreshKey, novel.novelId) {
        chapters = state.load { it.chapters.listByNovel(novel.novelId) }
        if (selected == null) selected = chapters.lastOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.WRITE) {
            Text("《${novel.title}》", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            OutlinedButton(onClick = { showNew = true }) { Text("新建章节") }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 34.dp)) {
            // 章节列表
            Column(Modifier.width(250.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                Text("章节（${chapters.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(6.dp))
                LazyColumn {
                    items(chapters.sortedBy { it.order }) { ch ->
                        val sel = selected?.chapterId == ch.chapterId
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                                .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { selected = ch }
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
            Spacer(Modifier.width(16.dp))
            // 章节工作台
            val ch = selected
            if (ch == null) EmptyHint("选择或新建一章开始。", Modifier.weight(1f))
            else ChapterWorkbench(state, novel, ch, Modifier.weight(1f))
        }
    }

    if (showNew) NewChapterDialog(state, novel, onDismiss = { showNew = false }, onCreated = { showNew = false })
}


@Composable
private fun ChapterWorkbench(state: DesktopAppState, novel: Novel, chapter: Chapter, modifier: Modifier = Modifier) {
    var progress by remember(chapter.chapterId, state.refreshKey) {
        mutableStateOf<String?>(null)
    }
    var draft by remember(chapter.chapterId, state.refreshKey) { mutableStateOf<Draft?>(null) }
    var waiting by remember(chapter.chapterId, state.refreshKey) { mutableStateOf(false) }
    var phase by remember(chapter.chapterId, state.refreshKey) { mutableStateOf("NOT_STARTED") }
    var ctxOpen by remember(chapter.chapterId) { mutableStateOf(false) }
    var showRewrite by remember(chapter.chapterId) { mutableStateOf(false) }
    var pack by remember(chapter.chapterId) { mutableStateOf<com.qianyan.model.lcl.ChapterContextPack?>(null) }

    // 上下文包：真实的确定性编译（ChapterContextCompileUseCases），不是装饰
    LaunchedEffect(ctxOpen, chapter.chapterId) {
        if (ctxOpen) {
            pack = runCatching {
                state.load { it.chapterContextPack.compileChapterContext(novel.novelId, null, chapter.chapterId) }
            }.getOrNull()
        }
    }

    LaunchedEffect(chapter.chapterId, state.refreshKey) {
        val info = state.load { c ->
            val p = c.workflowFacade.getChapterProgress(chapter.chapterId)
            ChapterWorkInfo(p.phase.name, p.waitingForUser, p.revisionCount, c.draftRepository.latestByChapter(chapter.chapterId))
        }
        val p = info
        phase = p.phaseName
        waiting = p.waiting
        draft = p.draft
        progress = "阶段 ${p.phaseName} · 修订 ${p.revisionCount}/3" + run {
            val did = p.draft?.draftId
            if (did != null) " · Draft ${did.value.take(8)}" else ""
        }
    }

    fun drive() {
        state.launchWork {
            val facade = state.container.workflowFacade
            var p = facade.getChapterProgress(chapter.chapterId)
            if (p.phase == com.qianyan.app.desktop.ui.write.PhaseRef.NOT_STARTED) {
                p = facade.startChapter(novel.novelId, null, chapter.chapterId)
            }
            var guard = 0
            while (!p.waitingForUser && p.phase != PhaseRef.COMPLETED && p.phase != PhaseRef.FAILED && guard++ < 12) {
                p = facade.advance(chapter.chapterId)
            }
            withContext(Dispatchers.Main) {
                state.toast(
                    when {
                        p.waitingForUser -> "流程停在人工闸门（${p.phase.name}）——等待你的审批"
                        p.phase == PhaseRef.COMPLETED -> "本章流程已完成"
                        else -> "流程推进至 ${p.phase.name}"
                    },
                )
            }
        }
    }

    Row(modifier.fillMaxHeight()) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 章头
        QianyanCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(chapter.title.ifBlank { "第 ${chapter.order} 章" }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                    Text(progress ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (waiting) PulsingDot(QianyanColors.AmberLight, 8.dp)
                    StatusTag(phase, statusColor(phase))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { drive() }, enabled = !state.busy) {
                    Text(if (phase == "NOT_STARTED") "开始创作本章" else if (waiting) "流程等待审批中" else "继续推进")
                }
                if (waiting) {
                    OutlinedButton(onClick = {
                        state.launchWork {
                            val p = state.container.workflowFacade.approve(chapter.chapterId)
                            withContext(Dispatchers.Main) { state.toast("已审批 · 流程推进至 ${p.phase.name}") }
                        }
                    }) { Text("通过闸门") }
                }
                val d = draft
                if (d != null && (d.status == DraftStatus.FINAL || d.status == DraftStatus.PENDING_CONFIRMATION)) {
                    Button(onClick = {
                        state.launchWork {
                            run { val did = d.draftId; state.container.confirmations.confirmFinalDraft(did, novel.novelId) }
                            withContext(Dispatchers.Main) { state.toast("定稿已确认（CONFIRMED）· 知识更新可在流程中执行") }
                        }
                    }) { Text("确认定稿") }
                }
                OutlinedButton(onClick = { showRewrite = true }, enabled = !state.busy) {
                    Text("AI 重写本章")
                }
                OutlinedButton(onClick = { ctxOpen = !ctxOpen }) {
                    Text(if (ctxOpen) "收起上下文" else "本章上下文")
                }
                if (d != null && d.content.isNotBlank()) {
                    OutlinedButton(onClick = {
                        state.launchWork {
                            val f = DesktopFilePicker.chooseSave("${chapter.title.ifBlank { "chapter" }}.txt") ?: return@launchWork
                            withContext(Dispatchers.IO) { f.writeText(d.content) }
                            withContext(Dispatchers.Main) { state.toast("已导出到 ${f.name}") }
                        }
                    }) { Text("导出 TXT") }
                }
            }
        }

        // 正文（数字书房排版：居中窄栏 + 段落缩进 + 衬线宽行距）
        val d = draft
        QianyanCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("正文", style = MaterialTheme.typography.titleMedium)
                if (d != null) StatusTag(d.status.name, statusColor(d.status.name))
            }
            Spacer(Modifier.height(14.dp))
            if (d == null || d.content.isBlank()) {
                EmptyHint("本章还没有正文——点击「开始创作本章」，千言会沿 Durable Workflow 规划并写出草稿（AI 当前为 Mock Provider，内容为示意稿；流程与持久化真实）。")
            } else {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 620.dp)) {
                        d.content.split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { para ->
                                Text(
                                    "\u3000\u3000$para",
                                    style = ProseStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.padding(bottom = 18.dp),
                                )
                            }
                    }
                }
            }
        }
    }

    if (showRewrite) {
        RewriteDialog(state, novel, chapter, onDismiss = { showRewrite = false })
    }

    // 本章上下文面板（真实 ChapterContextPack：确定性编译、有界）
    if (ctxOpen) {
        Spacer(Modifier.width(16.dp))
        ChapterContextPanel(pack, Modifier.width(340.dp).fillMaxHeight())
    }
    }
}

/** 「本章 AI 正在读取什么」——数据来自真实的 ChapterContextCompileUseCases。 */
@Composable
private fun ChapterContextPanel(pack: com.qianyan.model.lcl.ChapterContextPack?, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BreathingSparkle(fontSize = 13.sp)
                Text("本章 AI 正在读取什么", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            }
            Text(
                "由 ChapterContextCompileUseCase 确定性编译（窗口 + 预算），不是装饰数据",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
        }
        if (pack == null) {
            Text("正在编译上下文包…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        } else {
            CtxItem("上下文包版本", "v${pack.packVersion}")
            CtxItem("滚动窗口", "${pack.horizonWindow.size} 章")
            pack.activeNarrative?.let { ns ->
                CtxItem("主线目标", ns.mainGoal.ifBlank { "（未设定）" })
                if (ns.openThreads.isNotEmpty()) {
                    CtxItem("未收束线索", ns.openThreads.joinToString("；") { it.description })
                }
            }
            if (pack.activeForeshadows.isNotEmpty()) {
                CtxItem("活动伏笔", pack.activeForeshadows.joinToString("；") { "${it.content}（${it.state.name}）" })
            }
            if (pack.recentEvents.isNotEmpty()) {
                CtxItem("近期事件", pack.recentEvents.joinToString("；") { it.name })
            }
            if (pack.activeThreads.isNotEmpty()) {
                CtxItem("活动线索", pack.activeThreads.joinToString("；") { it.description })
            }
            Text(
                "以上被打包成本章上下文交给规划与写作——数据库与技术细节不会打扰你。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CtxItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 章节工作台的加载结果（IO 线程读取，主线程应用）。 */
private data class ChapterWorkInfo(
    val phaseName: String,
    val waiting: Boolean,
    val revisionCount: Int,
    val draft: Draft?,
)

/** ChapterPhase 的静态引用（避免在 lambda 内依赖 Composable 上下文）。 */
object PhaseRef {
    val NOT_STARTED = com.qianyan.application.usecase.workflow.ChapterPhase.NOT_STARTED
    val COMPLETED = com.qianyan.application.usecase.workflow.ChapterPhase.COMPLETED
    val FAILED = com.qianyan.application.usecase.workflow.ChapterPhase.FAILED
}

@Composable
private fun NewChapterDialog(state: DesktopAppState, novel: Novel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    var title by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建章节") },
        text = {
            OutlinedTextField(title, { title = it }, label = { Text("章节标题（可留空自动编号）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        },
        confirmButton = {
            Button(enabled = !state.busy, onClick = {
                state.launchWork {
                    val ch = state.container.chapters.createNextChapter(title.trim(), novel.novelId)
                    withContext(Dispatchers.Main) { state.toast("已创建「${ch.title.ifBlank { "第 ${ch.order} 章" }}」") }
                    onCreated()
                }
            }) { Text("创建") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
