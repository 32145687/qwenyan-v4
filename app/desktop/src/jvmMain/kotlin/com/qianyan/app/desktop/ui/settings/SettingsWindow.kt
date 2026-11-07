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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.ProseSize
import com.qianyan.app.desktop.ui.QuietField
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.hoverBackground
import com.qianyan.app.desktop.ui.providerLabel
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanPalette
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.theme.QianyanThemeMode
import com.qianyan.provider.ProviderType

/* ---------------- 设置导航（窗口内部；不是 Novel IDE 一级导航） ---------------- */

private data class SettingsItem(val key: String, val title: String)

private data class SettingsGroup(val title: String, val items: List<SettingsItem>)

/** 设置分区 key（入口定位用；必须与 [SETTINGS_NAV] 的 itemKey 一致）。 */
internal const val SETTINGS_PROVIDER_SECTION = "provider"
internal const val SETTINGS_MODELS_SECTION = "models"
internal const val SETTINGS_USAGE_SECTION = "usage"

private val SETTINGS_NAV = listOf(
    SettingsGroup("外观", listOf(SettingsItem("theme", "主题"), SettingsItem("layout", "界面"))),
    SettingsGroup(
        "AI",
        listOf(
            SettingsItem("provider", "AI Provider"),
            SettingsItem("models", "模型"),
            SettingsItem(SETTINGS_USAGE_SECTION, "用量与余额"),
        ),
    ),
    SettingsGroup("写作", listOf(SettingsItem("type", "排版"))),
    SettingsGroup("高级", listOf(SettingsItem("shortcut", "键盘快捷键"))),
)

/**
 * 设置（**弹窗**，不是主工作区页面）。
 *
 * Apple 式系统设置布局：左侧固定分组导航（灰底 + 白色选中胶囊），右侧页面标题 +
 * 分组卡片（圆角 / 发丝分隔 / 右对齐控件）。关闭不重置主 IDE 的页面与 WorkspaceSelection。
 *
 * 只呈现**真实可用**的设置：主题 / 面板默认显示 / 正文字号 / Provider / TXT 导入 /
 * 存储路径 / 快捷键。后端尚未交付或未接线的能力如实标注，不提供假开关。
 */
@Composable
fun SettingsPanel(state: DesktopAppState, onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    // 入口可要求定位到某个分区；消费后立刻清空，下一次普通打开（⌘, / 菜单）回到默认首页
    val requested = state.settingsSection
    val initialItem = SETTINGS_NAV.flatMap { group -> group.items }.firstOrNull { it.key == requested }?.key
        ?: SETTINGS_NAV.first().items.first().key
    val initialGroup = SETTINGS_NAV.first { group -> group.items.any { it.key == initialItem } }.title
    var groupKey by remember { mutableStateOf(initialGroup) }
    var itemKey by remember { mutableStateOf(initialItem) }
    LaunchedEffect(Unit) {
        state.settingsSection = null
        state.settingsProvider = null
    }
    val group = SETTINGS_NAV.firstOrNull { it.title == groupKey } ?: SETTINGS_NAV.first()
    val item = group.items.firstOrNull { it.key == itemKey } ?: group.items.first()

    Column(
        Modifier
            .fillMaxWidth(0.94f)
            .fillMaxHeight(0.9f)
            .widthIn(max = 1040.dp)
            .heightIn(max = 760.dp)
            .clip(RoundedCornerShape(QianyanShapes.window))
            .background(palette.raised)
            .border(1.dp, palette.lineStrong, RoundedCornerShape(QianyanShapes.window))
            .consumeClicks(),
    ) {
        Row(Modifier.fillMaxHeight()) {
            // 左：分组导航
            Column(Modifier.width(236.dp).fillMaxHeight().background(palette.surface)) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.text,
                    modifier = Modifier.padding(start = 22.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                )
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                ) {
                    SETTINGS_NAV.forEach { g ->
                        SettingsGroupLabel(g.title)
                        g.items.forEach { i ->
                            SidebarRow(
                                text = i.title,
                                selected = g.title == groupKey && i.key == itemKey,
                            ) { groupKey = g.title; itemKey = i.key }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(palette.line))

            // 右：设置内容
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 40.dp, end = 28.dp, top = 22.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.title, style = MaterialTheme.typography.displaySmall, color = palette.text, modifier = Modifier.weight(1f))
                    CircleCloseButton(onClose)
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 40.dp, end = 40.dp, top = 10.dp, bottom = 32.dp),
                ) {
                    when (itemKey) {
                        "theme" -> ThemeSettings(state)
                        "layout" -> LayoutSettings(state)
                        "provider" -> ProviderSection(state)
                        "models" -> ModelSettings(state)
                        SETTINGS_USAGE_SECTION -> UsageSettings(state)
                        "type" -> TypographySection(state)
                        "shortcut" -> ShortcutSection()
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    val shape = RoundedCornerShape(QianyanShapes.r)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) palette.raised else Color.Transparent)
            .hoverBackground(shape, enabled = !selected)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) palette.text else palette.text2,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun CircleCloseButton(onClose: () -> Unit) {
    val palette = QianyanTheme.palette
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(palette.soft)
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Text("✕", style = MaterialTheme.typography.labelMedium, color = palette.text2)
    }
}

