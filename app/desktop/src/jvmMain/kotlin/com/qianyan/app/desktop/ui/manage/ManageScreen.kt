package com.qianyan.app.desktop.ui.manage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qianyan.app.desktop.ui.ComingSoon
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader

/**
 * 05 故事管理（观察面板）——PC-1 页面骨架。
 *
 * 迁移说明：旧 `ui/desktop` 的 Manage 页为只读面板，但读取路径是
 * `container.storyState`（StoryStateRepository）——属 UI → Repository 直连。
 * 现状：Application 层**没有** Story State 的只读 UseCase（六类实体读取只有仓储接口）。
 * 因此 PC-1 保留骨架，PC-5 先补 Application 层只读 UseCase，再接面板
 * （Narrative State / Foreshadow / Chapter Context Pack 已有 UseCase，可直接接线）。
 * 写入类能力（Story State Variant Override / Foreshadow 流转 / Reveal 创建）为 Variant-only，Original 只读。
 */
@Composable
fun ManageScreen(state: DesktopAppState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.MANAGE)
        ComingSoon(
            title = "观察这本小说变成了什么",
            stage = "PC-1 · 页面骨架（完整接线见 PC-5）",
            lines = listOf(
                "Narrative State（叙事账本）：narrativeState.getNarrativeState（已可用）",
                "Chapter Context Pack：chapterContextPack.compileChapterContext（已可用，确定性编译）",
                "Foreshadow 生命线 / Reveal 记录：foreshadowLifecycle · reveals（只读展示）",
                "Story State 六类（人物 / 世界规则 / 事件 / 时间线）：PC-5 需先在 Application 层补只读 UseCase",
                "Variant 写入工作台（Override / 流转）属 PC-5，PC-1 不做",
            ),
        )
    }
}