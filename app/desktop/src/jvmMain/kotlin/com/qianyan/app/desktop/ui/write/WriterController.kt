package com.qianyan.app.desktop.ui.write

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.workflow.ChapterPhase
import com.qianyan.application.usecase.workflow.ChapterWorkflowGateway
import com.qianyan.application.usecase.workflow.ChapterWorkflowProgress
import com.qianyan.application.usecase.workflow.PostCommitStage
import com.qianyan.application.usecase.writing.WriterChapterContext
import com.qianyan.application.usecase.writing.WriterGateway
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.story.Chapter
import com.qianyan.model.workflow.HumanDecision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop Writer 状态编排（P20-PC2）。**UI 层**，不含任何业务规则。
 *
 * 接线（严格单向）：
 * ```
 * WriteScreen → WriterController → WriterGateway（loadContext / saveContent / continueWriting / rewrite）
 *                               → ChapterWorkflowGateway（getChapterProgress / approve：HITL 状态与人工门）
 * ```
 * 明确不做：
 *  - **不接触** DraftRepository / SQLDelight / WorkflowOrchestrator / Provider / Agent；
 *  - **不 Decision**：不引用 DecisionModel / DecisionPolicy，不触发、不重算（AI 决策由既有
 *    Application orchestration 在 PLANNING 一次性产生并落 Checkpoint，FD-4）；
 *  - **不自建写作流程**：不组合 PlanningExecutionUseCase / WritingExecutionUseCase；
 *  - 不复刻 Gate / 修订 / 生命周期规则：状态只由 [ChapterWorkflowGateway] 投影得到。
 *
 * @param variantId PC-2 只处理 Original 作用域（Variant 工作台属后续阶段）。
 */
