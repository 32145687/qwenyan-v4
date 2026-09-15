package com.qianyan.app.desktop.adapter

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType

/**
 * 桌面侧 Provider 组装包装（Desktop Adapter，不改共享核心）。
 *
 *  - `MOCK` → [DesktopOfflineLlmGateway]：覆盖 Planning / Writing / Critique / KnowledgeUpdate
 *    的确定性响应，使离线桌面可走通完整创作链路（qwenyan-v4 自带 MockLLMGateway 只覆盖词汇分析）。
 *  - `DEEPSEEK` / `MIMO` → 透传给 [delegate]（真实网关 + 真实凭证读取），行为与 Android 一致。
 */
class DesktopProviderAssembler(
    private val delegate: ProviderAssembler,
) : ProviderAssembler {

    override fun assemble(config: ProviderConfiguration): LLMGateway =
        if (config.provider == ProviderType.MOCK) DesktopOfflineLlmGateway()
        else delegate.assemble(config)

    override fun validate(config: ProviderConfiguration): List<String> = delegate.validate(config)
}
