package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.core.EntityOverride
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import kotlinx.serialization.json.JsonElement

/**
 * Novel / Variant / EntityOverride 持久化仓储（领域语义，不暴露 SQLDelight 生成类型）。
 *
 * 核心不变式（P2.4）：
 *  - Original Novel 不可写：不允许通过本仓储修改/删除 scope=ORIGINAL 的 Novel（仅创建+读取）。
 *    物理写保护由数据库守卫触发器兜底（见 QianyanDbFactory）。
 *  - Variant 仅允许以 Original 为基座创建：禁止 Variant→Variant。
 */
interface NovelRepository {

    /** 创建 Original Novel（强制 scope=ORIGINAL）。返回其强类型 NovelId。 */
    fun createOriginal(novel: Novel): NovelId

    /** 读取 Original。不存在返回 null。 */
    fun getNovel(novelId: NovelId): Novel?

    /** 从一个 Original 创建 Variant（base 必须指向 scope=ORIGINAL 的 Novel）。返回强类型 VariantId。 */
    fun createVariant(variant: NovelVariant): VariantId

    /** 读取 Variant。不存在返回 null。 */
    fun getVariant(variantId: VariantId): NovelVariant?

    /** 查询某 Original 下的全部 Variant。 */
    fun getVariantsOfNovel(novelId: NovelId): List<NovelVariant>

    /** 保存（更新）Variant 数据。仅允许写 Variant。 */
    fun saveVariantData(variant: NovelVariant)

    /** 保存一条 EntityOverride（逻辑唯一键 targetId + variantId）。 */
    fun saveOverride(override: EntityOverride)

    /** 读取单个 Override。 */
    fun getOverride(variantId: VariantId, targetId: String): EntityOverride?

    /** 读取某 Variant 的全部 Override。 */
    fun getOverrides(variantId: VariantId): List<EntityOverride>

    /** 删除/撤销某 Variant 对某 targetId 的 Override。 */
    fun deleteOverride(variantId: VariantId, targetId: String)

    /**
     * P2.6 Variant 读穿透核心：
     * 给定 Original 基值与某 Variant 的 targetId，命中 Override 则返回 Override 值，
     * 否则（无 Override / INHERIT）返回基值，REMOVE 返回 null。
     * 不复制整棵 Original 树；不实现递归继承。
     */
    fun resolveOverride(variantId: VariantId, targetId: String, originalValue: JsonElement?): JsonElement?
}