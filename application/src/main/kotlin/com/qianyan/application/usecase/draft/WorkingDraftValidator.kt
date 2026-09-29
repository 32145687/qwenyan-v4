package com.qianyan.application.usecase.draft

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.log.ActivityUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.session.AgentSessionUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.engine.markdown.ControlledMarkdown
import com.qianyan.model.spec.IssueSeverity
import com.qianyan.model.spec.ValidationIssue
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.workingdraft.WorkingDraft
import com.qianyan.model.workingdraft.WorkingDraftLimits
import com.qianyan.model.workingdraft.WorkingDraftStatus
import com.qianyan.model.workingdraft.WorkingDraftValidationCodes

/**
 * I8 · Working Draft Validation（**确定性**结构 / 边界检查）。
 *
 * 定位（architecture §18）：判断"Agent 产生的 Working Draft 是否符合结构 / 作用域 / 管道约束"。
 *
 * 不是：LLM Critique（语义评审属 Critique Skill）／Agent ／Skill ／Human Gate ／ActionPolicy（是否允许执行动作）／
 * Commit。**同输入 ⇒ 同结果**：无 LLM、无时间、无随机、无外部可变状态；只读既有 Application 能力。
 *
 * 结果复用既有 `core:model` 的 [ValidationResult] / [ValidationIssue] / [IssueSeverity]
 * （与 Critique / KnowledgeValidator 同一套；**不新建第二套 Validation 模型**）：
 *  - `ValidationIssue.field` 承载稳定错误码（见 [WorkingDraftValidationCodes]）；
 *  - `severity = ERROR` ⇒ `passed = false`；`WARNING` / `INFO` 不影响 `passed`。
 *
 * 固定检查序列（保证 findings 顺序确定）：状态 → Project/target/chapter → 归属（session/activity）
 * → 基线 → 内容。
 */
