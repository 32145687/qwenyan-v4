package com.qianyan.model.genre

import com.qianyan.model.GenreId
import kotlinx.serialization.Serializable

/**
 * P14-A · Genre Taxonomy：受控的故事类型分类体系。
 *
 * 冻结边界（P14 Architecture Freeze + P14-A Audit）：
 *  - **Controlled**：Taxonomy 是全局受控目录；LLM **只能推荐其中已有 Genre**，**不可创建新 Genre**。
 *  - **Hierarchical (single-level)**：MVP 只支持单层 parent（`parentId?`），不实现深层 taxonomy engine。
 *  - **Stable ID**：`genreId` 为稳定强类型 ID；`displayName` 仅作展示，**不作为身份**。
 *  - **Taxonomy Definition ≠ Confirmed Genre**：本文件是全局目录（定义层）；每本书"已确认 Genre"
 *    落在既有 `Novel.genre` / `NovelVariant.genre`（metadata），不经本目录承载实例状态。
 *  - 不加入 popularity / market trend / AI score / embedding / vector / weight 等动态数据（属 Later / P14-B+）。
 */

/** 单条受控 Genre。 */
@Serializable
data class Genre(
    val genreId: GenreId,
    val displayName: String,
    val parentId: GenreId? = null,
    val description: String = "",
)

/** 受控 Genre 目录（全局 Taxonomy 定义，immutable）。 */
@Serializable
data class GenreTaxonomy(
    val version: Long,
    val genres: List<Genre>,
)

/** 校验结果（纯函数输出，不依赖 Application 错误类型）。 */
data class GenreValidation(
    val isValid: Boolean,
    val errors: List<String>,
    val resolved: List<GenreId>,
)

/**
 * Genre Taxonomy 确定性校验规则（纯函数，无 storage / 无 LLM）。
 */
object GenreTaxonomyRules {

    /**
     * 校验一组 GenreId：
     *  1. 每个 id 必须存在于 Taxonomy；
     *  2. 不允许重复；
     *  3. parentId 必须存在；
     *  4. 不允许 self-parent（单层父，天然无环）；
     *  5. 不自动创建未知 Genre。
     * 校验不通过 → errors 非空、`isValid=false`；通过 → `resolved` 为该组 id。
     */
    fun validate(taxonomy: GenreTaxonomy, ids: List<GenreId>): GenreValidation {
        val errors = mutableListOf<String>()
        val byId = taxonomy.genres.associateBy { it.genreId.value }

        // 1 + 2
        if (ids.isEmpty()) errors += "Genre 集合为空"
        val seen = mutableSetOf<String>()
        for (id in ids) {
            if (!seen.add(id.value)) errors += "GenreId 重复: ${id.value}"
            if (byId[id.value] == null) errors += "未知 GenreId: ${id.value}"
        }

        // 3 + 4：Taxonomy 目录完整性（对目录自身校验；同时保证 resolved 的子项父节点存在）
        val catalogIds = byId.keys
        if (catalogIds.size != taxonomy.genres.size) errors += "Taxonomy 存在重复 GenreId"
        for (g in taxonomy.genres) {
            val parent = g.parentId
            if (parent != null) {
                if (parent == g.genreId) errors += "self-parent: ${g.genreId.value}"
                if (byId[parent.value] == null) errors += "parent 不存在: ${g.genreId.value} → ${parent.value}"
            }
        }

        return if (errors.isEmpty()) GenreValidation(true, emptyList(), ids) else GenreValidation(false, errors, emptyList())
    }
}

/**
 * P14-A 默认受控 Genre 目录（最小可用网文分类集合，单层 parent）。
 * immutable、稳定序；不随运行时修改。
 */
object GenreTaxonomyCatalog {

    const val VERSION: Long = 1L

    /** 顶层 Genre；每个 displayName 稳定且唯一。 */
    private val TOP_LEVEL: List<Genre> = listOf(
        Genre(GenreId("romance"), "言情", description = "以感情/亲密关系为核心驱动"),
        Genre(GenreId("fantasy_eastern"), "东方玄幻", description = "东方体系修炼/修真成长"),
        Genre(GenreId("fantasy_urban"), "都市异能", description = "现代都市中的非现实能力"),
        Genre(GenreId("xianxia"), "仙侠", description = "求道长生、飞升成仙"),
        Genre(GenreId("wuxia"), "武侠", description = "江湖侠义、武功对决"),
        Genre(GenreId("scifi"), "科幻", description = "未来科技/太空/硬科幻"),
        Genre(GenreId("system"), "系统流", description = "绑定系统/金手指驱动"),
        Genre(GenreId("game"), "游戏/无限流", description = "副本/无限世界"),
        Genre(GenreId("mystery"), "悬疑", description = "谜团/推理/惊悚"),
        Genre(GenreId("horror"), "灵异", description = "鬼怪/恐怖/超自然"),
        Genre(GenreId("historical"), "历史/架空", description = "古代背景/权谋争霸"),
        Genre(GenreId("military"), "军事/战争", description = "战场/军事行动"),
        Genre(GenreId("sliceoflife"), "日常/温馨", description = "轻松日常/治愈"),
        Genre(GenreId("adaptation"), "衍生/同人", description = "基于既有作品的再创作"),
    )

    /** 单层子 Genre（parent 均为上述顶层）。 */
    private val CHILDREN: List<Genre> = listOf(
        Genre(GenreId("romance_gu"), "古言", parentId = GenreId("romance")),
        Genre(GenreId("romance_xian"), "现言", parentId = GenreId("romance")),
        Genre(GenreId("romance_sweet"), "甜宠", parentId = GenreId("romance")),
        Genre(GenreId("fantasy_eastern_gaowu"), "高武", parentId = GenreId("fantasy_eastern")),
        Genre(GenreId("fantasy_eastern_yishi"), "异世大陆", parentId = GenreId("fantasy_eastern")),
        Genre(GenreId("scifi_hard"), "硬科幻", parentId = GenreId("scifi")),
        Genre(GenreId("scifi_apocalypse"), "末世/废土", parentId = GenreId("scifi")),
        Genre(GenreId("mystery_detective"), "推理", parentId = GenreId("mystery")),
        Genre(GenreId("historical_power"), "权谋/争霸", parentId = GenreId("historical")),
    )

    val DEFAULT: GenreTaxonomy = GenreTaxonomy(
        version = VERSION,
        genres = (TOP_LEVEL + CHILDREN).sortedWith(compareBy({ it.genreId.value })),
    )
}