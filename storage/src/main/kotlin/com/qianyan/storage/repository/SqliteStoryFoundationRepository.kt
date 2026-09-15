package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.foundation.FoundationOverride
import com.qianyan.model.foundation.StoryFoundation
import com.qianyan.storage.db.QianyanDb

/** [StoryFoundationRepository] 的 SQLDelight + SQLite 实现（P14-F.2）。 */
class SqliteStoryFoundationRepository(private val db: QianyanDb) : StoryFoundationRepository {

    override fun upsertStoryFoundation(foundation: StoryFoundation) {
        val row = StorageMappers.domainStoryFoundation(foundation)
        db.storyFoundationQueries.upsertStoryFoundation(
            novel_id = row.novel_id,
            base_novel_id = row.base_novel_id,
            scope = row.scope,
            version = row.version,
            genre = row.genre,
            direction = row.direction,
            audience = row.audience,
            policy = row.policy,
            created_at = row.created_at,
            updated_at = row.updated_at,
        )
    }

    override fun getStoryFoundation(novelId: NovelId): StoryFoundation? =
        db.storyFoundationQueries.getStoryFoundationByNovelId(novelId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbStoryFoundation(it) }

    override fun existsStoryFoundation(novelId: NovelId): Boolean =
        db.storyFoundationQueries.existsStoryFoundation(novelId.value).executeAsOne() > 0

    override fun deleteStoryFoundation(novelId: NovelId) {
        db.storyFoundationQueries.deleteStoryFoundation(novelId.value)
    }

    override fun upsertFoundationOverride(override: FoundationOverride) {
        val row = StorageMappers.domainFoundationOverride(override)
        db.storyFoundationQueries.upsertFoundationOverride(
            variant_id = row.variant_id,
            genre = row.genre,
            direction = row.direction,
            audience = row.audience,
            policy = row.policy,
            updated_at = row.updated_at,
        )
    }

    override fun getFoundationOverride(variantId: VariantId): FoundationOverride? =
        db.storyFoundationQueries.getFoundationOverrideByVariantId(variantId.value).executeAsOneOrNull()
            ?.let { StorageMappers.dbFoundationOverride(it) }

    override fun existsFoundationOverride(variantId: VariantId): Boolean =
        db.storyFoundationQueries.existsFoundationOverride(variantId.value).executeAsOne() > 0

    override fun deleteFoundationOverride(variantId: VariantId) {
        db.storyFoundationQueries.deleteFoundationOverride(variantId.value)
    }
}