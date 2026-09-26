package com.qianyan.app.desktop.ui.write

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qianyan.app.desktop.ui.ComingSoon
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.Section
import com.qianyan.app.desktop.ui.SectionHeader

/**
 * 04 正文创作（数字书房）——PC-1 页面骨架。
 *
 * 迁移说明（重要）：旧 `ui/desktop` 的 Write 页直接读取 `draftRepository.latestByChapter`
 * 并自行驱动 `workflowFacade.startChapter/advance/approve`，属于 UI → Repository + UI 自行编排业务，
 * 不符合当前架构边界（UI 只经 ApplicationContainer 暴露的 UseCase 访问能力）。
 * 因此 PC-1 **不迁移该实现**，只保留页面骨架；正式 Writer 接线在 PC-2 完成，
 * 且必须使用 PC-2 定的 seam：WriterGateway / WriterUseCases。
 */
@Composable
fun WriteScreen(state: DesktopAppState) {
    Column(Modifier.fillMaxSize()) {
        SectionHeader(Section.WRITE)
        ComingSoon(
            title = "数字书房",
            stage = "PC-1 · 页面骨架（Writer 接线见 PC-2）",
            lines = listOf(
                "章节列表 / 新建章节：chapters.listByNovel / createNextChapter（当前可在「小说规划」查看章节）",
                "AI 写作：WriterGateway（loadContext / saveContent / continueWriting / rewrite）",
                "草稿读写：WriterUseCases（经 WriterGateway，不经 DraftRepository）",
                "流程与人工门：workflowFacade（ChapterWorkflowGateway）投影为 ChapterPhase",
                "旧实现的 UI→Repository 直连与自行编排业务已移除，PC-1 不提供假按钮",
            ),
        )
    }
}