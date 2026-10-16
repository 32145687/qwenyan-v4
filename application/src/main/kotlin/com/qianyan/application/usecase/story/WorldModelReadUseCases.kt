package com.qianyan.application.usecase.story

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.writing.context.StoryWorldContextResolver
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.StoryWorldContext

/**
 * Phase 2 · World Model **读侧**用例（v3.1 §11.5「Resolver → 读侧契约（Tool 层能力）→ 被 Context 消费」）。
 *
 * 唯一职责：把已持久化的 World Model（Character / CharacterState / Event / WorldRule / Timeline /
 * Foreshadow，含 v27 补齐字段）经确定性 [StoryWorldContextResolver] 组装为 [StoryWorldContext]，
 * 供只读 Product Tool 与上层 Context 消费。
 *
 * 边界：
 *  - **只读**：不产生任何 World Model 写入、Change Artifact、Commit 或 Canonical 副作用；
 *  - 作用域校验复用既有 [NovelUseCases.getVariantContext]（Novel 存在性 + Variant 归属，类型化错误），
 *    与 Resolver 的 Original/Variant 隔离查询（base + own 合并，不跨 Variant 串数据）完全一致；
 *  - 空数据语义稳定：无任何 World Model 行 ⇒ 空列表的 [StoryWorldContext]（不报错、不伪造数据）；
 *  - 不新建第二套 World Model / Context 系统；不引入任何自动事实抽取或推断式写入（Phase 3 边界）。
 */
class WorldModelReadUseCases(
    private val novels: NovelUseCases,
    private val worldContextResolver: StoryWorldContextResolver,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 读取某 Novel / Variant 作用域的确定性世界上下文。
     *
     * @throws ApplicationException.EntityNotFound Novel / Variant 不存在
     * @throws ApplicationException.VariantMismatch Variant 不属于该 Novel
     */
    fun worldContext(novelId: NovelId, variantId: VariantId?): StoryWorldContext {
        // 作用域校验（存在性 + Variant 归属）：与 Planning Context 组装共用同一套既有校验。
        guard { novels.getVariantContext(novelId, variantId) }
        val novel = guard { novels.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        val scope = if (variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT
        return worldContextResolver.resolve(
            novelId = novelId,
            variantId = variantId,
            scope = scope,
            worldSummary = listOfNotNull(novel.title, novel.synopsis.takeIf { it.isNotBlank() }).joinToString("\n"),
        )
    }
}
