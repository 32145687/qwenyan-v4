package com.qianyan.app.android.ui.novel

import com.qianyan.model.core.Novel

/**
 * 小说列表 UI 状态（P7.4）。
 *
 * 显式四态：加载中 / 成功 / 空 / 错误，避免用隐式 null 或魔法值表达状态。
 */
sealed interface NovelListUiState {

    /** 首次加载中。 */
    data object Loading : NovelListUiState

    /** 已加载到至少一本 Original。 */
    data class Success(val novels: List<Novel>) : NovelListUiState

    /** 已加载但无任何 Original。 */
    data object Empty : NovelListUiState

    /** 加载失败（UI 展示友好文案，不泄露异常细节）。 */
    data object Error : NovelListUiState
}
