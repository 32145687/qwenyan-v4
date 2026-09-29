package com.qianyan.application.usecase.context

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.project.ProjectState

/*
 * I6 · Context 来源契约（application 层）。
 *
 * 与 I5 Tool 的区别（§16 / §17）：
 *   Tool        = Agent 面向的"能取什么"（ToolResult 是工具调用结果，不是 Context）；
 *   ContextSource = Context 面向的"候选是什么"（只产出候选，不决定取舍）。
 *
 * 职责边界：只读取既有 Application 能力、只投影为稳定文本；
 * **不做**选择 / 排序 / 预算（属 `com.qianyan.model.context.ContextSelector`）、
 * **不写**任何状态、**不调用** LLM / Agent / Tool、不是 Project Index（§19）。
 */

/**
 * 一次 Context 构建的解析后作用域（所有来源共享；Project 隔离在此收敛）。
 *
 * 由 `ContextEngineUseCases` 解析一次后传入，来源不再各自解析 projectId → novelId，
 * 从而保证所有候选都来自**同一个 Project / Novel**。
 */
data class ContextBuildScope(
    val projectId: ProjectId,
    val novelId: NovelId,
    val activeVariantId: VariantId?,
    val state: ProjectState?,
    val focusChapterId: ChapterId?,
)

/**
 * Context 来源：把已有读取能力产出为候选 [ContextItem]。
 *
 * 实现必须确定性：不使用当前时间、随机数、UUID 决定内容或顺序；集合一律显式排序。
 */
interface ContextSource {

    val kind: ContextSourceKind

    /** 产出候选（不取舍、不裁剪预算；预算由选择器统一执行）。 */
    fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem>
}