package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage

/**
 * Mock LLM 网关（P6 最小 Analysis 全链路实现）。
 *
 * 特性：
 *  - **确定性**：对相同请求返回相同文本/用量，支持可复现测试；
 *  - **注入**：[responseFor] 允许测试注入自定义响应（含抛 [com.qianyan.provider.ProviderException]
 *    以便覆盖 Provider failure），也可用于后续替换真实实现；
 *  - 不访问网络、不写库、不修改任何 Domain。返回文本由调用方（Application Analysis）解析。
 *
 * 默认返回一份稳定的"词汇候选"JSON，供 Analysis UseCase 验证全链路：
 * ```
 * {"vocabulary":[{"canonical":"灵石","type":"WORLD_TERM","aliases":[]},
 *                {"canonical":"丹田","type":"REALM","aliases":["气海"]}]}
 * ```
 */
class MockLLMGateway(
    private val responseFor: (ProviderRequest) -> ProviderResponse = MockLLMGateway::deterministicResponse,
) : LLMGateway {

    override fun chat(request: ProviderRequest): ProviderResponse = responseFor(request)

    companion object {

        /** 对相同请求返回确定不变的结果（含确认性 usage 计数）。 */
        private fun deterministicResponse(request: ProviderRequest): ProviderResponse {
            val promptTokens = request.messages.sumOf { it.content.length } / 4 + request.messages.size
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, MockOutput.DEFAULT_VOCABULARY_JSON),
                usage = Usage(promptTokens = promptTokens, completionTokens = 32, totalTokens = promptTokens + 32),
                finishReason = FinishReason.STOP,
            )
        }
    }
}

/** Mock 响应的稳定常量（供调用方对齐解析契约；仅 @Serializable 的纯数据）。 */
internal object MockOutput {
    const val DEFAULT_VOCABULARY_JSON: String =
        "{\"vocabulary\":[" +
            "{\"canonical\":\"灵石\",\"type\":\"WORLD_TERM\",\"aliases\":[]}," +
            "{\"canonical\":\"丹田\",\"type\":\"REALM\",\"aliases\":[\"气海\"]}" +
            "]}"
}