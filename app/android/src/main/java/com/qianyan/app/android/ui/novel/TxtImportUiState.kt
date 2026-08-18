package com.qianyan.app.android.ui.novel

/**
 * TXT 导入 UI 状态（P7.5）。
 *
 * 显式四态：空闲 / 导入中 / 成功 / 错误。Success 区分重复导入（isDuplicate），
 * 供 UI 提示"内容已存在"而非重复入库（去重由 Application 层 contentHash 保证）。
 */
sealed interface TxtImportUiState {

    /** 无导入进行中，也无需要展示的结果。 */
    data object Idle : TxtImportUiState

    /** 正在解析 + 写库。 */
    data object Loading : TxtImportUiState

    /** 导入成功。title 为展示用书名（已去掉 .txt 后缀）。 */
    data class Success(val title: String, val isDuplicate: Boolean) : TxtImportUiState

    /** 导入失败，message 为用户可读文案（不泄露底层技术细节）。 */
    data class Error(val message: String) : TxtImportUiState
}
