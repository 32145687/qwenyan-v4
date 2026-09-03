package com.qianyan.model.context

import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.memory.MemoryLayer
import kotlinx.serialization.Serializable

/**
 * 故事世界上下文（P11.6）：把已持久化的 Story / Knowledge / Memory 确定性组装为
 * Planning / Writing 可直接读取的世界状态视图。
 *
 * **Derived / assembled view，不是新的持久化事实**：本模型由 [com.qianyan.application.usecase.writing.context.StoryWorldContextResolver]
 * 从已有 Repository 数据确定性解析而来；不落库、不新增 Schema。纯领域数据，不依赖 SQLite / Repository 实现 / Android / Provider。
 *
 * Memory Layer 确定性分层（based 现有 [MemoryLayer] 语义），高优先 → 低优先：
 *  - [canon]     = MemoryLayer.ORIGINAL（不可被普通记忆覆盖）
 *  - [worldState]= MemoryLayer.CURRENT_STATE
 *  - [facts]     = MemoryLayer.LONG_TERM
 *  - [memories]  = MemoryLayer.WRITING（创作派生，最低优先）
 *
 * [orderedVisible] 提供 canon 优先的确定性命中序（不重复），供 Context 投影直接使用。
 * Canon 保护：任何普通 Memory（低层）不能在本视图里位于 canon 之前，因而不能覆盖 canon。
 */
@Serializable
data class StoryWorldContext(
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val scope: VariantScope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT,
    /** 世界摘要（Novel 标题 / 简介的最小投影，仅供参考与锚定）。 */
    val worldSummary: String = "",
    /** 已确认 Canon 事实（MemoryLayer.ORIGINAL）。 */
    val canon: List<String> = emptyList(),
    /** 当前世界状态（MemoryLayer.CURRENT_STATE）。 */
    val worldState: List<String> = emptyList(),
    /** 已确认长期事实（MemoryLayer.LONG_TERM）。 */
    val facts: List<String> = emptyList(),
    /** 当前创作记忆（MemoryLayer.WRITING，最低优先）。 */
    val memories: List<String> = emptyList(),
) {
    /** canon 优先的确定性顺序（canon → worldState → facts → memories），供 Context 投影。 */
    val orderedVisible: List<String> get() = canon + worldState + facts + memories
}