/* ---------------- 外观 · 主题 ---------------- */

@Composable
private fun ThemeSettings(state: DesktopAppState) {
    val dark = QianyanTheme.isDark
    SettingsSection(
        "外观",
        "选择深色或浅色主题。点击卡片即可切换（保存在本机，重启后仍生效）。",
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(
                label = "深色",
                desc = "适合长时间写作与夜间",
                palette = com.qianyan.app.desktop.ui.theme.QianyanDarkPalette,
                active = state.themeMode == QianyanThemeMode.DARK,
                modifier = Modifier.weight(1f),
            ) { state.updateThemeMode(QianyanThemeMode.DARK) }
            ThemeCard(
                label = "浅色",
                desc = "明亮 · 适合白天使用",
                palette = com.qianyan.app.desktop.ui.theme.QianyanLightPalette,
                active = state.themeMode == QianyanThemeMode.LIGHT,
                modifier = Modifier.weight(1f),
            ) { state.updateThemeMode(QianyanThemeMode.LIGHT) }
        }
        HLine(Modifier.padding(start = 16.dp))
        SettingsRow(
            title = "跟随系统",
            desc = "由操作系统外观决定深色 / 浅色（关闭时保留当前外观）",
        ) {
            SettingsSwitch(state.themeMode == QianyanThemeMode.SYSTEM) { on ->
                state.updateThemeMode(
                    if (on) {
                        QianyanThemeMode.SYSTEM
                    } else if (dark) {
                        QianyanThemeMode.DARK
                    } else {
                        QianyanThemeMode.LIGHT
                    },
                )
            }
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
    val shape = RoundedCornerShape(QianyanShapes.r3)
    Column(
        modifier
            .clip(shape)
            .background(current.raised)
            .border(if (active) 2.dp else 1.dp, if (active) current.accent else current.line, shape)
            .clickable { onSelect() }
            .padding(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp)
                .clip(RoundedCornerShape(QianyanShapes.r2))
                .background(palette.bg)
                .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.r2)),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(9.dp)
                    .width(58.dp).height(58.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r))
                    .background(palette.surface),
            )
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
    SettingsSection("面板", "控制工作区的三栏显示。") {
        SettingsRow("左侧导航", "显示项目导航（${shortcutLabel()}+B）") {
            SettingsSwitch(state.sidebarOpen) { state.toggle(com.qianyan.app.desktop.ui.PanelToggle.SIDEBAR) }
        }
        SettingsRow("检查器", "显示右侧上下文检查器（${shortcutLabel()}+\\）", divider = true) {
            SettingsSwitch(state.inspectorOpen) { state.toggle(com.qianyan.app.desktop.ui.PanelToggle.INSPECTOR) }
        }
        SettingsRow("专注模式", "隐藏两侧面板，只留正文（${shortcutLabel()}+Shift+E）", divider = true) {
            SettingsSwitch(state.focusMode) { state.toggle(com.qianyan.app.desktop.ui.PanelToggle.FOCUS) }
        }
    }
}

