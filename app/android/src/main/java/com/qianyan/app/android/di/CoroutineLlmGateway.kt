package com.qianyan.app.android.di

import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P12.5-M03 · 真实 LLM 调用的协程适配器。
 *
 * 把阻塞式 JVM [LLMGateway]（DeepSeek / MiMo 的同步 HTTP）桥接到协程：
 *  - 提供 `suspend fun chat(...)`，在 [Dispatchers.IO] 上执行真实网络调用，调用方可随所在协程作用域取消、
 *    且不阻塞主线程；
 *  - **不触碰 :application / :agent runtime**：内核仍调用阻塞式 [LLMGateway]；本适配器仅作为 Android
 *    侧「真实 AI 调用缝」使用（例如设置页"测试连接"、后续真实 Provider 接线）。
 */
class CoroutineLlmGateway(private val delegate: LLMGateway) {

    /** 以协程方式执行一次真实补全（阻塞网关隔离到 [Dispatchers.IO]，可取消）。 */
    suspend fun chat(request: ProviderRequest): ProviderResponse =
        withContext(Dispatchers.IO) { delegate.chat(request) }

    companion object {
        fun wrap(delegate: LLMGateway): CoroutineLlmGateway = CoroutineLlmGateway(delegate)
    }
}