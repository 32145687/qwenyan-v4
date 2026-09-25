package com.qianyan.app.android.ui.writer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Writer 屏（P20-P3 · Android Writer 最小产品闭环）。
 *
 * 流程：小说列表 → 章节列表 → 章节详情 → **本页**；加载 Draft → 编辑正文 → 保存 → AI 继续写 / AI 改写。
 *
 * 架构边界：只消费 [WriterViewModel] 的 [WriterUiState]（用户层状态）；不直接触碰 Repository / Provider /
 * Agent，也不触发 Decision。正文以「受控 Markdown v1」纯文本承载（不引入第三方 RichText / HTML / 完整
 * Markdown 引擎；渲染属 Reader 阶段）。
 */
@Composable
fun WriterScreen(
    novelTitle: String,
    viewModel: WriterViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        // 1) 章节信息区：小说名 / 章节名 / 当前状态
        Text(
            text = "写作 · $novelTitle",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.chapterTitle.ifBlank { "章节" },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "状态：${state.taskStatus}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // 4) 状态展示：错误
        state.error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        // 2) 编辑区：受控 Markdown 纯文本编辑（保存见下方操作区）
        OutlinedTextField(
            value = state.draftContent,
            onValueChange = viewModel::onContentChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            enabled = !state.isGenerating,
            label = { Text("正文（受控 Markdown）") },
            placeholder = { Text("尚无草稿：点击「AI 继续写」生成初稿") },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "格式：${state.draftFormat ?: "legacy 纯文本（format=null）"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // 3) AI 操作区
        WriterActionButton(
            text = if (state.isGenerating) "AI 继续写…" else "AI 继续写",
            enabled = state.canGenerate,
            onClick = viewModel::continueWriting,
        )
        WriterActionButton(
            text = "AI 改写",
            enabled = state.canGenerate && state.draftId != null,
            onClick = viewModel::rewrite,
        )
        WriterActionButton(
            text = if (state.isSaving) "保存中…" else "保存",
            enabled = state.canSave,
            onClick = viewModel::save,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "返回",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(0.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WriterActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(text)
    }
    Spacer(Modifier.height(10.dp))
}