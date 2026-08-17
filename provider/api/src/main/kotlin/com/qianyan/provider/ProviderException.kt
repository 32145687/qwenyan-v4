package com.qianyan.provider

/**
 * Provider / LLM 通信异常（P6 provider:api 契约）。
 *
 * 定义在契约层，供调用方(Analysis/Agent)按类型捕获并归一为 Application 错误；
 * 具体实现(Mock/DeepSeek/MiMo)只负责把底层错误翻译成这些子类型之一。
 * 严禁让原始网络/HTTP 异常直接泄漏到上层。
 */
sealed class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 请求超时。 */
    class Timeout(val detail: String, cause: Throwable? = null) :
        ProviderException("Provider timeout: $detail", cause)

    /** 触发限流 / 配额不足。 */
    class RateLimit(val detail: String) :
        ProviderException("Provider rate limit: $detail")

    /** 服务不可用 / 网关错误 / 连接失败。 */
    class ProviderUnavailable(val detail: String, cause: Throwable? = null) :
        ProviderException("Provider unavailable: $detail", cause)

    /** 响应不符合契约结构（缺字段 / 类型错误）。 */
    class InvalidResponse(val detail: String, cause: Throwable? = null) :
        ProviderException("Invalid provider response: $detail", cause)

    /** 响应文本无法按当前结构解析。 */
    class MalformedOutput(val detail: String, cause: Throwable? = null) :
        ProviderException("Malformed provider output: $detail", cause)

    /** 超出 token 上限。 */
    class TokenLimit(val detail: String) :
        ProviderException("Provider token limit: $detail")
}