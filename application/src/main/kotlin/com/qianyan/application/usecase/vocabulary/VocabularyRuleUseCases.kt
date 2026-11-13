package com.qianyan.application.usecase.vocabulary

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyRuleId
import com.qianyan.model.vocabulary.VocabularyRule
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.storage.repository.VocabularyRepository

/**
 * T-O37-A · 词库规则的应用入口（T-O37-B 起补齐**管理**：列表 / 新增 / 启停；无删除）。
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
 *
 * T-O37-B 管理面只开放 **NOVEL 层**（绑定当前作品）：VARIANT / GLOBAL / TASK 不经 UI 创建；
 * 创建期强约束 `deterministicOnly = true`、`enabled = true`，并拒绝空原词 / 同形 / 非幂等 / 重复。
 */
class VocabularyRuleUseCases(
    private val repository: VocabularyRepository,
    private val vocabulary: VocabularyUseCases,
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

    /** T-O37-B：当前作品的 NOVEL 层规则（UI「规则」tab 数据源；不含其它作品/其它层）。 */
    fun listRules(novelId: NovelId): List<VocabularyRule> = guard {
        repository.listRules().filter {
            it.scopeLevel == VocabularyScopeLevel.NOVEL && it.variantId == null && it.novelId == novelId
        }.sortedWith(compareBy({ it.from }, { it.ruleId.value }))
    }

    /**
     * T-O37-B：为当前作品创建一条 NOVEL 层确定性替换规则。
     *
     * 创建期校验（失败即类型化拒绝，绝不静默覆盖）：
     *  - 空原词（trim 后）→ [ApplicationError.InvalidOperation]；
     *  - 原词 == 替换词 → 无效（不会产生任何变化）；
     *  - 替换词包含原词 → 无效（重复执行会继续变化，非幂等）；
     *  - 同作品同层已存在同原词 → 拒绝（不覆盖既有规则）。
     */
    fun createRule(novelId: NovelId, from: String, to: String): VocabularyRule = guard {
        val normalizedFrom = from.trim()
        val normalizedTo = to.trim()
        requireCreatable(normalizedFrom, normalizedTo)
        val duplicated = repository.listRules().any {
            it.scopeLevel == VocabularyScopeLevel.NOVEL && it.variantId == null &&
                it.novelId == novelId && it.from == normalizedFrom
        }
        if (duplicated) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("该作品已存在原词「$normalizedFrom」的规则，未覆盖"),
            )
        }
        val rule = VocabularyRule(
            ruleId = VocabularyRuleId(nextId()),
            vocabularyId = vocabulary.getOrCreateNovelVocabulary(novelId),
            novelId = novelId,
            variantId = null,
            scopeLevel = VocabularyScopeLevel.NOVEL,
            from = normalizedFrom,
            to = normalizedTo,
            enabled = true,
            deterministicOnly = true,
        )
        repository.saveRule(rule)
        rule
    }

    /** T-O37-B：启用 / 停用一条既有规则（只改 enabled；规则不存在 → EntityNotFound，不静默成功）。 */
    fun setRuleEnabled(ruleId: VocabularyRuleId, enabled: Boolean) = guard {
        val exists = repository.listRules().any { it.ruleId == ruleId }
        if (!exists) {
            throw ApplicationException(ApplicationError.EntityNotFound("词库规则不存在：${ruleId.value}"))
        }
        repository.updateRuleEnabled(ruleId, enabled)
    }

    private fun requireCreatable(from: String, to: String) {
        when {
            from.isEmpty() ->
                throw ApplicationException(ApplicationError.InvalidOperation("规则原词不能为空"))
            from == to ->
                throw ApplicationException(
                    ApplicationError.InvalidOperation("原词与替换词相同（$from），不会产生任何变化"),
                )
            to.contains(from) ->
                throw ApplicationException(
                    ApplicationError.InvalidOperation("替换词「$to」包含原词「$from」，重复执行会继续变化"),
                )
        }
    }
}

