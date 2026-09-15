package com.qianyan.app.desktop.ui.story

import androidx.compose.runtime.Composable
import com.qianyan.app.desktop.ui.ComingSoon
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 02 故事创作（P14+ · 后端规划中）。
 * 视觉与交互已在 HTML 原型定稿（一句话想法 → AI 理解 → 类型谱系 → 故事方向 → 叙事画像 →
 * Story Foundation）。后端：Idea Intelligence / Story Direction 为 PLANNED（GenreTaxonomy seam 已就绪）。
 * 按约束：不伪造后端，UI 保留设计并标注开发中。
 */
@Composable
fun StoryScreen(state: DesktopAppState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.STORY)
        ComingSoon(
            title = "故事正在诞生",
            stage = "P14 Story Intent / Idea Intelligence · 规划中",
            lines = listOf(
                "一句话想法 → AI 理解（类型 / 主题 / 主角 / 冲突 / 长篇潜力）",
                "受控题材目录（Genre Taxonomy seam 已在后端就绪，本地已实现）",
                "故事方向 A/B/C：AI 提案，你决策（查看 / 选择 / 修改 / 删除 / 重新生成）",
                "叙事画像与写作政策 → Story Foundation 确认后进入规划",
                "交互全流程已在 HTML 设计稿中定稿，将由 P14/P15 按契约实现",
            ),
        )
    }
}
