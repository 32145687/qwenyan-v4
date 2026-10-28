package com.qianyan.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.ListRow
import com.qianyan.app.desktop.ui.PanelToggle
import com.qianyan.app.desktop.ui.QianyanTabs
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.VLine
import com.qianyan.app.desktop.ui.Workspace
import com.qianyan.app.desktop.ui.chapterTitleOnly
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.hoverBackground
import com.qianyan.app.desktop.ui.importTxtFromPicker
import com.qianyan.app.desktop.ui.isMac
import com.qianyan.app.desktop.ui.openNovel
import com.qianyan.app.desktop.ui.pages.AgentPage
import com.qianyan.app.desktop.ui.pages.AuthorPage
import com.qianyan.app.desktop.ui.pages.BackupPage
import com.qianyan.app.desktop.ui.pages.ChaptersPage
import com.qianyan.app.desktop.ui.pages.CharactersPage
import com.qianyan.app.desktop.ui.pages.CreateNovelDialog
import com.qianyan.app.desktop.ui.pages.HistoryPage
import com.qianyan.app.desktop.ui.pages.MemoryPage
import com.qianyan.app.desktop.ui.pages.MaterialsOverview
import com.qianyan.app.desktop.ui.pages.ReaderPage
import com.qianyan.app.desktop.ui.pages.StoryPage
import com.qianyan.app.desktop.ui.pages.TasksPage
import com.qianyan.app.desktop.ui.pages.VocabularyPage
import com.qianyan.app.desktop.ui.pages.WorldPage
import com.qianyan.app.desktop.ui.pages.WritePage
import com.qianyan.app.desktop.ui.settings.ProviderSettingsDialog
import com.qianyan.app.desktop.ui.settings.SettingsPanel
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.workspace.CommandPalette
import com.qianyan.app.desktop.ui.workspace.ContextInspector
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter
import java.awt.Cursor
import kotlin.math.max
import kotlin.math.roundToInt
import com.qianyan.model.workspace.WorkspaceSelection

/**
 * Qianyan · 桌面工作台入口。
 *
 * 五个一级工作区（创作 / 正文 / 故事 / 资料 / 历史）+ 左中右三栏 + 底部状态栏。
 * 顶栏只保留当前作品、项目切换、命令面板与设置；不显示品牌文字标识。
 */
fun main() = application {
    val graph = DesktopGraph.create()
    val state = DesktopAppState(graph)
    // 原生标题栏不显示品牌，只显示当前作品；无作品时显示中性空态。
    // 保留系统窗口按钮与缩放，避免 undecorated 窗口失去原生 resize / 最小化能力。
    val emptyWindowIcon = remember { BitmapPainter(ImageBitmap(1, 1)) }
    val windowTitle = state.novel?.title?.takeIf { it.isNotBlank() } ?: "未打开作品"

    Window(
        onCloseRequest = ::exitApplication,
        title = windowTitle,
        icon = emptyWindowIcon,
        // 无边框：去掉 Windows 纯白标题栏，由暖白顶栏承担拖动与窗口按钮
        undecorated = true,
        state = rememberWindowState(
            width = 1080.dp,
            height = 600.dp,
            position = WindowPosition(Alignment.Center),
        ),
        onPreviewKeyEvent = { event -> handleShortcut(state, event) },
    ) {
        // 最小逻辑尺寸 760×480（AWT 与 Compose 的 dp 在本窗口同一坐标空间，不要再乘 density）。
        // 760 dp < 780 dp，因此检查器仍会按既有规则在窄窗口自动折叠（保留两栏）。
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(760, 480)
            // 系统可能恢复上次位置 / 以最小化状态拉起；连续几次纠正（部分启动路径会在首帧后再次最小化）
            repeat(6) {
                kotlinx.coroutines.delay(200)
                if (window.extendedState and java.awt.Frame.ICONIFIED != 0) {
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                }
            }
            window.isVisible = true
            window.toFront()
            window.requestFocus()
            kotlinx.coroutines.delay(80)
            val gc = window.graphicsConfiguration
            val scale = gc.defaultTransform.scaleX.takeIf { it > 0.0 } ?: 1.0
            val bounds = gc.bounds
            val physicalWidth = window.width * scale
            val physicalHeight = window.height * scale
            val x = ((bounds.x + (bounds.width - physicalWidth) / 2.0) / scale).roundToInt()
            val y = ((bounds.y + (bounds.height - physicalHeight) / 2.0) / scale).roundToInt()
            window.setLocation(x, y)
        }
        QianyanTheme(state.themeMode) {
            Box(
                Modifier.fillMaxSize().background(QianyanTheme.palette.bg)
                    .border(1.dp, QianyanTheme.palette.line),
            ) {
                AppShell(state, window) { exitApplication() }
                WindowResizeHandles(window)
            }
        }
    }
}

