package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.QianyanChip
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.provider.ProviderType

/**
 * AI Provider 设置（PC 设置页）。
 *
 * 对应 Android 的 Provider Settings UI（P12.5-M02/M04）桌面侧等价物。
 * - Provider 切换：MOCK / DEEPSEEK / MIMO；
 * - API Key 仅经 ProviderCredentialStore 持有（本机 credentials.properties），
 *   不进入 ProviderConfiguration、不进入任何领域模型、不上传；
 * - 保存即尝试装配：缺 Key / 配置非法 → 拒绝切换并给出原因，不崩溃。
 */
@Composable
fun ProviderSettingsDialog(
    graph: com.qianyan.app.desktop.di.DesktopGraph,
    onDismiss: () -> Unit,
) {
    val current = graph.provider.value
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
        // 切换 Provider 时预填该 Provider 的默认模型（可改成任意模型 id）
        modelId = if (selected == current) graph.modelId() else graph.defaultModelId(selected)
        baseUrl = if (selected == current) graph.baseUrl() else ""
        issues = graph.validate(selected)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Provider 设置", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "API Key 只保存在本机（%APPDATA%\\Qianyan\\credentials.properties），不进领域模型、不上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ProviderType.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = selected == t,
                            onClick = { selected = t },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = ProviderType.entries.size),
                        ) { Text(t.name, style = MaterialTheme.typography.labelMedium) }
                    }
                }

                Text(graph.describe(selected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // 模型 id 自由填写（ModelProfile 是 data class，任何模型 id 都能透传给网关）
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "默认：${graph.defaultModelId(selected)} · 可填任意模型（如 deepseek-reasoner）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (selected != ProviderType.MOCK) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("端点 Base URL（可留空）") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                "留空用官方默认：${graph.defaultBaseUrl(selected)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (configured) "API Key（留空=沿用已保存的）" else "API Key") },
                        singleLine = true,
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Text(
                                if (reveal) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.clickable { reveal = !reveal }.padding(end = 10.dp),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QianyanChip(
                            if (configured) "已配置 Key" else "未配置 Key",
                            if (configured) QianyanColors.AiSoftLight else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        if (configured) {
                            Text(
                                "清除",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable {
                                    graph.clearApiKey(selected)
                                    configured = false
                                    note = "已清除 ${selected.name} 的 API Key"
                                }.padding(4.dp),
                            )
                        }
                    }
                } else {
                    QianyanChip("Mock 无需凭证", QianyanColors.AiSoftLight)
                }

                if (current != selected) {
                    Text(
                        "保存后将切换到 ${selected.name}（当前 ${current.name}）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                issues.forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                graph.providerMessage.value?.let {
                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val ok = graph.saveProviderConfig(selected, apiKey.trim(), modelId, baseUrl)
                    if (ok) {
                        note = "已保存并切换到 ${selected.name}（模型 ${graph.modelId()}）"
                        configured = graph.isConfigured(selected)
                        apiKey = ""
                    }
                    issues = graph.validate(selected)
                },
            ) { Text(if (current == selected) "保存" else "保存并切换") }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = {
                    // 仅保存凭证/模型但不切换（便于先把 Key 存好再切）
                    if (selected != ProviderType.MOCK && apiKey.isNotBlank()) {
                        graph.saveProviderConfig(selected, apiKey.trim(), modelId, baseUrl)
                        graph.selectProvider(current)
                    }
                    onDismiss()
                }) { Text("仅保存不切换") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}
