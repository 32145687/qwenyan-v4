package com.qianyan.application.usecase.dsh

/**
 * DSH-04 · 内存假件：可编排事件、可取消、可失败、可登记权限请求。
 *
 * 只用于契约测试与后续适配器对照，**不接生产路径**、不进 [com.qianyan.application.di.ApplicationContainer]。
 * 线程安全不做保证；同一实例的事件回放可重复（取消/应答状态除外）。
 *
 * @param script 事件编排（默认脚本含两轮工具调用）。
 * @param permissions 初始待应答权限请求（[pendingPermissions] 可读）。
 * @param failureSummary 非空时在编排结束后追加 FAILED（摘要经 [RuntimeEvent.summarize] 截断）。
 */
class InMemoryDshRuntime(
    private val script: List<RuntimeEvent> = DEFAULT_SCRIPT,
    permissions: List<PermissionRequest> = emptyList(),
    private val failureSummary: String? = null,
) : DshRuntime {

    private val pending = permissions.toMutableList()
    private val answered = linkedMapOf<String, PermissionResponse>()

    override fun openSession(workflowRef: String): RuntimeSession =
        Session(sessionId = "$SESSION_PREFIX$workflowRef", script = script, failureSummary = failureSummary)

    /** 尚未应答的权限请求（快照；不暴露内部可变列表）。 */
    fun pendingPermissions(): List<PermissionRequest> = pending.toList()

    /**
     * 应答一条权限请求并回传应答值。
     *
     * @return 成功应答时返回 [response]；请求未知或已应答时返回 null（不伪造结果）。
     */
    fun respond(requestId: String, response: PermissionResponse): PermissionResponse? {
        if (pending.none { it.requestId == requestId }) return null
        pending.removeAll { it.requestId == requestId }
        answered[requestId] = response
        return response
    }

    private class Session(
        override val sessionId: String,
        private val script: List<RuntimeEvent>,
        private val failureSummary: String?,
    ) : RuntimeSession {

        override fun dispatch(task: RuntimeTask): RuntimeHandle = Handle(script, failureSummary)
    }

    private class Handle(
        private val script: List<RuntimeEvent>,
        private val failureSummary: String?,
    ) : RuntimeHandle {

        @Volatile
        private var cancelled: Boolean = false

        override fun cancel() {
            cancelled = true
        }

        override fun events(): Sequence<RuntimeEvent> = sequence {
            var completed = false
            for (event in script) {
                if (cancelled) break
                yield(event)
            }
            if (!cancelled) {
                completed = true
                failureSummary?.let {
                    yield(
                        RuntimeEvent(
                            kind = RuntimeEventKind.FAILED,
                            name = "session",
                            detailSummary = RuntimeEvent.summarize(it),
                            ok = false,
                        ),
                    )
                }
            }
            if (cancelled && !completed) {
                yield(
                    RuntimeEvent(
                        kind = RuntimeEventKind.CANCELLED,
                        name = "session",
                        detailSummary = "任务已取消",
                        ok = false,
                    ),
                )
            }
        }
    }

    companion object {
        private const val SESSION_PREFIX = "dsh-session:"

        /** 默认编排：开始 → 选技能 → 两轮（模型调用 + 工具调用/返回）→ 候选就绪 → 结束。 */
        val DEFAULT_SCRIPT: List<RuntimeEvent> = listOf(
            RuntimeEvent(RuntimeEventKind.PHASE_START, "session", "运行开始"),
            RuntimeEvent(RuntimeEventKind.SKILL_SELECTED, "skill.default", "选择默认技能"),
            RuntimeEvent(RuntimeEventKind.MODEL_CALL, "model", "第 1 轮模型调用", durationMillis = 120),
            RuntimeEvent(RuntimeEventKind.TOOL_CALL, "tool.list_chapters", "第 1 轮工具调用", resultCount = 1),
            RuntimeEvent(
                RuntimeEventKind.TOOL_RESULT,
                "tool.list_chapters",
                "第 1 轮工具返回",
                resultCount = 3,
                ok = true,
            ),
            RuntimeEvent(RuntimeEventKind.MODEL_CALL, "model", "第 2 轮模型调用", durationMillis = 90),
            RuntimeEvent(RuntimeEventKind.TOOL_CALL, "tool.get_latest_draft", "第 2 轮工具调用", resultCount = 1),
            RuntimeEvent(
                RuntimeEventKind.TOOL_RESULT,
                "tool.get_latest_draft",
                "第 2 轮工具返回",
                resultCount = 1,
                ok = true,
            ),
            RuntimeEvent(RuntimeEventKind.CANDIDATE_READY, "candidate", "候选已就绪", resultCount = 1, ok = true),
            RuntimeEvent(RuntimeEventKind.PHASE_END, "session", "运行结束", ok = true),
        )
    }
}
