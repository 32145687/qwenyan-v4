package com.qianyan.app.android.ui.chapter

/** 创作链单步操作状态（P12.1.7）：Idle / Running / Error。 */
sealed interface ChapterWritingOp {
    data object Idle : ChapterWritingOp
    data class Running(val stage: String) : ChapterWritingOp
    data class Error(val stage: String, val message: String) : ChapterWritingOp
}