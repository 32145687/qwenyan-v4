package com.qianyan.app.android.ui.provider

import com.qianyan.provider.ProviderType

/** 提示消息级别（决定颜色）。 */
enum class MessageLevel { INFO, SUCCESS, ERROR }

/**
 * P12.5-M01 Provider 设置页 UI 状态。
 *
 * 仅承载用户可读设置项，不做任何 Agent / Workflow / LLM 调用；AI 调用只来自 ViewModel 的"测试连接"。
 */
data class ProviderSettingsUiState(
    val selectedProvider: ProviderType = ProviderType.MOCK,
    /** 输入框中的 API Key 明文（UI 掩码显示；不与已存 Key 绑定展示）。 */
    val apiKeyInput: String = "",
    /** 当前选中 Provider 是否已配置 Key。 */
    val hasKey: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null,
    val messageLevel: MessageLevel? = null,
)