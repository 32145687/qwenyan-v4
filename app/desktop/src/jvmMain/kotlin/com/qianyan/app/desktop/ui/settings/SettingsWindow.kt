package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.adapter.DesktopFilePicker
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.ProseSize
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDarkPalette
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanFonts
import com.qianyan.app.desktop.ui.theme.QianyanLightPalette
import com.qianyan.app.desktop.ui.theme.QianyanPalette
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.QianyanThemeMode

/* ---------------- 设置导航（窗口内部；不是 Novel IDE 一级导航） ---------------- */

private data class SettingsItem(val key: String, val title: String)

private data class SettingsGroup(val title: String, val items: List<SettingsItem>)

private val SETTINGS_NAV = listOf(
    SettingsGroup("外观", listOf(SettingsItem("theme", "主题"), SettingsItem("layout", "界面"))),
    SettingsGroup(
        "AI",
        listOf(
            SettingsItem("provider", "AI Provider"),
            SettingsItem("api", "API 设置"),
            SettingsItem("context", "上下文与行为"),
        ),
    ),
    SettingsGroup("写作", listOf(SettingsItem("type", "排版"), SettingsItem("author", "作者写作配置"))),
    SettingsGroup(
        "项目",
        listOf(
            SettingsItem("info", "项目信息"),
            SettingsItem("txt", "TXT 导入"),
            SettingsItem("storage", "数据存储"),
        ),
    ),
    SettingsGroup("高级", listOf(SettingsItem("shortcut", "键盘快捷键"), SettingsItem("diag", "诊断"))),
)

/**
 * 设置（**弹窗**，不是主工作区页面）。
 *
 * 点「设置」→ 模态浮层（左右两栏：设置分组导航 + 设置内容），关闭后主 IDE 的页面与
 * WorkspaceSelection **保持不变**（不重置、不重建）。
 *
 * 只呈现**真实可用**的设置：主题 / 面板默认显示 / 正文字号 / Provider（既有配置能力）/
 * TXT 导入 / 存储路径 / 快捷键。后端尚未交付或未接线的能力**如实标注**，不提供假开关。
 */
@Composable
fun SettingsPanel(state: DesktopAppState, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    var groupKey by remember { mutableStateOf(SETTINGS_NAV.first().title) }
    var itemKey by remember { mutableStateOf(SETTINGS_NAV.first().items.first().key) }
    val group = SETTINGS_NAV.firstOrNull { it.title == groupKey } ?: SETTINGS_NAV.first()
    val item = group.items.firstOrNull { it.key == itemKey } ?: group.items.first()

    Column(
        Modifier
            .fillMaxWidth(0.94f)
            .fillMaxHeight(0.92f)
            .widthIn(max = 1180.dp)
            .heightIn(max = 820.dp)
            .clip(RoundedCornerShape(QianyanShapes.window))
            .background(palette.raised)
            .border(1.dp, palette.lineStrong, RoundedCornerShape(QianyanShapes.window))
            .consumeClicks(),
    ) {
        // 标题行
        Row(
            Modifier.fillMaxWidth().height(QianyanDimens.TopBar).background(palette.surface)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = MaterialTheme.typography.titleMedium, color = palette.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text("千言 Qianyan", style = QianyanCodeStyle, color = palette.text3)
            Spacer(Modifier.weight(1f))
            Text("${group.title} / ${item.title}", style = QianyanCodeStyle, color = palette.text3)
            Spacer(Modifier.width(12.dp))
            ActionButton("关闭", ActionLevel.TEXT, true, onClose)
        }
        HLine()

        Row(Modifier.fillMaxHeight()) {
            // 左：分组导航
            Column(
                Modifier.width(196.dp).fillMaxHeight().background(palette.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            ) {
                SETTINGS_NAV.forEach { g ->
                    SectionLabel(g.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
                    g.items.forEach { i ->
                        val on = g.title == groupKey && i.key == itemKey
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(QianyanShapes.r))
                                .background(if (on) palette.selection else Color.Transparent)
                                .clickable { groupKey = g.title; itemKey = i.key }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                i.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (on) palette.text else palette.text2,
                                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.line))

            // 右：设置内容
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 34.dp, end = 34.dp, top = 26.dp)) {
                Text(item.title, style = MaterialTheme.typography.displaySmall, color = palette.text)
                Spacer(Modifier.height(18.dp))

                when {
                    itemKey == "theme" -> ThemeSettings(state)
                    itemKey == "layout" -> LayoutSettings(state)
                    itemKey == "provider" -> ProviderSection(state)
                    itemKey == "api" -> ApiSection(state)
                    itemKey == "context" -> ContextSection()
                    itemKey == "type" -> TypographySection(state)
                    itemKey == "author" -> AuthorSection()
                    itemKey == "info" -> ProjectInfoSection(state)
                    itemKey == "txt" -> TxtSection(state)
                    itemKey == "storage" -> StorageSection(state)
                    itemKey == "shortcut" -> ShortcutSection()
                    else -> DiagnosticsSection()
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/* ---------------- 可折叠区块（原型同款：标题 + 说明 + 内容） ---------------- */

@Composable
private fun SetBlock(title: String, desc: String? = null, content: @Composable () -> Unit) {
    val palette = QianyanTheme.palette
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (open) "▾" else "▸",
                style = QianyanCodeStyle,
                color = palette.text3,
                modifier = Modifier.width(16.dp),
            )
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.text, fontWeight = FontWeight.SemiBold)
        }
        if (desc != null && open) {
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = palette.text3, modifier = Modifier.padding(start = 16.dp))
        }
        if (open) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().padding(start = 16.dp)) { content() }
        }
        Spacer(Modifier.height(14.dp))
        HLine()
    }
}