/**
 * 键盘路由。
 *
 * 列表型页面通过 [DesktopAppState.listNavEnabled] 主动开启 ↑/↓/Enter 导航；
 * 编辑器 / 命令面板 / 弹窗打开时该开关为 false，避免窗口级预览抢走正文编辑器的输入。
 */
private fun handleShortcut(state: DesktopAppState, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val mod = if (isMac()) event.isMetaPressed else event.isCtrlPressed

    if (event.key == Key.Escape) {
        return when {
            state.settingsOpen -> { state.settingsOpen = false; true }
            state.providerSettingsOpen -> { state.providerSettingsOpen = false; true }
            state.createNovelOpen -> { state.createNovelOpen = false; true }
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
        Key.One, Key.Two, Key.Three, Key.Four, Key.Five -> {
            val keys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five)
            Workspace.entries.firstOrNull { it.shortcut == keys.indexOf(event.key) + 1 }?.let { state.openWorkspace(it) }
            true
        }
        else -> false
    }
}

@Composable
fun AppShell(state: DesktopAppState, window: ComposeWindow, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    val providerMsg by state.graph.providerMessage.collectAsState()
    var pendingText by remember { mutableStateOf("0") }

    // Workspace：当前对象变化 / 数据变化时重读 Inspector（只读投影）
    LaunchedEffect(state.novel?.projectId, state.refreshKey, state.workspace.selection) {
        state.workspace.load()
    }
    LaunchedEffect(state.projectId, state.refreshKey) {
        val pid = state.projectId
        if (pid == null) {
            pendingText = "0"
            return@LaunchedEffect
        }
        pendingText = try {
            state.load { it.changes.list(pid).size }.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "读取失败"
        }
    }
    LaunchedEffect(pendingText) { state.status = state.status.copy(pending = pendingText) }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg)) {
        // 窄窗口自动收起面板：状态本身不变，窗口放大后面板自然恢复。
        val showSidebar = state.sidebarOpen && maxWidth >= 720.dp
        val showInspector = state.inspectorOpen && maxWidth >= 780.dp
        val sidebarWidth = if (maxWidth < 1000.dp) 208.dp else QianyanDimens.Sidebar
        val inspectorWidth = if (maxWidth < 1000.dp) 272.dp else QianyanDimens.Inspector

        Column(Modifier.fillMaxSize()) {
            TopBar(state, window, onClose)

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
                if (showSidebar) {
                    LeftPanel(state, sidebarWidth)
                    VLine()
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        WorkspaceContent(state)
                    }
                }

                if (showInspector) {
                    VLine()
                    ContextInspector(state, inspectorWidth)
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

/* ---------------- 顶部栏（52dp） ---------------- */

@Composable
private fun TopBar(state: DesktopAppState, window: ComposeWindow, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    BoxWithConstraints(Modifier.fillMaxWidth().height(QianyanDimens.TopBar).background(palette.bg)) {
        val compact = maxWidth < 900.dp
        val tooNarrowForSidebar = maxWidth < 720.dp
        val tooNarrowForInspector = maxWidth < 780.dp
        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelToggleIcon(left = true, active = !state.sidebarOpen) {
                if (!state.sidebarOpen && tooNarrowForSidebar) {
                    state.toast("窗口宽度不足，请拉宽窗口后显示侧栏")
                } else {
                    state.toggle(PanelToggle.SIDEBAR)
                }
            }
            Spacer(Modifier.width(4.dp))

            // 作品名保持可点击（切换作品），不参与整窗拖动
            ProjectSwitcher(state, compact)

            // 面包屑其余区域即拖动区（Windows 原生移动；双击最大化）
            Row(
                Modifier.weight(1f).fillMaxHeight().windowDrag(window),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "/",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                    modifier = Modifier.padding(horizontal = 7.dp),
                )
                Text(state.nav.title, style = MaterialTheme.typography.bodySmall, color = palette.text2, maxLines = 1)
                Spacer(Modifier.weight(1f))
            }

            if (state.busy) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(palette.accent))
                Spacer(Modifier.width(10.dp))
            }

            SearchCommandField(state, compact)
            Spacer(Modifier.width(6.dp))
            PanelToggleIcon(left = false, active = !state.inspectorOpen) {
                if (!state.inspectorOpen && tooNarrowForInspector) {
                    state.toast("窗口宽度不足，请拉宽窗口后显示检查器")
                } else {
                    state.toggle(PanelToggle.INSPECTOR)
                }
            }
            Spacer(Modifier.width(4.dp))
            MoreMenu(state)
            Spacer(Modifier.width(4.dp))
            WindowControl(WinGlyph.MINIMIZE, palette.text2) {
                window.extendedState = window.extendedState or java.awt.Frame.ICONIFIED
            }
            WindowControl(WinGlyph.MAXIMIZE, palette.text2) { window.toggleMaximize() }
            WindowControl(WinGlyph.CLOSE, palette.text2) { onClose() }
        }
    }
    HLine()
}

