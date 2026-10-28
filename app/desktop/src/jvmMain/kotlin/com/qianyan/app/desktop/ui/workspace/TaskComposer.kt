package com.qianyan.app.desktop.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.importTxtFromPicker
import com.qianyan.app.desktop.ui.shortcutLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanElevation
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.provider.ProviderType

/**
 * 创作工作区的多行任务输入框与底部工具栏。
 *
 * 工具栏只放真实可控的入口：添加（含真实 TXT 导入）、模型选择、思考能力、发送。
 * 思考能力当前没有真实请求参数与模型能力声明，因此只显示「不可调 / 未声明」，
 * 不展示低/标准/高/极高等没有落地的档位。
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
            ThinkingMenu(state)
            Spacer(Modifier.weight(1f))
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

/* ---------------- 模型 ---------------- */

@Composable
private fun ModelMenu(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    val current by state.graph.provider.collectAsState()
    val effectiveModel by state.graph.effectiveModel.collectAsState()
    var open by remember { mutableStateOf(false) }

    Box {
        ActionButton("$effectiveModel  ⌄", ActionLevel.TEXT, !state.busy) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(330.dp)) {
            Text(
                "模型",
                style = MaterialTheme.typography.labelSmall,
                color = palette.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            HLine()
            ProviderType.entries.forEach { type ->
                val configured = type == ProviderType.MOCK || state.graph.isConfigured(type)
                val model = if (type == current) effectiveModel else state.graph.defaultModelId(type)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                providerLabel(type) + if (type == current) " · 当前" else "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.text,
                            )
                            Text(
                                "$model · ${if (configured) "可用" else "未配置"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.text3,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        when {
                            state.busy -> state.toast("任务进行中，暂时不能切换模型")
                            !configured -> {
                                state.providerSettingsOpen = true
                                state.toast("请先配置 ${providerLabel(type)} 的 API Key")
                            }
                            type != current -> state.graph.selectProvider(type)
                        }
                    },
                )
            }
            HLine()
            DropdownMenuItem(
                text = { Text("模型管理…", style = MaterialTheme.typography.bodyLarge, color = palette.text) },
                onClick = { open = false; state.providerSettingsOpen = true },
            )
        }
    }
}

/* ---------------- 思考能力（当前无真实能力声明） ---------------- */

@Composable
private fun ThinkingMenu(state: DesktopAppState) {
    val current by state.graph.provider.collectAsState()
    val unsupported = current == ProviderType.MOCK
    // 不展示任何未落地的档位；点击如实说明不会发送思考参数
    ActionButton("思考能力", ActionLevel.TEXT, !state.busy) {
        state.toast(
            if (unsupported) "思考能力未接入：离线示意模型不提供思考能力，不会发送思考参数"
            else "思考能力未接入：当前模型未声明思考能力，不会发送思考参数",
        )
    }
}

private fun providerLabel(type: ProviderType): String = when (type) {
    ProviderType.MOCK -> "离线示意"
    ProviderType.DEEPSEEK -> "DeepSeek"
    ProviderType.MIMO -> "MiMo"
}