/** 安静开关行（右侧滑块；不使用 Material Switch 的彩色样式）。 */
@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = QianyanTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = palette.text3)
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.width(32.dp).height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (checked) palette.accentSoft else palette.soft)
                .border(1.dp, if (checked) palette.accent else palette.lineStrong, RoundedCornerShape(9.dp))
                .clickable { onChange(!checked) },
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(horizontal = 2.dp).width(12.dp).height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (checked) palette.accent else palette.text3),
            )
        }
    }
    HLine()
}

/** 单行选择（分段）：用于主题之外的少量离散选项。 */
@Composable
private fun SegmentedRow(label: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val palette = QianyanTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { i, option ->
                val on = i == selectedIndex
                Text(
                    option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) palette.text else palette.text3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(QianyanShapes.r))
                        .background(if (on) palette.selection else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
    HLine()
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = QianyanTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text3,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = QianyanTheme.palette.text3,
        modifier = Modifier.padding(vertical = 3.dp),
    )
}

@Composable
private fun UnavailableRow(name: String) {
    val palette = QianyanTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = palette.text2, modifier = Modifier.weight(1f))
        Text("暂不可用", style = QianyanCodeStyle, color = palette.text3)
    }
}

/* ---------------- 外观 · 主题（主题卡 + 预览） ---------------- */

@Composable
private fun ThemeSettings(state: DesktopAppState) {
    SetBlock("主题", "选择深色或浅色主题。点击卡片即可切换（当前会话内生效）。") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(
                label = "深色",
                desc = "适合长时间写作与夜间",
                palette = QianyanDarkPalette,
                active = state.themeMode == QianyanThemeMode.DARK,
                modifier = Modifier.weight(1f),
            ) { state.themeMode = QianyanThemeMode.DARK }
            ThemeCard(
                label = "浅色",
                desc = "明亮 · 适合白天使用",
                palette = QianyanLightPalette,
                active = state.themeMode == QianyanThemeMode.LIGHT,
                modifier = Modifier.weight(1f),
            ) { state.themeMode = QianyanThemeMode.LIGHT }
        }
        Spacer(Modifier.height(10.dp))
        SwitchRow(
            title = "跟随系统",
            desc = "由操作系统外观决定深色 / 浅色（打开上面的卡片会取消跟随）",
            checked = state.themeMode == QianyanThemeMode.SYSTEM,
        ) { on ->
            state.themeMode = if (on) QianyanThemeMode.SYSTEM else QianyanThemeMode.LIGHT
        }
    }
}