/* ---------------- AI ---------------- */

@Composable
private fun ProviderSection(state: DesktopAppState) {
    val provider by state.graph.provider.collectAsState()
    val model by state.graph.effectiveModel.collectAsState()
    SettingsSection("Provider", "当前生效的 Provider 与模型。切换 Provider 只重建容器，数据仍在同一个本地库。") {
        SettingsInfoRow("Provider", provider.name)
        SettingsInfoRow("模型", model, divider = true)
        SettingsInfoRow("端点", state.graph.baseUrl().ifBlank { "官方默认" }, divider = true)
        SettingsInfoRow("密钥状态", if (state.graph.isConfigured(provider)) "已配置" else "未配置", divider = true)
        SettingsNote(
            "API Key 只保存在本机 credentials.properties，不进入数据库、领域模型与日志。",
            divider = true,
        )
        SettingsNote(
            "MOCK 为离线示意稿：结构与持久化全部真实，AI 生成内容为确定性占位。",
            divider = true,
        )
    }
    ProviderCredentials(state)
}

/**
 * 密钥与端点：原 Provider 设置弹窗的录入 / 保存 / 清除能力迁入设置页。
 *
 * 三条既有规则：MOCK 不收 Key；busy 时禁用并提示等待；
 * Key 只经 [DesktopGraph.saveProviderConfig] / [DesktopGraph.clearApiKey] 落到 credentials.properties，
 * 不写日志、不写 toast、不进异常信息。
 */
@Composable
private fun ProviderCredentials(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val current by state.graph.provider.collectAsState()
    var selected by remember {
        mutableStateOf(initialSettingsProvider(state.settingsSection, state.settingsProvider, current))
    }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var configured by remember(selected) { mutableStateOf(state.graph.isConfigured(selected)) }

    LaunchedEffect(selected) {
        apiKey = ""
        reveal = false
        note = null
        baseUrl = if (selected == current) state.graph.baseUrl() else ""
    }

    SettingsSection(
        "密钥与端点",
        "为所选 Provider 保存或清除本机 API Key；保存即尝试切换（装配失败会保留原配置）。",
    ) {
        SettingsRow("Provider", "保存后切换到所选项") {
            SettingsSegmented(
                options = ProviderType.entries.map { providerLabel(it) },
                selectedIndex = ProviderType.entries.indexOf(selected),
            ) { selected = ProviderType.entries[it] }
        }

        if (selected == ProviderType.MOCK) {
            SettingsNote("离线示意不需要凭证；保存后切换到 MOCK 的离线生成。", divider = true)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "API Key",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (reveal) "隐藏" else "显示",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(QianyanShapes.r))
                            .clickable { reveal = !reveal }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                QuietField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (configured) "留空 = 沿用已保存的 Key" else "sk-…",
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).clip(CircleShape)
                            .background(if (configured) palette.add else palette.text3.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (configured) "已配置 Key" else "未配置 Key",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text3,
                    )
                }
            }
            HLine(Modifier.padding(start = 16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("端点覆盖", style = MaterialTheme.typography.bodyLarge, color = palette.text)
                Spacer(Modifier.height(6.dp))
                QuietField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "留空用官方默认 · ${state.graph.defaultBaseUrl(selected)}",
                )
            }
        }

        if (state.busy) {
            SettingsNote("任务进行中，Provider 设置已锁定（请等待任务结束）。", divider = true)
        }
        note?.let { SettingsNote(it, divider = true) }
        HLine(Modifier.padding(start = 16.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != ProviderType.MOCK && configured) {
                ActionButton("清除 Key", ActionLevel.TEXT, !state.busy) {
                    state.graph.clearApiKey(selected)
                    apiKey = ""
                    configured = false
                    note = "已清除 ${providerLabel(selected)} 的 API Key"
                }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            ActionButton(
                if (current == selected) "保存" else "保存并切换",
                ActionLevel.PRIMARY,
                enabled = !state.busy && (selected == ProviderType.MOCK || configured || apiKey.isNotBlank()),
            ) {
                val ok = saveProviderFromSettings(state, selected, apiKey, baseUrl)
                note = if (ok) {
                    val switched = if (current == selected) "" else "并切换到 ${providerLabel(selected)}"
                    "已保存$switched（模型 ${state.graph.modelId()}）"
                } else {
                    state.graph.providerMessage.value ?: "保存失败：请检查 Key 与端点"
                }
                if (ok) {
                    apiKey = ""
                    configured = state.graph.isConfigured(selected)
                }
            }
        }
    }
}

