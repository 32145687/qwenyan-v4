package com.qianyan.application.usecase.index

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.project.Project
import com.qianyan.model.projectindex.ProjectIndex
import com.qianyan.model.projectindex.ProjectIndexEntries
import com.qianyan.model.projectindex.ProjectIndexEntry
import com.qianyan.model.projectindex.ProjectIndexEntryType
import com.qianyan.model.projectindex.ProjectIndexErrorCodes
import com.qianyan.model.projectindex.ProjectIndexReference
import com.qianyan.model.projectindex.ProjectIndexVersioning
import com.qianyan.storage.repository.StoryFoundationRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I12 · Project Index（项目内容的**派生**索引：可定位 / 可搜索 / 可重建）。
 *
 * 关系（architecture §19 / §26 A2）：
 * ```
 * Canonical Project Data（Novel / Chapter / Draft / StoryFoundation / Vocabulary）
 *        ↓ rebuild（本类，**只读** Canonical）
 * Project Index（"有什么内容 / 在哪里"）
 *        ↓ 候选定位
 * Context Engine（I6：选择 / 排序 / 截断）→ Novel Agent（I11）
 * ```
 *
 * 硬边界：
 *  - **Derived / Rebuildable**：索引永远可由 [rebuild] 从 Canonical 重建；**不存在** Index → Canonical 的恢复路径；
 *    索引可被整体丢弃（[discard]）而不影响任何业务数据；
 *  - **不是一个 Project 一个事实源**：**有意使用内存派生存储**（进程内，不落库、不加表、不加迁移）；
 *    Canonical 数据才是唯一事实来源（§7）；
 *  - **只读 Canonical**：只经既有 Application 读取能力（ProjectUseCases / NovelUseCases / ChapterUseCases /
 *    WriterUseCases / VocabularyUseCases）与既有 `StoryFoundationRepository`（与 I6 `StoryFoundationSource` 同一范式）；
 *    绝不写 Chapter / ChapterDraft / StoryFoundation / ProjectState / CommitHistory；
 *  - **不复制正文 / 不复制 Canonical Entity**：条目只保存稳定引用 + 标签 + 可检索词 + 来源版本（含正文**字数**这类元数据），
 *    正文仍由 Product Tool（I5）按引用读取；
 *  - **不是 Context Engine**：只做定位与候选发现，不做优先级 / 预算 / 截断 / ContextPack 组装；
 *  - **不是第二套搜索基础设施**：无 FTS / embedding / vector / RAG / LLM，`find` 只匹配标签与可检索词；
 *  - **不是 Project State**：不读也不复制运行态（作用域由调用方显式指定，不由 `ProjectState.activeVariantId` 推断）；
 *  - **不是 World Model**：只为有稳定 Canonical 来源的对象建条目。
 */