private enum class WinGlyph { MINIMIZE, MAXIMIZE, CLOSE }

private fun ComposeWindow.toggleMaximize() {
    val maximizing = (extendedState and java.awt.Frame.MAXIMIZED_BOTH) == 0
    extendedState = if (maximizing) java.awt.Frame.MAXIMIZED_BOTH else java.awt.Frame.NORMAL
}

/* ---------------- 无边框窗口：四边 / 四角缩放 ---------------- */

@Composable
private fun BoxScope.WindowResizeHandles(window: ComposeWindow) {
    val density = LocalDensity.current.density
    val edge = 6.dp
    val corner = 12.dp

    // 边
    ResizeHandle(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge), Cursor.N_RESIZE_CURSOR, window, density, left = false, top = true, right = false, bottom = false)
    ResizeHandle(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge), Cursor.S_RESIZE_CURSOR, window, density, left = false, top = false, right = false, bottom = true)
    ResizeHandle(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge), Cursor.W_RESIZE_CURSOR, window, density, left = true, top = false, right = false, bottom = false)
    ResizeHandle(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge), Cursor.E_RESIZE_CURSOR, window, density, left = false, top = false, right = true, bottom = false)

    // 角（后声明，覆盖在边之上）
    ResizeHandle(Modifier.align(Alignment.TopStart).size(corner), Cursor.NW_RESIZE_CURSOR, window, density, left = true, top = true, right = false, bottom = false)
    ResizeHandle(Modifier.align(Alignment.TopEnd).size(corner), Cursor.NE_RESIZE_CURSOR, window, density, left = false, top = true, right = true, bottom = false)
    ResizeHandle(Modifier.align(Alignment.BottomStart).size(corner), Cursor.SW_RESIZE_CURSOR, window, density, left = true, top = false, right = false, bottom = true)
    ResizeHandle(Modifier.align(Alignment.BottomEnd).size(corner), Cursor.SE_RESIZE_CURSOR, window, density, left = false, top = false, right = true, bottom = true)
}

@Composable
private fun ResizeHandle(
    modifier: Modifier,
    cursor: Int,
    window: ComposeWindow,
    density: Float,
    left: Boolean,
    top: Boolean,
    right: Boolean,
    bottom: Boolean,
) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            .pointerInput(left, top, right, bottom) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dx = (drag.x / density).roundToInt()
                    val dy = (drag.y / density).roundToInt()
                    val loc = window.location
                    val size = window.size
                    var x = loc.x
                    var y = loc.y
                    var w = size.width
                    var h = size.height
                    if (left) { x += dx; w -= dx }
                    if (right) w += dx
                    if (top) { y += dy; h -= dy }
                    if (bottom) h += dy
                    w = max(760, w)
                    h = max(480, h)
                    if (left) x = loc.x + (size.width - w)
                    if (top) y = loc.y + (size.height - h)
                    window.setBounds(x, y, w, h)
                }
            },
    )
}

