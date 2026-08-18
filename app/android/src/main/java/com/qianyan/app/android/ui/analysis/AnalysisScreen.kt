package com.qianyan.app.android.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.model.analysis.VocabularySuggestion
import com.qianyan.model.vocabulary.VocabularyCandidate

/**
 * Analysis 页 Screen（P7.6，功能第一版，非 UI 重设计）。
 *
 * 分层：只消费 [AnalysisViewModel] 暴露的 UI 状态；不直接调用 UseCase / Repository / LLM。
 *  - 顶部：返回 + 小说标题；
 *  - 操作：开始分析（Idle / Loading / Success / SuccessWithWarnings / Error）；
 *  - 结果：AnalysisOutput 的词汇建议 + 候选数量；
 *  - 候选：Analysis 完成后经 findCandidatesByNovel 展示；空候选显示"暂无候选"而非错误。
 */
@Composable
fun AnalysisScreen(
    novelTitle: String,
    viewModel: AnalysisViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val candidatesLoaded by viewModel.candidatesLoaded.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = novelTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = viewModel::startAnalysis,
            enabled = state !is AnalysisUiState.Loading,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (state is AnalysisUiState.Loading) "分析中…" else "开始分析")
        }
        Spacer(Modifier.height(12.dp))

        AnalysisStatusContent(state = state, onRetry = viewModel::startAnalysis)
        Spacer(Modifier.height(16.dp))

        if (candidatesLoaded) {
            CandidateSection(candidates = candidates)
        }

        Spacer(Modifier.weight(1f))
    }
}

/** Analysis 主状态区：Idle 提示 / Loading 进度 / Success / SuccessWithWarnings / Error（含重试）。 */
@Composable
private fun AnalysisStatusContent(state: AnalysisUiState, onRetry: () -> Unit) {
    when (state) {
        is AnalysisUiState.Idle -> Text(
            text = "点击「开始分析」，AI 将从正文中提取小说专属词汇候选。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is AnalysisUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在分析…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is AnalysisUiState.Success -> Column {
            Text(
                text = "分析完成",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            SuggestionList(
                suggestions = state.suggestions,
                candidateCount = state.candidateCount,
            )
        }
        is AnalysisUiState.SuccessWithWarnings -> Column {
            Text(
                text = "分析完成（有警告）",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.warning,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SuggestionList(
                suggestions = state.suggestions,
                candidateCount = state.candidateCount,
            )
        }
        is AnalysisUiState.Error -> Column {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("重试")
            }
        }
    }
}

/** AnalysisOutput 词汇建议 + 候选数量（candidateIds.size）。 */
@Composable
private fun SuggestionList(suggestions: List<VocabularySuggestion>, candidateCount: Int) {
    if (suggestions.isEmpty()) {
        Text(
            text = "本次未提取到词汇建议",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column {
            Text(
                text = "提取到 ${suggestions.size} 条词汇建议",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            suggestions.forEach { s ->
                SuggestionRow(suggestion = s)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = "候选词条数：$candidateCount",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SuggestionRow(suggestion: VocabularySuggestion) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = suggestion.canonical,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = suggestion.type.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (suggestion.aliases.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = suggestion.aliases.joinToString(" / "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 候选区：Analysis 完成后展示 findCandidatesByNovel 的结果；空候选显示"暂无候选"而非错误。 */
@Composable
private fun CandidateSection(candidates: List<VocabularyCandidate>) {
    Text(
        text = "候选词汇",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    if (candidates.isEmpty()) {
        Text(
            text = "暂无候选",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(candidates, key = { it.candidateId.value }) { candidate ->
                CandidateRow(candidate = candidate)
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: VocabularyCandidate) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = candidate.suggested.canonical,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = candidate.suggested.type.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = candidate.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = candidate.source.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (candidate.suggested.aliases.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "别名：${candidate.suggested.aliases.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
