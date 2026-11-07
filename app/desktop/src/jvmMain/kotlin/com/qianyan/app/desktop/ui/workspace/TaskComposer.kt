package com.qianyan.app.desktop.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.hoverBackground
import com.qianyan.app.desktop.ui.importTxtFromPicker
import com.qianyan.app.desktop.ui.providerLabel
import com.qianyan.app.desktop.ui.settings.SETTINGS_MODELS_SECTION
import com.qianyan.app.desktop.ui.settings.SETTINGS_PROVIDER_SECTION
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanElevation
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.provider.ProviderType
import kotlin.math.roundToInt

/**
 * 创作工作区的多行任务输入框与底部工具栏。
 *
 * 工具栏只放真实可控的入口：添加（含真实 TXT 导入）、模型选择、发送。
 * 模型菜单是三级选择：厂商 → 模型 → 思考强度（选择模型/强度都经 DesktopGraph.saveProviderConfig，
 * 触发容器整体重建）；思考强度按厂商能力分形态，离线示意不发送任何思考参数。
 */
@Composable
fun TaskComposer(
    state: DesktopAppState,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = QianyanTheme.palette
    var focused by remember { mutableStateOf(false) }
    // 主输入区：暖白静默底 + 大圆角 + 极轻投影——视觉上是明确的"主操作区"，而不是普通表单
    val shape = RoundedCornerShape(QianyanShapes.window)

    Column(
        modifier.fillMaxWidth()
            .shadow(QianyanElevation.Composer, shape, clip = false)
            .clip(shape)
            .background(palette.soft)
            .border(1.dp, if (focused) palette.accent else palette.line, shape)
            .padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = false,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 14.5.sp,
                lineHeight = 26.sp,
                color = palette.text,
            ),
            cursorBrush = SolidColor(palette.accent),
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 94.dp, max = 220.dp)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // 普通 Enter 只换行：中文输入法用 Enter 选词时不得误提交任务。
                    val sendChord = event.isCtrlPressed || event.isMetaPressed
                    if (event.key == Key.Enter && sendChord && value.isNotBlank() && !state.busy) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().heightIn(min = 94.dp)) {
                    if (value.isEmpty()) {
                        Text(
                            "描述你的故事，或直接写下第一句……",
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.5.sp, lineHeight = 26.sp),
                            color = palette.text3,
                        )
                    }
                    inner()
                }
            },
        )

        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AddMenu(state)
            Spacer(Modifier.width(2.dp))
            Spacer(Modifier.weight(1f))
            ProviderMarker(state)
            Spacer(Modifier.width(6.dp))
            ModelMenu(state)
            Spacer(Modifier.width(4.dp))
            SendButton(
                busy = state.busy,
                onClick = {
                    when {
                        state.busy -> Unit
                        value.isBlank() -> state.toast("请先输入内容，再发送")
                        else -> onSubmit()
                    }
                },
            )
        }
    }
}

/** 发送：暖蓝主按钮（↑）；空输入点击只提示，不提交。 */
@Composable
private fun SendButton(busy: Boolean, onClick: () -> Unit) {
    val palette = QianyanTheme.palette
    Box(
        Modifier
            .width(33.dp)
            .height(33.dp)
            .clip(RoundedCornerShape(QianyanShapes.r3))
            .background(palette.accent)
            .clickable(enabled = !busy) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (busy) "…" else "↑",
            style = MaterialTheme.typography.titleMedium,
            color = palette.onAccent,
        )
    }
}

/* ---------------- 添加 ---------------- */

@Composable
private fun AddMenu(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var open by remember { mutableStateOf(false) }

    Box {
        ActionButton("＋ 添加上下文", ActionLevel.TEXT, !state.busy) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(280.dp)) {
            Text(
                "添加",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            HLine()
            DropdownMenuItem(
                text = { Text("导入 TXT…", style = MaterialTheme.typography.bodyLarge, color = palette.text) },
                onClick = {
                    open = false
                    state.importTxtFromPicker()
                },
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("添加上下文…", style = MaterialTheme.typography.bodyLarge, color = palette.text2)
                        Text(
                            "未接入 · 当前任务自动使用所选章节",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.text3,
                        )
                    }
                },
                // 不再用「点了没反应的禁用项」：明确提示未接入，但绝不把用户输入变成 Agent 任务
                onClick = {
                    open = false
                    state.toast("「添加上下文」暂未接入；当前任务会自动使用所选章节")
                },
            )
        }
    }
}

/* ---------------- 模型（三级：厂商 → 模型 → 思考强度） ---------------- */

/* ---------------- Provider 状态（原左栏页脚的诚实标记） ---------------- */

/**
 * 「已配置 / 未配置 / 离线示意」唯一常驻标记：只有已配置的真实 Provider 才点绿点，
 * MOCK 是离线示意，不得显示成在线模型的样子。
 */
@Composable
private fun ProviderMarker(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val provider by state.graph.provider.collectAsState()
    val configured = provider != ProviderType.MOCK && state.graph.isConfigured(provider)
    val text = when {
        provider == ProviderType.MOCK -> "离线示意"
        configured -> "可用"
        else -> "未配置"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(7.dp).clip(CircleShape)
                .background(if (configured) palette.add else palette.text3.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${providerLabel(provider)} · $text",
            style = MaterialTheme.typography.labelSmall,
            color = palette.text3,
            maxLines = 1,
        )
    }
}

