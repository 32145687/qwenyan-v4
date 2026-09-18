package com.qianyan.application.usecase.author

import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.AuthorProfile
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceScope

/**
 * P16 AIL-1 · AuthorIntelligenceGateway — Android / 未来 Desktop 与 Author Intelligence 之间的**用户层 Application seam**。
 *
 * 只暴露 AIL-1 必需能力（Author Preference 基础 User Control：查看 / 确认 / 拒绝 / 暂停 / 恢复 /
 * Explicit 新增 / 只读采集 P15 Evidence / AuthorContext 投影）。
 * **不提前加入 P17/P18/P19 API**；UI 只能经本 seam 触 Author 能力，不得触碰
 * AuthorPreferenceRepository / AuthorEvidence / SQLDelight / 未确认 Candidate 的原始 Evidence / LLM。
 */
interface AuthorIntelligenceGateway {

    // ---- Profile ----
    fun getAuthorProfile(): AuthorProfile?
    fun getOrCreateAuthorProfile(displayName: String = "作者"): AuthorProfile

    // ---- Explicit ----
    fun addExplicitPreference(
        dimension: PreferenceDimension,
        statement: String,
        scope: PreferenceScope = PreferenceScope.GLOBAL,
        novelId: NovelId? = null,
        revocable: Boolean = false,
    ): AuthorPreference

    // ---- Evidence 采集（只读 P15） ----
    fun collectFoundationEvidence(novelId: NovelId): Int
    fun recordEvidence(type: AuthorEvidenceType, detail: String, source: String, novelId: NovelId? = null): AuthorEvidence

    // ---- User Control：确认 / 拒绝 / 暂停 / 恢复 ----
    fun confirmPreference(preferenceId: AuthorPreferenceId): AuthorPreference
    fun rejectPreference(preferenceId: AuthorPreferenceId): AuthorPreference
    fun pausePreference(preferenceId: AuthorPreferenceId): AuthorPreference
    fun resumePreference(preferenceId: AuthorPreferenceId): AuthorPreference

    // ---- View ----
    fun getPreference(preferenceId: AuthorPreferenceId): AuthorPreference?
    fun listPreferences(scope: PreferenceScope? = null, novelId: NovelId? = null): List<AuthorPreference>
    fun listCandidates(scope: PreferenceScope? = null, novelId: NovelId? = null): List<AuthorPreference>

    // ---- AuthorContext 投影（Planner / Writer 唯一 Author 入口） ----
    fun buildAuthorContext(novelId: NovelId?): AuthorContext
}

/**
 * P16 AIL-1 · AuthorIntelligenceFacade — [AuthorIntelligenceGateway] 的**极薄委托**实现（镜像
 * FoundationDecisionFacade / IdeaFirstFacade / ChapterWorkflowFacade）。纯转发 [AuthorPreferenceUseCases]，
 * 不携带业务规则。
 */
class AuthorIntelligenceFacade(
    private val useCases: AuthorPreferenceUseCases,
) : AuthorIntelligenceGateway {

    override fun getAuthorProfile(): AuthorProfile? = useCases.getAuthorProfile()

    override fun getOrCreateAuthorProfile(displayName: String): AuthorProfile =
        useCases.getOrCreateAuthorProfile(displayName)

    override fun addExplicitPreference(
        dimension: PreferenceDimension,
        statement: String,
        scope: PreferenceScope,
        novelId: NovelId?,
        revocable: Boolean,
    ): AuthorPreference = useCases.addExplicitPreference(dimension, statement, scope, novelId, revocable)

    override fun collectFoundationEvidence(novelId: NovelId): Int = useCases.collectFoundationEvidence(novelId)

    override fun recordEvidence(
        type: AuthorEvidenceType,
        detail: String,
        source: String,
        novelId: NovelId?,
    ): AuthorEvidence = useCases.recordEvidence(type, detail, source, novelId)

    override fun confirmPreference(preferenceId: AuthorPreferenceId): AuthorPreference =
        useCases.confirmPreference(preferenceId)

    override fun rejectPreference(preferenceId: AuthorPreferenceId): AuthorPreference =
        useCases.rejectPreference(preferenceId)

    override fun pausePreference(preferenceId: AuthorPreferenceId): AuthorPreference =
        useCases.pausePreference(preferenceId)

    override fun resumePreference(preferenceId: AuthorPreferenceId): AuthorPreference =
        useCases.resumePreference(preferenceId)

    override fun getPreference(preferenceId: AuthorPreferenceId): AuthorPreference? =
        useCases.getPreference(preferenceId)

    override fun listPreferences(scope: PreferenceScope?, novelId: NovelId?): List<AuthorPreference> =
        useCases.listPreferences(scope, novelId)

    override fun listCandidates(scope: PreferenceScope?, novelId: NovelId?): List<AuthorPreference> =
        useCases.listCandidates(scope, novelId)

    override fun buildAuthorContext(novelId: NovelId?): AuthorContext = useCases.buildAuthorContext(novelId)
}