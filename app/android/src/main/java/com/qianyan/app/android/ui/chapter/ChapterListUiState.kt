package com.qianyan.app.android.ui.chapter

import com.qianyan.model.story.Chapter

/**
 * 章节列表 UI 状态（P12.1.6）：Loading / Success / Error。
 */
sealed interface ChapterListUiState {

    /** 首次加载中。 */
    data object Loading : ChapterListUiState

    /** 已加载真实章节（按 order ASC，来自 Application Use Case / Storage）。 */
    data class Success(val chapters: List<Chapter>) : ChapterListUiState

    /** 加载失败（用户可读文案，不泄露底层细节）。 */
    data class Error(val message: String) : ChapterListUiState
}

/** 新建章节操作状态（P12.1.6）。Creation 成功经 [ChapterViewModel.created] 单次下发导航。 */
sealed interface ChapterCreateState {

    /** 无进行中的创建。 */
    data object Idle : ChapterCreateState

    /** 正在创建（一次创建期间 UI 禁用「新建」，防止重复点击产生重复状态）。 */
    data object Creating : ChapterCreateState

    /** 创建失败（用户可读文案）。 */
    data class Error(val message: String) : ChapterCreateState
}