class WriterController(
    private val novelId: NovelId,
    private val chapter: Chapter,
    /**
     * O-88：Writer 服务在**每次动作发生时**解析，而不是构造时缓存。
     * 设置页切换 Provider 会重建 DesktopGraph 容器；控制器不重建（避免丢正文 / 候选 / 进度），
     * 下一次动作自然取到新容器的服务。
     */
    private val gatewayProvider: () -> WriterGateway,
    private val workflowProvider: () -> ChapterWorkflowGateway,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val variantId: VariantId? = null,
    /** 正文页开启：编辑停顿后自动保存到现有 Draft（测试默认关闭）。 */
    private val autoSave: Boolean = false,
    /** 单调时钟（毫秒），用于 Gate 决议防抖；测试可注入。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /** O-50：Gate 决议成功后回调（写页用于 `refreshKey++`，刷新状态栏/Inspector 的待确认计数）。 */
    private val onDecision: () -> Unit = {},
    /**
     * O-96②：busy 真实变化回调（写页用于同步 `DesktopAppState.writerBusy`）。
     * 只在 busy 翻转时触发，因此离页不再由页面无条件清零；任务收敛后自然会收到 false。
     */
    private val onBusyChange: (Boolean) -> Unit = {},
) {

    /** 兼容既有调用方（直接持有服务实例，不做热切换）：等价于固定返回同一实例的提供者。 */
    constructor(
        novelId: NovelId,
        chapter: Chapter,
        gateway: WriterGateway,
        workflow: ChapterWorkflowGateway,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        variantId: VariantId? = null,
        autoSave: Boolean = false,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
        onDecision: () -> Unit = {},
        onBusyChange: (Boolean) -> Unit = {},
    ) : this(
        novelId,
        chapter,
        { gateway },
        { workflow },
        scope,
        ioDispatcher,
        variantId,
        autoSave,
        nowMillis,
        onDecision,
        onBusyChange,
    )

    /**
     * 唯一的 UI 状态写入口：busy 翻转时通知 [onBusyChange]（O-96②）。
     * busy 由 isSaving / isGenerating / isApproving / isRejecting 派生，只在真正翻转时回调。
     */
    private fun updateState(transform: (WriterUiState) -> WriterUiState) {
        val wasBusy = _uiState.value.busy
        val next = _uiState.updateAndGet(transform)
        if (next.busy != wasBusy) onBusyChange(next.busy)
    }

    /** 当前 Writer 服务（调用时解析，见 [gatewayProvider]）。 */
    private val gateway: WriterGateway get() = gatewayProvider()

    /** 当前 Workflow 服务（调用时解析，见 [workflowProvider]）。 */
    private val workflow: ChapterWorkflowGateway get() = workflowProvider()

    private var autoSaveJob: kotlinx.coroutines.Job? = null

    /** T-O108：后台收尾的启动协程（避免同一次投递被重复启动）。 */
    private var postCommitKickJob: kotlinx.coroutines.Job? = null

    /** 决议防抖截止时间：阻止同一双击的第二击穿透到生成类动作。 */
    private var decisionCooldownUntil = 0L
    private var decisionCooldownJob: kotlinx.coroutines.Job? = null

    private val chapterId: ChapterId get() = chapter.chapterId

    private val _uiState = MutableStateFlow(
        WriterUiState(
            chapterTitle = chapter.title,
            chapterOrder = chapter.order,
        ),
    )
    val uiState: StateFlow<WriterUiState> = _uiState.asStateFlow()

    /** 加载：先做 O-52 批准收尾补偿，再只读加载章节信息 + 最新 Draft + Workflow 阶段 / 人工门。 */
    fun load() {
        scope.launch {
            runCatching {
                compensateApprovedWorkflow()
                readContext()
            }
                .onSuccess { loaded ->
                    updateState { it.applyContext(loaded).copy(isLoaded = true, dirty = false) }
                    kickPendingPostCommit()
                }
                .onFailure { e -> updateState { it.copy(error = messageFor(e)) } }
        }
    }

    /**
     * T-O108：页面加载时若已有**已投递未执行**的后台收尾（补偿投递 / 上次退出遗留），异步启动一次。
     * 不在加载路径上同步执行（WMU 是数十秒的 LLM 调用），只交到独立协程；已完成/已失败不启动。
     */
    private fun kickPendingPostCommit() {
        if (_uiState.value.postCommitState != WriterPostCommitState.PENDING) return
        if (postCommitKickJob?.isActive == true) return
        postCommitKickJob = scope.launch {
            runCatching { withContext(ioDispatcher) { workflow.runPostCommit(chapterId) } }
            runCatching { readContext() }
                .onSuccess { loaded -> updateState { it.applyContext(loaded) } }
        }
    }

    /**
     * O-52 补偿（T-O108/A-32 改写）：Gate 已**批准**但未收尾 ⇒ 只走**快路径决议 + 幂等投递**，
     * 绝不再调用整链 [ChapterWorkflowGateway.approve]（后者会同步跑 WMU + KU，数十秒）。
     * 未批准 / 已完成 / **已有后台任务（进行中或失败）** 一律不动 —— 后者归后台收尾器与人工重试，
     * 补偿若再投递会每次打开章节都新建任务并重跑 LLM（失败不可见、正文页反复卡顿）。
     */
    private suspend fun compensateApprovedWorkflow() {
        withContext(ioDispatcher) {
            val progress = workflow.getChapterProgress(chapterId)
            // 只补「已决议、未提交」的崩溃窗口（Workflow 仍停在人工门）。
            // RUNNING 表示提交已完成、收尾由后台任务负责 —— 此处绝不重复投递，也绝不整链 approve。
            val hasPostCommitJob = workflow.postCommitState(chapterId) != PostCommitStage.NONE
            if (progress.pendingGateDecision == HumanDecision.APPROVED &&
                progress.phase == ChapterPhase.WAITING_CONFIRMATION &&
                !hasPostCommitJob
            ) {
                workflow.approveDecision(chapterId)
            }
        }
    }

    /** 用户编辑正文（仅本地缓冲，不落库；保存由 [save] 或自动保存触发）。 */
    fun onContentChange(text: String) {
        updateState { it.copy(draftContent = text, dirty = true, error = null) }
        if (autoSave) scheduleAutoSave()
    }

    /**
     * 自动保存：停止输入 1.2 秒后写入现有 Draft。
     *
     * 与手动保存完全同一条 `save()` 路径（受「无草稿 / 待审候选 / busy」限制）；
     * 只取消重复计时，不改变任何业务状态。
     */
    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            kotlinx.coroutines.delay(1200)
            val state = _uiState.value
            if (state.dirty && state.draftId != null && !state.waitingForUser && !state.busy) {
                save()
            }
        }
    }

    /**
     * 保存当前编辑内容：已有 Draft → 写入该 Draft（draftId / chapterId / status / format / lineage 全部不变）；
     * 空章节（O-84）→ 调用首稿入口建立确定性首稿并写 Canonical。正文为空则拒绝保存。
     */
    fun save() {
        val state = _uiState.value
        // 待审候选不得经普通保存进入 Canonical（必须先批准或拒绝 Human Gate）。
        if (state.waitingForUser) {
            updateState { it.copy(error = "当前有未批准的 AI 候选：请先「通过确认」或「拒绝」，不能直接保存") }
            return
        }
        if (state.busy) return
        val draftId = state.draftId
        if (draftId == null && state.draftContent.isBlank()) {
            updateState { it.copy(error = "正文为空：请先输入内容再保存") }
            return
        }
        updateState { it.copy(isSaving = true, error = null) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    if (draftId == null) {
                        gateway.saveFirstDraft(chapterId, state.draftContent)
                    } else {
                        gateway.saveContent(draftId, state.draftContent)
                    }
                }
            }
                .onSuccess { saved ->
                    updateState {
                        it.copy(
                            isSaving = false,
                            draftId = saved.draftId,
                            draftStatus = saved.status.name,
                            draftFormat = saved.format,
                            draftContent = saved.content,
                            dirty = false,
                        )
                    }
                }
                .onFailure { e -> updateState { it.copy(isSaving = false, error = messageFor(e)) } }
        }
    }

    /**
     * AI 继续写：驱动既有章节 Workflow 前进（PLANNING → WRITING → …）。
     * 每次调用推进 Workflow 的**一步**（既有 WriterGateway.continueWriting 语义，UI 不改写）；
     * 完成后以 durable Workflow 阶段投影 + 最新 Draft 覆盖 UI 状态。
     */
    fun continueWriting() = generate(WriterTaskStatus.PLANNING, WriterFailureAction.CONTINUE_WRITING) {
        gateway.continueWriting(novelId, variantId, chapterId)
    }

    /** AI 改写：复用既有 Critique → Revision（新 draftId、status=REVISED，原稿不破坏）。 */
    fun rewrite() = generate(WriterTaskStatus.REVISION, WriterFailureAction.REWRITE) {
        gateway.rewrite(novelId, variantId, chapterId)
    }

    /**
     * T-O82-F：按**上次失败的动作**重新发起一次（继续写 / 改写），不改变任何工作流语义。
     * 没有失败记录时 no-op（避免误触发）。
     */
    fun retryFailed() {
        when (_uiState.value.failureAction) {
            WriterFailureAction.CONTINUE_WRITING -> continueWriting()
            WriterFailureAction.REWRITE -> rewrite()
            null -> Unit
        }
    }

    /**
     * 续篇（O-33）：从**当前已完成且已确认**的章节续写下一章。
     *
     * 复用既有 2A 链 [ChapterWorkflowGateway.continueToNextChapter]（durable continuation 幂等），
     * 再以同一 gateway 的 [ChapterWorkflowGateway.advance] 把下一章推到人工门（不绕过 Gate）。
     * 前置不满足时**降级不硬拒**：给明确文案、不改任何持久状态（O-42 同族语义）。
     *
     * @param onReady 成功时回调下一章 [ChapterId]（UI 负责导航；本类不触碰 workspace）。
     */
    fun continueToNextChapter(onReady: (ChapterId) -> Unit) {
        val state = _uiState.value
        if (state.busy) return
        if (state.taskStatus != WriterTaskStatus.COMPLETED) {
            updateState { it.copy(error = CONTINUATION_NOT_COMPLETED_MESSAGE) }
            return
        }
        updateState { it.copy(isGenerating = true, error = null) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val target = workflow.continueToNextChapter(chapterId).chapterId
                    workflow.advance(target)
                    target
                }
            }
                .onSuccess { target ->
                    updateState { it.copy(isGenerating = false) }
                    onReady(target)
                }
                .onFailure { e ->
                    updateState { it.copy(isGenerating = false, error = continuationFailureMessage(e)) }
                }
        }
    }

    /**
     * 通过人工门（HITL）· **快路径**（T-O108/A-32）：决议 + Canonical 提交 + 投递后台收尾。
     *
     * 调用返回时界面即可恢复（按钮行、待确认 0、新正文）；WMU 与 KNOWLEDGE_UPDATE 在后台执行，
     * 由 [WriterUiState.postCommitState] 投影「后台更新中 / 更新失败」。
     */
    fun approveGate() {
        if (!_uiState.value.canApprove) return
        updateState { it.copy(isApproving = true, error = null) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { workflow.approveDecision(chapterId) }
                readContext()
            }
                .onSuccess { loaded ->
                    startDecisionCooldown()
                    updateState { it.applyContext(loaded).copy(isApproving = false) }
                    onDecision()
                    // 后台收尾：只投递 + 异步启动，不阻塞 UI。
                    runCatching { withContext(ioDispatcher) { workflow.runPostCommit(chapterId) } }
                }
                .onFailure { e -> updateState { it.copy(isApproving = false, error = messageFor(e)) } }
        }
    }

    /** T-O108：后台收尾进行中时的轻量轮询入口（只重读投影，不触发任何业务动作）。 */
    fun refreshPostCommit() {
        if (_uiState.value.postCommitState != WriterPostCommitState.PENDING) return
        scope.launch {
            runCatching { readContext() }
                .onSuccess { loaded -> updateState { it.applyContext(loaded) } }
        }
    }

    /** T-O108：后台收尾失败后的人工重试（只在 FAILED 时可用；重试 = 新建后台任务并异步启动）。 */
    fun retryPostCommit() {
        if (_uiState.value.postCommitState != WriterPostCommitState.FAILED) return
        updateState { it.copy(postCommitState = WriterPostCommitState.PENDING, error = null) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { workflow.retryPostCommit(chapterId) }
                readContext()
            }
                .onSuccess { loaded ->
                    updateState { it.applyContext(loaded) }
                    onDecision()
                }
                .onFailure { e ->
                    updateState {
                        it.copy(postCommitState = WriterPostCommitState.FAILED, error = messageFor(e))
                    }
                }
        }
    }

    /**
     * 拒绝当前候选（HITL）：调用既有 [ChapterWorkflowGateway.reject]。
     * 拒绝只结束**当前 Gate**，不改写 Canonical；再次改写会由 Workflow 开启**新** Gate。
     */
    fun rejectGate() {
        if (!_uiState.value.canReject) return
        updateState { it.copy(isRejecting = true, error = null) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { workflow.reject(chapterId) }
                readContext()
            }
                .onSuccess { loaded ->
                    startDecisionCooldown()
                    updateState { it.applyContext(loaded).copy(isRejecting = false) }
                    onDecision()
                }
                .onFailure { e -> updateState { it.copy(isRejecting = false, error = messageFor(e)) } }
        }
    }

    /** 执行一次 AI 操作：先置过程态（UI 本地提示，非业务状态机），完成后以真实投影覆盖。 */
    private fun generate(transient: WriterTaskStatus, action: WriterFailureAction, block: () -> ChapterWorkflowProgress) {
        // 同一双击的第二击可能落在刚切换出的「继续写 / 让 Qianyan 修改」上：
        // 决议防抖窗口内直接忽略，不触达 gateway / 不 advance。
        if (decisionCooldownActive()) return
        if (_uiState.value.decisionLocked) updateState { it.copy(decisionLocked = false) }
        if (!_uiState.value.canGenerate) return
        updateState { it.copy(isGenerating = true, error = null, taskStatus = transient) }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    // T-O82b-F：用**本次动作返回的进度**作为权威终态（FAILED 时 chapter 反查会得到 blank）。
                    val progress = block()
                    readContext(progress)
                }
            }
                .onSuccess { loaded ->
                    updateState {
                        it.applyContext(loaded).copy(
                            isGenerating = false,
                            dirty = false,
                            failure = if (loaded.failed) loaded.failureMessage ?: WORKFLOW_FAILED_SUMMARY else null,
                            failureAction = action.takeIf { loaded.failed },
                        )
                    }
                }
                .onFailure { e ->
                    // T-O82-F：失败原因必须留在正文页（不只是转瞬即逝的 error 行），并允许「重试」。
                    val text = messageFor(e)
                    updateState {
                        it.copy(
                            isGenerating = false,
                            error = text,
                            failure = failureSummary(text),
                            failureAction = action,
                        )
                    }
                }
        }
    }

    /** Gate 决议成功后的防抖：锁生成类动作 500ms，到点自动解锁（不涉及保存 / 审批链）。 */
    private fun startDecisionCooldown() {
        decisionCooldownUntil = nowMillis() + DECISION_COOLDOWN_MS
        decisionCooldownJob?.cancel()
        updateState { it.copy(decisionLocked = true) }
        decisionCooldownJob = scope.launch {
            kotlinx.coroutines.delay(DECISION_COOLDOWN_MS)
            decisionCooldownUntil = 0L
            updateState { it.copy(decisionLocked = false) }
        }
    }

    private fun decisionCooldownActive(): Boolean = nowMillis() < decisionCooldownUntil

    /**
     * 用户层上下文（Draft + 阶段）+ Workflow 进度（人工门 / 修订计数）；只读、不推进状态。
     *
     * O-108（方案 D）：两次查询都是**同步 DB**，整体切到 [ioDispatcher]，Main 线程只接收结果。
     * 去重结论：**保留**第二次 `getChapterProgress`。`WriterFacade.loadContext` 内部确实已查进度
     * （application/.../writing/WriterFacade.kt:68），但投影 [WriterChapterContext] 只暴露
     * phase / waitingForUser，不含本处需要的 pendingGateDecision / revisionCount / failureMessage；
     * 扩展该 DTO 属白名单外改动，故不去重（O-108 只做线程收尾，不改跨模块契约）。
     */
    private suspend fun readContext(actionProgress: ChapterWorkflowProgress? = null): LoadedContext =
        withContext(ioDispatcher) {
            val context = gateway.loadContext(novelId, variantId, chapterId)
            val progress = workflow.getChapterProgress(chapterId)
            LoadedContext(
                context = context,
                waitingForUser = progress.waitingForUser,
                // O-90：Gate 决议随进度投影进入 UI 状态（拒绝后显示「上一轮已拒绝」）。
                pendingGateDecision = actionProgress?.pendingGateDecision ?: progress.pendingGateDecision,
                revisionCount = progress.revisionCount,
                // FAILED 时 Orchestrator 会清空 activeChapterId → chapter 反查为 blank；以动作返回值为准。
                failed = actionProgress?.phase == ChapterPhase.FAILED || progress.phase == ChapterPhase.FAILED,
                failureMessage = actionProgress?.failureMessage ?: progress.failureMessage,
                // T-O108：后台收尾阶段（由 BACKGROUND 任务状态投影，不新增业务状态）。
                postCommitState = when (workflow.postCommitState(chapterId)) {
                    com.qianyan.application.usecase.workflow.PostCommitStage.PENDING -> WriterPostCommitState.PENDING
                    com.qianyan.application.usecase.workflow.PostCommitStage.FAILED -> WriterPostCommitState.FAILED
                    else -> WriterPostCommitState.NONE
                },
            )
        }

    private fun WriterUiState.applyContext(loaded: LoadedContext): WriterUiState = copy(
        novelTitle = loaded.context.novelTitle,
        chapterTitle = loaded.context.chapterTitle.ifBlank { "第 ${chapter.order} 章" },
        chapterOrder = chapter.order,
        draftId = loaded.context.draft?.draftId,
        draftStatus = loaded.context.draft?.status?.name,
        draftFormat = loaded.context.draft?.format,
        draftContent = loaded.context.draft?.content ?: "",
        taskStatus = WriterTaskStatus.of(loaded.context.phase),
        waitingForUser = loaded.context.waitingForUser || loaded.waitingForUser,
        pendingGateDecision = loaded.pendingGateDecision,
        pendingProposal = loaded.context.pendingProposal,
        revisionCount = loaded.revisionCount,
        postCommitState = loaded.postCommitState,
        error = null,
        failure = null,
        failureAction = null,
    )

    /** 一次只读加载的合并结果（用户层上下文 + Workflow 进度投影）。 */
    private data class LoadedContext(
        val context: WriterChapterContext,
        val waitingForUser: Boolean,
        val pendingGateDecision: HumanDecision? = null,
        val revisionCount: Int,
        val failed: Boolean = false,
        val failureMessage: String? = null,
        val postCommitState: WriterPostCommitState = WriterPostCommitState.NONE,
    )

    private companion object {
        /** 与双击间隔（通常 <150ms）相比留足余量；只防穿透，不做业务节流。 */
        const val DECISION_COOLDOWN_MS = 500L
    }
}

