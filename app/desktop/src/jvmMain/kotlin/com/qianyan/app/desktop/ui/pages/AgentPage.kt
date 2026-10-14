package com.qianyan.app.desktop.ui.pages

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
import com.qianyan.app.desktop.adapter.DesktopFilePicker
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.ListRow
import com.qianyan.app.desktop.ui.NavItem
import com.qianyan.app.desktop.ui.QuietField
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.QianyanWordmarkStyle
import com.qianyan.model.NovelId
import com.qianyan.model.core.Novel

/**
 * 12.1 · Qianyan Agent（**入口页，不是 Dashboard、不是聊天站**）。
 *
 * 一个入口：`告诉 Qianyan 你现在想做什么`；下面是作品列表（真实 `novels.listOriginals()`）。
 * 没有统计卡、没有能力墙、没有 AI 装饰。输入后按 Enter 进入命令面板（既有能力入口）。
 */
@Composable
fun AgentPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var novels by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(state.refreshKey) {
        novels = runCatching { state.load { it.novels.listOriginals() } }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(58.dp))

        Text("千言", style = QianyanWordmarkStyle, color = palette.text)
        Spacer(Modifier.height(10.dp))
        Text("继续你的小说创作", style = MaterialTheme.typography.bodyLarge, color = palette.text2)
        Spacer(Modifier.height(6.dp))
        Text(
            "让 Qianyan 帮你理解、规划、修改和继续创作。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.text3,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(26.dp))

        QuietField(
            value = input,
            onValueChange = { input = it },
            placeholder = "告诉 Qianyan 你现在想做什么……",
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            // 新执行链路（任务 A）：自然语言任务直接进入既有 NovelAgent 编排（确定性意图解析，无第二套 Agent Loop）；
            // 命令面板保留独立入口（paletteOpen 快捷键），导航/设置类命令仍走它。
            onSubmit = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    state.launchAgentTask(text)
                    input = ""
                }
            },
        )

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickEntry("继续写作") { state.nav = NavItem.WRITE }
            QuickEntry("规划剧情") { state.nav = NavItem.STORY }
            QuickEntry("修改章节") { state.nav = NavItem.WRITE }
            QuickEntry("分析小说") { state.nav = NavItem.CHARACTERS }
        }

        Spacer(Modifier.height(44.dp))

        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
            SectionLabel("作品", trailing = {
                ActionButton("导入 TXT", ActionLevel.TEXT, true) {
                    val file = DesktopFilePicker.chooseTxt()
                    if (file == null) {
                        state.toast("已取消导入")
                    } else {
                        state.launchTxtImport(file.name, file.readBytes())
                    }
                }
                ActionButton("新建作品", ActionLevel.TEXT, true) { state.createNovelOpen = true }
            })

            if (novels.isEmpty()) {
                EmptyState("还没有作品", "点右上角「新建作品」，或导入一份 TXT 初稿")
            } else {
                novels.forEach { novel ->
                    ListRow(
                        title = novel.title,
                        meta = novel.genre.joinToString(" · ").ifBlank { statusLabel(novel.status.name) },
                        tag = if (state.novel?.novelId == novel.novelId) "当前" else null,
                        selected = state.novel?.novelId == novel.novelId,
                        horizontalPadding = 0.dp,
                        onClick = { openNovel(state, novel.novelId) },
                    )
                    HLine(Modifier.padding(horizontal = 8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "打开作品后，左侧导航的「正文 / 故事 / 章节 / 人物 / 世界 / 任务 / 历史」会读取该作品的真实数据。",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
            )
        }

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickEntry(label: String, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    Box(
        Modifier.weight(1f)
            .clip(RoundedCornerShape(QianyanShapes.r2))
            .clickable { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = palette.text2)
    }
}

/** 打开作品：`projects.openProject` 建立 Project 作用域，再读取 Novel（既有 I1 能力）。 */
private fun openNovel(state: DesktopAppState, novelId: NovelId) {
    state.io(
        then = { novel ->
            state.novel = novel
            novel?.let { state.toast("已打开《${it.title}》") }
        },
    ) {
        state.container.projects.openProject(novelId)
        state.container.novels.getNovel(novelId)
    }
}

/**
 * 新建作品（恢复 P20-PC1 已交付的入口；走既有 `novels.createOriginal`）。
 *
 * 只收集标题：题材 / 简介属于 Story Foundation 阶段（P14），不在此处伪造。
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
                QuietField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "作品标题",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton("取消", ActionLevel.TEXT, !working, onClose)
                    Spacer(Modifier.width(8.dp))
                    ActionButton("创建", ActionLevel.PRIMARY, title.isNotBlank() && !working) {
                        val name = title.trim()
                        working = true
                        state.io(
                            then = { novelId ->
                                working = false
                                onClose()
                                state.toast("已创建《$name》")
                                openNovel(state, novelId)
                            },
                        ) {
                            val novelId = state.container.novels.createOriginal(title = name)
                            state.container.projects.openProject(novelId)
                            novelId
                        }
                    }
                }
            }
        }
    }
}
