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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.application.usecase.chapter.ChapterChainResult

/**
 * 章节创作链 Screen（P12.1.7，最小 UI）：按真实链跑通
 * Planning → Writing → Critique → (Revision) → Finalize → Confirm → Knowledge Update。
 * 只消费 [ChapterWritingViewModel] 状态；真实业务由 Application [ChapterWritingUseCases] 执行。
 */
@Composable
fun ChapterWritingScreen(
    viewModel: ChapterWritingViewModel,
    onBack: () -> Unit,
) {
    val chain by viewModel.chain.collectAsStateWithLifecycle()
    val op by viewModel.op.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("章节创作链", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        ResultSummary(chain)

        Spacer(Modifier.height(24.dp))
        val running = op is ChapterWritingOp.Running
        OpError(op)

        DefaultGap()
        StepButton("1 · Planning", enabled = !running && chain?.plan == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Planning", onClick = viewModel::plan)
        StepButton("2 · Writing", enabled = !running && chain?.plan != null && chain?.draft == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Writing", onClick = viewModel::write)
        StepButton("3 · Critique", enabled = !running && chain?.draft != null && chain?.critique == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Critique", onClick = viewModel::critique)
        StepButton("4 · Revision", enabled = !running && chain?.draft != null && chain?.critique != null && chain?.finalDraft == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Revision", onClick = viewModel::revise)
        StepButton("5 · 定稿 Finalize", enabled = !running && chain?.draft != null && chain?.finalDraft == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Finalize", onClick = viewModel::finalize)
        StepButton("6 · 确认 Confirmation", enabled = !running && chain?.finalDraft != null && chain?.confirmedDraft == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Confirmation", onClick = viewModel::confirm)
        StepButton("7 · Knowledge Update", enabled = !running && chain?.confirmedDraft != null && chain?.knowledgeUpdate == null, loading = running && (op as? ChapterWritingOp.Running)?.stage == "Knowledge Update", onClick = viewModel::knowledgeUpdate)

        Spacer(Modifier.height(20.dp))
        Text("返回章节详情", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(0.dp))
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ResultSummary(chain: ChapterChainResult?) {
    if (chain == null) {
        Text("状态恢复中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val planId = chain.plan?.chapterPlanId?.value ?: "—"
    val draftStatus = chain.draft?.status ?: "—"
    val critiquePassed = chain.critique?.passed
    val revisionCount = chain.revisions.size
    val finalStatus = chain.finalDraft?.status ?: "—"
    val confirmedStatus = chain.confirmedDraft?.status ?: "—"
    val kuApplied = chain.knowledgeUpdate?.applied?.size
    val lines = buildList {
        add("章节：${chain.chapterId.value}")
        add("Plan ID：$planId")
        add("Draft 状态：$draftStatus")
        add("Critique：${if (critiquePassed != null) "已完成(passed=$critiquePassed)" else "—"}")
        add("Revision 次数：$revisionCount")
        add("Final Draft：$finalStatus")
        add("Confirmed：$confirmedStatus")
        add("Knowledge Update：${if (kuApplied != null) "已完成(applied=$kuApplied)" else "—"}")
    }
    lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun OpError(op: ChapterWritingOp) {
    if (op is ChapterWritingOp.Error) {
        Text("[${op.stage}] ${op.message}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
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