/** 主题预览卡（用该主题自己的色板绘制预览，而不是当前主题）。 */
@Composable
private fun ThemeCard(
    label: String,
    desc: String,
    palette: QianyanPalette,
    active: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val current = QianyanTheme.palette
    Column(
        modifier
            .clip(RoundedCornerShape(QianyanShapes.r3))
            .background(current.raised)
            .border(1.dp, if (active) current.accent else current.line, RoundedCornerShape(QianyanShapes.r3))
            .clickable { onSelect() }
            .padding(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .clip(RoundedCornerShape(QianyanShapes.r))
                .background(palette.bg)
                .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.r)),
        ) {
            // 左：导航面板块
            Box(
                Modifier.align(Alignment.CenterStart).padding(8.dp)
                    .width(56.dp).height(56.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r))
                    .background(palette.surface),
            )
            // 右：强调色的一个"当前项"标记
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .width(26.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = current.text, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (active) Text("✓", style = QianyanCodeStyle, color = current.accent)
        }
        Text(desc, style = MaterialTheme.typography.labelSmall, color = current.text3)
    }
}

/* ---------------- 外观 · 界面 ---------------- */

@Composable
private fun LayoutSettings(state: DesktopAppState) {
    SetBlock("面板", "控制工作区的三栏显示。") {
        SwitchRow("左侧导航", "显示项目导航（Ctrl/⌘+B）", state.sidebarOpen) { state.toggle(com.qianyan.app.desktop.ui.PanelToggle.SIDEBAR) }
        SwitchRow("检查器", "显示右侧上下文检查器（Ctrl/⌘+\\）", state.inspectorOpen) { state.toggle(com.qianyan.app.desktop.ui.PanelToggle.INSPECTOR) }
        SwitchRow("专注模式", "隐藏两侧面板，只留正文（Ctrl/⌘+Shift+E）", state.focusMode) { state.toggle(com.qianyan.app.desktop.ui.PanelToggle.FOCUS) }
    }
}

/* ---------------- AI ---------------- */

@Composable
private fun ProviderSection(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    SetBlock("AI Provider", "当前生效的 Provider 与模型。切换 Provider 只重建容器，数据仍在同一个本地库。") {
        InfoRow("Provider", state.graph.provider.value.name)
        InfoRow("模型", state.graph.modelId())
        InfoRow("端点", state.graph.baseUrl().ifBlank { "官方默认" })
        Spacer(Modifier.height(8.dp))
        ActionButton("打开 Provider 设置", ActionLevel.PRIMARY, true) { state.providerSettingsOpen = true }
        Spacer(Modifier.height(6.dp))
        Note("MOCK 为离线示意稿：结构与持久化全部真实，AI 生成内容为确定性占位。")
        Spacer(Modifier.height(2.dp))
        Text(
            "更完整的上下文与行为选项在 Provider 设置内配置（不在此处重复实现）。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.text3,
        )
    }
}

@Composable
private fun ApiSection(state: DesktopAppState) {
    SetBlock("凭证", "API Key 只保存在本机，不进入数据库、领域模型与日志。") {
        InfoRow("Provider", state.graph.provider.value.name)
        InfoRow("密钥状态", if (state.graph.isConfigured(state.graph.provider.value)) "已配置" else "未配置")
        InfoRow("存储位置", "%APPDATA%\\Qianyan\\credentials.properties")
        Spacer(Modifier.height(8.dp))
        ActionButton("管理密钥 / 模型 / 端点", ActionLevel.SECONDARY, true) { state.providerSettingsOpen = true }
    }
}

@Composable
private fun ContextSection() {
    SetBlock("上下文", "Context 的条目选择、排序与预算由既有 Context Engine（I6）决定，UI 不重算。") {
        Note("检查器「上下文」页展示的是引擎给出的一次只读投影：条目来源、预览与取舍说明。")
        Note("预算裁剪、去重与优先级是引擎行为，不提供手工覆盖开关。")
    }
    SetBlock("行为", "Qianyan 在什么情况下需要你确认。") {
        InfoRow("修改正文", "始终先生成待确认修改，不自动改写正文")
        InfoRow("人工门", "工作流停在人工确认时由你决定是否通过")
        InfoRow("修订上限", "由后端修订策略决定（不在 UI 侧放宽）")
    }
}

/* ---------------- 写作 ---------------- */