/** 无边框窗口拖动：按“屏幕鼠标绝对位置”计算窗口位置，避免相对增量随窗口移动回弹造成的抖动。 */
@Composable
private fun Modifier.windowDrag(window: ComposeWindow): Modifier {
    return this
        .pointerInput(window) {
            var startMouse: java.awt.Point? = null
            var startWindow: java.awt.Point? = null
            detectDragGestures(
                onDragStart = {
                    startMouse = java.awt.MouseInfo.getPointerInfo()?.location
                    startWindow = window.location
                },
                onDrag = { change, _ ->
                    change.consume()
                    val mouse = java.awt.MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                    val origin = startMouse ?: return@detectDragGestures
                    val win = startWindow ?: return@detectDragGestures
                    window.setLocation(
                        win.x + (mouse.x - origin.x),
                        win.y + (mouse.y - origin.y),
                    )
                },
            )
        }
        .pointerInput(window) {
            detectTapGestures(onDoubleTap = { window.toggleMaximize() })
        }
}

/** 无边框窗口的最小化 / 最大化 / 关闭按钮（与顶栏同底色）。 */
@Composable
private fun WindowControl(glyph: WinGlyph, tint: Color, danger: Boolean = false, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    Box(
        Modifier.width(38.dp).fillMaxHeight()
            .hoverBackground(
                androidx.compose.ui.graphics.RectangleShape,
                color = if (danger) palette.del.copy(alpha = 0.14f) else palette.hover,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val color = if (danger) palette.del else tint
        Canvas(Modifier.size(12.dp)) {
            val d = density
            val stroke = 1.2f * d
            when (glyph) {
                WinGlyph.MINIMIZE -> drawLine(color, Offset(1.5f * d, 6f * d), Offset(10.5f * d, 6f * d), strokeWidth = stroke)
                WinGlyph.MAXIMIZE -> drawRect(
                    color,
                    topLeft = Offset(1.5f * d, 1.5f * d),
                    size = Size(9f * d, 9f * d),
                    style = Stroke(stroke),
                )
                WinGlyph.CLOSE -> {
                    drawLine(color, Offset(2f * d, 2f * d), Offset(10f * d, 10f * d), strokeWidth = stroke)
                    drawLine(color, Offset(10f * d, 2f * d), Offset(2f * d, 10f * d), strokeWidth = stroke)
                }
            }
        }
    }
}

/** 左右栏收起按钮（Canvas 线框图标，不依赖 Material Icons 扩展包）。 */
@Composable
private fun PanelToggleIcon(left: Boolean, active: Boolean, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    val shape = RoundedCornerShape(QianyanShapes.r2)
    Box(
        Modifier.size(28.dp).clip(shape)
            .then(if (active) Modifier.background(palette.accentSoft) else Modifier)
            .hoverBackground(shape, color = palette.hover)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (active) palette.accent else palette.text2
        Canvas(Modifier.size(16.dp)) {
            val d = density
            val stroke = 1.3f * d
            drawRoundRect(
                color = tint,
                topLeft = Offset(2f * d, 2.5f * d),
                size = Size(12f * d, 11f * d),
                cornerRadius = CornerRadius(2f * d),
                style = Stroke(stroke),
            )
            val x = if (left) 6f else 10f
            drawLine(tint, Offset(x * d, 2.5f * d), Offset(x * d, 13.5f * d), strokeWidth = stroke)
        }
    }
}

/** 顶栏搜索入口：点击打开命令面板（不是全文检索）。 */
@Composable
private fun SearchCommandField(state: DesktopAppState, compact: Boolean) {
    val palette = QianyanTheme.palette
    val shape = RoundedCornerShape(QianyanShapes.r2)
    Row(
        Modifier.width(if (compact) 132.dp else 200.dp).height(28.dp)
            .clip(shape)
            .background(palette.soft)
            .hoverBackground(shape, color = palette.hover)
            .border(1.dp, palette.line, shape)
            .clickable { state.openPalette() }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(13.dp)) {
            val d = density
            val stroke = 1.3f * d
            drawCircle(palette.text3, radius = 4.1f * d, center = Offset(5.2f * d, 5.2f * d), style = Stroke(stroke))
            drawLine(palette.text3, Offset(8.4f * d, 8.4f * d), Offset(11.5f * d, 11.5f * d), strokeWidth = stroke)
        }
        Spacer(Modifier.width(7.dp))
        Text(
            if (compact) "" else "搜索命令",
            style = MaterialTheme.typography.bodySmall,
            color = palette.text3,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text("${shortcutLabel()}+K", style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
    }
}

/** 顶栏「更多」：设置（与命令面板 / ⌘, 同一入口）。 */
@Composable
private fun MoreMenu(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(QianyanShapes.r2))
                .hoverBackground(RoundedCornerShape(QianyanShapes.r2), color = palette.hover)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("···", style = MaterialTheme.typography.titleMedium, color = palette.text2, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("设置", style = MaterialTheme.typography.bodyLarge, color = palette.text) },
                onClick = { open = false; state.settingsOpen = true },
            )
            DropdownMenuItem(
                text = { Text("AI Provider 设置…", style = MaterialTheme.typography.bodyLarge, color = palette.text) },
                onClick = {
                    open = false
                    if (state.busy) state.toast("任务进行中，请等待完成后再修改模型配置")
                    else state.providerSettingsOpen = true
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        if (state.focusMode) "退出专注模式" else "专注模式",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.text,
                    )
                },
                onClick = { open = false; state.toggle(PanelToggle.FOCUS) },
            )
        }
    }
}

