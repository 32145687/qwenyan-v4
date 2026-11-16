package com.qianyan.application.usecase.session

import com.qianyan.application.usecase.dsh.DshRuntime
import com.qianyan.application.usecase.dsh.InMemoryDshRuntime
import com.qianyan.application.usecase.dsh.RuntimeEvent
import com.qianyan.application.usecase.dsh.RuntimeEventKind
import com.qianyan.application.usecase.dsh.RuntimeHandle
import com.qianyan.application.usecase.dsh.RuntimeSession
import com.qianyan.application.usecase.dsh.RuntimeTask
import com.qianyan.model.AgentSessionId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.session.AgentSessionStatus
import java.util.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * DSH-05 · 业务侧 Runtime 适配器：把 DSH-04 契约落到 [DshSessionStore]（既有 AgentSession 表）。
 *
 * 放在 `usecase/session/`（业务映射层）而非 `usecase/dsh/`：构造需要 ProjectId/NovelId，
 * 而契约包必须零小说概念（DSH-04 源码扫描守卫）。业务映射全部留在本包。
 *
 * 语义：
 *  - `openSession(ref)` → `store.openOrCreate`（复用或新建）→ `activate`（CREATED → ACTIVE）；
 *  - dispatch 返回的句柄在事件流**消费完毕**时按最后事件映射终态
 *    （FAILED→FAILED、CANCELLED→CANCELLED、其余→COMPLETED），保证「库状态 = 最后事件」；
 *  - `cancel()` 置位并立即落 CANCELLED；源事件序列在下一次拉取时截断并以 CANCELLED 结束；
 *  - 事件通道保持 Sequence（与 DSH-04 契约一致，不引 Flow）。
 *
 * @param eventSource 事件源（默认委托 InMemoryDshRuntime，便于本单元独立测试；真实事件源由后续单元接入）。
 */
class AgentSessionDshRuntime(
    private val store: DshSessionStore,
    private val projectId: ProjectId,
    private val novelId: NovelId,
    private val workflowRef: String? = null,
    private val now: () -> Instant = { Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()) },
    private val eventSource: (String) -> Sequence<RuntimeEvent> = { ref ->
        InMemoryDshRuntime().openSession(ref).dispatch(DEFAULT_TASK).events()
    },
) : DshRuntime {

    override fun openSession(workflowRef: String): RuntimeSession = Session(workflowRef)

    private inner class Session(private val ref: String) : RuntimeSession {
        override val sessionId: String = store
            .openOrCreate(projectId, novelId, workflowRef, now())
            .sessionId.value

        override fun dispatch(task: RuntimeTask): RuntimeHandle {
            val session = store.openOrCreate(projectId, novelId, workflowRef, now())
            store.activate(session.sessionId, now())
            return Handle(
                sessionId = session.sessionId,
                source = eventSource(session.sessionId.value),
            )
        }
    }

    private inner class Handle(
        private val sessionId: AgentSessionId,
        private val source: Sequence<RuntimeEvent>,
    ) : RuntimeHandle {

        @Volatile
        private var cancelled: Boolean = false

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            store.finish(sessionId, AgentSessionStatus.CANCELLED, now())
        }

        override fun events(): Sequence<RuntimeEvent> = sequence {
            var last: RuntimeEvent? = null
            for (event in source) {
                if (cancelled) break
                last = event
                yield(event)
            }
            if (cancelled && last?.kind != RuntimeEventKind.CANCELLED) {
                val cancelledEvent = RuntimeEvent(
                    kind = RuntimeEventKind.CANCELLED,
                    name = "session",
                    detailSummary = "任务已取消",
                    ok = false,
                )
                yield(cancelledEvent)
                last = cancelledEvent
            }
            // 终态映射（与最后事件一致；空流按「其余」→ COMPLETED）
            val terminal = when (last?.kind) {
                RuntimeEventKind.FAILED -> AgentSessionStatus.FAILED
                RuntimeEventKind.CANCELLED -> AgentSessionStatus.CANCELLED
                else -> AgentSessionStatus.COMPLETED
            }
            store.finish(sessionId, terminal, now())
        }
    }

    private companion object {
        val DEFAULT_TASK = RuntimeTask(
            input = "",
            allowedTools = emptyList(),
            maxRounds = 1,
            budgetMillis = 0L,
        )
    }
}
