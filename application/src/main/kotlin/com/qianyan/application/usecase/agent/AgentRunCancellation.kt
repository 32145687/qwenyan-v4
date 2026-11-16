package com.qianyan.application.usecase.agent

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DSH-06 · 一次 Agent run 的协作式取消令牌。
 *
 * 语义：
 *  - 只在 NovelAgent **相位边界检查点**生效（不在 LLM 调用内部中断）；
 *  - `cancel()` 幂等（CAS，重复取消返回 false）；
 *  - 线程安全（UI 线程置位、执行线程检查）。
 */
class AgentRunCancellation(val runId: String = UUID.randomUUID().toString()) {

    private val flag = AtomicBoolean(false)

    /** 请求取消；首次调用返回 true，重复调用幂等返回 false。 */
    fun cancel(): Boolean = flag.compareAndSet(false, true)

    fun isCancelled(): Boolean = flag.get()

    /** 取消文案（进入失败条 / 运行摘要）。 */
    val message: String get() = "已由作者中止"
}
