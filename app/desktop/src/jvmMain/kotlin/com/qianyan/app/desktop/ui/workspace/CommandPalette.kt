package com.qianyan.app.desktop.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.importTxtFromPicker
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.hoverBackground
import com.qianyan.app.desktop.ui.Workspace
import com.qianyan.app.desktop.ui.PanelToggle
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme

/** 一条命令（快捷入口，不是一级导航）。 */
private data class PaletteCommand(
    val group: String,
    val label: String,
    val hint: String,
    val run: () -> Unit,
)

/**
 * Command Palette（Ctrl/⌘ + K）。
 *
 * 用途：低频操作与导航的**快捷入口**。只列**真实可用**的能力——
 * 没有真实能力的一律不出现（不伪造"暂不可用"的执行结果）。
 */
@Composable
fun CommandPalette(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var query by remember { mutableStateOf(state.paletteSeed) }
    var index by remember { mutableStateOf(0) }

    val commands = buildCommands(state)
    val filtered = commands.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.hint.contains(query, ignoreCase = true)
    }

    LaunchedEffect(state.paletteSeed) {
        query = state.paletteSeed
        index = 0
    }
    LaunchedEffect(query, filtered.size) {
        if (index >= filtered.size) index = 0
    }

    fun execute() {
        val cmd = filtered.getOrNull(index) ?: return
        state.paletteOpen = false
        cmd.run()
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = if (QianyanTheme.isDark) 0.5f else 0.3f))
            .clickable { state.paletteOpen = false },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 92.dp)
                .width(552.dp)
                .clip(RoundedCornerShape(QianyanShapes.window))
                .background(palette.raised)
                .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.window))
                .consumeClicks(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("›", style = MaterialTheme.typography.bodyLarge, color = palette.text3)
                Spacer(Modifier.width(11.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.text),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> { if (filtered.isNotEmpty()) index = (index + 1) % filtered.size; true }
                            Key.DirectionUp -> { if (filtered.isNotEmpty()) index = (index - 1 + filtered.size) % filtered.size; true }
                            Key.Enter -> { execute(); true }
                            Key.Escape -> { state.paletteOpen = false; true }
                            else -> false
                        }
                    },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("输入命令…", style = MaterialTheme.typography.bodyLarge, color = palette.text3)
                        }
                        inner()
                    },
                )
            }
            HLine()

            Column(Modifier.fillMaxWidth().height(332.dp).verticalScroll(rememberScrollState()).padding(vertical = 7.dp)) {
                if (filtered.isEmpty()) {
                    Text(
                        "没有匹配的命令",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text3,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                var lastGroup: String? = null
                filtered.forEachIndexed { i, cmd ->
                    if (cmd.group != lastGroup) {
                        lastGroup = cmd.group
                        SectionLabel(cmd.group, Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp))
                    }
                    val on = i == index
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(QianyanShapes.r))
                            .background(if (on) palette.selection else Color.Transparent)
                            .hoverBackground(RoundedCornerShape(QianyanShapes.r), enabled = !on)
                            .clickable { index = i; execute() }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            cmd.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            color = if (on) palette.text else palette.text2,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(cmd.hint, style = QianyanCodeStyle, color = palette.text3, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            HLine()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                Text("↑ ↓ 选择", style = MaterialTheme.typography.labelSmall, color = palette.text3)
                Text("⏎ 执行", style = MaterialTheme.typography.labelSmall, color = palette.text3)
                Text("Esc 关闭", style = MaterialTheme.typography.labelSmall, color = palette.text3)
            }
        }
    }
}

private fun buildCommands(state: DesktopAppState): List<PaletteCommand> {
    val out = mutableListOf<PaletteCommand>()
    val mod = shortcutLabel()

    Workspace.entries.forEach { item ->
        out += PaletteCommand(
            group = "导航",
            label = "打开 · ${item.title}",
            hint = "$mod+${item.shortcut}",
        ) { state.openWorkspace(item) }
    }

    out += PaletteCommand("操作", "设置", "$mod+,") { state.settingsOpen = true }
    out += PaletteCommand("操作", "新建作品", "") {
        if (state.busy) state.toast("任务进行中，请等待完成后再新建作品")
        else state.createNovelOpen = true
    }
    out += PaletteCommand("操作", "导入 TXT", "") {
        state.importTxtFromPicker()
    }
    out += PaletteCommand("操作", "AI Provider 设置", "") {
        if (state.busy) state.toast("任务进行中，请等待完成后再修改模型配置")
        else state.providerSettingsOpen = true
    }
    out += PaletteCommand("视图", "折叠 / 展开左侧导航", "$mod+B") { state.toggle(PanelToggle.SIDEBAR) }
    out += PaletteCommand("视图", "折叠 / 展开检查器", "$mod+\\") { state.toggle(PanelToggle.INSPECTOR) }
    out += PaletteCommand("视图", "专注模式", "$mod+Shift+E") { state.toggle(PanelToggle.FOCUS) }
    return out
}
