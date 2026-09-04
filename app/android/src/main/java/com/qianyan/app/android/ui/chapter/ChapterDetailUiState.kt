package com.qianyan.app.android.ui.chapter

import com.qianyan.model.story.Chapter

/** 章节详情 UI 状态（P12.1.6）：Loading / Content / Error。 */
sealed interface ChapterDetailUiState {
    data object Loading : ChapterDetailUiState
    data class Content(val chapter: Chapter) : ChapterDetailUiState
    data class Error(val message: String) : ChapterDetailUiState
}