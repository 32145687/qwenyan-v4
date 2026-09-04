package com.qianyan.app.android.ui.novel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qianyan.model.core.Novel

/**
 * 小说详情 hub（P12.1.6）：从 Novel List 进入，提供「章节」与「分析」两个能力入口。
 * 本页只承载导航，不持有业务状态。
 */
@Composable
fun NovelDetailScreen(
    novel: Novel,
    onChapters: () -> Unit,
    onAnalysis: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = novel.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Original",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onChapters,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("章节")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAnalysis,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text("AI 分析")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "返回小说列表",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(0.dp),
        )
    }
}