package com.qianyan.app.desktop.ui.author

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qianyan.app.desktop.ui.ComingSoon
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader

/**
 * 07 作者智能（设计定稿 · 能力未接入）。
 * Author DNA / Author Core / Decision Model / Feedback Loop 的视觉与交互已在设计稿定稿；
 * 后端 P16–P19 已实现（authorPreferenceUseCases / authorCoreUseCases / authorDnaUseCases /
 * decisionModelGateway），Desktop 侧接线属后续阶段（PC-6）。
 */
@Composable
fun AuthorScreen(state: DesktopAppState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.AUTHOR)
        ComingSoon(
            title = "千言正在逐渐理解你",
            stage = "PC-1 · 页面骨架（完整接线见 PC-6）",
            lines = listOf(
                "Author Preference：你主动告诉千言的创作偏好（P16/AIL-1）",
                "Author Core：从你的选择、修改、拒绝中学习（P17/AIL-2 · 差异→维度→置信度）",
                "Author DNA：TXT 作品分析 → 语言 / 剧情 / 人物 / 世界观指纹（P18/AIL-3）",
                "Author Decision Model：理解你的创作决定，只建议、不替你决定（P19/AIL-4）",
                "反馈闭环：AI 生成 → 你修改 → 千言比较差异 → 更新作者模型（安静 · 不打扰）",
            ),
        )
    }
}