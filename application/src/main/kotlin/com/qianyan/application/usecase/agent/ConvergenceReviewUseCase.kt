package com.qianyan.application.usecase.agent

import com.qianyan.agent.agents.CoreSkills
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.change.ChangeArtifactId
import com.qianyan.model.characterconsistency.CharacterConsistencyFinding
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.commit.CommitId
import com.qianyan.model.novelagent.NovelAgentOutcome
import com.qianyan.model.novelagent.NovelAgentRequest
import com.qianyan.model.novelagent.NovelAgentResult
import com.qianyan.model.spec.IssueSeverity

/**
 * D-35 · 复审结论的显式四态。
 *
 * [REVIEW_FAILED] 表示第二轮没跑成（FAILED / CANCELLED / 没有 CharacterConsistency 结果）；
 * **不得**折叠成 [CONSISTENT]——"没检查"与"检查通过"是不同事实。
 */
enum class ConvergenceOutcome {
    CONSISTENT,
    ISSUES_FOUND,
    INSUFFICIENT_EVIDENCE,
    REVIEW_FAILED,
}

/**
 * D-35 · 显式收敛评审轮的结果（T-8-E · 16→17 段显式契约）。
 *
 * 身份字段（novel / chapter / commit / artifact / round / reviewSession）与结论字段
 * （outcome / findings / counts / summary）全部扁平化，供 UI / 下游直接消费；
 * [result] 保留原始 Agent 事实，不复制 ContextPack / Draft / Artifact 大对象。
 */
data class ConvergenceReviewResult(
    val novelId: NovelId,
    val chapterId: ChapterId,
    val sourceCommitId: CommitId,
    val sourceArtifactId: ChangeArtifactId,
    val round: Int = 2,
    val outcome: ConvergenceOutcome,
    val findings: List<CharacterConsistencyFinding>,
    val errorCount: Int,
    val warningCount: Int,
    val summary: String,
    val reviewSessionId: AgentSessionId?,
    val result: NovelAgentResult,
)

/**
 * 显式映射（可测的单一入口）：
 * 只有第二轮 COMPLETED **且**返回了 CharacterConsistency 结果时才进入三种业务态；
 * 其余（FAILED / CANCELLED / WAITING_HUMAN / 结果缺失）一律 [ConvergenceOutcome.REVIEW_FAILED]。
 */
internal fun convergenceOutcomeOf(result: NovelAgentResult): ConvergenceOutcome {
    val consistency = result.characterConsistency
    if (result.outcome != NovelAgentOutcome.COMPLETED || consistency == null) {
        return ConvergenceOutcome.REVIEW_FAILED
    }
    return when (consistency.status) {
        CharacterConsistencyStatus.PASS -> ConvergenceOutcome.CONSISTENT
        CharacterConsistencyStatus.ISSUES -> ConvergenceOutcome.ISSUES_FOUND
        CharacterConsistencyStatus.INSUFFICIENT_EVIDENCE -> ConvergenceOutcome.INSUFFICIENT_EVIDENCE
    }
}

private fun reviewFailureSummary(result: NovelAgentResult): String =
    result.failure?.let { "${it.code}: ${it.detail}" }
        ?: "复审未完成（outcome=${result.outcome.name}，无 CharacterConsistency 结果）"

/** 扁平化映射：findings / counts / summary / session 直接取自第二轮事实，不重算业务判断。 */
private fun convergenceReviewResult(
    novelId: NovelId,
    chapterId: ChapterId,
    sourceCommitId: CommitId,
    sourceArtifactId: ChangeArtifactId,
    result: NovelAgentResult,
): ConvergenceReviewResult {
    val outcome = convergenceOutcomeOf(result)
    val findings = result.characterConsistency?.findings.orEmpty()
    return ConvergenceReviewResult(
        novelId = novelId,
        chapterId = chapterId,
        sourceCommitId = sourceCommitId,
        sourceArtifactId = sourceArtifactId,
        outcome = outcome,
        findings = findings,
        errorCount = findings.count { it.severity == IssueSeverity.ERROR },
        warningCount = findings.count { it.severity == IssueSeverity.WARNING },
        summary = if (outcome == ConvergenceOutcome.REVIEW_FAILED) {
            reviewFailureSummary(result)
        } else {
            result.characterConsistency?.summary ?: reviewFailureSummary(result)
        },
        reviewSessionId = result.sessionId,
        result = result,
    )
}

/**
 * D-35 · Commit 后的显式收敛评审轮。
 *
 * - 只有 [CommitUseCases.committedByArtifact] 已存在时才允许；
 * - 每次调用新建一个 [NovelAgentRequest]（不传 sessionId），固定只读 CharacterConsistency Skill；
 * - 只调用既有 [NovelAgent.run]，不自行 build Context / resolve World Model；
 * - 不产生 WorkingDraft / ChangeArtifact / Commit / Canonical 变更。
 */
class ConvergenceReviewUseCase(
    private val projects: ProjectUseCases,
    private val novelAgent: NovelAgent,
    private val commits: CommitUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    fun review(
        novelId: NovelId,
        variantId: VariantId?,
        chapterId: ChapterId,
        artifactId: ChangeArtifactId,
    ): ConvergenceReviewResult = guard {
        val committed = commits.committedByArtifact(artifactId)
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("提交事实不存在: ${artifactId.value}"),
            )
        if (committed.target.novelId != novelId || committed.target.chapterId != chapterId) {
            throw ApplicationException(
                ApplicationError.EntityNotFound("提交事实不属于指定作品/章节: ${artifactId.value}"),
            )
        }
        val project = projects.projectOf(novelId)
        val reviewRequest = NovelAgentRequest(
            projectId = project.projectId,
            userIntent = "检查本章人物一致性与前文偏离",
            activeChapterId = chapterId,
            activeVariantId = variantId,
            preferredSkillId = CoreSkills.CHARACTER_CONSISTENCY.skillId,
        )
        convergenceReviewResult(
            novelId = novelId,
            chapterId = chapterId,
            sourceCommitId = committed.commitId,
            sourceArtifactId = artifactId,
            result = novelAgent.run(reviewRequest),
        )
    }
}
