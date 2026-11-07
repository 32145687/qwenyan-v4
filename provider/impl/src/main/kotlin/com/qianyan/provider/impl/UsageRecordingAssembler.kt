package com.qianyan.provider.impl

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.UsageSink

/**
 * 用量记账装饰器（A-14 装配口）：包装 delegate 的 [ProviderAssembler.assemble]，
 * 在 [LLMGateway.chat] 成功后把 [ProviderResponse.usage] 交给 [sink]。
 *
 * 语义边界：
 *  - 只有成功返回且 usage 有实际 token 才记录（全零 usage 视为厂商未上报，不计一次调用）；
 *  - chat 的异常原样抛出且不记录；
 *  - 记账是旁路：sink 自身抛错被吞掉，绝不影响聊天链路；
 *  - [validate] 原样委托，配置期行为不变。
 */
class UsageRecordingAssembler(
    private val delegate: ProviderAssembler,
    private val sink: UsageSink,
    private val modelId: String? = null,
) : ProviderAssembler {

    override fun assemble(config: ProviderConfiguration): LLMGateway {
        val gateway = delegate.assemble(config)
        val recordedModel = modelId ?: config.model?.id
        return object : LLMGateway {
            override fun chat(request: ProviderRequest): ProviderResponse {
                val response = gateway.chat(request)
                val usage = response.usage
                if (usage.promptTokens > 0 || usage.completionTokens > 0 || usage.totalTokens > 0) {
                    runCatching { sink.record(config.provider, recordedModel, usage) }
                }
                return response
            }
        }
    }

    override fun validate(config: ProviderConfiguration): List<String> = delegate.validate(config)
}
