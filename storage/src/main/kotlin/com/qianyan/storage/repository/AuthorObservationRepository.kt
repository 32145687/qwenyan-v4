package com.qianyan.storage.repository

import com.qianyan.model.AuthorObservationId
import com.qianyan.model.author.AuthorObservation

/** P18-A · AuthorObservation 持久化边界（独立 Author Storage Boundary；不改 Story 表）。 */
interface AuthorObservationRepository {
    /** 幂等 upsert：observationId 为 deterministic 键，重复提交覆盖自身（不产生新学习结果）。 */
    fun upsert(observation: AuthorObservation)

    fun getById(id: AuthorObservationId): AuthorObservation?

    fun exists(id: AuthorObservationId): Boolean

    fun all(): List<AuthorObservation>

    fun count(): Long

    fun delete(id: AuthorObservationId)
}