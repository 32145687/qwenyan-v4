package com.qianyan.app.desktop.ui.story

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qianyan.app.desktop.ui.ComingSoon
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader

/**
 * 02 故事创作（设计定稿 · 能力未接入）。
 * 视觉与交互已在设计稿定稿（一句话想法 → AI 理解 → 类型谱系 → 故事方向 → 叙事画像 → Story Foundation）。
 * 后端：Story Intent / Idea Intelligence / Story Foundation 决策链已存在（P14-F / P15），
 * 但 Desktop 侧接线属后续阶段（PC-4）。按约束：不伪造后端、不放假按钮。
 */
@Composable
fun StoryScreen(state: DesktopAppState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.STORY)
        ComingSoon(
            title = "故事正在诞生",
            stage = "PC-1 · 页面骨架（完整接线见 PC-4）",
            lines = listOf(
                "一句话想法 → AI 理解（类型 / 主题 / 主角 / 冲突 / 长篇潜力）",
                "受控题材目录（genres.availableGenres · 已可用）",
                "故事方向 A/B/C：AI 提案，你决策（查看 / 选择 / 修改 / 删除 / 重新生成）",
                "叙事画像与写作政策 → Story Foundation 确认后进入规划",
                "届时经 ApplicationContainer 的 storyIntentUseCases / foundationDecisions 接线，不自建业务逻辑",
            ),
        )
    }
}