package com.qianyan.app.desktop

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.NavItem
import com.qianyan.app.desktop.ui.PanelToggle
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.VLine
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.isMac
import com.qianyan.app.desktop.ui.pages.AgentPage
import com.qianyan.app.desktop.ui.pages.AuthorPage
import com.qianyan.app.desktop.ui.pages.BackupPage
import com.qianyan.app.desktop.ui.pages.ChaptersPage
import com.qianyan.app.desktop.ui.pages.CharactersPage
import com.qianyan.app.desktop.ui.pages.CreateNovelDialog
import com.qianyan.app.desktop.ui.pages.HistoryPage
import com.qianyan.app.desktop.ui.pages.MemoryPage
import com.qianyan.app.desktop.ui.pages.ReaderPage
import com.qianyan.app.desktop.ui.pages.StoryPage
import com.qianyan.app.desktop.ui.pages.TasksPage
import com.qianyan.app.desktop.ui.pages.VocabularyPage
import com.qianyan.app.desktop.ui.pages.WorldPage
import com.qianyan.app.desktop.ui.pages.WritePage
import com.qianyan.app.desktop.ui.settings.ProviderSettingsDialog
import com.qianyan.app.desktop.ui.settings.SettingsPanel
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.workspace.CommandPalette
import com.qianyan.app.desktop.ui.workspace.ContextInspector
import com.qianyan.model.workspace.InspectorTab

/**
 * Qianyan Writing IDE · PC 入口。
 *
 * 一个窗口 = 一个工作空间：顶部状态行（44dp）+ 三栏（导航 216 | 内容 | 检查器 300）+ 底部状态行（24dp）。
 * 三栏同属一个空间（同一层背景级差 + 1px 分隔线），不是三张独立卡片。
 *
 * 浮层（命令面板 / 设置弹窗 / Provider 对话框）叠在整个工作空间之上；
 * 设置是**弹窗**：不替换主工作区页面，关闭后页面与当前对象保持不变。
 */
fun main() = application {
    val graph = DesktopGraph.create()
    val state = DesktopAppState(graph)

    Window(
        onCloseRequest = ::exitApplication,
        title = "千言 Qianyan · Novel IDE",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
        onPreviewKeyEvent = { event -> handleShortcut(state, event) },
    ) {
        QianyanTheme(state.themeMode) {
            Surface(Modifier.fillMaxSize(), color = QianyanTheme.palette.bg) {
                AppShell(state)
            }
        }
    }
}

/**
 * 键盘路由。
 *
 * 列表型页面通过 [DesktopAppState.listNavEnabled] 主动开启 ↑/↓/Enter 导航；
 * 编辑器 / 命令面板 / 弹窗打开时该开关为 false —— 否则窗口级预览会抢走正文编辑器的
 * Enter（换行）与 ↑↓（光标移动）。
 */
private fun handleShortcut(state: DesktopAppState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val mod = if (isMac()) event.isMetaPressed else event.isCtrlPressed

    if (event.key == Key.Escape) {
        return when {
            state.settingsOpen -> { state.settingsOpen = false; true }
            state.providerSettingsOpen -> { state.providerSettingsOpen = false; true }
            state.paletteOpen -> { state.paletteOpen = false; true }
            state.focusMode -> { state.toggle(PanelToggle.FOCUS); true }
            else -> false
        }
    }

    // 浮层打开时不响应工作区快捷键（键盘属于浮层）。
    if (state.settingsOpen || state.providerSettingsOpen || state.createNovelOpen) return false
    if (state.modalOpen) return false

    if (!mod) {
        if (state.paletteOpen || !state.listNavEnabled) return false
        return when (event.key) {
            Key.DirectionDown -> { state.workspace.move(1); true }
            Key.DirectionUp -> { state.workspace.move(-1); true }
            Key.Enter -> { state.workspace.openCurrent(); true }
            else -> false
        }
    }

    if (event.isShiftPressed && event.key == Key.E) { state.toggle(PanelToggle.FOCUS); return true }
    return when (event.key) {
        Key.K -> { state.openPalette(); true }
        Key.B -> { state.toggle(PanelToggle.SIDEBAR); true }
        Key.Backslash -> { state.toggle(PanelToggle.INSPECTOR); true }
        Key.Comma -> { state.settingsOpen = true; true }
        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight -> {
            val keys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)
            NavItem.entries.firstOrNull { it.shortcut == keys.indexOf(event.key) + 1 }?.let { state.nav = it }
            true
        }
        else -> false
    }
}

