package com.qianyan.provider.impl

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.RetryConfig

/**
 * 有界重试网关（L-2）。
 *
 * 只重试 [ProviderException.Timeout] / [ProviderException.RateLimit] / [ProviderException.ProviderUnavailable]；
 * [ProviderException.TokenLimit] / [ProviderException.CredentialMissing] /
 * [ProviderException.AuthenticationFailed] / [ProviderException.InvalidResponse] /
 * [ProviderException.MalformedOutput] 一律不重试
 * （同一请求重试无意义，或属配置 / 契约问题）。
 *
 * 总尝试次数 = `1 + retry.maxRetries`；退避采用**线性**：第 n 次重试前等待
 * `backoffMillis × n`（默认 500ms、1000ms），实现简单、可预测、便于测试断言。
 * 线程被中断时立即停止；最后一次异常原类型抛出（不吞、不改类型）。
 *
 * chat 是纯读取（不写库、不持久化），因此重试不会重复业务副作用。
 */
class RetryingGateway(
    private val delegate: LLMGateway,
    private val retry: RetryConfig = RetryConfig(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : LLMGateway {

    override fun chat(request: ProviderRequest): ProviderResponse {
        var attempt = 0
        while (true) {
            try {
                return delegate.chat(request)
            } catch (e: ProviderException) {
                if (!isRetryable(e) || attempt >= retry.maxRetries || Thread.currentThread().isInterrupted) throw e
                attempt++
                try {
                    sleep(retry.backoffMillis * attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    private fun isRetryable(e: ProviderException): Boolean = when (e) {
        is ProviderException.Timeout,
        is ProviderException.RateLimit,
        is ProviderException.ProviderUnavailable,
        -> true

        is ProviderException.TokenLimit,
        is ProviderException.CredentialMissing,
        is ProviderException.AuthenticationFailed,
        is ProviderException.InvalidResponse,
        is ProviderException.MalformedOutput,
        -> false
    }
}

/** 把 [RetryingGateway] 插到装配口（对外仍是 [ProviderAssembler]，validate 原样委托）。 */
class RetryingAssembler(
    private val delegate: ProviderAssembler,
    private val retry: RetryConfig = RetryConfig(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : ProviderAssembler {

    override fun assemble(config: ProviderConfiguration): LLMGateway =
        RetryingGateway(delegate.assemble(config), retry, sleep)

    override fun validate(config: ProviderConfiguration): List<String> = delegate.validate(config)
}
