package com.qianyan.storage.repository

import com.qianyan.model.NarrativeDeltaId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.lcl.NarrativeDelta
import com.qianyan.model.lcl.NarrativeState

/**
 * Narrative State 账本仓储（P13 LCL-A）。
 *
 * 职责：Narrative State（折叠快照）与 Narrative Delta（按章追加日志）的持久化。
 *
 * 变体隔离语义（与既有 Story State 一致）：
 *  - `variantId = null`：Original 作用域（**只读语义由 Application 层校验**，本层仅暴露方法）；
 *  - `variantId = 指定值`：该 Variant 作用域。
 * 不同 Variant / 不同 Novel 之间互不可见。
 *
 * 语义说明：
 *  - [appendNarrativeDelta]：仅 Insert（日志追加），支持 `expectedVersion` 乐观锁（防并发丢失更新）；
 *  - [project]：确定性折叠各 Delta → 写回快照（INSERT OR REPLACE，PK 为确定性账本 ID，幂等）；
 *  - [get]：读取最近折叠快照。
 */
interface NarrativeStateRepository {

    /** 按 novel+variant 读取折叠快照；未折叠返回 null。 */
    fun getNarrativeState(novelId: NovelId, variantId: VariantId?): NarrativeState?

    /** 折叠并持久化当前状态的确定性快照（version = Delta 数），返回快照。 */
    fun project(novelId: NovelId, variantId: VariantId?): NarrativeState

    /**
     * 追加一条 Narrative Delta（append log）。
     *
     * @param expectedVersion 乐观锁：非空时要求"当前已追加 Delta 数"=== expectedVersion，
     *   否则抛 [IllegalStateException]（并发修改已发生）；null 表示不校验。
     */
    fun appendNarrativeDelta(delta: NarrativeDelta, expectedVersion: Long? = null)

    /** 列出某 novel+variant 的全部 Delta（createdAt 升序 → deltaId 升序）。 */
    fun listNarrativeDeltas(novelId: NovelId, variantId: VariantId?): List<NarrativeDelta>
}