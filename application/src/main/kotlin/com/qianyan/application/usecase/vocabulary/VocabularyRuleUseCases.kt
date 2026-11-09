package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.repository.VocabularyRepository

/**
 * T-O37-A · 词库规则的**只读**应用入口（不做管理：无新增/启停/删除，属 T-O37-B）。
 *
 * 归属过滤（本类负责；引擎只负责执行语义）：
 *  - NOVEL：`variantId == null && novelId == 目标 novel`；
 *  - VARIANT：`variantId == 目标 variant && (novelId == null || novelId == 目标 novel)`；
 *  - **全部 GLOBAL / TASK 行**：一并返回，由引擎判 `OUT_OF_SCOPE` 后跳过（使"未执行"可见）；
 *  - 其他作品的 NOVEL / VARIANT：不属于本上下文，排除。
 *
 * 返回的是**候选规则集**，其中 GLOBAL/TASK 由引擎判 OUT_OF_SCOPE 后跳过（不在此复制第二套过滤逻辑）。
 *
 * 不在此过滤 `enabled` / `deterministicOnly`：交给引擎逐条记录跳过原因（可观测）。
 */
class VocabularyRuleUseCases(
    private val repository: VocabularyRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    fun activeFor(novelId: NovelId, variantId: VariantId?): List<VocabularyRule> = guard {
        repository.listRules().filter { rule ->
            when (rule.scopeLevel) {
                VocabularyScopeLevel.NOVEL ->
                    rule.variantId == null && rule.novelId == novelId
                VocabularyScopeLevel.VARIANT ->
                    variantId != null && rule.variantId == variantId &&
                        (rule.novelId == null || rule.novelId == novelId)
                // GLOBAL / TASK：进入候选集，由引擎判 OUT_OF_SCOPE（可见而非静默过滤）
                VocabularyScopeLevel.GLOBAL, VocabularyScopeLevel.TASK -> true
            }
        }
    }
}