/**
 * 设置页的初始厂商：入口点名了厂商就用它，否则用当前生效的 Provider。
 *
 * 只有 AI Provider 分区接受厂商预设；其它分区（模型 / 主题 …）一律回到当前生效值。
 */
internal fun initialSettingsProvider(
    requestedSection: String?,
    requestedProvider: ProviderType?,
    current: ProviderType,
): ProviderType =
    if (requestedSection == SETTINGS_PROVIDER_SECTION && requestedProvider != null) {
        requestedProvider
    } else {
        current
    }

/**
 * 设置 › AI Provider 的保存动作（UI 与测试共用，只经 [DesktopGraph.saveProviderConfig] 落盘）。
 *
 * 同一 Provider 保留当前模型；跨 Provider 取清单首项或该厂商默认模型——
 * 避免把上一个厂商的模型 ID 带到新厂商。
 */
internal fun saveProviderFromSettings(
    state: DesktopAppState,
    selected: ProviderType,
    apiKey: String,
    baseUrl: String,
): Boolean {
    val current = state.graph.provider.value
    val modelId = if (selected == current) {
        state.graph.modelId()
    } else {
        state.graph.customModels(selected).firstOrNull()?.modelId ?: state.graph.defaultModelId(selected)
    }
    return state.graph.saveProviderConfig(
        selected,
        // MOCK 不收 Key（离线示意不需要凭证）
        apiKey = if (selected == ProviderType.MOCK) "" else apiKey.trim(),
        modelId = modelId,
        baseUrl = baseUrl.trim(),
    )
}

/* ---------------- 写作 ---------------- */

@Composable
private fun TypographySection(state: DesktopAppState) {
    SettingsSection("正文字号", "只影响正文编辑区的显示大小，不改变稿件内容。") {
        SettingsRow(
            title = "字号",
            desc = "当前：${state.proseSize.label} · ${state.proseSize.size}sp · 行距约 2.0 倍",
        ) {
            SettingsSegmented(
                options = ProseSize.entries.map { it.label },
                selectedIndex = ProseSize.entries.indexOf(state.proseSize),
            ) { state.proseSize = ProseSize.entries[it] }
        }
    }
    SettingsSection("字体", "界面与正文分别使用不同的字体族。") {
        SettingsInfoRow("界面字体", "Noto Sans SC（bundled）")
        SettingsInfoRow(
            "正文字体",
            "衬线系统族（Noto Serif SC → Songti SC → SimSun；bundled 衬线资源待授权）",
            divider = true,
        )
        SettingsInfoRow("行距", "约 2.0 倍", divider = true)
    }
}

/* ---------------- 高级 ---------------- */

@Composable
private fun ShortcutSection() {
    val mod = shortcutLabel()
    SettingsSection("导航") {
        SettingsInfoRow("一级导航", "$mod+1 … $mod+8")
        SettingsInfoRow("命令面板", "$mod+K", divider = true)
        SettingsInfoRow("设置", "$mod+,", divider = true)
        SettingsInfoRow("左侧导航", "$mod+B", divider = true)
        SettingsInfoRow("检查器", "$mod+\\", divider = true)
        SettingsInfoRow("专注模式", "$mod+Shift+E", divider = true)
    }
    SettingsSection("列表与编辑") {
        SettingsInfoRow("列表上下", "↑ / ↓（仅列表页面）")
        SettingsInfoRow("打开 / 进入", "Enter", divider = true)
        SettingsInfoRow("关闭浮层", "Esc", divider = true)
        SettingsInfoRow("正文换行", "Enter（编辑器内）", divider = true)
    }
}
