package com.qianyan.app.desktop.ui.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.QianyanChip
import com.qianyan.app.desktop.ui.theme.QianyanColors

/**
 * 「AI 改写本章」对话框（P20-PC2）。
 *
 * 走**既有** seam：`WriterGateway.rewrite(novelId, variantId, chapterId)`
 * → Application 内复用既有 Critique → Revision（与 WorkflowOrchestrator 的 CRITIQUE / REVISION 同一对
 * UseCase），产出**新 draftId、status=REVISED**，原稿仍在版本链上（`previousDraftId`），不破坏现有内容。
 *
 * 边界说明（重要）：当前 `WriterGateway.rewrite` **不接收「修改方向」参数**。为了不伪造输入、
 * 也不擅自改动共享 Writer 契约，本对话框**不提供**「修改要求」输入框。
 */
@Composable
fun RewriteDialog(
    state: WriterUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用 AI 改写「${state.chapterTitle.ifBlank { "本章" }}」", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "会对本章当前最新草稿执行一次评审 + 修订。新草稿保留在版本链上" +
                        "（previousDraftId 指向上一版），现有正文不会被破坏。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    QianyanChip("草稿状态 ${state.draftStatus ?: "-"}")
                    QianyanChip("修订 ${state.revisionCount}/3", QianyanColors.AmberSoftLight)
                    QianyanChip(state.formatLabel)
                }
                Text(
                    "走真实链路：WriterGateway.rewrite → 既有 Critique → Revision。当前 AI Provider 为 " +
                        "MOCK 时为离线示意稿，切到真实 Provider 即为真实生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (state.isLegacyFormat) {
                    Text(
                        "注意：本章现有草稿是 legacy 纯文本（format=null）。改写产生的新草稿会标记为受控 Markdown v1，原稿格式不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = state.canRewrite) {
                Text(if (state.isGenerating) "改写中…" else "执行改写")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
            }
        },
    )
}