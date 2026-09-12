package com.qianyan.app.android.ui.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qianyan.app.android.QianyanApplication
import com.qianyan.app.android.di.CoroutineLlmGateway
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Provider 设置 ViewModel（P12.5-M01）。
 *
 * 职责：给用户层"选择 Provider + 配置 API Key + 测试连接"；所有 Key 读写委托给
 * [QianyanApplication]（内部走 Android Keystore 安全存储），不直接触碰 Repository / Agent / LLMGateway。
 * 真实 LLM 调用（测试连接）经 [CoroutineLlmGateway]（P12.5-M03 协程适配器）在协程中执行。
 */
class ProviderSettingsViewModel(
    private val application: QianyanApplication,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        ProviderSettingsUiState(selectedProvider = application.provider.value),
    )
    val ui: StateFlow<ProviderSettingsUiState> = _ui.asStateFlow()

    init {
        refreshHasKey()
    }

    /** 切换 Provider（真实 Provider 需已配置 Key）。 */
    fun selectProvider(provider: ProviderType) {
        val ok = application.setProvider(provider)
        _ui.value = _ui.value.copy(
            selectedProvider = application.provider.value,
            apiKeyInput = "",
            message = if (ok) null else "该服务尚未配置 API Key，请先保存密钥后方可使用",
            messageLevel = if (ok) null else MessageLevel.ERROR,
        )
        if (ok) refreshHasKey()
    }

    /** 输入框内容变化（仅存输入，不持久化）。 */
    fun onApiKeyChanged(input: String) {
        _ui.value = _ui.value.copy(apiKeyInput = input)
    }

    /** 保存当前选中 Provider 的 API Key（Keystore 加密写入，非网络操作）。 */
    fun saveKey() {
        val key = _ui.value.apiKeyInput.trim()
        val provider = application.provider.value
        if (key.isEmpty()) {
            _ui.value = _ui.value.copy(message = "API Key 不能为空", messageLevel = MessageLevel.ERROR)
            return
        }
        _ui.value = _ui.value.copy(saving = true, message = null)
        application.saveApiKey(provider, key)
        _ui.value = _ui.value.copy(
            saving = false,
            apiKeyInput = "",
            message = "已保存（Android Keystore 加密）",
            messageLevel = MessageLevel.SUCCESS,
        )
        refreshHasKey()
    }

    /** 清除当前选中 Provider 的 API Key。 */
    fun clearKey() {
        application.credentialStore.clearApiKey(application.provider.value)
        refreshHasKey()
        _ui.value = _ui.value.copy(apiKeyInput = "", message = "已清除", messageLevel = MessageLevel.SUCCESS)
    }

    /** 测试连接：以协程方式调用真实 Provider（Mock / 缺 Key 时给出提示，不做真实网络）。 */
    fun testConnection() {
        val provider = application.provider.value
        if (provider == ProviderType.MOCK) {
            _ui.value = _ui.value.copy(message = "Mock 为本地模拟，无需测试", messageLevel = MessageLevel.INFO)
            return
        }
        if (application.credentialStore.getApiKey(provider).isNullOrBlank()) {
            _ui.value = _ui.value.copy(message = "请先保存 API Key 再测试", messageLevel = MessageLevel.ERROR)
            return
        }
        _ui.value = _ui.value.copy(testing = true, message = null)
        viewModelScope.launch {
            try {
                val gateway = CoroutineLlmGateway.wrap(application.assembleGateway(provider))
                val model = if (provider == ProviderType.DEEPSEEK) ModelProfile.DEEPSEEK_V4_FLASH else ModelProfile.MIMO_V2_5
                val request = ProviderRequest(
                    model = model,
                    messages = listOf(ChatMessage(ChatRole.USER, "ping")),
                    temperature = 0.0,
                )
                gateway.chat(request)
                _ui.value = _ui.value.copy(testing = false, message = "连接成功", messageLevel = MessageLevel.SUCCESS)
            } catch (e: ProviderException) {
                _ui.value = _ui.value.copy(testing = false, message = "连接失败：${e.message}", messageLevel = MessageLevel.ERROR)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(testing = false, message = "连接失败：${e.message ?: "未知错误"}", messageLevel = MessageLevel.ERROR)
            }
        }
    }

    private fun refreshHasKey() {
        val provider = application.provider.value
        _ui.value = _ui.value.copy(
            hasKey = application.credentialStore.getApiKey(provider) != null,
        )
    }

    companion object {
        fun factory(application: QianyanApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProviderSettingsViewModel(application) as T
            }
    }
}