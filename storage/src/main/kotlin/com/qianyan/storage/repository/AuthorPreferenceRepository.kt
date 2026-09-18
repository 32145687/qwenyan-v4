package com.qianyan.storage.repository

import com.qianyan.model.AuthorEvidenceId
import com.qianyan.model.AuthorPreferenceId
import com.qianyan.model.NovelId
import com.qianyan.model.author.AuthorEvidence
import com.qianyan.model.author.AuthorPreference
import com.qianyan.model.author.AuthorProfile
import com.qianyan.model.author.PreferenceScope

/**
 * Author Intelligence 持久化仓储（P16 AIL-1）。
 *
 * 语义（P16 AIL-0 DEC-013/014）：
 *  - **独立 Author Storage Boundary**：只持久化 Author 领域（Profile / Preference / Evidence），
 *    禁止复用 StoryFoundation / StoryState / NarrativeState / NovelKnowledge / MemoryEntry / Checkpoint / Gate。
 *  - P15 的 Checkpoint / Gate 仅作为**只读 Evidence 来源**（由 Application 层经 WorkflowRepository/TaskRepository 读取），
 *    不进入 Author 主存储。
 *  - 本层只做持久化，不实现"User Confirmation Gate"等 Application 层业务约束（那些在 UseCase）。
 */
interface AuthorPreferenceRepository {

    // ---- AuthorProfile ----
    fun upsertAuthorProfile(profile: AuthorProfile)
    fun getAuthorProfile(): AuthorProfile?

    // ---- AuthorPreference ----
    fun upsertAuthorPreference(preference: AuthorPreference)
    fun getAuthorPreference(id: AuthorPreferenceId): AuthorPreference?
    fun listPreferences(scope: PreferenceScope? = null, novelId: NovelId? = null): List<AuthorPreference>
    fun deleteAuthorPreference(id: AuthorPreferenceId)

    // ---- AuthorEvidence ----
    fun upsertAuthorEvidence(evidence: AuthorEvidence)
    fun getAuthorEvidence(id: AuthorEvidenceId): AuthorEvidence?
    fun listEvidence(novelId: NovelId? = null): List<AuthorEvidence>
}