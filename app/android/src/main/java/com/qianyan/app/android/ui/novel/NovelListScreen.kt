package com.qianyan.app.android.ui.novel

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.model.core.Novel
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 小说列表 Screen（P7.4 + P7.5 + P7.6）：主界面 + Original 列表，Apple-inspired 极简。
 *
 * 分层：本 Composable 只消费 [NovelListViewModel] 暴露的 UI 状态，不直接访问 UseCase / Repository。
 * P7.5：新增「导入 TXT」入口（触发 SAF 由 MainActivity 处理）与导入状态展示，不改动整体布局风格。
 * P7.6：点击小说卡片进入 Analysis 页（由 MainActivity 切换页面，本 Screen 通过 [onNovelClick] 上报）。
 */
@Composable
fun NovelListScreen(
    viewModel: NovelListViewModel,
    onImportClick: () -> Unit,
    onNovelClick: (Novel) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    NovelListContent(
        state = state,
        importState = importState,
        onRetry = viewModel::load,
        onImportClick = onImportClick,
        onNovelClick = onNovelClick,
    )
}

@Composable
private fun NovelListContent(
    state: NovelListUiState,
    importState: TxtImportUiState,
    onRetry: () -> Unit,
    onImportClick: () -> Unit,
    onNovelClick: (Novel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Qianyan",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Living Story",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "我的小说",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Button(
                onClick = onImportClick,
                enabled = importState !is TxtImportUiState.Loading,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(if (importState is TxtImportUiState.Loading) "导入中…" else "导入 TXT")
            }
        }
        Spacer(Modifier.height(12.dp))
        ImportStatus(importState = importState)
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (state) {
                is NovelListUiState.Loading -> LoadingState()
                is NovelListUiState.Empty -> EmptyState()
                is NovelListUiState.Error -> ErrorState(onRetry = onRetry)
                is NovelListUiState.Success -> NovelList(novels = state.novels, onNovelClick = onNovelClick)
            }
        }
    }
}

/** 导入状态提示行：Idle 不渲染；Loading 显示进度；Success / Error 显示文案。 */
@Composable
private fun ImportStatus(importState: TxtImportUiState) {
    when (importState) {
        is TxtImportUiState.Idle -> Unit
        is TxtImportUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "正在导入…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is TxtImportUiState.Success -> Text(
            text = if (importState.isDuplicate) {
                "《${importState.title}》已存在，跳过重复导入"
            } else {
                "已导入《${importState.title}》"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is TxtImportUiState.Error -> Text(
            text = importState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "还没有小说",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "导入一本 TXT 小说，Qianyan 会从这里开始建立你的故事世界。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "无法加载小说",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请稍后重试",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
private fun NovelList(novels: List<Novel>, onNovelClick: (Novel) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(novels, key = { it.novelId.value }) { novel ->
            NovelCard(novel = novel, onClick = { onNovelClick(novel) })
        }
    }
}

@Composable
private fun NovelCard(novel: Novel, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Text(
                text = novel.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Original",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = formatDate(novel.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 将 Instant 格式化为 `yyyy-MM-dd`（本地时区），避免 UI 依赖复杂日期库。 */
private fun formatDate(instant: Instant): String {
    val dt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = dt.monthNumber.toString().padStart(2, '0')
    val day = dt.dayOfMonth.toString().padStart(2, '0')
    return "${dt.year}-$month-$day"
}
