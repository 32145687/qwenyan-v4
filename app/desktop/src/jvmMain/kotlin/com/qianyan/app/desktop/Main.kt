package com.qianyan.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.BreathingSparkle
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.author.AuthorScreen
import com.qianyan.app.desktop.ui.book.BookScreen
import com.qianyan.app.desktop.ui.home.HomeScreen
import com.qianyan.app.desktop.ui.manage.ManageScreen
import com.qianyan.app.desktop.ui.plan.PlanScreen
import com.qianyan.app.desktop.ui.story.StoryScreen
import com.qianyan.app.desktop.ui.theme.QianyanColors
import com.qianyan.app.desktop.ui.settings.ProviderSettingsDialog
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.app.desktop.ui.write.WriteScreen

fun main() = application {
    val graph = DesktopGraph.create()
    val state = DesktopAppState(graph)

    Window(
        onCloseRequest = ::exitApplication,
        title = "千言 Qianyan · 长篇小说创作空间",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        QianyanTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppRoot(state)
            }
        }
    }
}

@Composable
fun AppRoot(state: DesktopAppState) {
    val provider by state.graph.provider.collectAsState()
    val providerMsg by state.graph.providerMessage.collectAsState()

    Row(Modifier.fillMaxSize()) {
        Sidebar(state)

        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Provider 提示条（切换失败 / 当前 Mock 提示）
            val msg = providerMsg
            if (msg != null) {
                Text(
                    "⚠ $msg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f)).padding(horizontal = 34.dp, vertical = 8.dp),
                )
            }
            when (state.section) {
                Section.HOME -> HomeScreen(state)
                Section.STORY -> StoryScreen(state)
                Section.PLAN -> PlanScreen(state)
                Section.WRITE -> WriteScreen(state)
                Section.MANAGE -> ManageScreen(state)
                Section.BOOK -> BookScreen(state)
                Section.AUTHOR -> AuthorScreen(state)
            }
        }
    }

    if (state.providerSettingsOpen) {
        ProviderSettingsDialog(state.graph) { state.providerSettingsOpen = false }
    }
}

private fun sectionIcon(s: Section): ImageVector = when (s) {
    Section.HOME -> Icons.Default.Home
    Section.STORY -> Icons.Default.AutoAwesome
    Section.PLAN -> Icons.Default.Explore
    Section.WRITE -> Icons.Default.Edit
    Section.MANAGE -> Icons.Default.Layers
    Section.BOOK -> Icons.Default.MenuBook
    Section.AUTHOR -> Icons.Default.Person
}

@Composable
private fun Sidebar(state: DesktopAppState) {
    Column(
        Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(14.dp),
    ) {
        // 品牌
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 20.dp)) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(QianyanColors.AiLight, QianyanColors.AmberLight)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text("千", color = Color.White, fontSize = 17.sp, fontFamily = FontFamily.Serif)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("千言", fontFamily = FontFamily.Serif, fontSize = 20.sp, letterSpacing = 3.sp)
                Text("QIANYAN", fontSize = 9.sp, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.outline)
            }
        }

        Section.entries.forEach { s ->
            val selected = state.section == s
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (selected) Modifier.background(MaterialTheme.colorScheme.surface)
                            else Modifier,
                        )
                        .clickable { state.section = s }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = sectionIcon(s),
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(19.dp),
                    )
                    Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(s.no, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                }
                // 选中态左侧渐变竖条（原型特征）
                if (selected) {
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(start = 1.dp)
                            .width(3.dp).height(22.dp).clip(RoundedCornerShape(3.dp))
                            .background(Brush.verticalGradient(listOf(QianyanColors.AiLight, QianyanColors.AmberLight))),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部：当前 Provider + 作者
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { state.providerSettingsOpen = true }
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text("言午", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BreathingSparkle(fontSize = 12.sp)
                Text(
                    "AI Provider：${state.graph.provider.value.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "点击设置 API Key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
