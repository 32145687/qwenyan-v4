package com.qianyan.application.usecase.context

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextPackVersion
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSelection
import com.qianyan.model.context.ContextSelector
import com.qianyan.model.context.ContextSizeEstimators
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/*
 * I6 · Context Engine：把"当前任务"翻译成"模型应该看到什么"。
 *
 * 流程（architecture §9 / §11）：
 *   ContextRequest
 *     → 解析作用域（Project → Novel / Variant / ProjectState；Project 隔离在此收敛）
 *     → ContextSource 产出候选（已有 Application 读取能力）
 *     → ContextSelector：Priority → Budget → Selected
 *     → ContextPack（冻结结果 + 版本指纹）
 *
 * 严格边界：
 *  - **只读**：不写任何状态、不创建 Context 表 / Workspace / World Model / ProjectState；
 *  - **确定性**：无 LLM、无随机、不读当前时间决定排序与取舍（`createdAt` 仅作元数据且可注入时钟）；
 *  - **不调用 LLM / Provider / Agent**，不做 Prompt 渲染（§15）；
 *  - **不写 I4 记录**：Context 构建是纯只读派生，不是 Agent 行为事实（§17）；
 *  - 不是 Project Index（§19）；不建立 Compression 实现（§24，只保留原文与淘汰边界）。
 */
class ContextEngineUseCases(
    private val projects: ProjectUseCases,
    private val chapters: ChapterUseCases,
    private val sessions: AgentSessionUseCases,
    private val sources: List<ContextSource>,
    private val clock: Clock = Clock.System,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 构建一次 Context Pack（确定性；同输入 ⇒ 同 packVersion ⇒ 同 items）。
     *
     * @throws ApplicationError.InvalidOperation 非法 budget / 候选边界 / 未知尺寸估算器。
     * @throws ApplicationError.EntityNotFound Project 不存在、Session 不属于该 Project、焦点章节越界。
     */
    fun build(request: ContextRequest): ContextPack {
        requireValid(request)

        val estimator = ContextSizeEstimators.of(request.budget.estimator)
            ?: throw ApplicationException(ApplicationError.InvalidOperation("未知尺寸估算器: ${request.budget.estimator}"))

        val project = guard { projects.projectOf(request.projectId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Project 不存在: ${request.projectId.value}"))

        // Project 隔离：携带 AgentSession 时必须与请求的 Project 一致（越界按不存在拒绝，不泄漏存在性）。
        request.sessionId?.let { sessionId ->
            val session = guard { sessions.sessionOf(sessionId) }
            if (session.projectId != request.projectId) {
                throw ApplicationException(ApplicationError.EntityNotFound("AgentSession 不存在: ${sessionId.value}"))
            }
        }

        // Project 隔离：焦点章节必须属于本 Project 的 Novel。
        val focusChapterId = request.focusChapterId?.let { chapterId ->
            val chapter = guard { chapters.findById(chapterId) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
            if (chapter.novelId != project.novelId) {
                throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
            }
            chapterId
        }

        val scope = ContextBuildScope(
            projectId = project.projectId,
            novelId = project.novelId,
            activeVariantId = project.state.activeVariantId,
            state = project.state,
            focusChapterId = focusChapterId,
        )

        val candidates = sources.flatMap { source -> guard { source.candidates(scope, request) } }
        val selection: ContextSelection = ContextSelector.select(candidates, request.budget, estimator)
        val version = ContextPackVersion.of(request, project.novelId, project.state.activeVariantId, selection)

        return ContextPack(
            packId = "ctx-${project.projectId.value}-${request.purpose.name.lowercase()}-" +
                "${focusChapterId?.value ?: "none"}-$version",
            request = request.copy(focusChapterId = focusChapterId),
            novelId = project.novelId,
            activeVariantId = project.state.activeVariantId,
            items = selection.items,
            budgetGuard = selection.budgetGuard,
            packVersion = version,
            createdAt = now(),
        )
    }

    /**
     * 失效判定：按 pack 记录的请求重编一次并比对 `packVersion`。
     * **不改动既有 pack**（pack 内容已冻结 ⇒ 底层数据变化不会悄悄改变它，只会被判为过期）。
     */
    fun isStale(pack: ContextPack): Boolean = build(pack.request).packVersion != pack.packVersion

    private fun requireValid(request: ContextRequest) {
        if (request.budget.maxTokens <= 0L) {
            throw ApplicationException(ApplicationError.InvalidOperation("budget.maxTokens 必须为正: ${request.budget.maxTokens}"))
        }
        if (request.candidateHorizon <= 0) {
            throw ApplicationException(ApplicationError.InvalidOperation("candidateHorizon 必须为正: ${request.candidateHorizon}"))
        }
    }

    /** 时间戳精度与存储一致（epoch 毫秒）；时钟可注入，保证测试确定性。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
}