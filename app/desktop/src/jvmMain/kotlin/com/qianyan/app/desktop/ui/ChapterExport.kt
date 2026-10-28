package com.qianyan.app.desktop.ui

import com.qianyan.app.desktop.adapter.DesktopFilePicker
import com.qianyan.model.story.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出当前章节为 TXT（桌面文件选择器 + UTF-8 写入）。
 *
 * 导出的是**编辑器里当前内容**（可能尚未保存），只做文件 I/O，不写入 Canonical、不触发提交。
 */
fun DesktopAppState.exportChapterToTxt(chapter: Chapter, content: String) {
    if (busy) {
        toast("任务进行中，请稍后再导出")
        return
    }
    if (content.isBlank()) {
        toast("本章还没有正文，暂无可导出的内容")
        return
    }
    val title = chapterTitleOnly(chapter.title).ifBlank { "未命名" }
    val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
    scope.launch {
        try {
            val file = DesktopFilePicker.saveTxt("第${chapter.order}章 ${safeTitle}.txt") ?: return@launch
            val body = buildString {
                append("第 ${chapter.order} 章  $title\n\n")
                append(content)
                if (!content.endsWith("\n")) append('\n')
            }
            withContext(Dispatchers.IO) { file.writeText(body, Charsets.UTF_8) }
            toast("已导出到 ${file.name}")
        } catch (e: Exception) {
            toast("导出失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