/* ---------------- 模型（三级：厂商 → 模型 → 思考强度） ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelMenu(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val current by state.graph.provider.collectAsState()
    val effectiveModel by state.graph.effectiveModel.collectAsState()
    val effort by state.graph.effectiveReasoningEffort.collectAsState()
    val revision by state.graph.customModelsRevision.collectAsState()
    var open by remember { mutableStateOf(false) }
    val models = remember(revision, current) { state.graph.customModels(current) }

    Box {
        ActionButton("$effectiveModel  ⌄", ActionLevel.TEXT, !state.busy) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(304.dp),
            shape = RoundedCornerShape(QianyanShapes.r3),
            containerColor = palette.raised,
            tonalElevation = 0.dp,
            shadowElevation = 14.dp,
            border = BorderStroke(1.dp, palette.lineStrong),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "模型",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.text,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${providerLabel(current)} · $effectiveModel",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.text3,
                        maxLines = 1,
                    )
                }

                MenuSectionLabel("厂商")
                ProviderType.entries.forEach { type ->
                    val configured = type == ProviderType.MOCK || state.graph.isConfigured(type)
                    val status = when {
                        type == ProviderType.MOCK -> "无需 Key"
                        configured -> "可用"
                        else -> "未配置"
                    }
                    MenuRow(
                        onClick = {
                            open = false
                            when {
                                state.busy -> state.toast("任务进行中，暂时不能切换模型")
                                !configured -> {
                                    state.openSettingsSection(SETTINGS_PROVIDER_SECTION)
                                    state.toast("请先配置 ${providerLabel(type)} 的 API Key")
                                }
                                type != current -> state.chooseProviderModel(type)
                            }
                        },
                    ) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(if (type == current) palette.accent else Color.Transparent),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            providerLabel(type),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (type == current) palette.text else palette.text2,
                            modifier = Modifier.weight(1f),
                        )
                        Text(status, style = MaterialTheme.typography.labelSmall, color = palette.text3)
                        if (type == current) {
                            Spacer(Modifier.width(6.dp))
                            Text("✓", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                        }
                    }
                }

                MenuDivider()
                MenuSectionLabel("模型")
                when {
                    current == ProviderType.MOCK -> MenuRow(onClick = { open = false }) {
                        Text(
                            "离线示意：不提供模型选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text3,
                        )
                    }

                    models.isEmpty() -> MenuRow(
                        onClick = {
                            open = false
                            state.openSettingsSection(SETTINGS_MODELS_SECTION)
                            state.toast("请在「设置 · 模型」中添加或启用模型")
                        },
                    ) {
                        Text(
                            "清单为空，在「设置 · 模型」中添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text3,
                            modifier = Modifier.weight(1f),
                        )
                        Text("›", style = MaterialTheme.typography.labelMedium, color = palette.text3)
                    }

                    else -> models.forEach { model ->
                        val isCurrent = model.modelId == effectiveModel
                        MenuRow(
                            onClick = {
                                open = false
                                if (!isCurrent) state.chooseProviderModel(current, modelId = model.modelId)
                            },
                        ) {
                            Text(
                                model.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) palette.text else palette.text2,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                model.modelId,
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.text3,
                                maxLines = 1,
                            )
                            if (isCurrent) {
                                Spacer(Modifier.width(6.dp))
                                Text("✓", style = MaterialTheme.typography.labelMedium, color = palette.accent)
                            }
                        }
                    }
                }

                MenuDivider()
                MenuSectionLabel("思考能力")
                val note = reasoningNote(current)
                if (note != null) {
                    MenuRow(
                        onClick = {
                            open = false
                            state.toast(note)
                        },
                    ) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = palette.text3)
                    }
                } else {
                    val choices = reasoningChoices(current)
                    val committed = effectiveChoice(current, effort)
                    val committedIndex = choices.indexOfFirst { it.effort == committed }.coerceAtLeast(0)
                    // 拖动时只更新预览；松手才提交（提交会重建容器，不能每个刻度都做）。
                    var previewIndex by remember(current, effort, open) { mutableStateOf(committedIndex) }
                    val sliderIndex = previewIndex.coerceIn(0, choices.lastIndex)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "思考强度",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.text3,
                                modifier = Modifier.weight(1f),
                            )
                            Text(choices[sliderIndex].label, style = MaterialTheme.typography.labelMedium, color = palette.accent)
                        }
                        Slider(
                            value = sliderIndex.toFloat(),
                            onValueChange = { previewIndex = it.roundToInt() },
                            onValueChangeFinished = {
                                val target = choices[previewIndex.coerceIn(0, choices.lastIndex)].effort
                                if (target != committed) state.chooseProviderModel(current, reasoningEffort = target)
                            },
                            valueRange = 0f..choices.lastIndex.toFloat(),
                            steps = (choices.size - 2).coerceAtLeast(0),
                            enabled = !state.busy,
                            colors = SliderDefaults.colors(
                                thumbColor = palette.accent,
                                activeTrackColor = palette.accent,
                                inactiveTrackColor = palette.line,
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent,
                            ),
                            thumb = {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(palette.accent))
                            },
                            track = { slider ->
                                val start = slider.valueRange.start
                                val end = slider.valueRange.endInclusive
                                val fraction = if (end > start) {
                                    ((slider.value - start) / (end - start)).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                Box(
                                    Modifier.fillMaxWidth().height(3.dp)
                                        .clip(RoundedCornerShape(2.dp)).background(palette.line),
                                ) {
                                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.accent))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            choices.forEach { choice ->
                                Text(
                                    choice.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (choice.effort == committed) palette.text else palette.text3,
                                )
                            }
                        }
                    }
                }

                MenuDivider()
                MenuRow(onClick = { open = false; state.openSettingsSection(SETTINGS_MODELS_SECTION) }) {
                    Text(
                        "模型管理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.text2,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", style = MaterialTheme.typography.labelLarge, color = palette.text3)
                }
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = QianyanTheme.palette.text3,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuDivider() {
    HLine(Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
}

@Composable
private fun MenuRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(QianyanShapes.r)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            .clip(shape)
            .hoverBackground(shape)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
