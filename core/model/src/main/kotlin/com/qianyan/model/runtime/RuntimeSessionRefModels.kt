package com.qianyan.model.runtime

import com.qianyan.model.AgentSessionId
import com.qianyan.model.RuntimeSessionRefId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I1 Runtime Integration · Runtime Session 绑定（vendor-neutral）。
 *
 * 定位：记录"某一次 Qianyan AgentSession 曾/正在使用哪一次外部 Agent Runtime 会话"，
 * 用于跨进程重启后把 Qianyan 会话身份解析回 Runtime 会话（resume / 审计）。
 *
 * 硬边界：
 *  - **厂商中立**：本文件不出现任何具体运行时实现概念（DSH / ACP / JSON-RPC / dshSessionId）；
 *    [RuntimeSessionRef.runtimeName] 是**不透明标识**（取值由 Adapter 提供，Domain 不认识其含义）；
 *  - **不是一对一**：一个 AgentSession 可以先后绑定多次 Runtime Session（`1 : N`），
 *    因为 Runtime 会话是进程/连接作用域的，而 AgentSession 是跨天跨周持续的工作身份；
 *  - **不承载**：正文 / World Model / Project State / Workflow·Task 状态 / Runtime transcript
 *    （DSH 原始 session 记录继续由对端自管，不进入 Qianyan）；
 *  - **不改 AgentSession**：绑定是**旁路引用**，不给 AgentSession 增加任何运行时字段。
 */

/**
 * 一条 Runtime Session 绑定记录。
 *
 * @param agentSessionId Qianyan 会话身份（1 : N 的"1"侧；外键引用既有 AgentSession）。
 * @param runtimeName 运行时**不透明**标识（如某个具体 Runtime 的名称；Domain 不解释其含义）。
 * @param runtimeSessionId 对端 Runtime 会话 id（对 Domain 同样是不透明字符串）。
 * @param createdAt 绑定建立时间（epoch 毫秒，与存储精度一致）。
 */
@Serializable
data class RuntimeSessionRef(
    val refId: RuntimeSessionRefId,
    val agentSessionId: AgentSessionId,
    val runtimeName: String,
    val runtimeSessionId: String,
    val createdAt: Instant,
)