/** [ApplicationError] → 用户可读文案（不泄露底层细节）。 */
internal fun messageFor(error: Throwable): String = when (error) {
    is ApplicationException -> when (val e = error.error) {
        is ApplicationError.EntityNotFound -> "目标不存在：${e.detail}"
        is ApplicationError.VariantMismatch -> "作用域不匹配：${e.detail}"
        is ApplicationError.ProviderCredentialMissing -> "AI 服务未配置密钥（请在设置中填写 API Key）"
        is ApplicationError.ProviderTimeout -> "AI 服务响应超时，请检查网络后重试"
        is ApplicationError.ProviderRateLimited -> "AI 调用过于频繁，请稍后再试"
        is ApplicationError.ProviderTokenLimit -> "内容过长，超出模型上下文限制，请缩短内容或换用更大上下文的模型"
        is ApplicationError.ProviderAuthFailed -> "API Key 无效或已过期，请到设置 › AI Provider 更新"
        is ApplicationError.ProviderUnavailable -> "AI 服务暂不可用（Provider unavailable）"
        is ApplicationError.InvalidPlanningOutput -> "规划输出无效，请重试"
        is ApplicationError.PlanningFailed -> "规划失败，请重试"
        is ApplicationError.InvalidWritingOutput -> "写作输出无效，请重试"
        is ApplicationError.WritingFailed -> "写作失败，请重试"
        is ApplicationError.InvalidCritiqueOutput -> "评审输出无效，请重试"
        is ApplicationError.CritiqueFailed -> "评审失败，请重试"
        is ApplicationError.InvalidRevisionOutput -> "修订输出无效，请重试"
        is ApplicationError.RevisionFailed -> "修订失败，请重试"
        is ApplicationError.RevisionNotAllowed -> "修订次数已达上限"
        is ApplicationError.RevisionLimitExceeded -> "任务检查点已达上限"
        is ApplicationError.TaskNotFound -> "任务不存在：${e.detail}"
        is ApplicationError.InvalidOperation -> "操作被拒绝：${e.detail}"
        // 候选工作产物只存在进程内存（WorkingDraft），重启后 Gate 仍在、候选已失：显式告知，不落泛化文案。
        is ApplicationError.RestoreFailure -> CANDIDATE_LOST_MESSAGE
        is ApplicationError.ChapterSourceConflict ->
            "该作品已存在其他来源的章节，已拒绝导入初始化；暂不支持把多份 TXT 合并到同一作品，请新建作品分别导入"
        is ApplicationError.TxtCanonicalInitIncomplete -> "TXT 已导入，但章节初始化未完成：${messageFor(ApplicationException(e.cause))}"
        is ApplicationError.UnknownStorage -> "存储错误，请重试"
        else -> "操作失败，请重试"
    }
    else -> "操作失败：${error.message ?: error.javaClass.simpleName}"
}

