package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
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
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.QuietField
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.provider.ProviderType

/**
 * AI Provider 设置（浮层；对应 Android 的 Provider Settings UI，桌面侧等价物）。
 *
 * - Provider 切换：MOCK / DEEPSEEK / MIMO；
 * - API Key 仅经 ProviderCredentialStore 持有（本机 credentials.properties），
 *   不进入 ProviderConfiguration、不进入任何领域模型、不上传；
 * - 保存即尝试装配：缺 Key / 配置非法 → 拒绝切换并给出原因，不崩溃；
 * - 切换 Provider 只重建 ApplicationContainer（同一个 SQLite 文件），不产生第二套数据库。
 */
@Composable
fun ProviderSettingsDialog(
    graph: com.qianyan.app.desktop.di.DesktopGraph,
    onDismiss: () -> Unit,
) {
    val palette = QianyanTheme.palette
    val current by graph.provider.collectAsState()
    val providerMessage by graph.providerMessage.collectAsState()
    var selected by remember { mutableStateOf(current) }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(graph.modelId()) }
    var baseUrl by remember { mutableStateOf(graph.baseUrl()) }
    var reveal by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf<List<String>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) {
        configured = graph.isConfigured(selected)
        apiKey = ""
        note = null
        modelId = if (selected == current) graph.modelId() else graph.defaultModelId(selected)
        baseUrl = if (selected == current) graph.baseUrl() else ""
        issues = graph.validate(selected)
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (QianyanTheme.isDark) 0.55f else 0.32f))
                .clickable { onDismiss() },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(560.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.raised)
                    .border(1.dp, palette.lineStrong, RoundedCornerShape(QianyanShapes.r3))
                    .consumeClicks()
                    .padding(18.dp),
            ) {
                Text("AI Provider", style = MaterialTheme.typography.titleLarge, color = palette.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "API Key 只保存在本机（%APPDATA%\\Qianyan\\credentials.properties），不进领域模型、不上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                )

                Spacer(Modifier.height(14.dp))
                ProviderType.entries.forEach { type ->
                    ProviderOption(
                        type = type,
                        selected = selected == type,
                        configured = graph.isConfigured(type),
                        onSelect = { selected = type },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    graph.describe(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text2,
                )

                Spacer(Modifier.height(14.dp))
                Text("模型 ID", style = MaterialTheme.typography.labelSmall, color = palette.text3)
                Spacer(Modifier.height(5.dp))
                QuietField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = graph.defaultModelId(selected),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "默认：${graph.defaultModelId(selected)} · 可填任意模型（如 deepseek-reasoner）",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.text3,
                )

                if (selected != ProviderType.MOCK) {
                    Spacer(Modifier.height(12.dp))
                    Text("端点 Base URL", style = MaterialTheme.typography.labelSmall, color = palette.text3)
                    Spacer(Modifier.height(5.dp))
                    QuietField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "留空用官方默认：${graph.defaultBaseUrl(selected)}",
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (configured) "API Key（留空 = 沿用已保存的）" else "API Key",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                            modifier = Modifier.weight(1f),
                        )
                        ActionButton(if (reveal) "隐藏" else "显示", ActionLevel.TEXT, true) { reveal = !reveal }
                    }
                    Spacer(Modifier.height(5.dp))
                    QuietField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "sk-…",
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Tag(if (configured) "已配置 Key" else "未配置 Key", if (configured) palette.accent else palette.text3)
                        if (configured) {
                            ActionButton("清除", ActionLevel.TEXT, true) {
                                graph.clearApiKey(selected)
                                configured = false
                                note = "已清除 ${selected.name} 的 API Key"
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Tag("Mock 无需凭证", palette.text3)
                }

                if (current != selected) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "保存后将切换到 ${selected.name}（当前 ${current.name}）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text2,
                    )
                }
                issues.forEach {
                    Spacer(Modifier.height(3.dp))
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = palette.del)
                }
                note?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.text2)
                }
                providerMessage?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = palette.del)
                }

                Spacer(Modifier.height(16.dp))
                HLine()
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (selected != ProviderType.MOCK && apiKey.isNotBlank()) {
                        ActionButton("仅保存不切换", ActionLevel.TEXT, true) {
                            graph.saveProviderConfig(selected, apiKey.trim(), modelId, baseUrl)
                            graph.selectProvider(current)
                            onDismiss()
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    ActionButton("关闭", ActionLevel.TEXT, true) {
                        onDismiss()
                    }
                    Spacer(Modifier.width(10.dp))
                    ActionButton(if (current == selected) "保存" else "保存并切换", ActionLevel.PRIMARY, true) {
                        val ok = graph.saveProviderConfig(selected, apiKey.trim(), modelId, baseUrl)
                        if (ok) {
                            note = "已保存并切换到 ${selected.name}（模型 ${graph.modelId()}）"
                            configured = graph.isConfigured(selected)
                            apiKey = ""
                        }
                        issues = graph.validate(selected)
                    }
                }
            }
        }
    }
}

/** Provider 选项行（安静的单选行，不是彩色分段按钮）。 */
@Composable
private fun ProviderOption(
    type: ProviderType,
    selected: Boolean,
    configured: Boolean,
    onSelect: () -> Unit,
) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(QianyanShapes.r2))
            .background(if (selected) palette.selection else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(12.dp).height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, if (selected) palette.accent else palette.lineStrong, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(palette.accent))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(type.name, style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
        if (type != ProviderType.MOCK) {
            Text(
                if (configured) "已配置" else "未配置",
                style = QianyanCodeStyle,
                color = if (configured) palette.accent else palette.text3,
            )
        }
    }
}
