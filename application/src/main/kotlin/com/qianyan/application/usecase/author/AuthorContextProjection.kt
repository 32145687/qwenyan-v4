package com.qianyan.application.usecase.author

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.PreferenceScope
import com.qianyan.storage.repository.AuthorPreferenceRepository
import kotlinx.datetime.Clock

/**
 * P16 AIL-1 · AuthorContext Projection —— 把 Author Intelligence 投影为**最小只读 [AuthorContext]**。
 *
 * 边界（P16 AIL-0 DEC-006/007/011）：
 *  - 只投影**稳定且激活**的偏好（`isStable`，且未暂停、未过期）；
 *  - **不暴露**未确认 Candidate、原始 Evidence、Repository、完整 Author 数据库；
 *  - 最小 Global/Novel Override：同一 [PreferenceDimension] 下，Novel 偏好覆盖 Global 偏好；
 *  - 供 Planner / Writer 消费（经 PlanningContext / WritingContext）。Planner / Writer / Agent **禁止直读 AuthorRepository**，
 *    统一经本项目投影得到的 [AuthorContext] 读取。
 */
class AuthorContextProjection(
    private val repository: AuthorPreferenceRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 投影当前创作的作者偏好（read-only）。
     * @param novelId 当前创作作用域；null = 仅 Global。
     */
    fun project(novelId: NovelId?): AuthorContext {
        val now = Clock.System.now()

        // 1) 稳定且激活的全局偏好
        val globalStable = guard { repository.listPreferences(scope = PreferenceScope.GLOBAL) }
            .filter { it.isActive(now) }

        // 2) 稳定且激活的 Novel 偏好（当前书覆盖）
        val novelStable = if (novelId != null) {
            guard { repository.listPreferences(novelId = novelId) }
                .filter { it.isActive(now) }
        } else {
            emptyList()
        }

        // 3) 最小 Override 合并：同 dimension 下 Novel 覆盖 Global（AIL-1 不实现复杂合并规则）
        val overriddenDimensions = novelStable.mapTo(mutableSetOf()) { it.dimension }
        val resolved = novelStable + globalStable.filter { it.dimension !in overriddenDimensions }

        // 4) 投影为最小只读 AuthorContext（按置信度降序，稳定顺序）
        return AuthorContext(
            preferences = resolved
                .sortedByDescending { it.confidence.value }
                .map { it.toLite() },
        )
    }
}

/** 激活判定：稳定 且 未暂停 且 未过期。 */
private fun AuthorPreference.isActive(now: kotlinx.datetime.Instant): Boolean =
    isStable && !paused && !expired(now)

/** expiry 为 nullable 跨模块属性，不能用智能转换，故显式判断。 */
private fun AuthorPreference.expired(now: kotlinx.datetime.Instant): Boolean =
    expiry?.let { it <= now } ?: false

/** 最小投影（不携带 Repository / 原始 Evidence / 未确认候选）。 */
private fun AuthorPreference.toLite() = AuthorContext.AuthorPreferenceLite(
    preferenceId = preferenceId,
    scope = scope,
    novelId = novelId,
    dimension = dimension,
    statement = statement,
    confidence = confidence,
    revocable = revocable,
)