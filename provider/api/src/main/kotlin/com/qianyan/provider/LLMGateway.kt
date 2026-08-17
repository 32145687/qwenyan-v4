package com.qianyan.provider

/**
 * LLM 网关接口（P6 provider:api 契约）。
 *
 * 职责边界：
 *  - 只负责把 [ProviderRequest] 交给某个模型实现并返回 [ProviderResponse]；
 *  - 不解析为领域结构、不写库、不创建 Novel/Variant、不修改 Domain；
 *  - 实现（Mock/DeepSeek/MiMo）在 :provider:impl，调用方只依赖本接口。
 *
 * 调用方（Analysis UseCase / 最小 Agent）经此接口向 AI 取文本；由调用方负责任何 AI
 * 输出的结构化解析与校验（Provider 不"理解"小说领域）。
 */
interface LLMGateway {

    /** 执行一次补全。实现负责超时/重试语义；失败抛 [ProviderException] 具体子类。 */
    fun chat(request: ProviderRequest): ProviderResponse
}