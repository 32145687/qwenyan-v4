package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.core.OverrideOperation
import com.qianyan.storage.db.QianyanDb
import kotlinx.serialization.json.JsonElement

/** [NovelRepository] 的 SQLDelight + SQLite JDBC 实现。 */
class SqliteNovelRepository(
    private val db: QianyanDb,
) : NovelRepository {

    override fun createOriginal(novel: Novel): NovelId {
        require(novel.scope == VariantScope.ORIGINAL) {
            "Novel 必须以 scope=ORIGINAL 创建；实际 scope=${novel.scope}"
        }
        val row = StorageMappers.domainNovel(novel)
        db.transaction {
            runCatching {
                db.novelQueries.insertNovel(
                    row.novel_id, row.project_id, row.title, row.source,
                    row.genre, row.synopsis, row.scope, row.status,
                    row.created_at, row.updated_at,
                )
            }.onFailure { throw mapWriteError(it) }
        }
        return novel.novelId
    }

    override fun getNovel(novelId: NovelId): Novel? =
        db.novelQueries.getNovel(novelId.value).executeAsOneOrNull()?.let { StorageMappers.dbNovel(it) }

    override fun createVariant(variant: NovelVariant): VariantId {
        // 应用层校验：base 必须是 ORIGINAL（Variant→Variant 拒绝）；物理触发器兜底。
        val base = getNovel(NovelId(variant.baseNovelId.value))
            ?: throw VariantBaseViolation("baseNovelId=${variant.baseNovelId.value} 不存在或不是 Original")
        if (base.scope != VariantScope.ORIGINAL) throw VariantBaseViolation()

        val row = StorageMappers.domainVariant(variant)
        db.transaction {
            runCatching { db.novelVariantQueries.insertVariant(row.variant_id, row.novel_id, row.base_novel_id, row.project_id, row.name, row.status, row.blueprint, row.scope_spec, row.created_at, row.updated_at) }
        }
        return variant.variantId
    }

    override fun getVariant(variantId: VariantId): NovelVariant? =
        db.novelVariantQueries.getVariantById(variantId.value).executeAsOneOrNull()?.let { StorageMappers.dbVariant(it) }

    override fun getVariantsOfNovel(novelId: NovelId): List<NovelVariant> =
        db.novelVariantQueries.getVariantsByNovel(novelId.value).executeAsList().map { StorageMappers.dbVariant(it) }

    override fun saveVariantData(variant: NovelVariant) {
        val row = StorageMappers.domainVariant(variant)
        db.transaction {
            runCatching {
                db.novelVariantQueries.updateVariant(
                    name = row.name,
                    status = row.status,
                    blueprint = row.blueprint,
                    scope_spec = row.scope_spec,
                    updated_at = row.updated_at,
                    variant_id = row.variant_id,
                )
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun saveOverride(override: EntityOverride) {
        val row = StorageMappers.domainOverride(override)
        db.transaction {
            runCatching {
                db.entityOverrideQueries.insertOverride(
                    row.override_id, row.variant_id, row.target_kind, row.target_id,
                    row.operation, row.replaced_value, row.note,
                )
            }.onFailure { throw mapWriteError(it) }
        }
    }

    override fun getOverride(variantId: VariantId, targetId: String): EntityOverride? =
        db.entityOverrideQueries.selectOverrideByVariantAndTarget(variantId.value, targetId)
            .executeAsOneOrNull()?.let { StorageMappers.dbOverride(it) }

    override fun getOverrides(variantId: VariantId): List<EntityOverride> =
        db.entityOverrideQueries.selectOverridesByVariant(variantId.value)
            .executeAsList().map { StorageMappers.dbOverride(it) }

    override fun deleteOverride(variantId: VariantId, targetId: String) {
        db.transaction {
            db.entityOverrideQueries.deleteOverrideByVariantAndTarget(variantId.value, targetId)
        }
    }

    override fun resolveOverride(variantId: VariantId, targetId: String, originalValue: JsonElement?): JsonElement? {
        val override = getOverride(variantId, targetId)
        return when {
            override == null -> originalValue
            override.operation == OverrideOperation.INHERIT -> originalValue
            override.operation == OverrideOperation.REMOVE -> null
            else -> override.replacedValue
        }
    }

    private fun mapWriteError(e: Throwable): Throwable {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("UNIQUE", ignoreCase = true) -> UniqueConflictException("违反唯一约束: $msg")
            msg.contains("constraint", ignoreCase = true) -> UniqueConflictException("违反约束: $msg")
            msg.contains("immutable", ignoreCase = true) -> OriginalImmutableException()
            msg.contains("Variant base", ignoreCase = true) -> VariantBaseViolation()
            else -> e
        }
    }
}