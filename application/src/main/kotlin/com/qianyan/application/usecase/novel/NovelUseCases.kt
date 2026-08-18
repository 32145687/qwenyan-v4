package com.qianyan.application.usecase.novel

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.core.NovelVariant
import com.qianyan.model.core.VariantContext
import com.qianyan.model.core.VariantStatus
import com.qianyan.storage.repository.NovelRepository
import kotlinx.datetime.Clock

/**
 * Novel 相关 Use Case（P3.2）：
 *  - CreateOriginalNovel：创建 Original（Immutable 基座）。
 *  - CreateVariant：从 Original 派生 Variant（禁止 Variant→Variant）。
 *  - GetNovel：读取 Original。
 *  - GetVariantContext：按（novelId, variantId?）构建校验后的调用链上下文。
 *
 * 均通过 [NovelRepository] 接口访问 Storage，不直接依赖 Sqlite 实现。
 */
class NovelUseCases(
    private val repo: NovelRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** CreateOriginalNovel：创建 scope=ORIGINAL 的 Novel，返回其强类型 NovelId。 */
    fun createOriginal(
        title: String,
        genre: List<String> = emptyList(),
        synopsis: String = "",
        source: ProjectSource = ProjectSource.ORIGINAL_NOVEL,
        novelId: NovelId? = null,
        projectId: ProjectId? = null,
    ): NovelId {
        val now = Clock.System.now()
        val novel = Novel(
            novelId = novelId ?: NovelId(nextId()),
            projectId = projectId ?: ProjectId(nextId()),
            title = title,
            source = source,
            genre = genre,
            synopsis = synopsis,
            scope = VariantScope.ORIGINAL,
            status = ProjectStatus.DRAFT,
            createdAt = now,
            updatedAt = now,
        )
        return guard { repo.createOriginal(novel) }
    }

    /**
     * CreateVariant：从 [context] 指向的 Original 基座派生一个 Variant。
     * 复用 Original 的 projectId；baseNovelId 指向基座，保证单层（Original → Variant）。
     */
    fun createVariant(
        context: VariantContext,
        name: String,
        variantId: VariantId? = null,
    ): VariantId {
        require(!context.isOriginal) { "创建 Variant 必须使用 Variant 上下文" }
        val baseNovelId = context.baseNovelId
        val now = Clock.System.now()
        // 取基座 Novel 的 projectId；基座必须是已存在的 Original（由 Storage 侧校验单层约束）。
        val baseProjectId = guard { repo.getNovel(NovelId(baseNovelId.value))?.projectId }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("基座 Original 不存在: ${baseNovelId.value}"))
        val variant = NovelVariant(
            variantId = variantId ?: VariantId(nextId()),
            novelId = NovelId(baseNovelId.value),
            baseNovelId = baseNovelId,
            projectId = baseProjectId,
            name = name,
            status = VariantStatus.DRAFT,
            createdAt = now,
            updatedAt = now,
        )
        return guard { repo.createVariant(variant) }
    }

    /** GetNovel：按 NovelId 读取 Original，不存在返回 null。 */
    fun getNovel(novelId: NovelId): Novel? = guard { repo.getNovel(novelId) }

    /** ListOriginals：列出全部 Original Novel（按创建时间升序）。P7.1：Android UI 小说选择列表入口。 */
    fun listOriginals(): List<Novel> = guard { repo.listOriginals() }

    /**
     * GetVariantContext：构建并校验调用链上下文（P3.3 统一传递）。
     *  - variantId = null → Original 上下文（baseNovelId = novelId）。
     *  - variantId 非空  → Variant 上下文，并校验其确实属于该 Novel。
     */
    fun getVariantContext(novelId: NovelId, variantId: VariantId?): VariantContext = guard {
        if (variantId == null) {
            val novel = repo.getNovel(novelId)
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
            VariantContext(baseNovelId = BaseNovelId(novel.novelId.value), variantId = null)
        } else {
            val variant = repo.getVariant(variantId)
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Variant 不存在: ${variantId.value}"))
            if (variant.novelId != novelId) {
                throw ApplicationException(ApplicationError.VariantMismatch("Variant ${variantId.value} 不属于 Novel ${novelId.value}"))
            }
            VariantContext(baseNovelId = variant.baseNovelId, variantId = variantId)
        }
    }
}