@Composable
private fun TypographySection(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    SetBlock("正文字号", "只影响正文编辑区的显示大小，不改变稿件内容。") {
        SegmentedRow(
            label = "字号",
            options = ProseSize.entries.map { it.label },
            selectedIndex = ProseSize.entries.indexOf(state.proseSize),
        ) { state.proseSize = ProseSize.entries[it] }
        Spacer(Modifier.height(6.dp))
        Text(
            "当前：${state.proseSize.label} · ${state.proseSize.size}sp · 行距约 2.0 倍",
            style = QianyanCodeStyle,
            color = palette.text3,
        )
    }
    SetBlock("字体", "界面与正文分别使用不同的字体族。") {
        InfoRow("界面字体", "Noto Sans SC（bundled）")
        InfoRow("正文字体", "衬线系统族（Noto Serif SC → Songti SC → SimSun；bundled 衬线资源待授权）")
        InfoRow("行距", "约 2.0 倍")
    }
}

@Composable
private fun AuthorSection() {
    SetBlock("作者写作配置", "这些能力由后端 Authorship / Decision 阶段提供，PC 侧尚未接线。") {
        listOf(
            "作者风格" to "Author Style",
            "作者 DNA" to "Author DNA",
            "写作偏好" to "Writing Preferences",
            "词库" to "Vocabulary",
            "禁用词" to "Forbidden Words",
            "文风规则" to "Style Rules",
            "自动检查" to "Auto Check",
        ).forEach { (cn, en) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(cn, style = MaterialTheme.typography.bodyMedium, color = QianyanTheme.palette.text2, modifier = Modifier.weight(1f))
                Text("$en · 暂不可用", style = QianyanCodeStyle, color = QianyanTheme.palette.text3)
            }
        }
        Spacer(Modifier.height(6.dp))
        Note("不提供假开关：接线后这些项才会出现可操作控件。")
    }
}

/* ---------------- 项目 ---------------- */

@Composable
private fun ProjectInfoSection(state: DesktopAppState) {
    val novel = state.novel
    SetBlock("项目信息") {
        if (novel == null) {
            Note("尚未打开作品。")
        } else {
            InfoRow("作品", novel.title)
            InfoRow("题材", novel.genre.joinToString(" · "))
            InfoRow("简介", novel.synopsis)
            InfoRow("状态", com.qianyan.app.desktop.ui.statusLabel(novel.status.name))
        }
    }
}

@Composable
private fun TxtSection(state: DesktopAppState) {
    SetBlock("TXT 导入", "导入 TXT 初稿并建立初始章节结构（确定性引擎，不调用 AI）。") {
        ActionButton("选择 TXT 文件…", ActionLevel.PRIMARY, true) {
            val file = DesktopFilePicker.chooseTxt()
            if (file == null) {
                state.toast("已取消导入")
            } else {
                state.launchTxtImport(file.name, file.readBytes())
            }
        }
        Spacer(Modifier.height(8.dp))
        Note("导入在后台执行，完成后状态栏与章节页会更新。")
    }
    SetBlock("导出", "导出能力尚未接入。") {
        Note("当前不提供导出入口，以免给出不真实的结果。")
    }
}

@Composable
private fun StorageSection(state: DesktopAppState) {
    SetBlock("数据存储", "本地优先：无云同步、无外部中间件。") {
        InfoRow("数据库", state.graph.appDir.resolve("qianyan.db").toString())
        InfoRow("数据目录", state.graph.appDir.toString())
        InfoRow("Provider 选择", state.graph.appDir.resolve("provider-selection.properties").toString())
        Spacer(Modifier.height(6.dp))
        Note("备份 / 恢复尚未接入（后续阶段）。")
    }
}

/* ---------------- 高级 ---------------- */

@Composable
private fun ShortcutSection() {
    val mod = shortcutLabel()
    SetBlock("导航") {
        InfoRow("一级导航", "$mod+1 … $mod+8")
        InfoRow("命令面板", "$mod+K")
        InfoRow("设置", "$mod+,")
        InfoRow("左侧导航", "$mod+B")
        InfoRow("检查器", "$mod+\\")
        InfoRow("专注模式", "$mod+Shift+E")
    }
    SetBlock("列表与编辑") {
        InfoRow("列表上下", "↑ / ↓（仅列表页面）")
        InfoRow("打开 / 进入", "Enter")
        InfoRow("关闭浮层", "Esc")
        InfoRow("正文换行", "Enter（编辑器内）")
    }
}

@Composable
private fun DiagnosticsSection() {
    SetBlock("诊断") {
        InfoRow("Activity Log", "检查器「活动」页（真实 I4 事实记录）")
        InfoRow("Agent / Skill / Tool", "内部能力，不在设置中暴露")
        InfoRow("调试信息", "暂不可用")
    }
}
