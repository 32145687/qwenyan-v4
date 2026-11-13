package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantScope
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-O109（O-109）· WMU 输出契约 + 剥围栏 + **≤1 次**纠正重试。
 *
 * 34 号真机证据：MiMo 6 次 WMU 中 5 次失败，errors 为 `kind 非法: STATE`（8 条历史标记）
 * 与非法 JSON（2 条）。本类锁定三件事：
 *  - 提示词必须显式给出 kind / operation 合法值，并显式禁止 STATE 等其它值；
 *  - 只有 `InvalidOutput` 才纠正重试，且最多 1 次（第二次仍非法 → 抛原类型，调用次数=2）；
 *  - Provider / 网络类失败**不重试**（调用次数=1）。
 */
class WorldModelUpdateAgentContractTest {

    private val calls = AtomicInteger()

    private fun agent(vararg bodies: String): WorldModelUpdateAgent {
        val gateway = MockLLMGateway { _ ->
            val index = calls.getAndIncrement()
            val body = bodies[minOf(index, bodies.lastIndex)]
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        return WorldModelUpdateAgent(gateway)
    }

    private fun context() = WorldModelUpdateContext(
        historyId = "h-o109",
        commitId = "c-o109",
        novelId = NovelId("novel-o109"),
        variantId = null,
        scope = VariantScope.ORIGINAL,
        chapterId = ChapterId("chapter-o109"),
        previousContent = "旧正文",
        resultingContent = "雪是她提灯走进这条巷子时落下来的，很碎。",
    )

    private fun fact(kind: String = "EVENT"): String =
        """{"facts":[{"factId":"f1","kind":"$kind","operation":"ADD","name":"雨夜提灯",""" +
            """"summary":"主角在雨夜提灯行走","excerpt":"雪是她提灯走进这条巷子时落下来的","reason":"正文"}]}"""

    /* ---------- (a) 提示词契约 ---------- */

    @Test
    fun `prompt states the output contract explicitly`() {
        val captured = StringBuilder()
        val gateway = MockLLMGateway { req ->
            req.messages.firstOrNull { it.role == ChatRole.USER }?.let { captured.append(it.content) }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", fact()) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        WorldModelUpdateAgent(gateway).propose(context(), emptyList())

        val prompt = captured.toString()
        assertTrue("kind 只能是 EVENT" in prompt, "提示词必须显式声明 kind 只能是 EVENT，实际=$prompt")
        assertTrue("禁止输出 STATE" in prompt, "提示词必须显式禁止 STATE（34 号真机失败根因）")
        assertTrue(
            "operation 只能是 ADD / UPDATE / REMOVE" in prompt,
            "提示词必须显式枚举 operation 合法值",
        )
        assertTrue("只输出一个 JSON 对象" in prompt, "提示词必须要求只输出 JSON 对象")
        assertTrue("Markdown 代码围栏" in prompt, "提示词必须要求不要 Markdown 围栏")
        assertTrue("excerpt 必须是提交后正文的逐字子串" in prompt, "提示词必须保留 excerpt 逐字约束")
    }

    /* ---------- (c) 纠正重试成功 ---------- */

    @Test
    fun `invalid kind is corrected once and succeeds on retry`() {
        val agent = agent(fact(kind = "STATE"), fact())
        calls.set(0)

        val facts = agent.propose(context(), emptyList())

        assertEquals(1, facts.size)
        assertEquals("EVENT", facts.single().kind.name, "纠正后必须得到合法 kind")
        assertEquals(2, calls.get(), "解析失败必须恰好纠正重试 1 次（共 2 次调用）")
    }

    /* ---------- (d) 只重试一次 ---------- */

    @Test
    fun `retry happens at most once`() {
        val agent = agent(fact(kind = "STATE"), fact(kind = "STATE"))
        calls.set(0)

        val error = assertFailsWith<WorldModelUpdateException.InvalidOutput> {
            agent.propose(context(), emptyList())
        }

        assertTrue(
            "第二次" in (error.message ?: ""),
            "最终错误必须带上两次摘要，实际=${error.message}",
        )
        assertEquals(2, calls.get(), "不得无限重试：严格 ≤1 次纠正（总调用 2 次）")
    }

    /* ---------- (e) 非解析失败不重试 ---------- */

    @Test
    fun `provider failure is not retried`() {
        val gateway = MockLLMGateway { _ ->
            calls.incrementAndGet()
            throw ProviderException.Timeout("provider timeout")
        }
        calls.set(0)

        assertFailsWith<WorldModelUpdateException.Failed> {
            WorldModelUpdateAgent(gateway).propose(context(), emptyList())
        }

        assertEquals(1, calls.get(), "Provider 类失败不重试（调用次数必须为 1）")
    }
}