/* ---------------- 项目切换 ---------------- */

@Composable
private fun ProjectSwitcher(state: DesktopAppState, compact: Boolean = false) {
    val palette = QianyanTheme.palette
    var open by remember { mutableStateOf(false) }
    var novels by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(open, reloadKey) {
        if (!open) return@LaunchedEffect
        loading = true
        loadError = null
        try {
            novels = state.load { it.novels.listOriginals() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            novels = emptyList()
            loadError = e.message?.takeIf { message -> message.isNotBlank() } ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    val currentError = loadError

    Box {
        Row(
            Modifier.clip(RoundedCornerShape(QianyanShapes.r2))
                .clickable {
                    // 任务运行期间锁定作品切换（与关键字 / 菜单同一判断源：state.busy）
                    if (state.busy) state.toast("任务进行中，请等待完成后再切换作品") else open = true
                }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.novel?.title ?: "未打开作品",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (state.busy) palette.text3 else palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 160.dp else 360.dp),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(320.dp)) {
            Text(
                if (state.busy) "任务运行中 · 切换已锁定" else "切换作品",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            HLine()
            when {
                loading -> Text(
                    "正在读取…",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                currentError != null -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("作品列表读取失败", style = MaterialTheme.typography.bodySmall, color = palette.del)
                    Spacer(Modifier.height(3.dp))
                    Text(currentError, style = MaterialTheme.typography.labelSmall, color = palette.text3, maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    ActionButton("重试", ActionLevel.TEXT, true) { reloadKey++ }
                }
                novels.isEmpty() -> Text(
                    "还没有作品",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                else -> novels.forEach { novel ->
                    val current = state.novel?.novelId == novel.novelId
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    novel.title + if (current) " · 当前" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.text,
                                    maxLines = 1,
                                )
                                Text(
                                    listOfNotNull(
                                        novel.genre.joinToString(" · ").takeIf { it.isNotBlank() },
                                        statusLabel(novel.status.name),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.text3,
                                )
                            }
                        },
                        onClick = {
                            open = false
                            openNovel(state, novel.novelId)
                        },
                        enabled = !state.busy,
                    )
                }
            }
            HLine()
            DropdownMenuItem(
                text = { Text("新建作品…", style = MaterialTheme.typography.bodyLarge, color = if (state.busy) palette.text3 else palette.text) },
                onClick = { open = false; state.createNovelOpen = true },
                enabled = !state.busy,
            )
            DropdownMenuItem(
                text = { Text("导入 TXT…", style = MaterialTheme.typography.bodyLarge, color = if (state.busy) palette.text3 else palette.text) },
                onClick = {
                    open = false
                    state.importTxtFromPicker()
                },
                enabled = !state.busy,
            )
        }
    }
}

/* ---------------- 左栏：工作区导航 + 当前作品 + 章节目录 ---------------- */

@Composable
private fun LeftPanel(state: DesktopAppState, width: androidx.compose.ui.unit.Dp) {
    val palette = QianyanTheme.palette
    Column(Modifier.width(width).fillMaxHeight().background(palette.surface)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
        ) {
            Workspace.entries.forEach { workspace ->
                NavRow(
                    title = workspace.title,
                    glyph = when (workspace) {
                        Workspace.CREATE -> NavGlyph.PEN
                        Workspace.WRITE -> NavGlyph.DOC
                        Workspace.STORY -> NavGlyph.BOOK
                        Workspace.MATERIALS -> NavGlyph.FOLDER
                        Workspace.HISTORY -> NavGlyph.CLOCK
                    },
                    selected = state.nav == workspace,
                ) { state.openWorkspace(workspace) }
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // 当前作品信息已在顶栏呈现（避免左栏重复堆叠文字）；左栏只保留「作品内容」入口
            ChapterDirectoryBlock(state)
            MaterialsBlock(state)
            Spacer(Modifier.height(12.dp))
        }

        ProviderFooter(state)
    }
}

private enum class NavGlyph { PEN, DOC, BOOK, FOLDER, CLOCK }

@Composable
private fun NavRow(title: String, glyph: NavGlyph, selected: Boolean, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(QianyanShapes.r))
            .background(if (selected) palette.selection else Color.Transparent)
            .hoverBackground(RoundedCornerShape(QianyanShapes.r), enabled = !selected)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavGlyphIcon(glyph, if (selected) palette.accent else palette.text2)
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) palette.accent else palette.text,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NavGlyphIcon(glyph: NavGlyph, tint: Color) {
    Canvas(Modifier.size(16.dp)) {
        val d = density
        val stroke = 1.3f * d
        fun p(x: Float, y: Float) = Offset(x * d, y * d)
        when (glyph) {
            NavGlyph.PEN -> {
                drawLine(tint, p(3.5f, 12.5f), p(11f, 5f), strokeWidth = stroke)
                drawLine(tint, p(11f, 5f), p(13f, 3f), strokeWidth = stroke)
                drawLine(tint, p(3.5f, 12.5f), p(3f, 13.5f), strokeWidth = stroke)
            }
            NavGlyph.DOC -> {
                drawRoundRect(
                    tint,
                    topLeft = p(4f, 2.5f),
                    size = Size(8f * d, 11f * d),
                    cornerRadius = CornerRadius(1.5f * d),
                    style = Stroke(stroke),
                )
                drawLine(tint, p(9.5f, 2.5f), p(12f, 5f), strokeWidth = stroke)
                drawLine(tint, p(6f, 8f), p(10f, 8f), strokeWidth = stroke)
                drawLine(tint, p(6f, 11f), p(10f, 11f), strokeWidth = stroke)
            }
            NavGlyph.BOOK -> {
                drawLine(tint, p(4f, 3f), p(4f, 13f), strokeWidth = stroke)
                drawLine(tint, p(4f, 3f), p(8f, 3f), strokeWidth = stroke)
                drawLine(tint, p(4f, 13f), p(8f, 13f), strokeWidth = stroke)
                drawLine(tint, p(8f, 3f), p(8f, 13f), strokeWidth = stroke)
                drawLine(tint, p(8f, 5f), p(12.5f, 4f), strokeWidth = stroke)
                drawLine(tint, p(12.5f, 4f), p(12.5f, 12f), strokeWidth = stroke)
                drawLine(tint, p(8f, 13f), p(12.5f, 12f), strokeWidth = stroke)
            }
            NavGlyph.FOLDER -> {
                drawRoundRect(
                    tint,
                    topLeft = p(2.5f, 4.5f),
                    size = Size(11f * d, 9f * d),
                    cornerRadius = CornerRadius(1.8f * d),
                    style = Stroke(stroke),
                )
                drawLine(tint, p(2.5f, 7f), p(13.5f, 7f), strokeWidth = stroke)
            }
            NavGlyph.CLOCK -> {
                drawCircle(tint, radius = 5.3f * d, center = p(8f, 8f), style = Stroke(stroke))
                drawLine(tint, p(8f, 8f), p(8f, 4.8f), strokeWidth = stroke)
                drawLine(tint, p(8f, 8f), p(10.7f, 9.3f), strokeWidth = stroke)
            }
        }
    }
}

@Composable
private fun ChapterDirectoryBlock(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(state.novel?.novelId, state.refreshKey, reloadKey) {
        val novelId = state.novel?.novelId
        if (novelId == null) {
            chapters = emptyList()
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            chapters = state.load { it.chapters.listByNovel(novelId, null) }
                .sortedBy { chapter -> chapter.order }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            chapters = emptyList()
            error = e.message?.takeIf { message -> message.isNotBlank() } ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    val currentError = error

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SectionLabel(
            "章节目录",
            Modifier.padding(horizontal = 20.dp),
            trailing = {
                ActionButton("全部", ActionLevel.TEXT, true) { state.openWorkspace(Workspace.STORY, 1) }
            },
        )
        when {
            state.novel == null -> Text(
                "打开作品后显示章节",
                style = MaterialTheme.typography.bodySmall,
                color = palette.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            loading -> Text(
                "正在读取…",
                style = MaterialTheme.typography.bodySmall,
                color = palette.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            currentError != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text("章节目录读取失败", style = MaterialTheme.typography.bodySmall, color = palette.del)
                Spacer(Modifier.height(3.dp))
                Text(currentError, style = MaterialTheme.typography.labelSmall, color = palette.text3, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                ActionButton("重试", ActionLevel.TEXT, true) { reloadKey++ }
            }
            chapters.isEmpty() -> Text(
                "还没有章节",
                style = MaterialTheme.typography.bodySmall,
                color = palette.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            else -> {
                chapters.take(8).forEach { chapter ->
                    ListRow(
                        title = chapterTitleOnly(chapter.title).ifBlank { "未命名" },
                        index = chapter.order.toString().padStart(2, '0'),
                        selected = state.workspace.selection?.chapterId == chapter.chapterId,
                        horizontalPadding = 8.dp,
                        onClick = {
                            state.novel?.let { novel ->
                                state.workspace.select(
                                    WorkspaceSelection.chapter(novel.projectId, novel.novelId, chapter.chapterId),
                                )
                                state.openWorkspace(Workspace.WRITE, 0)
                            }
                        },
                    )
                }
                if (chapters.size > 8) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
                        ActionButton(
                            "还有 ${chapters.size - 8} 章 · 查看全部",
                            ActionLevel.TEXT,
                            true,
                        ) { state.openWorkspace(Workspace.STORY, 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaterialsBlock(state: DesktopAppState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SectionLabel("人物与世界观", Modifier.padding(horizontal = 20.dp))
        ListRow(
            title = "人物",
            horizontalPadding = 10.dp,
            onClick = { state.openWorkspace(Workspace.MATERIALS, 0) },
        )
        ListRow(
            title = "世界观",
            horizontalPadding = 10.dp,
            onClick = { state.openWorkspace(Workspace.MATERIALS, 1) },
        )
    }
}

@Composable
private fun ProviderFooter(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val provider by state.graph.provider.collectAsState()
    val model by state.graph.effectiveModel.collectAsState()
    val configured = state.graph.isConfigured(provider)
    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        HLine()
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                "AI PROVIDER",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(QianyanShapes.r2))
                    .hoverBackground(RoundedCornerShape(QianyanShapes.r2), color = palette.hover)
                    .clickable {
                        if (state.busy) state.toast("任务进行中，请等待完成后再修改模型配置")
                        else state.providerSettingsOpen = true
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                        // MOCK 是离线示意：不显示为"在线/已连接"的绿点
                        .background(if (configured && provider.name != "MOCK") palette.add else palette.text3.copy(alpha = 0.5f)),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state.busy) palette.text3 else palette.text,
                        maxLines = 1,
                    )
                    Text(
                        buildString {
                            append(model)
                            append(if (provider.name == "MOCK") " · 离线示意" else if (configured) " · 可用" else " · 未配置")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text2,
                        maxLines = 1,
                    )
                }
                Text("⌄", style = QianyanCodeStyle, color = palette.text3)
            }
        }
    }
}

/* ---------------- 中栏：工作区内容 ---------------- */

@Composable
private fun WorkspaceContent(state: DesktopAppState) {
    when (state.nav) {
        Workspace.CREATE -> AgentPage(state)

        Workspace.WRITE -> WorkspaceTabs(
            tabs = listOf("写作", "阅读"),
            selected = state.writeTab,
            onSelect = { state.writeTab = it },
        ) { tab ->
            if (tab == 0) WritePage(state) else ReaderPage(state)
        }

        Workspace.STORY -> WorkspaceTabs(
            tabs = listOf("故事", "章节"),
            selected = state.storyTab,
            onSelect = { state.storyTab = it },
        ) { tab ->
            if (tab == 0) StoryPage(state) else ChaptersPage(state)
        }

        Workspace.MATERIALS -> WorkspaceTabs(
            tabs = listOf("总览", "人物", "世界观", "词库", "记忆", "作者智能"),
            selected = state.materialsTab,
            onSelect = { state.materialsTab = it },
        ) { tab ->
            when (tab) {
                0 -> MaterialsOverview(state)
                1 -> CharactersPage(state)
                2 -> WorldPage(state)
                3 -> VocabularyPage(state)
                4 -> MemoryPage(state)
                else -> AuthorPage(state)
            }
        }

        Workspace.HISTORY -> WorkspaceTabs(
            tabs = listOf("变更历史", "任务", "备份"),
            selected = state.historyTab,
            onSelect = { state.historyTab = it },
        ) { tab ->
            when (tab) {
                0 -> HistoryPage(state)
                1 -> TasksPage(state)
                else -> BackupPage(state)
            }
        }
    }
}

@Composable
private fun WorkspaceTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    content: @Composable (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        QianyanTabs(tabs, selected, onSelect = onSelect)
        Box(Modifier.weight(1f).fillMaxWidth()) { content(selected) }
    }
}

/* ---------------- 底部状态栏（28dp） ---------------- */

@Composable
private fun StatusBar(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val s = state.status
    val provider by state.graph.provider.collectAsState()
    val model by state.graph.effectiveModel.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(QianyanDimens.StatusBar).background(palette.surface)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 未打开作品时不得暗示"正在保存 / 有待确认变更"——显示明确的空状态文案
        if (state.novel == null) {
            StatusText("作品 未打开")
            StatusDot()
            StatusText("任务 暂无运行任务")
            StatusDot()
            StatusText("变更 无待确认变更")
        } else {
            StatusText(if (s.chapter != "—") "${s.chapter} · ${s.words} 字" else "未选择章节")
            StatusDot()
            // 真实保存状态（未保存 / 已保存 / 无草稿），不写"已同步"这类不存在的承诺
            StatusText("保存 ${s.save}")
            StatusDot()
            StatusText("待确认 ${s.pending}")
            if (state.busy) {
                StatusDot()
                StatusText("任务运行中", palette.accent)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("${provider.name} · $model", style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
        Spacer(Modifier.width(14.dp))
        Text(
            "${shortcutLabel()}+K 命令 · ${shortcutLabel()}+, 设置",
            style = QianyanCodeStyle,
            color = palette.text3,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusText(text: String, tint: Color = QianyanTheme.palette.text2) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
}

@Composable
private fun StatusDot() {
    Text(
        "·",
        style = MaterialTheme.typography.labelSmall,
        color = QianyanTheme.palette.text3,
        modifier = Modifier.padding(horizontal = 9.dp),
    )
}