class WorkingDraftValidator(
    private val projects: ProjectUseCases,
    private val chapters: ChapterUseCases,
    private val sessions: AgentSessionUseCases,
    private val activities: ActivityUseCases,
    private val drafts: WriterUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 校验一份 Working Draft（只读；不改动 draft 与任何 Canonical 数据）。 */
    fun validate(draft: WorkingDraft): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        // 0) 状态：已丢弃的临时成果不可再校验
        if (draft.status.isTerminal) {
            issues += issue(
                WorkingDraftValidationCodes.INVALID_STATE,
                IssueSeverity.ERROR,
                "Working Draft 已丢弃（${draft.status.name}），不可再校验",
            )
        }

        // 1) Project / target / chapter
        val project = readOrNull { projects.projectOf(draft.projectId) }
        when {
            project == null -> issues += issue(
                WorkingDraftValidationCodes.PROJECT_SCOPE_MISMATCH,
                IssueSeverity.ERROR,
                "Project 无法解析（不存在或未建立运行态）: ${draft.projectId.value}",
            )

            project.novelId != draft.target.novelId -> issues += issue(
                WorkingDraftValidationCodes.PROJECT_SCOPE_MISMATCH,
                IssueSeverity.ERROR,
                "Project ${draft.projectId.value} 的 Novel（${project.novelId.value}）" +
                    "与 target.novelId（${draft.target.novelId.value}）不一致",
            )
        }

        val chapter = readOrNull { chapters.findById(draft.target.chapterId) }
        when {
            chapter == null -> issues += issue(
                WorkingDraftValidationCodes.TARGET_NOT_FOUND,
                IssueSeverity.ERROR,
                "target chapter 不存在: ${draft.target.chapterId.value}",
            )

            chapter.novelId != draft.target.novelId -> issues += issue(
                WorkingDraftValidationCodes.TARGET_NOT_FOUND,
                IssueSeverity.ERROR,
                "target chapter ${draft.target.chapterId.value} 不属于 target novel ${draft.target.novelId.value}",
            )

            chapter.variantId != draft.target.variantId -> issues += issue(
                WorkingDraftValidationCodes.CHAPTER_SCOPE_MISMATCH,
                IssueSeverity.ERROR,
                "target chapter 的作用域（variantId=${chapter.variantId?.value ?: "ORIGINAL"}）" +
                    "与 target.variantId（${draft.target.variantId?.value ?: "ORIGINAL"}）不一致",
            )
        }

        // 2) 归属（session / activity 必须属于本 Project；activity 必须与声明的 session 一致）
        draft.sessionId?.let { sessionId ->
            val sameProject = readOrNull { sessions.sessionsOfProject(draft.projectId) }
                ?.any { it.sessionId == sessionId } == true
            if (!sameProject) {
                issues += issue(
                    WorkingDraftValidationCodes.SESSION_SCOPE_MISMATCH,
                    IssueSeverity.ERROR,
                    "AgentSession ${sessionId.value} 不存在或不属于 Project ${draft.projectId.value}",
                )
            }
        }
        draft.activityId?.let { activityId ->
            val activity = readOrNull { activities.listByProject(draft.projectId) }
                ?.firstOrNull { it.activityId == activityId }
            val declaredSessionId = draft.sessionId
            when {
                activity == null -> issues += issue(
                    WorkingDraftValidationCodes.ACTIVITY_SCOPE_MISMATCH,
                    IssueSeverity.ERROR,
                    "Activity ${activityId.value} 不存在或不属于 Project ${draft.projectId.value}",
                )

                declaredSessionId != null && activity.sessionId != declaredSessionId -> issues += issue(
                    WorkingDraftValidationCodes.ACTIVITY_SCOPE_MISMATCH,
                    IssueSeverity.ERROR,
                    "Activity ${activityId.value} 属于 Session ${activity.sessionId.value}，" +
                        "与声明的 Session ${declaredSessionId.value} 不一致",
                )
            }
        }

        // 3) 基线引用（必须存在且属于同一 target；只引用既有 Draft，不新建版本体系）
        val baseDraft = draft.base.baseDraftId?.let { baseDraftId -> readOrNull { drafts.draft(baseDraftId) } }
        draft.base.baseDraftId?.let { baseDraftId ->
            when {
                baseDraft == null -> issues += issue(
                    WorkingDraftValidationCodes.BASE_DRAFT_NOT_FOUND,
                    IssueSeverity.ERROR,
                    "基线 Draft 不存在: ${baseDraftId.value}",
                )

                baseDraft.novelId != draft.target.novelId || baseDraft.chapterId != draft.target.chapterId ->
                    issues += issue(
                        WorkingDraftValidationCodes.BASE_DRAFT_NOT_FOUND,
                        IssueSeverity.ERROR,
                        "基线 Draft ${baseDraftId.value} 不属于 target 章节（novel=${baseDraft.novelId.value}, " +
                            "chapter=${baseDraft.chapterId?.value ?: "none"}）",
                    )
            }
        }

        // 4) 内容（确定性；不做任何文学质量判断 —— §11）
        if (draft.content.isBlank()) {
            issues += issue(WorkingDraftValidationCodes.EMPTY_CONTENT, IssueSeverity.ERROR, "正文为空")
        }
        if (draft.content.length > WorkingDraftLimits.MAX_CONTENT_CHARS) {
            issues += issue(
                WorkingDraftValidationCodes.CONTENT_TOO_LONG,
                IssueSeverity.ERROR,
                "正文长度 ${draft.content.length} 超出上限 ${WorkingDraftLimits.MAX_CONTENT_CHARS}",
            )
        }
        val parsed = ControlledMarkdown.parse(draft.content)
        if (parsed.hasIllegalStructure) {
            issues += issue(
                WorkingDraftValidationCodes.CONTROLLED_MARKDOWN_DEGRADED,
                IssueSeverity.WARNING,
                "有 ${parsed.degradedCount} 处结构超出受控 Markdown v1（P2/FD-1），提交前将被降级渲染",
            )
        }
        if (baseDraft != null && baseDraft.content == draft.content) {
            issues += issue(
                WorkingDraftValidationCodes.UNCHANGED_FROM_BASE,
                IssueSeverity.INFO,
                "正文与基线 Draft ${baseDraft.draftId.value} 完全相同（无实际改动）",
            )
        }

        return ValidationResult(
            passed = issues.none { it.severity == IssueSeverity.ERROR },
            issues = issues,
        )
    }

    /** 错误码承载在既有 `ValidationIssue.field`（既有语义为"被检查项/字段"；本阶段检查项即确定性码）。 */
    private fun issue(code: String, severity: IssueSeverity, message: String): ValidationIssue =
        ValidationIssue(field = code, severity = severity, message = message)

    /** 只读查询；缺失/异常一律表达为 null（Validation 必须返回结果，不抛异常）。 */
    private fun <T> readOrNull(block: () -> T?): T? = try {
        guard(block)
    } catch (e: ApplicationException) {
        null
    }
}