/** 续篇前置未满足（本章未完成 / 未通过确认）时的唯一用户文案（不静默、不伪造）。 */
internal const val CONTINUATION_NOT_COMPLETED_MESSAGE: String =
    "本章尚未完成并通过确认，无法续篇：请先通过本章的待确认提案。"

/* ---------- T-O82-F：失败可见（只影响 UI 展示，不改工作流语义 / 重试策略） ---------- */

/** 失败摘要上限：attempt 原文可能很长，正文页只显示这一段（不整段塞进 UI）。 */
internal const val FAILURE_SUMMARY_LIMIT: Int = 200

/** 工作流以 FAILED 收尾但未抛出（失败被工作流吸收）时的兜底摘要。 */
internal const val WORKFLOW_FAILED_SUMMARY: String = "工作流中断（本次未返回具体原因），请重试"

/** 失败摘要：换行折叠为单行 + 去空白 + 截断 ≤ [FAILURE_SUMMARY_LIMIT] 字。 */
internal fun failureSummary(text: String): String =
    text.replace('\r', ' ').replace('\n', ' ').trim().take(FAILURE_SUMMARY_LIMIT)

/**
 * 续篇失败的降级文案（O-42 同族语义）：
 *  - 无已提交 Canonical → "尚无已确认正文"；
 *  - 源 Workflow 非 COMPLETED / 无 active Chapter → "尚未完成并通过确认"；
 *  - 其余沿用既有用户文案映射。
 */
private fun continuationFailureMessage(error: Throwable): String = when {
    error is ApplicationException && error.error is ApplicationError.DraftConfirmationRequired ->
        "上一章尚无已确认正文，无法续篇。"
    error is ApplicationException && error.error is ApplicationError.InvalidOperation ->
        "本章尚未完成并通过确认，无法续篇。"
    else -> messageFor(error)
}