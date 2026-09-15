package com.qianyan.app.desktop.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyHint
import com.qianyan.app.desktop.ui.QianyanCard
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import com.qianyan.app.desktop.ui.StatusTag
import com.qianyan.app.desktop.ui.statusColor
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter

/**
 * 03 小说规划（总览视图）。
 * 真实能力：chapters.listByNovel + workflowFacade.getChapterProgress（章节生命周期投影）。
 * 说明：章节规划（ChapterPlan）由创作流程在 PLANNING 阶段真实产出并持久化；
 * 独立的"规划画布 / 剧情网络 / 三层时间"可视化属后续阶段（原型已有设计）。
 */
@Composable
fun PlanScreen(state: DesktopAppState) {
    val novel: Novel? = state.novel
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.PLAN)
        if (novel == null) {
            EmptyHint("尚未打开作品——回到「首页」打开一本书后，这里会显示它的章节规划与流程状态。")
            return
        }

        var chapters by remember(novel.novelId, state.refreshKey) { mutableStateOf<List<Chapter>>(emptyList()) }
        var phases by remember(novel.novelId, state.refreshKey) { mutableStateOf<Map<String, Pair<String, Boolean>>>(emptyMap()) }

        LaunchedEffect(novel.novelId, state.refreshKey) {
            val loaded = state.load { c ->
                val chs = c.chapters.listByNovel(novel.novelId)
                val facade = c.workflowFacade
                chs to chs.associate {
                    val p = facade.getChapterProgress(it.chapterId)
                    it.chapterId.value to (p.phase.name to p.waitingForUser)
                }
            }
            chapters = loaded.first
            phases = loaded.second
        }

        QianyanCard(Modifier.padding(horizontal = 34.dp)) {
            Text("《${novel.title}》· 规划总览", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            Text(
                "每章的规划（ChapterPlan）在创作流程的 PLANNING 阶段真实生成并落库；此处显示生命周期投影。" +
                    "章节级可视化规划画布（结构树 / 剧情网络 / 三层时间）为后续阶段交付。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 34.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chapters.isEmpty()) item { EmptyHint("还没有章节——到「正文创作」新建第一章。") }
            items(chapters.sortedBy { it.order }) { ch ->
                val phase = phases[ch.chapterId.value]?.first ?: "NOT_STARTED"
                val waiting = phases[ch.chapterId.value]?.second ?: false
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column {
                        Text(ch.title.ifBlank { "第 ${ch.order} 章" }, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                        Text("第 ${ch.order} 章 · ${ch.status.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (waiting) StatusTag("等待审批", com.qianyan.app.desktop.ui.theme.QianyanColors.WatchLight)
                        StatusTag(phase, statusColor(phase))
                    }
                }
            }
        }
    }
}
