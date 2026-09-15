package com.qianyan.app.desktop.ui.write

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.model.BaseNovelId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter
import com.qianyan.model.task.TaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「AI 重写本章」——走**真实**链路，不是装饰按钮：
 *
 * ```
 * tasks.create(TaskType.WRITING)
 *   → planning.execute(taskId, request, null, chapterId)   // 真实产出并持久化 ChapterPlan
 *   → writingExecution.execute(taskId, request, plan)      // 真实产出 Draft（带 previousDraftId 版本链）
 * ```
 *
 * 与 Workflow 驱动那条路的区别：这条是"作者主动重写"的直达入口（不走人工门序列），
 * 适合"我改主意了，按这个方向再来一版"。Task / Checkpoint / Draft 持久化全部真实；
 * AI 内容在 MOCK Provider 下为示意稿，切到真实 Provider 即为真实生成。
 */
@Composable
fun RewriteDialog(
    state: DesktopAppState,
    novel: Novel,
    chapter: Chapter,
    onDismiss: () -> Unit,
) {
    var direction by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用 AI 重写「${chapter.title.ifBlank { "第 ${chapter.order} 章" }}」", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "会为本章重新生成规划与草稿。新草稿会保留在版本链上（previousDraftId 指向上一版），不会丢掉现有内容。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = direction,
                    onValueChange = { direction = it },
                    label = { Text("这一章你想要什么变化（可选）") },
                    placeholder = { Text("例如：让主角在结尾就发现那封信，语气更冷一些") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Text(
                    "走真实链路：Task → Planning（产出 ChapterPlan 并落库）→ Writing（产出 Draft）。" +
                        "当前 AI Provider 为 ${state.graph.provider.value.name}——MOCK 下为离线示意稿，切到真实 Provider 即为真实生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = QianyanColors.Ink3Light,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !state.busy,
                onClick = {
                    onDismiss()
                    val text = direction.trim()
                    state.launchWork {
                        val c = state.container
                        // 规划与写作各用一个类型正确的 Task（UseCase 会校验 Task 类型）
                        val planTaskId = c.tasks.create(TaskType.PLANNING)
                        val request = UserWritingRequest(
                            requestId = RequestId("req-rewrite-" + chapter.chapterId.value.take(8) + "-" + System.currentTimeMillis()),
                            intentType = IntentType.REWRITE,
                            target = TargetRef(kind = TargetKind.CHAPTER, id = TargetRefId(chapter.chapterId.value)),
                            planningScope = PlanningScope.CHAPTER,
                            rawText = text,
                            baseNovelId = BaseNovelId(novel.novelId.value),
                            variantId = null,
                            scope = VariantScope.ORIGINAL,
                        )
                        val plan = c.planning.execute(planTaskId, request, null, chapter.chapterId)
                        val writeTaskId = c.tasks.create(TaskType.WRITING)
                        val fresh = c.writingExecution.execute(writeTaskId, request, plan)
                        withContext(Dispatchers.Main) {
                            state.toast(
                                "已生成新草稿 · ${fresh.content.length} 字 · 目标：${plan.chapterGoal.take(24)}" +
                                    if (plan.chapterGoal.length > 24) "…" else "",
                            )
                        }
                    }
                },
            ) { Text("开始重写") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
