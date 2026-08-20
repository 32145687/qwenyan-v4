package com.qianyan.application.usecase.writing

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.writing.postprocessor.PassthroughWritingPostProcessor
import com.qianyan.application.usecase.writing.postprocessor.WritingPostProcessor
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.story.ChapterPlan
import com.qianyan.model.writing.Draft

/**
 * 写作 Use Case 骨架（P11.1 Scaffold）。
 *
 * 职责：为后续完整创作 Pipeline 预留清晰、稳定的契约边界：
 * ```
 * Task
 *   ↓
 * WritingUseCases (plan → write → critique → revise)
 *   ↓
 * AgentRuntime → LLMGateway → DeepSeek / MiMo
 *   ↓
 * Draft 输出 → WritingPostProcessor（MiMo seam）
 * ```
 *
 * P11.1 **明确未实现真实创作**：plan/write/critique/revise 四个入口存在，但一律抛
 * [ApplicationError.WritingScaffoldNotImplemented]（不伪装具备真实创作能力，不返回假数据）。
 * 真实编排/Agent/LLM 调用属 P11.2+。
 *
 * 本阶段**真实可用**的部分：写作产物后处理 seam —— [postProcessDraft] 委托注入的
 * [WritingPostProcessor]（默认直通）。MiMo 专用后处理器在 P11.5 接入，不在此改动模型输出。
 *
 * 明显不做（P11.1）：正文持久化（见 Preflight §11，延后 P11.3）、Task 集成、Agent Workflow、
 * HITL、Retry、异步、流式。
 */
class WritingUseCases(
    errorMapper: ErrorMapper,
    private val postProcessor: WritingPostProcessor = PassthroughWritingPostProcessor,
) : UseCase(errorMapper) {

    /** 写作产物后处理 seam（唯一在 P11.1 真实生效的路径；默认直通，不特判模型）。 */
    fun postProcessDraft(draft: Draft): Draft = postProcessor.postProcess(draft)

    /** 规划骨架：意图 → ChapterPlan。P11.2 实现。 */
    fun plan(request: UserWritingRequest): ChapterPlan = notImplemented("plan")

    /** 写作骨架：按规划产出 Draft(P11.2 实现）。 */
    fun write(request: UserWritingRequest, plan: ChapterPlan): Draft = notImplemented("write")

    /** 评审骨架：对 Draft 产出校验结果（复用 ValidationResult）。P11.4 实现。 */
    fun critique(draft: Draft): ValidationResult = notImplemented("critique")

    /** 修订骨架：依评审结果产出修订版 Draft。P11.4 实现。 */
    fun revise(draft: Draft, feedback: ValidationResult): Draft = notImplemented("revise")

    private fun notImplemented(stage: String): Nothing = throw ApplicationException(
        ApplicationError.WritingScaffoldNotImplemented(
            "P11.1 Scaffold：$stage 阶段尚未实现；真实创作编排属 P11.2+",
        ),
    )
}