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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.PageHeader
import com.qianyan.app.desktop.ui.PendingNote
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.SourceNote
import com.qianyan.app.desktop.ui.chapterTitleOnly
import com.qianyan.app.desktop.ui.formatCount
import com.qianyan.app.desktop.ui.theme.QianyanChapterStyle
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.qianyanProseStyle
import com.qianyan.app.desktop.ui.wordCount
import com.qianyan.application.usecase.reading.ReaderChapter
import com.qianyan.engine.markdown.MarkdownBlock
import com.qianyan.model.reading.ReadingProgress
import com.qianyan.model.story.Chapter
import com.qianyan.model.workspace.WorkspaceObjectKind
import com.qianyan.model.workspace.WorkspaceSelection

/**
 * 阅读（Reader seam · P20-P4 的 PC 接线位）。
 *
 * **真实链路**：`reading.openChapter(novelId, variantId, chapterId)` → `ReaderChapter`
 * （受控 Markdown 的 blocks + 上一章 / 下一章）；`reading.position(chapterId)` → 已保存的阅读位置。
 * 这两个都是**只读**。
 *
 * 写操作（`reading.savePosition(novelId, chapterId, position)`）本轮按约定**未接入**，界面如实标注。
 */
@Composable
fun ReaderPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ws = state.workspace
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var reader by remember { mutableStateOf<ReaderChapter?>(null) }
    var saved by remember { mutableStateOf<ReadingProgress?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val novel = state.novel
    val variantId = ws.selection?.variantId
    val selectedChapter = ws.selection?.takeIf { it.kind == WorkspaceObjectKind.CHAPTER }?.chapterId

    LaunchedEffect(novel?.novelId, state.refreshKey) {
        val novelId = novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        chapters = runCatching { state.load { it.chapters.listByNovel(novelId, null) } }
            .getOrDefault(emptyList()).sortedBy { it.order }
        loading = false
    }

    val chapterId = selectedChapter ?: chapters.firstOrNull()?.chapterId
    LaunchedEffect(chapterId, novel?.novelId, variantId, state.refreshKey) {
        val novelId = novel?.novelId
        if (novelId == null || chapterId == null) {
            reader = null
            saved = null
            return@LaunchedEffect
        }
        error = null
        val chapter = runCatching {
            state.load { c ->
                c.reading.openChapter(novelId, variantId, chapterId)
            }
        }.onFailure { error = it.message }.getOrNull()
        reader = chapter
        saved = runCatching { state.load { it.reading.position(chapterId) } }.getOrNull()
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "阅读",
            meta = reader?.let { "第 ${it.chapterOrder} 章 · ${chapterTitleOnly(it.chapterTitle)}" },
            actions = {
                ActionButton("上一章", ActionLevel.SECONDARY, reader?.canGoPrevious == true) {
                    reader?.previousChapterId?.let { ws.select(selection(state, it)) }
                }
                Spacer(Modifier.width(8.dp))
                ActionButton("下一章", ActionLevel.SECONDARY, reader?.canGoNext == true) {
                    reader?.nextChapterId?.let { ws.select(selection(state, it)) }
                }
            },
        )

        when {
            novel == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            chapters.isEmpty() -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("这部作品还没有章节", "导入 TXT 或新建章节后再阅读")
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(26.dp))
                Column(Modifier.width(QianyanDimens.EditorWidth).padding(horizontal = 8.dp)) {
                    val r = reader
                    if (r == null) {
                        EmptyState("本章正文读取失败", error)
                        HLine()
                        PendingNote("reading.openChapter(novelId, variantId, chapterId)")
                    } else {
                        Text(
                            "第 ${r.chapterOrder} 章",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            chapterTitleOnly(r.chapterTitle).ifBlank { "未命名" },
                            style = QianyanChapterStyle,
                            color = palette.text,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(20.dp))

                        if (!r.hasDraft) {
                            EmptyState("本章还没有正文", "正文由「正文」页的继续写生成，或直接写入后再阅读")
                        } else {
                            MarkdownBody(r.blocks, state.proseSize.size)
                        }

                        Spacer(Modifier.height(28.dp))
                        HLine()
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val pos = saved?.position ?: r.position
                            Text(
                                "共 ${formatCount(wordCount(r.blocks.joinToString("") { blockText(it) }))} 字 · " +
                                    "格式 ${r.format ?: "纯文本"} · 阅读位置 $pos",
                                style = QianyanCodeStyle,
                                color = palette.text3,
                            )
                            Spacer(Modifier.weight(1f))
                        }
                        PendingNote(
                            "reading.savePosition(novelId, chapterId, position)",
                            "「记住阅读位置 / 从上次位置续读」尚未接入；上方显示的阅读位置是既有 ReadingProgress 的真实值。",
                        )
                        SourceNote("来源：reading.openChapter + reading.position（P20-P4 Reader seam，只读）")
                    }
                }
                Spacer(Modifier.height(50.dp))
            }
        }
    }
}

/** 按受控 Markdown 的块类型渲染（block 结构来自引擎，不做二次解析）。 */
@Composable
private fun MarkdownBody(blocks: List<MarkdownBlock>, sizeSp: Int) {
    val palette = QianyanTheme.palette
    val prose = qianyanProseStyle(sizeSp.sp)
    if (blocks.isEmpty()) {
        EmptyState()
        return
    }
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.Heading -> {
                Text(
                    block.text,
                    style = prose.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (sizeSp + 3).sp,
                    ),
                    color = palette.text,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
                )
            }

            is MarkdownBlock.UnorderedListItem -> {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text("·", style = prose, color = palette.text3)
                    Spacer(Modifier.width(8.dp))
                    Text(block.text, style = prose, color = palette.text)
                }
            }

            is MarkdownBlock.Quote -> {
                Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp)) {
                    Box(Modifier.width(2.dp).height(20.dp).background(palette.lineStrong))
                    Spacer(Modifier.width(10.dp))
                    Text(block.text, style = prose.copy(color = palette.text2), color = palette.text2)
                }
            }

            is MarkdownBlock.Degraded -> {
                SectionLabel("未支持的 Markdown 结构（原文保留）")
                Text(block.text, style = QianyanCodeStyle, color = palette.text3)
            }

            is MarkdownBlock.Paragraph -> {
                Text(block.text, style = prose, color = palette.text, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
    }
}

private fun blockText(block: MarkdownBlock): String = when (block) {
    is MarkdownBlock.Paragraph -> block.text
    is MarkdownBlock.Heading -> block.text
    is MarkdownBlock.UnorderedListItem -> block.text
    is MarkdownBlock.Quote -> block.text
    is MarkdownBlock.Degraded -> block.text
}

private fun selection(state: DesktopAppState, chapterId: com.qianyan.model.ChapterId): WorkspaceSelection =
    WorkspaceSelection.chapter(
        projectId = state.workspace.selection?.projectId ?: state.novel!!.projectId,
        novelId = state.novel!!.novelId,
        chapterId = chapterId,
    )
