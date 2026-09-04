package com.qianyan.app.android.ui.chapter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.model.story.Chapter

/**
 * 章节列表 Screen（P12.1.6）。只消费 [ChapterViewModel] 状态 + 回调，不触碰 Use Case / Repository。
 * 提供「新建章节」输入（标题）+ 真实章节列表（order ASC），创建成功经 [onOpen](/created) 导航。
 */
@Composable
fun ChapterListScreen(
    viewModel: ChapterViewModel,
    novelTitle: String,
    onOpen: (Chapter) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createState by viewModel.createState.collectAsStateWithLifecycle()
    val created by viewModel.created.collectAsStateWithLifecycle()

    // 创建成功 → 导航到 Detail（单次；consume 后位空）
    androidx.compose.runtime.LaunchedEffect(created) {
        val c = created
        if (c != null) {
            viewModel.onCreatedConsumed()
            onOpen(c)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = novelTitle,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "章节",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        CreateChapterRow(
            createState = createState,
            onCreateRequest = viewModel::createChapter,
        )
        Spacer(Modifier.height(6.dp))
        CreateError(createState = createState)

        Spacer(Modifier.height(14.dp))
        Text("返回 ${novelTitle}", modifier = Modifier.clickable(onClick = onBack),
            color = MaterialTheme.colorScheme.primary)

        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                is ChapterListUiState.Loading -> LoadingState()
                is ChapterListUiState.Error -> Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is ChapterListUiState.Success -> ChapterList(
                    chapters = s.chapters,
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun CreateChapterRow(
    createState: ChapterCreateState,
    onCreateRequest: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    val creating = createState is ChapterCreateState.Creating
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            label = { Text("章节标题") },
            enabled = !creating,
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = {
                val t = title.trim()
                if (t.isEmpty()) return@Button
                onCreateRequest(t)
            },
            enabled = !creating,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (creating) "创建中…" else "新建章节")
        }
    }
}

@Composable
private fun CreateError(createState: ChapterCreateState) {
    if (createState is ChapterCreateState.Error) {
        Text(
            text = createState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ChapterList(chapters: List<Chapter>, onOpen: (Chapter) -> Unit) {
    if (chapters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "还没有章节。输入标题点击「新建章节」。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(chapters, key = { it.chapterId.value }) { chapter ->
            ChapterCard(chapter = chapter, onClick = { onOpen(chapter) })
        }
    }
}

@Composable
private fun ChapterCard(chapter: Chapter, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chapter.order.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}