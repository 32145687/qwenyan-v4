package com.qianyan.application.usecase.context

import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.context.ContextPack
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A2 · 任务实际使用的 ContextPack 的进程内只读快照存储。
 *
 * 边界（Phase 4B.1 D-2 / D-3）：
 *  - 只保存在内存中；进程重启后不可获取，由 Inspector 如实显示 `PACK_UNAVAILABLE`；
 *  - 按 `sessionId` 索引，并校验 `projectId`，避免跨 Project 读取；
 *  - 有界（插入顺序淘汰最旧条目），不持久化、不新增 Schema/Migration；
 *  - 保存的是实际构建并被任务使用的 [ContextPack] 快照，不是重新构建的相似 Pack。
 */
data class StoredContextPack(
    val projectId: ProjectId,
    val sessionId: AgentSessionId,
    val taskId: TaskId?,
    val pack: ContextPack,
    val publishedAt: Instant,
)

interface ContextPackStore {

    /** 发布一次任务实际构建的 Pack；同一 session 重复发布时覆盖旧快照。 */
    fun put(
        sessionId: AgentSessionId,
        projectId: ProjectId,
        pack: ContextPack,
        taskId: TaskId? = null,
    )

    /** 按 session 读取；Project 不一致时按不存在处理（不泄漏其他 Project 的存在性）。 */
    fun find(sessionId: AgentSessionId, projectId: ProjectId): StoredContextPack?

    val size: Int
}

/**
 * 进程内有界实现。
 *
 * 淘汰规则：插入顺序，超过上限时移除最旧条目。读取不改变顺序，保证行为可解释。
 * 线程安全：方法级同步；任务执行与 Inspector 读取可能来自不同线程。
 */
class InMemoryContextPackStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: Clock = Clock.System,
) : ContextPackStore {

    init {
        require(maxEntries > 0) { "maxEntries 必须为正: $maxEntries" }
    }

    private val entries = LinkedHashMap<AgentSessionId, StoredContextPack>()

    @Synchronized
    override fun put(
        sessionId: AgentSessionId,
        projectId: ProjectId,
        pack: ContextPack,
        taskId: TaskId?,
    ) {
        require(pack.request.projectId == projectId) {
            "Pack 的 Project 与存储键不一致: pack=${pack.request.projectId.value}, key=${projectId.value}"
        }
        entries.remove(sessionId)
        while (entries.size >= maxEntries) {
            val oldest = entries.entries.firstOrNull() ?: break
            entries.remove(oldest.key)
        }
        entries[sessionId] = StoredContextPack(
            projectId = projectId,
            sessionId = sessionId,
            taskId = taskId,
            pack = pack,
            publishedAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
        )
    }

    @Synchronized
    override fun find(sessionId: AgentSessionId, projectId: ProjectId): StoredContextPack? =
        entries[sessionId]?.takeIf { it.projectId == projectId }

    override val size: Int
        get() = synchronized(this) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 32
    }
}
