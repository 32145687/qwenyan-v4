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

    /**
     * DSH-07 · 流式补全（增量顺序拉取；拼接结果等价于全文）。
     *
     * 默认实现 = 经 [chat] 一次取全文并作为**单个增量**返回——非流式网关零改动即兼容，
     * 行为与 chat 完全一致（不伪造分块）。真实分块流式（SSE）由各网关按需覆盖（DSH-07R）。
     * 中途失败抛 [ProviderException] 具体子类；**已拉取的增量不回滚**（调用方保留已收内容）。
     */
    fun stream(request: ProviderRequest): Sequence<String> =
        sequence { yield(chat(request).message.content) }
}