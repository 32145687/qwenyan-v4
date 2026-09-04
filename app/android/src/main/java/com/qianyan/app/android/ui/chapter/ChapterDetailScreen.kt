package com.qianyan.app.android.ui.chapter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.model.story.Chapter

/**
 * 章节详情基础页（P12.1.6）：显示标题 / 顺序 / Novel+Variant 身份。
 * 正文展示非本阶段必须；「下一步：Planning」为 navigation seam（无真实 UseCase 前显示禁用/TODO）。
 */
@Composable
fun ChapterDetailScreen(
    viewModel: ChapterDetailViewModel,
    novelTitle: String,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "章节详情 · $novelTitle",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                is ChapterDetailUiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                is ChapterDetailUiState.Error ->
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                is ChapterDetailUiState.Content -> DetailContent(
                    chapter = s.chapter,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun DetailContent(chapter: Chapter, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${chapter.order} · ${chapter.title}",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "顺序：${chapter.order}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "作用域：${chapter.scope}" +
                (chapter.variantId?.let { " / Variant ${it.value}" } ?: " / Original"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "chapterId：${chapter.chapterId.value}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        // 「下一步：Planning」navigation seam —— P12.1.7 接入真实 Planning Use Case 前保持禁用（TODO）。
        Button(
            onClick = { /* TODO(P12.1.7): planning entry */ },
            enabled = false,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text("下一步：Planning（待开放）")
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "返回章节列表",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(0.dp),
        )
    }
}