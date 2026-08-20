package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.NovelId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.storage.repository.MemoryRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.VocabularyRepository

/**
 * Planning Context Assembly（P11.2）。
 *
 * 把用户创作要求 + 必要的既有领域信息组装为最小 [PlanningContext]，供 Planner Agent 使用。
 * 只收集 Planning 真正需要的信息：请求本体 + Novel / Variant 背景 + 当前作用域下可见的
 * Memory / Vocabulary（Character 无持久化仓储，P11.2 保持空投影，见 Known Issue）。
 *
 * 复用已有仓储契约（[NovelRepository] / [MemoryRepository] / [VocabularyRepository]），
 * 不新增仓储、不触碰 SQLDelight。错误类型化：缺 Novel / Variant → EntityNotFound；
 * 请求缺 baseNovelId → InvalidOperation；Variant 与 Novel 不匹配 → VariantMismatch。
 */
class PlanningContextAssembly(
    private val novelRepository: NovelRepository,
    private val memoryRepository: MemoryRepository,
    private val vocabularyRepository: VocabularyRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    fun assemble(request: UserWritingRequest): PlanningContext {
        // 1) 定位目标 Novel：baseNovelId 必填（P11.2 Planning 必须有明确作用域）
        val baseNovelId = request.baseNovelId
            ?: throw ApplicationException(
                ApplicationError.InvalidOperation("UserWritingRequest 缺少 baseNovelId，无法定位规划目标 Novel"),
            )
        val novel = guard { novelRepository.getNovel(NovelId(baseNovelId.value)) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("Novel 不存在: ${baseNovelId.value}"),
            )

        // 2) Variant（可选）：存在则校验属于该 Novel
        val variantId = request.variantId
        val variant = variantId?.let { vid ->
            guard { novelRepository.getVariant(vid) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Variant 不存在: ${vid.value}"))
        }
        if (variant != null && variant.novelId.value != novel.novelId.value) {
            throw ApplicationException(
                ApplicationError.VariantMismatch("Variant ${variant.variantId.value} 不属于 Novel ${novel.novelId.value}"),
            )
        }

        // 3) 当前作用域可见 Memory / Vocabulary（Variant 上下文只取该 Variant，不读其它 Variant）
        val memories = if (variantId != null) {
            guard { memoryRepository.findEntriesByVariant(novel.novelId, variantId) }
        } else {
            guard { memoryRepository.findEntriesByNovel(novel.novelId) }
        }
        val vocabulary = if (variantId != null) {
            guard { vocabularyRepository.findEntriesByVariant(variantId) }
        } else {
            guard { vocabularyRepository.findEntriesByNovel(novel.novelId) }
        }

        // 4) 组装最小投影（Character 无持久化仓储 → 空列表，见 Known Issue）
        return PlanningContext(
            request = request,
            novelId = novel.novelId,
            variantId = variantId,
            novelTitle = novel.title,
            novelGenre = novel.genre,
            novelSynopsis = novel.synopsis,
            variantName = variant?.name ?: "",
            variantDirective = listOfNotNull(
                variant?.blueprint?.note,
                variant?.scopeSpec?.directive,
            ).joinToString("\n"),
            characters = emptyList(),
            memories = memories.map { it.content },
            vocabulary = vocabulary.map { VocabularyLite(canonical = it.canonical, aliases = it.aliases, replacement = it.replacement) },
        )
    }
}