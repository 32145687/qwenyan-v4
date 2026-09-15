package com.qianyan.app.desktop.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.adapter.DesktopFilePicker
import com.qianyan.app.desktop.adapter.DesktopNovelDeletion
import com.qianyan.app.desktop.ui.BreathingSparkle
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyHint
import com.qianyan.app.desktop.ui.QianyanCard
import com.qianyan.app.desktop.ui.QianyanChip
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectSource
import com.qianyan.model.core.Novel
import com.qianyan.model.genre.Genre
import com.qianyan.model.story.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 首页所需的真实统计（全部来自 UseCase，不编造）。 */
private data class HomeStats(
    val chapterCount: Int,
    val waitingCount: Int,
    val activeChapter: Chapter?,
    val activePhase: String?,
)

/**
 * 01 首页 / 作品空间。
 * 真实能力：novels.listOriginals / createOriginal / getNovel；chapters.listByNovel；
 * workflowFacade.getChapterProgress；txts.importTxtAsOriginal；genres.availableGenres（P14-A）。
 */
@Composable
fun HomeScreen(state: DesktopAppState) {
    var novels by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var stats by remember { mutableStateOf<HomeStats?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<Novel?>(null) }
    var busyMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.refreshKey) {
        val all = state.load { it.novels.listOriginals() }
        val visible = all.filterNot { it.novelId.value in state.graph.hiddenNovelIds() }
        novels = visible.sortedByDescending { it.updatedAt }

        val focus = state.novel?.let { cur -> visible.firstOrNull { it.novelId == cur.novelId } } ?: visible.firstOrNull()
        stats = if (focus == null) null else {
            val chapters = state.load { it.chapters.listByNovel(focus.novelId) }.sortedBy { ch -> ch.order }
            val waiting = state.load { c -> chapters.count { c.workflowFacade.getChapterProgress(it.chapterId).waitingForUser } }
            val active = chapters.lastOrNull()
            val phase = active?.let { ch -> state.load { it.workflowFacade.getChapterProgress(ch.chapterId).phase.name } }
            HomeStats(chapters.size, waiting, active, phase)
        }
    }

    fun importTxt() {
        val file = DesktopFilePicker.chooseTxt()
        if (file == null) { state.toast("已取消选择文件"); return }
        if (!file.exists() || !file.canRead()) { state.toast("无法读取文件：${file.name}"); return }

        busyMsg = "正在导入《${file.name}》…"
        state.launchWork {
            try {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    state.toast("文件是空的：${file.name}")
                    return@launchWork
                }
                val out = state.container.txts.importTxtAsOriginal(
                    TxtSource(bytes, file.name),
                    title = file.nameWithoutExtension,
                )
                val name = state.container.novels.getNovel(out.novelId)?.title ?: file.nameWithoutExtension
                state.toast(
                    if (out.isDuplicate) "《$name》已在书架（内容相同，未重复导入）"
                    else "已导入《$name》· ${out.charCount} 字 · ${out.chapterCount} 章 · ${out.encoding}",
                )
            } finally {
                busyMsg = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.HOME) {
            OutlinedButton(onClick = { importTxt() }, enabled = !state.busy) { Text("导入 TXT") }
            Button(
                onClick = { showCreate = true },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("新建作品") }
        }

        val focus = novels.firstOrNull { it.novelId == state.novel?.novelId } ?: novels.firstOrNull()

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 34.dp, end = 34.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            busyMsg?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("✦", color = MaterialTheme.colorScheme.secondary)
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HeroCard(
                    novel = focus,
                    stats = stats,
                    modifier = Modifier.weight(1.5f),
                    onContinue = {
                        if (focus == null) showCreate = true
                        else { state.novel = focus; state.section = Section.WRITE }
                    },
                    onObserve = {
                        if (focus == null) state.toast("先创建或导入一部作品")
                        else { state.novel = focus; state.section = Section.MANAGE }
                    },
                )
                TodayCard(
                    novel = focus,
                    stats = stats,
                    modifier = Modifier.weight(1f),
                    onOpenManage = { focus?.let { state.novel = it; state.section = Section.MANAGE } },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { showCreate = true }.padding(22.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("＋", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("新建作品", style = MaterialTheme.typography.titleMedium)
                            Text("从一句话想法开始", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                QianyanCard(Modifier.weight(2f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("书架", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                        Text("${novels.size} 部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (novels.isEmpty()) {
                        EmptyHint("书架还是空的——点「新建作品」从一句话开始，或用「导入 TXT」把已有稿件带进来。")
                    } else {
                        novels.forEach { n ->
                            ShelfRow(
                                novel = n,
                                isCurrent = n.novelId == state.novel?.novelId,
                                onOpen = { state.novel = n; state.section = Section.WRITE },
                                onRemove = { pendingRemove = n },
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                NavCard("作者智能", "千言会从你的选择与修改里学习（P16+ 规划中）", Modifier.weight(1f)) {
                    state.section = Section.AUTHOR
                }
                NavCard("成书", "把已完稿的章节当一本书来读", Modifier.weight(1f)) {
                    focus?.let { state.novel = it; state.section = Section.BOOK }
                }
            }
        }
    }

    if (showCreate) CreateNovelDialog(
        state = state,
        onDismiss = { showCreate = false },
        onCreated = { id ->
            showCreate = false
            state.launchWork {
                state.container.novels.getNovel(id)?.let { created ->
                    withContext(Dispatchers.Main) {
                        state.novel = created
                        state.section = Section.WRITE
                        state.toast("《${created.title}》已创建")
                    }
                }
            }
        },
    )

    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除《${target.title}》？", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("会删除这部作品及其全部关联数据：章节、正文草稿、TXT 文档、词库、故事状态（人物 / 世界规则 / 事件 / 时间线）、伏笔、叙事账本、流程记录。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("删除前会自动把每一章正文备份到：",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%APPDATA%\\Qianyan\\deleted-backups\\${target.title}-<时间戳>\\",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary)
                    Text("误删可从那里找回。此操作在数据库中不可撤销。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        pendingRemove = null
                        state.launchWork {
                            val backups = state.load { c ->
                                c.chapters.listByNovel(target.novelId).map { ch ->
                                    DesktopNovelDeletion.ChapterBackup(
                                        order = ch.order,
                                        title = ch.title,
                                        content = c.draftRepository.latestByChapter(ch.chapterId)?.content ?: "",
                                    )
                                }
                            }
                            val dir = state.graph.deletion().backup(target.title, target.synopsis, backups)
                            val counts = state.graph.deletion().deleteCascade(target.novelId.value)
                            state.graph.unhideNovel(target.novelId.value)
                            val affected = counts.values.sum()
                            withContext(Dispatchers.Main) {
                                if (state.novel?.novelId == target.novelId) state.novel = null
                                state.toast(
                                    "已删除《${target.title}》· 清理 $affected 行" +
                                        (dir?.let { " · 备份：${it.fileName}" } ?: " · ⚠ 备份失败，请检查磁盘"),
                                )
                            }
                        }
                    },
                ) { Text("备份并删除") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun HeroCard(
    novel: Novel?,
    stats: HomeStats?,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
    onObserve: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    ),
                ),
            )
            .padding(32.dp),
    ) {
        Box(
            Modifier.align(Alignment.TopEnd).size(220.dp).clip(RoundedCornerShape(200.dp))
                .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f), Color.Transparent))),
        )
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                BreathingSparkle(fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                Text(
                    if (novel == null) "开始创作" else "正在创作",
                    style = MaterialTheme.typography.labelMedium,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                novel?.let { "《${it.title}》" } ?: "你的书架还是空的",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                fontFamily = FontFamily.Serif,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                novel?.let { n ->
                    n.genre.take(3).joinToString(" · ").ifBlank { "未设题材" } + " · ${stats?.chapterCount ?: 0} 章"
                } ?: "新建一部作品，或把你已有的 TXT 稿件导入进来",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats?.activeChapter?.let {
                    QianyanChip("第 ${it.order} 章 · ${it.title.ifBlank { "未命名" }}", MaterialTheme.colorScheme.secondaryContainer)
                }
                val waiting = stats?.waitingCount ?: 0
                QianyanChip(
                    if (waiting > 0) "$waiting 章等待确认" else "无待确认变更",
                    if (waiting > 0) QianyanColors.AmberSoftLight else MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onContinue) { Text(if (novel == null) "新建作品" else "继续创作") }
                OutlinedButton(onClick = onObserve) { Text("看看故事现在什么样") }
            }
        }
    }
}

@Composable
private fun TodayCard(novel: Novel?, stats: HomeStats?, modifier: Modifier = Modifier, onOpenManage: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(QianyanColors.AmberSoftLight.copy(alpha = 0.8f)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("今天的创作状态", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
        if (novel == null) {
            Text(
                "创建或导入一部作品后，这里显示它的即时状态。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TodayItem("${stats?.chapterCount ?: 0} 章 · 当前停在第 ${stats?.activeChapter?.order ?: 0} 章")
            TodayItem("流程阶段：${phaseLabel(stats?.activePhase)}")
            val waiting = stats?.waitingCount ?: 0
            TodayItem(
                if (waiting > 0) "有 $waiting 章停在人工确认门——确认后才会写入故事事实" else "没有待确认变更",
                ai = true,
            )
            Text(
                "本章的上下文包（Narrative State / 伏笔 / 近期事件）可在「故事管理」实时编译预览。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "去故事管理 →", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpenManage() }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TodayItem(text: String, ai: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 6.dp).size(6.dp).clip(RoundedCornerShape(6.dp))
                .background(if (ai) MaterialTheme.colorScheme.secondary else QianyanColors.AmberLight),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavCard(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("✦", color = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Text("›", color = MaterialTheme.colorScheme.outline, fontSize = 18.sp)
    }
}

@Composable
private fun ShelfRow(novel: Novel, isCurrent: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen() }.padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(width = 36.dp, height = 50.dp).clip(RoundedCornerShape(5.dp, 9.dp, 9.dp, 5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (isCurrent) QianyanColors.BrownLight else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                            if (isCurrent) Color(0xFF4A3E2C) else MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(novel.title.take(1), color = Color.White, fontFamily = FontFamily.Serif, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(novel.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                if (isCurrent) QianyanChip("当前", QianyanColors.AmberSoftLight)
            }
            Text(
                novel.genre.take(2).joinToString(" · ").ifBlank { "未设题材" } +
                    (if (novel.source != ProjectSource.ORIGINAL_NOVEL) " · 来自 TXT" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            "删除", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove() }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun phaseLabel(phase: String?): String = when (phase) {
    null -> "尚未开始"
    ChapterPhase.PLANNING.name -> "规划中"
    ChapterPhase.WRITING.name -> "写作中"
    ChapterPhase.REVIEWING.name -> "审校中"
    ChapterPhase.REVISING.name -> "修订中"
    ChapterPhase.WAITING_CONFIRMATION.name -> "等待你确认"
    ChapterPhase.UPDATING_STORY.name -> "更新故事状态"
    ChapterPhase.COMPLETED.name -> "已完成"
    ChapterPhase.FAILED.name -> "失败（可恢复）"
    ChapterPhase.NOT_STARTED.name -> "尚未开始"
    else -> phase
}

@Composable
private fun CreateNovelDialog(state: DesktopAppState, onDismiss: () -> Unit, onCreated: (NovelId) -> Unit) {
    var title by remember { mutableStateOf("") }
    var synopsis by remember { mutableStateOf("") }
    var genres by remember { mutableStateOf<List<Genre>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        genres = runCatching { state.load { it.genres.availableGenres() } }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建作品", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("书名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(synopsis, { synopsis = it }, label = { Text("简介（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (genres.isNotEmpty()) {
                    Text("题材（受控目录 · 可多选）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    genres.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { g ->
                                val on = g.genreId.value in selected
                                Text(
                                    g.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { selected = if (on) selected - g.genreId.value else selected + g.genreId.value }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && !state.busy,
                onClick = {
                    val t = title.trim()
                    val g = selected.toList()
                    val s = synopsis.trim()
                    state.launchWork {
                        val id = state.container.novels.createOriginal(title = t, genre = g, synopsis = s)
                        withContext(Dispatchers.Main) { onCreated(id) }
                    }
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