class ProjectIndexUseCases(
    private val projects: ProjectUseCases,
    private val novels: NovelUseCases,
    private val chapters: ChapterUseCases,
    private val writer: WriterUseCases,
    private val vocabularies: VocabularyUseCases,
    private val foundations: StoryFoundationRepository,
    private val clock: Clock = Clock.System,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /** 应用级内存派生存储（进程内；不落库、不跨进程共享、可丢弃）。 */
    private val store: MutableMap<ProjectId, ProjectIndex> = LinkedHashMap()

    /* ---------------- 构建 / 重建 ---------------- */

    /**
     * 重建索引并缓存（**唯一构建入口**；`build` 与 `rebuild` 语义一致，故只保留本方法）。
     *
     * 确定性：相同 Canonical 数据 + 相同作用域 ⇒ 相同 entries / 相同顺序 / 相同 version
     * （不依赖随机、当前时间、LLM、网络或 Map 迭代序）。
     *
     * @param variantId 索引覆盖的作用域（`null` = Original 基座）。显式指定，不由运行态推断。
     * @throws ApplicationError.EntityNotFound Project / Novel 不存在或越界。
     * @throws ApplicationError.InvalidOperation 作用域不属于本 Project 的 Novel，或构建失败。
     */
    @Synchronized
    fun rebuild(projectId: ProjectId, variantId: VariantId? = null): ProjectIndex {
        val index = build(projectId, variantId)
        store[projectId] = index
        return index
    }

    /** 丢弃派生索引（派生数据可整体丢失；再次 [rebuild] 即恢复，不影响任何 Canonical 数据）。 */
    @Synchronized
    fun discard(projectId: ProjectId): Boolean = store.remove(projectId) != null

    /* ---------------- 查询（只读既有索引） ---------------- */

    /**
     * 读取已构建的索引；未构建 → [ApplicationError.EntityNotFound]（查询**不隐式构建**，保持只读语义）。
     */
    @Synchronized
    fun get(projectId: ProjectId): ProjectIndex = store[projectId] ?: throw notBuilt(projectId)

    /** 全部条目（固定排序：类型 → 位次 → 条目 ID）。 */
    @Synchronized
    fun entries(projectId: ProjectId): List<ProjectIndexEntry> = get(projectId).entries

    /** 按类型筛选（顺序稳定）。 */
    @Synchronized
    fun findByType(projectId: ProjectId, type: ProjectIndexEntryType): List<ProjectIndexEntry> =
        get(projectId).ofType(type)

    /**
     * 候选定位：按标签 / 可检索词做**大小写不敏感**的包含匹配（不是全文检索，也**不返回正文**）。
     *
     * 空白 query ⇒ 返回全部条目（等价于 [entries]，便于"列出候选"）。
     */
    @Synchronized
    fun find(projectId: ProjectId, query: String): List<ProjectIndexEntry> {
        val index = get(projectId)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return index.entries
        return index.entries.filter { entry ->
            entry.label.lowercase().contains(q) || entry.keywords.any { it.lowercase().contains(q) }
        }
    }

    /**
     * 索引是否可能已过期：按**记录的作用域**用当前 Canonical 数据重算一次并比对版本指纹。
     *
     * **不改动既有索引**（索引已冻结 ⇒ 底层数据变化不会悄悄改变它，只会被判为过期）。
     */
    @Synchronized
    fun isStale(projectId: ProjectId): Boolean {
        val index = get(projectId)
        return build(projectId, index.scopeVariantId).version != index.version
    }

    /* ---------------- internals ---------------- */

    /** 纯构建（不写 store；供 [rebuild] 与 [isStale] 共用）。 */
    private fun build(projectId: ProjectId, variantId: VariantId?): ProjectIndex = try {
        val project = readProject(projectId)
        val novel = readNovel(project.novelId, projectId)
        verifyScope(novel.novelId, variantId)

        val entries = ProjectIndexEntries.normalize(buildEntries(projectId, novel.novelId, variantId))
        ProjectIndex(
            projectId = projectId,
            novelId = novel.novelId,
            scopeVariantId = variantId,
            entries = entries,
            version = ProjectIndexVersioning.of(projectId, novel.novelId, variantId, entries),
            builtAt = now(),
        )
    } catch (e: ApplicationException) {
        throw e
    } catch (t: Throwable) {
        val mapped = errorMapper.map(t)
        throw ApplicationException(
            ApplicationError.InvalidOperation("[${ProjectIndexErrorCodes.INDEX_BUILD_FAILED}] 索引构建失败: ${mapped.error}"),
        )
    }

    /** 从 Canonical 读取能力收集条目（**只定位，不复制内容**）。 */
    private fun buildEntries(projectId: ProjectId, novelId: NovelId, variantId: VariantId?): List<ProjectIndexEntry> {
        val entries = mutableListOf<ProjectIndexEntry>()
        val novelRef = ProjectIndexReference(novelId = novelId, variantId = variantId)

        // NOVEL（作品级定位）
        val novel = guard { novels.getNovel(novelId) } ?: return emptyList()
        if (novel.projectId != projectId) return emptyList()
        entries += ProjectIndexEntry(
            entryId = ProjectIndexEntries.entryId(ProjectIndexEntryType.NOVEL, novel.novelId.value),
            projectId = projectId,
            entryType = ProjectIndexEntryType.NOVEL,
            reference = novelRef,
            label = novel.title,
            parentReference = null,
            ordinal = 0,
            sourceVersion = "status=${novel.status.name};updatedAt=${novel.updatedAt}",
            keywords = ProjectIndexEntries.keywords(novel.title, *novel.genre.toTypedArray()),
        )

        // CHAPTER + 其当前正文载体（DRAFT）
        val orderedChapters = guard { chapters.listByNovel(novelId, variantId) }
            .sortedWith(compareBy({ it.order }, { it.chapterId.value }))
        orderedChapters.forEach { chapter ->
            val chapterRef = ProjectIndexReference(
                novelId = novelId,
                variantId = chapter.variantId,
                chapterId = chapter.chapterId,
            )
            entries += ProjectIndexEntry(
                entryId = ProjectIndexEntries.entryId(ProjectIndexEntryType.CHAPTER, chapter.chapterId.value),
                projectId = projectId,
                entryType = ProjectIndexEntryType.CHAPTER,
                reference = chapterRef,
                label = chapter.title,
                parentReference = novelRef,
                ordinal = chapter.order,
                sourceVersion = "status=${chapter.status.name};updatedAt=${chapter.updatedAt}",
                keywords = ProjectIndexEntries.keywords(chapter.title, "第${chapter.order}章", chapter.order.toString()),
            )
            val draft = guard { writer.latestDraft(chapter.chapterId) }
            if (draft != null && draft.novelId == novelId) {
                entries += ProjectIndexEntry(
                    entryId = ProjectIndexEntries.entryId(ProjectIndexEntryType.DRAFT, draft.draftId.value),
                    projectId = projectId,
                    entryType = ProjectIndexEntryType.DRAFT,
                    reference = ProjectIndexReference(
                        novelId = novelId,
                        variantId = draft.variantId,
                        chapterId = chapter.chapterId,
                        draftId = draft.draftId,
                    ),
                    label = "第 ${chapter.order} 章当前稿",
                    parentReference = chapterRef,
                    ordinal = chapter.order,
                    // 只含元数据（含正文**字数**），不含正文本身
                    sourceVersion = "status=${draft.status.name};format=${draft.format ?: "plain"};" +
                        "chars=${draft.content.length};updatedAt=${draft.updatedAt}",
                    keywords = ProjectIndexEntries.keywords(chapter.title, "当前稿", "第${chapter.order}章"),
                )
            }
        }

        // STORY_FOUNDATION（本书级创作约束；有稳定 Canonical 来源时才建条目）
        guard { foundations.getStoryFoundation(novelId) }
            ?.takeIf { it.novelId == novelId }
            ?.let { foundation ->
                entries += ProjectIndexEntry(
                    entryId = ProjectIndexEntries.entryId(ProjectIndexEntryType.STORY_FOUNDATION, novelId.value),
                    projectId = projectId,
                    entryType = ProjectIndexEntryType.STORY_FOUNDATION,
                    reference = novelRef,
                    label = "故事基础（已确认）",
                    parentReference = novelRef,
                    ordinal = 0,
                    sourceVersion = "version=${foundation.version}",
                    keywords = ProjectIndexEntries.keywords(
                        "故事基础",
                        *foundation.genre.map { it.value }.toTypedArray(),
                    ),
                )
            }

        // VOCABULARY（作品词汇候选；命名 / 术语一致性）
        guard { vocabularies.findCandidatesByNovel(novelId) }
            .filter { it.novelId == novelId }
            .sortedWith(compareBy({ it.suggested.canonical }, { it.candidateId.value }))
            .forEach { candidate ->
                entries += ProjectIndexEntry(
                    entryId = ProjectIndexEntries.entryId(
                        ProjectIndexEntryType.VOCABULARY,
                        candidate.candidateId.value,
                    ),
                    projectId = projectId,
                    entryType = ProjectIndexEntryType.VOCABULARY,
                    reference = ProjectIndexReference(
                        novelId = novelId,
                        variantId = candidate.variantId,
                        vocabularyCandidateId = candidate.candidateId,
                    ),
                    label = candidate.suggested.canonical,
                    parentReference = novelRef,
                    ordinal = 0,
                    sourceVersion = "status=${candidate.status.name};source=${candidate.source.name}",
                    keywords = ProjectIndexEntries.keywords(
                        candidate.suggested.canonical,
                        *candidate.suggested.aliases.toTypedArray(),
                    ),
                )
            }

        return entries
    }

    /** Project 解析（越界统一表现为"不存在"，不泄漏其他 Project 的存在性）。 */
    private fun readProject(projectId: ProjectId): Project =
        guard { projects.projectOf(projectId) } ?: throw notFound(projectId)

    private fun readNovel(novelId: NovelId, projectId: ProjectId): com.qianyan.model.core.Novel {
        val novel = guard { novels.getNovel(novelId) } ?: throw notFound(projectId)
        if (novel.projectId != projectId) throw notFound(projectId)
        return novel
    }

    /** 显式作用域必须属于本 Project 的 Novel（越界 → SCOPE_MISMATCH；不泄漏存在性）。 */
    private fun verifyScope(novelId: NovelId, variantId: VariantId?) {
        if (variantId == null) return
        try {
            guard { novels.getVariantContext(novelId, variantId) }
        } catch (e: ApplicationException) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "[${ProjectIndexErrorCodes.SCOPE_MISMATCH}] 作用域不属于本 Project 的 Novel: ${variantId.value}",
                ),
            )
        }
    }

    private fun notBuilt(projectId: ProjectId): ApplicationException = ApplicationException(
        ApplicationError.EntityNotFound(
            "[${ProjectIndexErrorCodes.INDEX_NOT_BUILT}] Project Index 不存在（未构建）: ${projectId.value}",
        ),
    )

    private fun notFound(projectId: ProjectId): ApplicationException = ApplicationException(
        ApplicationError.EntityNotFound("[${ProjectIndexErrorCodes.PROJECT_NOT_FOUND}] Project 不存在: ${projectId.value}"),
    )

    /** 时间戳精度与存储一致（epoch 毫秒）；时钟可注入，保证测试确定性。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
}