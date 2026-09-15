package com.qianyan.app.desktop.ui.book
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyHint
import com.qianyan.app.desktop.ui.QianyanCard
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import com.qianyan.app.desktop.ui.StatusTag
import com.qianyan.app.desktop.ui.theme.ProseStyle
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter
import com.qianyan.model.writing.DraftStatus

/**
 * 06 成书（阅读空间）。
 * 真实能力：chapters.listByNovel + draftRepository.latestByChapter——展示已定稿/已写章节；
 * 阅读字号可调，正文导出走 Desktop File Picker。
 */
@Composable
fun BookScreen(state: DesktopAppState) {
    val novel: Novel? = state.novel
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.BOOK)
        if (novel == null) {
            EmptyHint("尚未打开作品。")
            return
        }

        var chapters by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<Chapter>>(emptyList()) }
        var drafts by remember(novel.novelId, state.refreshKey) { mutableStateOf<Map<String, com.qianyan.model.writing.Draft?>>(emptyMap()) }
        var selected by remember(novel.novelId, state.refreshKey) { mutableStateOf<String?>(null) }
        var fontSize by remember { mutableStateOf(17f) }
        var theme by remember { mutableStateOf(0) } // 0 纸色 · 1 暖黄 · 2 夜间

        LaunchedEffect(novel.novelId, state.refreshKey) {
            val loaded = state.load { c ->
                val chs = c.chapters.listByNovel(novel.novelId).sortedBy { it.order }
                chs to chs.associate { it.chapterId.value to c.draftRepository.latestByChapter(it.chapterId) }
            }
            chapters = loaded.first
            drafts = loaded.second
            if (selected == null) selected = chapters.firstOrNull { ch ->
                drafts[ch.chapterId.value]?.content?.isNotBlank() == true
            }?.chapterId?.value
        }

        val readable = chapters.filter { drafts[it.chapterId.value]?.content?.isNotBlank() == true }
        val selIndex = readable.indexOfFirst { it.chapterId.value == selected }

        Row(Modifier.fillMaxSize().padding(horizontal = 34.dp)) {
            // 封面 + 目录
            Column(Modifier.width(272.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                BookCover(novel.title, readable.size)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("目录", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                        Text("${readable.size} 章可读", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    chapters.forEach { ch ->
                        val d = drafts[ch.chapterId.value]
                        val sel = selected == ch.chapterId.value
                        val canRead = d?.content?.isNotBlank() == true
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable(enabled = canRead) { selected = ch.chapterId.value }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                ch.title.ifBlank { "第 ${ch.order} 章" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Serif,
                                color = if (canRead) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.weight(1f), maxLines = 1,
                            )
                            if (canRead) {
                                StatusTag(
                                    d!!.status.name,
                                    if (d.status == DraftStatus.CONFIRMED) QianyanColors.OkLight else QianyanColors.Ink3Light,
                                )
                            } else {
                                Text("待写", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val ch = chapters.firstOrNull { it.chapterId.value == selected }
                val d = selected?.let { drafts[it] }
                if (ch == null || d == null || d.content.isBlank()) {
                    EmptyHint("选择一章开始阅读。只有写出正文的章节可以读——写完一章后回到这里，它就像书一样可以翻。")
                } else {
                    val paper = when (theme) {
                        1 -> Color(0xFFF6EFDD)
                        2 -> Color(0xFF191715)
                        else -> MaterialTheme.colorScheme.surface
                    }
                    val ink = if (theme == 2) Color(0xFFCFC8BA) else Color(0xFF2E2B25)
                    QianyanCard(Modifier.padding(bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(ch.title.ifBlank { "第 ${ch.order} 章" }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                                Text("《${novel.title}》 · ${d.status.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(14f) }) { Text("A−") }
                                OutlinedButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(24f) }) { Text("A+") }
                                Text("·", color = MaterialTheme.colorScheme.outline)
                                listOf("纸色" to 0, "暖黄" to 1, "夜间" to 2).forEach { (label, idx) ->
                                    val on = theme == idx
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable { theme = idx }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(paper)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 30.dp),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Column(Modifier.widthIn(max = 640.dp)) {
                                d.content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
                                    Text(
                                        "\u3000\u3000$para",
                                        style = ProseStyle.copy(fontSize = fontSize.sp, color = ink),
                                        modifier = Modifier.padding(bottom = 18.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("—— 本章完 ——", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            OutlinedButton(
                                enabled = selIndex > 0,
                                onClick = { readable.getOrNull(selIndex - 1)?.let { selected = it.chapterId.value } },
                            ) { Text("← 上一章") }
                            Spacer(Modifier.width(10.dp))
                            OutlinedButton(
                                enabled = selIndex in 0 until readable.size - 1,
                                onClick = { readable.getOrNull(selIndex + 1)?.let { selected = it.chapterId.value } },
                            ) { Text("下一章 →") }
                        }
                    }
                }
            }
        }
    }
}

/** 书封：竖排书名的深色封面（原型里的成书视觉）。 */
@Composable
private fun BookCover(title: String, readableCount: Int) {
    Column(
        Modifier.fillMaxWidth().height(300.dp)
            .clip(RoundedCornerShape(6.dp, 16.dp, 16.dp, 6.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF33302A), Color(0xFF211F1C))))
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("千言 · 长篇", style = MaterialTheme.typography.labelMedium, color = Color(0xFF8C8375), letterSpacing = 2.sp)
        // 竖排书名（Compose 无 writing-mode，逐字纵向排列）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            title.take(7).forEach { ch ->
                Text(ch.toString(), fontFamily = FontFamily.Serif, fontSize = 26.sp, color = Color(0xFFEFE8DA))
            }
        }
        Text(
            if (readableCount > 0) "$readableCount 章已可阅读" else "尚无完稿章节",
            style = MaterialTheme.typography.bodySmall, color = Color(0xFF8C8375),
        )
    }
}
