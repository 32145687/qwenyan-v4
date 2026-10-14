package com.qianyan.app.desktop.ui.write

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.consumeClicks
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme

/**
 * 「让 Qianyan 修改」确认浮层（P20-PC2）。
 *
 * 走**既有** seam：`WriterGateway.rewrite(novelId, variantId, chapterId)`
 * → Application 内复用既有 Critique → Revision（与 WorkflowOrchestrator 的 CRITIQUE / REVISION 同一对
 * UseCase），产出**新 draftId、status=REVISED**，原稿仍在版本链上（`previousDraftId`），不破坏现有内容。
 *
 * 边界说明（重要）：当前 `WriterGateway.rewrite` **不接收「修改方向」参数**。为不伪造输入、
 * 也不擅自改动共享 Writer 契约，本浮层**不提供**「修改要求」输入框——它只会是一个不生效的死输入。
 */
@Composable
fun RewriteDialog(
    state: WriterUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = QianyanTheme.palette
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = if (QianyanTheme.isDark) 0.55f else 0.32f))
                .clickable { onDismiss() },
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(480.dp)
                    .clip(RoundedCornerShape(QianyanShapes.r3))
                    .background(palette.raised)
                    .consumeClicks()
                    .padding(18.dp),
            ) {
                Text("让 Qianyan 修改", style = MaterialTheme.typography.titleLarge, color = palette.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "对「${state.chapterTitle.ifBlank { "本章" }}」的最新草稿执行一次评审 + 修订；新草稿保留在版本链上，现有正文不会被破坏。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.text2,
                )

                Spacer(Modifier.height(14.dp))
                HLine()
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(statusLabel(state.draftStatus), palette.text2)
                    Tag("修订 ${state.revisionCount}/3", palette.accent)
                    Tag(state.formatLabel, palette.text3)
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "真实链路：WriterGateway.rewrite → 既有 Critique → Revision。" +
                        "当前 Provider 为 MOCK 时是离线示意稿，切到真实 Provider 即为真实生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text3,
                )
                if (state.isLegacyFormat) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "本章现有草稿是 legacy 纯文本（format=null）：改写产生的新草稿会标记为受控 Markdown v1，原稿格式不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text3,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ActionButton("取消", ActionLevel.TEXT, true, onDismiss)
                    Spacer(Modifier.width(8.dp))
                    ActionButton(if (state.isGenerating) "改写中…" else "生成修改", ActionLevel.PRIMARY, state.canRewrite, onConfirm)
                }
            }
        }
    }
}