@Composable
fun AppShell(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val providerMsg by state.graph.providerMessage.collectAsState()
    var pendingChanges by remember { mutableStateOf(0) }

    // Workspace：当前对象变化 / 数据变化时重读 Inspector（只读投影）
    LaunchedEffect(state.novel?.projectId, state.refreshKey, state.workspace.selection) {
        state.workspace.load()
    }
    LaunchedEffect(state.projectId, state.refreshKey) {
        val pid = state.projectId
        pendingChanges = if (pid == null) 0 else runCatching { state.load { it.changes.list(pid).size } }.getOrDefault(0)
    }
    LaunchedEffect(pendingChanges) { state.status = state.status.copy(pending = pendingChanges.toString()) }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state)

            if (providerMsg != null) {
                Row(
                    Modifier.fillMaxWidth().background(palette.delSoft).padding(horizontal = QianyanDimens.PageX, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AI 服务提示", style = MaterialTheme.typography.labelSmall, color = palette.del)
                    Spacer(Modifier.width(10.dp))
                    Text(providerMsg ?: "", style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 2)
                }
                HLine()
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (state.sidebarOpen) {
                    NavigationRail(state)
                    VLine()
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // Focus Mode：隐藏次要 UI（当前对象行），保留内容主体
                    if (!state.focusMode) WorkspaceHeader(state)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (state.nav) {
                            NavItem.AGENT -> AgentPage(state)
                            NavItem.WRITE -> WritePage(state)
                            NavItem.READ -> ReaderPage(state)
                            NavItem.STORY -> StoryPage(state)
                            NavItem.CHAPTERS -> ChaptersPage(state)
                            NavItem.CHARACTERS -> CharactersPage(state)
                            NavItem.WORLD -> WorldPage(state)
                            NavItem.VOCABULARY -> VocabularyPage(state)
                            NavItem.MEMORY -> MemoryPage(state)
                            NavItem.AUTHOR -> AuthorPage(state)
                            NavItem.TASKS -> TasksPage(state)
                            NavItem.HISTORY -> HistoryPage(state)
                            NavItem.BACKUP -> BackupPage(state)
                            NavItem.SETTINGS -> AgentPage(state) // 设置是弹窗，不落在此处
                        }
                    }
                }

                if (state.inspectorOpen) {
                    VLine()
                    ContextInspector(state)
                }
            }

            StatusBar(state)
        }

        /* ---------------- 浮层：Toast ---------------- */
        state.toast?.let { msg ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text,
                    modifier = Modifier.padding(bottom = 40.dp)
                        .clip(RoundedCornerShape(QianyanShapes.r3))
                        .background(palette.raised)
                        .consumeClicks()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        /* ---------------- 浮层：命令面板 ---------------- */
        if (state.paletteOpen) CommandPalette(state)

        /* ---------------- 浮层：设置弹窗 ---------------- */
        if (state.settingsOpen) {
            val dark = QianyanTheme.isDark
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = if (dark) 0.55f else 0.32f))
                        .clickable { state.settingsOpen = false },
                )
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SettingsPanel(state) { state.settingsOpen = false }
                }
            }
        }

        /* ---------------- 浮层：新建作品 ---------------- */
        if (state.createNovelOpen) {
            CreateNovelDialog(state) { state.createNovelOpen = false }
        }

        /* ---------------- 浮层：Provider 设置 ---------------- */
        if (state.providerSettingsOpen) {
            ProviderSettingsDialog(state.graph) { state.providerSettingsOpen = false }
        }
    }
}

/* ---------------- 顶部状态行（44dp） ---------------- */

@Composable
private fun TopBar(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.fillMaxWidth().height(QianyanDimens.TopBar).background(palette.surface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("千言", style = MaterialTheme.typography.titleSmall, color = palette.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(7.dp))
        Text("QIANYAN", style = QianyanCodeStyle, color = palette.text3, letterSpacing = 1.2.sp)
        Spacer(Modifier.width(12.dp))
        VLine()
        Spacer(Modifier.width(12.dp))

        Text(
            state.novel?.title ?: "未打开作品",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.text2,
            maxLines = 1,
        )
        state.novel?.let { novel ->
            if (novel.genre.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(novel.genre.joinToString(" · "), style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
            }
        }
        val s = state.status
        if (s.chapter != "—") {
            Spacer(Modifier.width(12.dp))
            Text("${s.chapter} · ${s.words} 字", style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
        }

        Spacer(Modifier.weight(1f))

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(palette.accent))
                Spacer(Modifier.width(7.dp))
                Text("Qianyan 工作中", style = MaterialTheme.typography.labelSmall, color = palette.accent, maxLines = 1)
            }
            Spacer(Modifier.width(16.dp))
        }

        ActionButton("搜索  ${shortcutLabel()}+K", ActionLevel.GHOST, true) { state.openPalette() }
        Spacer(Modifier.width(6.dp))
        ActionButton("侧栏", ActionLevel.TEXT, true) { state.toggle(PanelToggle.SIDEBAR) }
        ActionButton("检查器", ActionLevel.TEXT, true) { state.toggle(PanelToggle.INSPECTOR) }
        ActionButton("专注", ActionLevel.TEXT, true) { state.toggle(PanelToggle.FOCUS) }
    }
    HLine()
}

