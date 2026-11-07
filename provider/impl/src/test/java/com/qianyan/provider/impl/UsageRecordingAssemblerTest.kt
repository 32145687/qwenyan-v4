package com.qianyan.provider.impl

import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderAssembler
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.ProviderType
import com.qianyan.provider.Usage
import com.qianyan.provider.UsageSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A-14 记账装饰器：成功记一条、异常不记且原样抛出。 */
class UsageRecordingAssemblerTest {

    private data class Recorded(val provider: ProviderType, val modelId: String?, val usage: Usage)

    private class RecordingSink : UsageSink {
        val records = mutableListOf<Recorded>()
        override fun record(provider: ProviderType, modelId: String?, usage: Usage) {
            records += Recorded(provider, modelId, usage)
        }
    }

    private fun gatewayReturning(response: ProviderResponse): ProviderAssembler = object : ProviderAssembler {
        override fun assemble(config: ProviderConfiguration): LLMGateway = object : LLMGateway {
            override fun chat(request: ProviderRequest): ProviderResponse = response
        }

        override fun validate(config: ProviderConfiguration): List<String> = emptyList()
    }

    private val request = ProviderRequest(
        model = com.qianyan.provider.ModelProfile("mimo-v2.6-pro"),
        messages = listOf(ChatMessage(ChatRole.USER, "ping")),
    )

    @Test
    fun `successful chat records one usage entry`() {
        val usage = Usage(promptTokens = 12, completionTokens = 34, totalTokens = 46)
        val sink = RecordingSink()
        val assembler = UsageRecordingAssembler(
            delegate = gatewayReturning(
                ProviderResponse(
                    message = ChatMessage(ChatRole.ASSISTANT, "pong"),
                    usage = usage,
                ),
            ),
            sink = sink,
            modelId = "mimo-v2.6-pro",
        )

        val response = assembler.assemble(ProviderConfiguration(ProviderType.MIMO, request.model)).chat(request)

        assertEquals("pong", response.content)
        assertEquals(1, sink.records.size)
        assertEquals(ProviderType.MIMO, sink.records[0].provider)
        assertEquals("mimo-v2.6-pro", sink.records[0].modelId)
        assertEquals(usage, sink.records[0].usage)
        assertTrue(assembler.validate(ProviderConfiguration(ProviderType.MIMO)).isEmpty(), "validate 原样委托")
    }

    @Test
    fun `failed chat propagates unchanged and records nothing`() {
        val sink = RecordingSink()
        val boom = ProviderException.ProviderUnavailable("上游 503")
        val assembler = UsageRecordingAssembler(
            delegate = object : ProviderAssembler {
                override fun assemble(config: ProviderConfiguration): LLMGateway = object : LLMGateway {
                    override fun chat(request: ProviderRequest): ProviderResponse = throw boom
                }

                override fun validate(config: ProviderConfiguration): List<String> = emptyList()
            },
            sink = sink,
            modelId = "deepseek-v4-flash",
        )

        val thrown = assertFailsWith<ProviderException.ProviderUnavailable> {
            assembler.assemble(ProviderConfiguration(ProviderType.DEEPSEEK)).chat(request)
        }
        assertEquals(boom, thrown, "异常必须原样抛出")
        assertTrue(sink.records.isEmpty(), "失败调用不得记账")
    }
}
