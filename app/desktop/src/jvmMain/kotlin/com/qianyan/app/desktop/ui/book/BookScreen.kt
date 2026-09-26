package com.qianyan.app.desktop.ui.book

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qianyan.app.desktop.ui.ComingSoon
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader

/**
 * 06 成书（阅读空间）——PC-1 页面骨架。
 *
 * 迁移说明：旧 `ui/desktop` 的 Book 页直接读 `container.draftRepository.latestByChapter`
 * （UI → Repository 直连）。P20-P4 已交付 ReadingUseCases（openChapter / savePosition / position）
 * 与 ReadingProgress 阅读位置；PC-3 将以此正式接入 Desktop Reader
 * （ReadingUseCases + ReadingProgress + 受控 Markdown 渲染）。
 * 导出 / 章节批量管理 / 作品删除属 PC-7，PC-1 不做。
 */
@Composable
fun BookScreen(state: DesktopAppState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.BOOK)
        ComingSoon(
            title = "一本正在完成的书",
            stage = "PC-1 · 页面骨架（Reader 接线见 PC-3）",
            lines = listOf(
                "章节阅读：ReadingUseCases.openChapter（P20-P4 已交付的 Reader seam）",
                "阅读位置：ReadingProgress + readingProgressRepository（schema v17 的真实表）",
                "正文渲染：受控 Markdown v1（P20-P2 冻结契约）",
                "导出 / 章节批量管理 / 作品删除：属 PC-7，PC-1 不提供入口",
                "旧实现的 UI→DraftRepository 直连已移除，PC-1 不提供假按钮",
            ),
        )
    }
}