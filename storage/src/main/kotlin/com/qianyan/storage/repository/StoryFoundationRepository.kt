package com.qianyan.storage.repository

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.foundation.FoundationOverride
import com.qianyan.model.foundation.StoryFoundation

/**
 * Story Foundation 持久化仓储（P14-F.2）。
 *
 * 语义（P14-F 冻结）：
 *  - [StoryFoundation]：Novel-level，一个 Novel 至多一个当前 Foundation；不进入 Story State，
 *    不使用 EntityOverride；`genre` 复用 P14-A 的 GenreId。
 *  - [FoundationOverride]：Variant-level，一个 Variant 至多一个 Override，仅保存差异；
 *    NULL 字段 = 继承 Original，非 NULL = 该字段被本 Variant 覆盖。
 *
 * 本层职责为持久化，不实现"Original 真源不可删除"等 Application 层业务约束（后续阶段）。
 */
interface StoryFoundationRepository {

    /** 写入/覆盖某 Novel 的 Foundation（PK=novelId，INSERT OR REPLACE，幂等）。 */
    fun upsertStoryFoundation(foundation: StoryFoundation)

    /** 读取某 Novel 的 Foundation；不存在返回 null。 */
    fun getStoryFoundation(novelId: NovelId): StoryFoundation?

    /** 是否存在。 */
    fun existsStoryFoundation(novelId: NovelId): Boolean

    /** 删除某 Novel 的 Foundation。 */
    fun deleteStoryFoundation(novelId: NovelId)

    /** 写入/覆盖某 Variant 的 Override（PK=variantId，INSERT OR REPLACE，幂等）。 */
    fun upsertFoundationOverride(override: FoundationOverride)

    /** 读取某 Variant 的 Override；不存在返回 null。 */
    fun getFoundationOverride(variantId: VariantId): FoundationOverride?

    /** 是否存在。 */
    fun existsFoundationOverride(variantId: VariantId): Boolean

    /** 删除某 Variant 的 Override（删除后该 Variant 自然恢复继承 Original）。 */
    fun deleteFoundationOverride(variantId: VariantId)
}