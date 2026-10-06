package com.qianyan.storage.repository

import com.qianyan.model.AgentSessionId
import com.qianyan.model.RuntimeSessionRefId
import com.qianyan.model.runtime.RuntimeSessionRef

/**
 * Runtime Session 绑定持久化仓储（I1）。
 *
 * 只承担"Qianyan AgentSession ↔ 外部 Runtime 会话"绑定记录的读写（`1 : N`）。
 *
 * 明确不做：
 *  - 不修改 / 不复制 AgentSession 的生命周期（绑定是旁路引用）；
 *  - 不存 Runtime transcript / 用量统计 / analytics（DSH 原始记录由对端自管）；
 *  - 不认识任何具体运行时实现（runtimeName 是不透明标识）。
 */
interface RuntimeSessionRefRepository {

    /** 创建/覆盖一条绑定（同 refId 覆盖，不产生第二行）。 */
    fun save(ref: RuntimeSessionRef)

    /** 读取绑定；不存在返回 null（不伪造）。 */
    fun get(refId: RuntimeSessionRefId): RuntimeSessionRef?

    /** 某会话的全部绑定（新到旧）。 */
    fun listByAgentSession(agentSessionId: AgentSessionId): List<RuntimeSessionRef>

    /** 某会话最近一次绑定；无则返回 null。 */
    fun latestByAgentSession(agentSessionId: AgentSessionId): RuntimeSessionRef?
}