/* ---------------- 左：Project Navigation（216dp） ---------------- */

@Composable
private fun NavigationRail(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    Column(Modifier.width(QianyanDimens.Sidebar).fillMaxHeight().background(palette.surface)) {
        // 项目头（作品名 + 题材）
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
            Text(
                state.novel?.title ?: "未打开作品",
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                state.novel?.genre?.joinToString(" · ")?.ifBlank { "尚未设置题材" } ?: "在 Qianyan Agent 中打开或新建作品",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
                maxLines = 2,
            )
        }
        HLine()

        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            var lastGroup: String? = null
            NavItem.entries.forEach { item ->
                val group = item.group
                if (group != null && group != lastGroup) {
                    lastGroup = group
                    SectionLabel(group, Modifier.padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
                }
                if (item == NavItem.SETTINGS) {
                    Spacer(Modifier.height(6.dp))
                    HLine()
                    Spacer(Modifier.height(6.dp))
                }
                NavRow(
                    title = item.title,
                    hint = item.shortcut?.let { "${shortcutLabel()}+$it" },
                    selected = state.nav == item && item != NavItem.SETTINGS,
                ) {
                    // 「设置」是弹窗，不替换主工作区页面
                    if (item == NavItem.SETTINGS) state.settingsOpen = true else state.nav = item
                }
            }
        }

        Spacer(Modifier.weight(1f))
        HLine()
        Column(
            Modifier.fillMaxWidth().clickable { state.providerSettingsOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("AI PROVIDER", style = MaterialTheme.typography.labelSmall, color = palette.text3, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(3.dp))
            Text(state.graph.provider.value.name, style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 1)
        }
    }
}

@Composable
private fun NavRow(title: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(QianyanShapes.r))
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 当前项标记：一条极轻的竖线（不是胶囊、不是彩色图标）
        Box(
            Modifier.width(2.dp).height(11.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected) palette.accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) palette.text else palette.text2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) Text(hint, style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
    }
}

/* ---------------- 中央：当前对象行（唯一选择驱动 Inspector） ---------------- */

@Composable
private fun WorkspaceHeader(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val ws = state.workspace
    Row(
        Modifier.fillMaxWidth().background(palette.surface)
            .padding(start = QianyanDimens.PageX, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("当前对象", style = MaterialTheme.typography.labelSmall, color = palette.text3, letterSpacing = 1.1.sp)
        Text(
            ws.view?.title ?: ws.selection?.let { "已选择" } ?: "未选择",
            style = MaterialTheme.typography.bodySmall,
            color = palette.text2,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))

        // Inspector 收起时，这里提供固定三标签入口（展开时在检查器内切换，避免重复）
        if (!state.inspectorOpen) {
            InspectorTab.entries.forEach { tab ->
                ActionButton(tabLabel(tab), ActionLevel.TEXT, true) { ws.openInspector(tab) }
            }
            ActionButton("展开检查器  ${shortcutLabel()}+\\", ActionLevel.TEXT, true) {
                state.toggle(PanelToggle.INSPECTOR)
            }
        }
    }
    HLine()
}

private fun tabLabel(tab: InspectorTab): String = when (tab) {
    InspectorTab.CONTEXT -> "上下文"
    InspectorTab.ACTIVITY -> "活动"
    InspectorTab.CHANGES -> "修改"
}

/* ---------------- 底部：状态行（24dp） ---------------- */

@Composable
private fun StatusBar(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val s = state.status
    Row(
        Modifier.fillMaxWidth().height(QianyanDimens.StatusBar).background(palette.surface)
            .padding(horizontal = QianyanDimens.PageX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusItem("章节", s.chapter)
        StatusDot()
        StatusItem("字数", s.words)
        StatusDot()
        StatusItem("待确认", s.pending)
        StatusDot()
        StatusItem("任务", s.task)
        StatusDot()
        StatusItem("保存", s.save)
        Spacer(Modifier.weight(1f))
        Text(
            "${shortcutLabel()}+K 命令 · ${shortcutLabel()}+, 设置",
            style = QianyanCodeStyle,
            color = palette.text3,
            maxLines = 1,
        )
    }
    HLine()
}

@Composable
private fun StatusItem(key: String, value: String) {
    val palette = QianyanTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Text(value, style = QianyanCodeStyle, color = palette.text2, maxLines = 1)
    }
}

@Composable
private fun StatusDot() {
    Text("·", style = QianyanCodeStyle, color = QianyanTheme.palette.text3, modifier = Modifier.padding(horizontal = 9.dp))
}
