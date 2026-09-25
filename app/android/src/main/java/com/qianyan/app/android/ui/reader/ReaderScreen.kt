package com.qianyan.app.android.ui.reader

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.engine.markdown.MarkdownBlock

/**
 * Reader 屏（P20-P4 · FD-7 正式阅读链路）。
 *
 * 流程：Novel → ChapterList → ChapterDetail → **本页**；上一章 / 下一章就地切换，阅读位置自动保存与恢复。
 *
 * 架构边界：
 *  - 只消费 [ReaderViewModel] 的 [ReaderUiState]；不直接触碰 Repository / Provider / Agent；
 *  - 正文块来自 P2 同一份 [MarkdownBlock] 模型（不新建第二套章节正文模型）；行内强调经
 *    [markdownInline] 最小适配为 Compose AnnotatedString（不改 P2 契约）；
 *  - legacy（format=null）按纯文本展示，不做 Markdown 解析。
 */
@Composable
fun ReaderScreen(
    novelTitle: String,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 章节切换 → 以该章节自己的阅读位置新建列表状态（恢复阅读位置）
    val listState = remember(state.chapterId) {
        LazyListState(firstVisibleItemIndex = state.safePosition)
    }
    // 首可见块变化 → 保存阅读位置（同值由 ViewModel 去重）
    LaunchedEffect(listState, state.chapterId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> viewModel.onPositionChanged(index) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = "阅读 · $novelTitle",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.chapterOrder > 0) "${state.chapterOrder} · ${state.chapterTitle}" else state.chapterTitle.ifBlank { "章节" },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))

        state.error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading && state.blocks.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }

                state.blocks.isEmpty() ->
                    Text(
                        text = if (state.hasDraft) "本章正文为空。" else "本章暂无正文（可先在写作页生成初稿）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.blocks) { block ->
                        ReaderBlock(block = block, parseInline = state.isControlledMarkdown)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReaderNavButton(
                text = "上一章",
                enabled = state.canGoPrevious,
                onClick = viewModel::goToPreviousChapter,
                modifier = Modifier.weight(1f),
            )
            ReaderNavButton(
                text = "下一章",
                enabled = state.canGoNext,
                onClick = viewModel::goToNextChapter,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "返回章节详情",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(0.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** 单个正文块渲染（P2 块类型 → Compose 样式；Degraded 原样展示，不做行内解析）。 */
@Composable
private fun ReaderBlock(block: MarkdownBlock, parseInline: Boolean) {
    val body = { text: String, degraded: Boolean ->
        if (parseInline && !degraded) markdownInline(text) else AnnotatedString(text)
    }
    when (block) {
        is MarkdownBlock.Heading -> {
            Text(
                text = body(block.text, false),
                style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                },
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }

        is MarkdownBlock.Paragraph -> {
            Text(
                text = body(block.text, false),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        is MarkdownBlock.UnorderedListItem -> {
            Row(modifier = Modifier.padding(bottom = 6.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = body(block.text, false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        is MarkdownBlock.Quote -> {
            Text(
                text = body(block.text, false),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
            )
        }

        is MarkdownBlock.Degraded -> {
            Text(
                text = body(block.text, true),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ReaderNavButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(text)
    }
}