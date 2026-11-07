package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.di.CustomModel
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.QuietField
import com.qianyan.app.desktop.ui.providerLabel
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderType

/**
 * 设置 · 模型页：管理自定义模型清单（`models.properties`，与 provider-selection 同族）。
 *
 * 只显示真实 Provider 的条目（mock-v1 不进入候选）；清单与 Provider 设置里的模型 ID 手填共用同一数据源。
 */
@Composable
fun ModelSettings(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val revision by state.graph.customModelsRevision.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CustomModel?>(null) }

    SettingsSection("自定义模型", "使用自有 API Key 管理自定义模型。") {
        val models = remember(revision) { state.graph.customModels() }
        if (models.isEmpty()) {
            SettingsNote("还没有自定义模型。")
        } else {
            models.forEachIndexed { index, model ->
                if (index > 0) HLine(Modifier.padding(start = 16.dp))
                ModelRow(
                    state = state,
                    model = model,
                    onEdit = { editing = model; adding = false },
                    onToggle = { enabled -> state.graph.setCustomModelEnabled(model.provider, model.modelId, enabled) },
                    onDelete = { state.graph.removeCustomModel(model.provider, model.modelId) },
                )
            }
        }
        HLine(Modifier.padding(start = 16.dp))
        Row(
            Modifier.fillMaxWidth().clickable { adding = true; editing = null }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "＋ 添加模型",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.accent,
            )
        }
    }

    if (adding) {
        Spacer(Modifier.height(4.dp))
        ModelEditor(
            title = "添加模型",
            initial = null,
            onCancel = { adding = false },
            onSave = { draft ->
                val ok = state.graph.addCustomModel(draft)
                if (ok) adding = false
                ok
            },
        )
    }
    editing?.let { original ->
        Spacer(Modifier.height(4.dp))
        ModelEditor(
            title = "编辑模型",
            initial = original,
            onCancel = { editing = null },
            onSave = { draft ->
                val ok = state.graph.updateCustomModel(original, draft)
                if (ok) editing = null
                ok
            },
        )
    }
}

@Composable
private fun ModelRow(
    state: DesktopAppState,
    model: CustomModel,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val palette = QianyanTheme.palette
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(model.label, style = MaterialTheme.typography.bodyLarge, color = palette.text, fontWeight = FontWeight.Medium)
            Text(
                "${providerLabel(model.provider)} · ${endpointText(state, model.provider)}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.text3,
            )
        }
        SettingsSwitch(model.enabled) { onToggle(it) }
        Spacer(Modifier.width(6.dp))
        Box {
            ActionButton("…", ActionLevel.TEXT, true) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑", style = MaterialTheme.typography.bodyLarge, color = palette.text) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("删除", style = MaterialTheme.typography.bodyLarge, color = palette.del) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun ModelEditor(
    title: String,
    initial: CustomModel?,
    onCancel: () -> Unit,
    onSave: (CustomModel) -> Boolean,
) {
    val palette = QianyanTheme.palette
    val editableProviders = listOf(ProviderType.DEEPSEEK, ProviderType.MIMO)
    var provider by remember(initial) { mutableStateOf(initial?.provider ?: editableProviders.first()) }
    var modelId by remember(initial) { mutableStateOf(initial?.modelId ?: "") }
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var error by remember(initial) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(QianyanShapes.r3))
            .background(palette.raised)
            .border(1.dp, palette.line, RoundedCornerShape(QianyanShapes.r3))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = palette.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("厂商", style = MaterialTheme.typography.labelSmall, color = palette.text3)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            editableProviders.forEach { type ->
                val on = provider == type
                Text(
                    providerLabel(type),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) palette.text else palette.text3,
                    modifier = Modifier
                        .clip(RoundedCornerShape(QianyanShapes.r))
                        .background(if (on) palette.selection else Color.Transparent)
                        .clickable { provider = type }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("模型 ID", style = MaterialTheme.typography.labelSmall, color = palette.text3)
        Spacer(Modifier.height(6.dp))
        QuietField(
            value = modelId,
            onValueChange = { modelId = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "例如 deepseek-v4-pro",
        )
        Spacer(Modifier.height(12.dp))
        Text("显示名称", style = MaterialTheme.typography.labelSmall, color = palette.text3)
        Spacer(Modifier.height(6.dp))
        QuietField(
            value = label,
            onValueChange = { label = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "留空则使用模型 ID",
        )
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = palette.del)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionButton("取消", ActionLevel.TEXT, true, onCancel)
            Spacer(Modifier.width(10.dp))
            ActionButton("保存", ActionLevel.PRIMARY, true) {
                val id = modelId.trim()
                error = when {
                    id.isEmpty() -> "模型 ID 不能为空"
                    id == ModelProfile.MOCK.id -> "离线示意模型不在自定义清单中"
                    else -> null
                }
                if (error == null) {
                    val ok = onSave(
                        CustomModel(
                            provider = provider,
                            modelId = id,
                            label = label.trim().ifEmpty { id },
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                    if (!ok) error = "保存失败，请检查清单文件权限"
                }
            }
        }
    }
}

private fun endpointText(state: DesktopAppState, provider: ProviderType): String =
    state.graph.defaultBaseUrl(provider).ifBlank { "本地离线" }
