package com.qianyan.application.usecase.author

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.AuthorProfile
import com.qianyan.model.author.Confidence
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceOrigin
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.repository.AuthorPreferenceRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * P16 AIL-1 · Author Preference Use Cases（Application 边界；无 LLM / Provider / Android 依赖）。
 *
 * 已冻结语义（P16 AIL-0）：
 *  - **Explicit / Inferred 双态**；
 *  - **Evidence → Candidate → Confidence → User Confirmation → Stable**：一次行为绝不直接成为永久偏好；
 *    未确认 Candidate（[AuthorPreference.isCandidate]）不进入 [AuthorContext]，可衰减（[expiry]）/ 被否定（[rejectPreference]）；
 *  - **User Control**：查看 / 确认 / 拒绝 / 暂停 / 恢复；AI Inference ≠ Author Truth，仅确认后 Candidate → Stable；
 *  - **只读采集 P15 信号**（[FoundationEvidenceSource]，不改 P15）；独立 Author Storage Boundary。
 *
 * 明确 Deferred：P17 Author Core / P18 DNA / P19 Decision Model / 复杂 Override 合并 / Backup / RAG / MCP / Cloud。
 */
class AuthorPreferenceUseCases(
    private val repository: AuthorPreferenceRepository,
    private val projection: AuthorContextProjection,
    private val evidenceSource: FoundationEvidenceSource,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    // ---------------- AuthorProfile ----------------

    /** 获取全局 Author Profile；不存在返回 null。 */
    fun getAuthorProfile(): AuthorProfile? = guard { repository.getAuthorProfile() }

    /** 幂等获取（不存在则创建）全局 Author Profile。 */
    fun getOrCreateAuthorProfile(displayName: String = "作者"): AuthorProfile {
        guard { repository.getAuthorProfile() }?.let { return it }
        val now = Clock.System.now()
        val profile = AuthorProfile(
            profileId = AuthorProfileId(nextId()),
            displayName = displayName,
            createdAt = now,
            updatedAt = now,
        )
        guard { repository.upsertAuthorProfile(profile) }
        return profile
    }

    // ---------------- Explicit Preference ----------------

    /**
     * 新增 Explicit Author Preference（用户明确表达 → 天然 Stable）。
     * @param scope GLOBAL 或 NOVEL；NOVEL 必须提供 [novelId]。
     */
    fun addExplicitPreference(
        dimension: PreferenceDimension,
        statement: String,
        scope: PreferenceScope = PreferenceScope.GLOBAL,
        novelId: NovelId? = null,
        revocable: Boolean = false,
    ): AuthorPreference {
        if (scope == PreferenceScope.NOVEL && novelId == null) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("NOVEL scope 的 Author Preference 必须提供 novelId"),
            )
        }
        val now = Clock.System.now()
        val pref = AuthorPreference(
            preferenceId = AuthorPreferenceId(nextId()),
            scope = scope,
            novelId = if (scope == PreferenceScope.NOVEL) novelId else null,
            dimension = dimension,
            statement = statement,
            origin = PreferenceOrigin.EXPLICIT,
            confidence = Confidence.HIGH,
            confirmed = false, // EXPLICIT 天然 Stable（见 isStable）
            revocable = revocable,
            obtainedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        guard { repository.upsertAuthorPreference(pref) }
        return pref
    }

    // ---------------- Evidence + Candidate（只读采集 P15 信号） ----------------

    /**
     * 从 P15 只读采集现有信号 → 只对**尚未持久化**的 Evidence 写库并各推进一次 Candidate。
     *
     * 幂等不变量（M-1 修复）：同一批 P15 Evidence 无论采集多少次，最终状态与只采集一次完全一致。
     * 依据确定性 Evidence ID（如 `p15-gate-<gateId>`）去重：
     *  - 已存在 → 不重写 Evidence、不重复 apply、不改 Candidate、无副作用；
     *  - 不存在 → 写 Evidence 并推进一次 Candidate（confidence 只 +0.1 一次）。
     *
     * “检查存在 → 写 Evidence → 更新 Candidate”在单写者 + 确定性 ID 下为一次性边界（不引入新事务/基础设施）。
     *
     * @return 本次**实际新增并应用**的 Evidence 数量（非扫描总数）。
     */
    fun collectFoundationEvidence(novelId: NovelId): Int {
        val signals = evidenceSource.foundationSignals(novelId)
        var newlyApplied = 0
        signals.forEach { signal ->
            val alreadyPersisted = guard { repository.getAuthorEvidence(signal.evidenceId) } != null
            if (!alreadyPersisted) {
                guard { repository.upsertAuthorEvidence(signal) }
                applyEvidence(signal)
                newlyApplied++
            }
        }
        return newlyApplied
    }

    /** 显式写入一条 AuthorEvidence（供上层把已识别的信号落为学习证据）并据此更新候选。 */
    fun recordEvidence(type: AuthorEvidenceType, detail: String, source: String, novelId: NovelId? = null): AuthorEvidence {
        val observed = AuthorEvidence(
            evidenceId = AuthorEvidenceId(nextId()),
            novelId = novelId ?: NovelId(""),
            type = type,
            detail = detail,
            source = source,
            observedAt = Clock.System.now(),
        )
        guard { repository.upsertAuthorEvidence(observed) }
        if (novelId != null) applyEvidence(observed)
        return observed
    }

    // ---------------- Candidate → Stable（User Confirmation Gate） ----------------

    /**
     * 用户确认一个 Candidate → Stable Author Preference。
     * 已 Stable（Explicit 或已确认）→ 幂等 no-op。
     */
    fun confirmPreference(preferenceId: AuthorPreferenceId): AuthorPreference {
        val pref = requirePreference(preferenceId)
        if (pref.isStable) return pref
        val stable = pref.copy(confirmed = true, expiry = null, updatedAt = Clock.System.now())
        guard { repository.upsertAuthorPreference(stable) }
        return stable
    }

    /** 拒绝（否定）一个 Candidate：立即过期 → 衰减/失效，不再进入投影。 */
    fun rejectPreference(preferenceId: AuthorPreferenceId): AuthorPreference {
        val pref = requirePreference(preferenceId)
        val negated = pref.copy(expiry = Clock.System.now(), updatedAt = Clock.System.now())
        guard { repository.upsertAuthorPreference(negated) }
        return negated
    }

    /** 暂停：投影隐藏但保留记录。 */
    fun pausePreference(preferenceId: AuthorPreferenceId): AuthorPreference {
        val pref = requirePreference(preferenceId)
        val paused = pref.copy(paused = true, updatedAt = Clock.System.now())
        guard { repository.upsertAuthorPreference(paused) }
        return paused
    }

    /** 恢复暂停。 */
    fun resumePreference(preferenceId: AuthorPreferenceId): AuthorPreference {
        val pref = requirePreference(preferenceId)
        val resumed = pref.copy(paused = false, updatedAt = Clock.System.now())
        guard { repository.upsertAuthorPreference(resumed) }
        return resumed
    }

    // ---------------- View ----------------

    fun getPreference(preferenceId: AuthorPreferenceId): AuthorPreference? =
        guard { repository.getAuthorPreference(preferenceId) }

    fun listPreferences(scope: PreferenceScope? = null, novelId: NovelId? = null): List<AuthorPreference> =
        guard { repository.listPreferences(scope, novelId) }

    /** 列出当前未确认（且未过期/未被动否定）的 Inferred Candidate（仅信誉候选，未进入 Stable）。 */
    fun listCandidates(scope: PreferenceScope? = null, novelId: NovelId? = null): List<AuthorPreference> =
        guard { repository.listPreferences(scope, novelId) }
            .filter { it.isCandidate && !expired(it) }

    /** 投影最小只读 [AuthorContext]（Planner / Writer 唯一 Author 入口）。 */
    fun buildAuthorContext(novelId: NovelId?): AuthorContext = projection.project(novelId)

    // ---------------- internal ----------------

    /**
     * 基础 Evidence → Candidate 学习（AIL-1 最小规则，P17 才实现正式学习算法）。
     *  - 正向（ADOPT / ADOPT_THEN_REVISE / MODIFY）：生成/强化一个 GLOBAL·STORY_DIRECTION·INFERRED Candidate，
     *    置信度 0.4 起、每例 +0.1、封顶 0.9；
     *  - 负向（REJECT / PARTIAL_REWRITE）：使既有同维度 Candidate 立即过期（衰减/否定）。
     * 所有 Candidate 均未确认（不进入 Stable），须用户确认后才晋升。
     */
    private fun applyEvidence(evidence: AuthorEvidence) {
        val now = Clock.System.now()
        val candidates = guard { repository.listPreferences(scope = PreferenceScope.GLOBAL) }
            .filter { it.dimension == CANDIDATE_DIMENSION && it.origin == PreferenceOrigin.INFERRED && !it.confirmed }

        when (evidence.type) {
            AuthorEvidenceType.REJECT, AuthorEvidenceType.PARTIAL_REWRITE -> {
                candidates.forEach {
                    val negated = it.copy(expiry = now, updatedAt = now)
                    guard { repository.upsertAuthorPreference(negated) }
                }
            }

            AuthorEvidenceType.ADOPT, AuthorEvidenceType.ADOPT_THEN_REVISE, AuthorEvidenceType.MODIFY -> {
                val existing = candidates.maxByOrNull { it.confidence.value }
                val nextConfidence = Confidence(
                    ((existing?.confidence?.value ?: 0.4) + 0.1).coerceAtMost(0.9),
                )
                val candidate = if (existing != null) {
                    existing.copy(
                        confidence = nextConfidence,
                        expiry = future(now, 30),
                        updatedAt = now,
                    )
                } else {
                    AuthorPreference(
                        preferenceId = AuthorPreferenceId(nextId()),
                        scope = PreferenceScope.GLOBAL,
                        dimension = CANDIDATE_DIMENSION,
                        statement = "用户对故事创作方向有持续倾向（P15 信号：${evidence.type.name}）",
                        origin = PreferenceOrigin.INFERRED,
                        confidence = nextConfidence,
                        confirmed = false,
                        revocable = true,
                        obtainedAt = now,
                        expiry = future(now, 30),
                        createdAt = now,
                        updatedAt = now,
                    )
                }
                guard { repository.upsertAuthorPreference(candidate) }
            }
        }
    }

    private fun requirePreference(id: AuthorPreferenceId): AuthorPreference =
        guard { repository.getAuthorPreference(id) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Author Preference 不存在: ${id.value}"))

    /** 是否已过期/被动否定（候选衰减失效）。 */
    private fun expired(p: AuthorPreference): Boolean =
        p.expiry?.let { it <= Clock.System.now() } ?: false

    /** 距今 [days] 天后的时刻（基于 epoch 秒，避免依赖 kotlinx-datetime Duration API）。 */
    private fun future(from: Instant, days: Long): Instant =
        Instant.fromEpochSeconds(from.epochSeconds + days * 86400, from.nanosecondsOfSecond)

    private companion object {
        val CANDIDATE_DIMENSION: PreferenceDimension = PreferenceDimension.STORY_DIRECTION
    }
}