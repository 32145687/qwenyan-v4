package com.qianyan.agent.runtime

/**
 * Agent Runtime 层类型化错误（P10）。
 *
 * 只用类型化错误；禁止 String.contains 判断错误类型。
 * 能复用既有错误就复用：Provider 错误直接用 [com.qianyan.provider.ProviderException]，
 * Tool 错误直接用 [com.qianyan.agent.tool.ToolException]；此处只定义 Agent Runtime 自身新增类型。
 */
sealed class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 超过最大执行步数，防止无限 Agent Loop。 */
    class MaxStepsExceeded(val maxSteps: Int) :
        AgentException("Agent 执行超过最大步数限制: $maxSteps")
}