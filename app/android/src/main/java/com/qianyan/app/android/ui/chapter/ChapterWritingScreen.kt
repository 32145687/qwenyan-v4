package com.qianyan.app.android.ui.chapter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.model.ChapterId

/**
 * 章节工作流 Screen（P12.2 M3 · Facade Migration）。
 * 只消费 [ChapterWritingViewModel] 的用户层状态（[ChapterWorkflowProgress]/[ChapterPhase]），
 * 真实业务流程由 Facade → Workflow 执行。UI 依据 [ChapterPhase] 决定按钮可用性，
 * 但**不决定 Workflow 的下一步**（下一步由 Orchestrator 推进）。
 */
@Composable
fun ChapterWritingScreen(
    viewModel: ChapterWritingViewModel,
    onBack: () -> Unit,
    onContinueToNext: (ChapterId) -> Unit,
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val op by viewModel.op.collectAsStateWithLifecycle()
    val next by viewModel.nextChapter.collectAsStateWithLifecycle()

    // 续篇成功 → 通知宿主导航到下一章，并消费单次事件。
    LaunchedEffect(next) {
        next?.let {
            onContinueToNext(it.chapterId)
            viewModel.onNextConsumed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("章节工作流", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        ProgressSummary(progress)

        Spacer(Modifier.height(24.dp))
        val running = op is ChapterWritingOp.Running
        OpError(op)

        DefaultGap()
        val phase = progress?.phase
        when {
            phase == null -> Unit // 状态恢复中，ProgressSummary 已显示 loading
            phase == ChapterPhase.NOT_STARTED -> ActionButton("开始创作", enabled = !running, loading = running, onClick = viewModel::start)
            phase == ChapterPhase.COMPLETED -> ActionButton("下一章", enabled = !running, loading = running, onClick = viewModel::continueToNext)
            phase == ChapterPhase.FAILED -> ActionButton("重试推进", enabled = !running, loading = running, onClick = viewModel::advance)
            else -> {
                ActionButton("推进写作", enabled = !running, loading = running, onClick = viewModel::advance)
                if (phase == ChapterPhase.WAITING_CONFIRMATION && progress?.waitingForUser == true) {
                    ActionButton("确认内容", enabled = !running, onClick = viewModel::approve)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("返回章节详情", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(0.dp))
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ProgressSummary(progress: ChapterWorkflowProgress?) {
    if (progress == null) {
        Text("状态恢复中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val lines = buildList {
        add("章节：${progress.chapterId.value}")
        add("阶段：${progress.phaseLabel}")
        if (progress.revisionCount > 0) add("修订次数：${progress.revisionCount}")
        progress.draftId?.let { add("Draft：${it.value.take(8)}") }
        if (progress.waitingForUser) add("等待作者确认")
    }
    lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private val ChapterWorkflowProgress.phaseLabel: String
    get() = when (phase) {
        ChapterPhase.NOT_STARTED -> "未开始"
        ChapterPhase.PLANNING -> "规划中"
        ChapterPhase.WRITING -> "写作中"
        ChapterPhase.REVIEWING -> "评审中"
        ChapterPhase.REVISING -> "修订中"
        ChapterPhase.WAITING_CONFIRMATION -> "待确认"
        ChapterPhase.UPDATING_STORY -> "更新故事状态"
        ChapterPhase.COMPLETED -> "已完成"
        ChapterPhase.FAILED -> "失败"
    }

@Composable
private fun OpError(op: ChapterWritingOp) {
    if (op is ChapterWritingOp.Error) {
        Text("[${op.stage}] ${op.message}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ActionButton(text: String, enabled: Boolean, loading: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(if (loading) "$text…" else text)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun DefaultGap() {
    Spacer(Modifier.height(4.dp))
}