package com.qianyan.application.usecase.genre

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.GenreId
import com.qianyan.model.genre.Genre
import com.qianyan.model.genre.GenreTaxonomy
import com.qianyan.model.genre.GenreTaxonomyCatalog
import com.qianyan.model.genre.GenreTaxonomyRules

/**
 * P14-A · GenreTaxonomyUseCases（薄 façade，受控 Taxonomy seam；不做 LLM 推荐——推荐属 P14-B）。
 *
 * 职责：
 *  - [availableGenres]：全局受控 Genre 集合（deterministic、immutable）。
 *  - [isKnown]：判断 GenreId 是否存在。
 *  - [validate]：确定性校验 GenreId 集合（存在 / 不重复 / parent 存在 / 非 self-parent）；非法 → typed 拒绝。
 *
 * ⚠️ BLOCKER（Confirmed Genre 写入路径）：冻结方案要求 Confirmed Genre 落 `NovelVariant.genre`，
 * 但 **`NovelVariant` 无 `genre` 字段**（仅 `Novel.genre` 存在，且 Original 创建后只读）。
 * 按 P14-A §11 硬约束（不修改 Schema / 不修改 domain 字段），Variant 级 Confirmed-Genre 写入**无法在 P14-A 兑现**，
 * 故本 seam 仅提供受控校验能力，**不实现写入**；Confirmed-Genre 持久化需在后续阶段（P14-F Story Foundation /
 * 或在允许扩展 NovelVariant metadata 的授权下）决定。此处如实上报 BLOCKER，不做静默 workaround。
 */
class GenreTaxonomyUseCases(
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 全局受控 Taxonomy 定义（immutable；后续 P14-B 推荐以此为准）。 */
    val taxonomy: GenreTaxonomy get() = GenreTaxonomyCatalog.DEFAULT

    /** 完整受控 Genre 集合（deterministic 稳定序，来自 Catalog）。 */
    fun availableGenres(): List<Genre> = taxonomy.genres.sortedBy { it.genreId.value }

    fun isKnown(genreId: GenreId): Boolean = taxonomy.genres.any { it.genreId == genreId }

    /** 校验 GenreId 集合；非法 → [ApplicationError.InvalidOperation]；合法返回 resolved ids。 */
    fun validate(ids: List<GenreId>): List<GenreId> {
        val result = GenreTaxonomyRules.validate(taxonomy, ids)
        if (!result.isValid) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("Genre 校验失败: ${result.errors.joinToString("; ")}"),
            )
        }
        return result.resolved
    }
}