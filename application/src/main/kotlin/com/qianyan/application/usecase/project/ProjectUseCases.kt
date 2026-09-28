package com.qianyan.application.usecase.project

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import com.qianyan.model.core.Novel
import com.qianyan.model.project.Project
import com.qianyan.model.project.ProjectState
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.ProjectStateRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * I1 · Project 聚合入口（Novel IDE 第一阶段）。
 *
 * 依据：`docs/architecture/qianyan-novel-ide-architecture.md` §4（Project）/ §8（Project State）。
 *
 * 职责：
 *  - **Project 聚合**：把「项目身份（`Novel.projectId`）+ 身份锚（`novelId`）+ 当前运行态」组装为 [Project]。
 *  - **Project State**：读写 IDE / Agent 运行态（当前 Variant / Chapter / Task 的引用）。
 *
 * 明确不做（I1 边界，属后续阶段）：
 *  - 不创建第二套 Novel 创建路径：`createProject` 直接委托既有 [NovelUseCases.createOriginal]；
 *      Project **不单独持久化**——身份来自 `Novel`（`project_id` 列），运行态来自 `ProjectState` 表；
 *  - 不复制 Novel 元数据（title / genre / synopsis / status）与世界观 / 章节 / 人物数据；
 *  - 不建立第二套状态机：不复制 Workflow / Task 的 PAUSED / CANCELLED / FAILED / WAITING_HUMAN / COMPLETED
 *      语义（那些仍以 Task / Workflow / Checkpoint 为唯一事实来源 —— FD-2）；
 *  - 不实现 Agent Session / Activity Log / Context Engine / Project Index / Working Draft / Validation /
 *      Diff / Artifact / Commit / History / Queue（I3 及以后）；
 *  - 不触碰 P19 DecisionPolicy、不触碰 P20 FD-1…FD-10 的既有语义。
 *
 * 依赖方向：Application → Repository（[NovelRepository] / [ChapterRepository] / [ProjectStateRepository]）；
 * 领域模型不依赖 SQLDelight（由 `:storage` 映射）。
 */
class ProjectUseCases(
    private val novels: NovelUseCases,
    private val novelRepository: NovelRepository,
    private val chapterRepository: ChapterRepository,
    private val states: ProjectStateRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 创建 Project：委托既有 [NovelUseCases.createOriginal]（Original Novel 创建时即分配 `projectId`），
     * 建立默认运行态并返回聚合。**不存在"只建 Project 不建 Novel"的第二条路径。**
     */
    fun createProject(
        title: String,
        genre: List<String> = emptyList(),
        synopsis: String = "",
        source: ProjectSource = ProjectSource.ORIGINAL_NOVEL,
    ): Project {
        val novelId = novels.createOriginal(title = title, genre = genre, synopsis = synopsis, source = source)
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        val state = defaultState(novel)
        guard { states.save(state) }
        return Project(projectId = novel.projectId, novelId = novel.novelId, state = state)
    }

    /**
     * 打开/读取项目（novelId 入口，主入口）。
     *
     * 若该项目尚无运行态记录：返回**默认运行态**（不落库、不伪造进度）。
     * 首次写入由 [openProject] / [selectVariant] / [selectChapter] / [setActiveTask] 落库。
     */
    fun projectOf(novelId: NovelId): Project {
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        return assemble(novel, guard { states.get(novel.projectId) })
    }

    /**
     * 读取项目（projectId 入口）。
     * 需要该项目**已建立运行态**（即已被 [openProject] 创建过或写入过），否则返回 null
     * —— 避免为 I1 引入 projectId → novelId 的额外仓储查询（`Novel` 上没有该索引查询）。
     */
    fun projectOf(projectId: ProjectId): Project? {
        val state = guard { states.get(projectId) } ?: return null
        val novel = guard { novelRepository.getNovel(state.novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${state.novelId.value}"))
        return assemble(novel, state)
    }

    /** IDE 打开项目：确保运行态记录存在（幂等），返回聚合。 */
    fun openProject(novelId: NovelId): Project {
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        val existing = guard { states.get(novel.projectId) }
        val state = existing ?: defaultState(novel).also { guard { states.save(it) } }
        return assemble(novel, state)
    }

    /** 读取运行态（只读；无记录返回 null，不伪造）。 */
    fun state(projectId: ProjectId): ProjectState? = guard { states.get(projectId) }

    /**
     * 选择当前工作 Variant（`null` = Original 作用域）。
     * 归属校验复用既有 [NovelUseCases.getVariantContext]（不存在 → EntityNotFound；不属于该 Novel → VariantMismatch）。
     *
     * 不变式：`activeChapterId` 的作用域必须与 `activeVariantId` 一致 —— 因此切换作用域时，
     * 若原工作章节不属于新作用域，则**清除**该选择（不静默保留一个跨作用域的章节）。
     */
    fun selectVariant(novelId: NovelId, variantId: VariantId?): Project {
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        val context = novels.getVariantContext(novelId, variantId)
        return writeState(novel) { current ->
            val keepChapter = current.activeChapterId?.takeIf { chapterId ->
                guard { chapterRepository.findById(chapterId) }?.variantId == context.variantId
            }
            current.copy(activeVariantId = context.variantId, activeChapterId = keepChapter)
        }
    }

    /**
     * 选择当前工作章节（`null` = 未选定）。
     * 归属校验：章节必须存在且属于该 Novel；若已选定工作 Variant，则章节作用域必须一致。
     */
    fun selectChapter(novelId: NovelId, chapterId: ChapterId?): Project {
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        val current = guard { states.get(novel.projectId) }
        if (chapterId != null) {
            val chapter = guard { chapterRepository.findById(chapterId) }
                ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在: ${chapterId.value}"))
            if (chapter.novelId != novelId) {
                throw ApplicationException(
                    ApplicationError.InvalidOperation("Chapter ${chapterId.value} 不属于 Novel ${novelId.value}"),
                )
            }
            val activeVariantId = current?.activeVariantId
            if (chapter.variantId != activeVariantId) {
                throw ApplicationException(
                    ApplicationError.InvalidOperation(
                        "Chapter ${chapterId.value} 的作用域（variantId=${chapter.variantId?.value ?: "ORIGINAL"}）" +
                            "与当前工作作用域（variantId=${activeVariantId?.value ?: "ORIGINAL"}）不一致",
                    ),
                )
            }
        }
        return writeState(novel) { it.copy(activeChapterId = chapterId) }
    }

    /**
     * 设置当前任务**引用**（不承载 Task 生命周期状态；Task 状态仍以 Task / Checkpoint 为唯一事实来源 —— FD-2）。
     */
    fun setActiveTask(novelId: NovelId, taskId: TaskId?): Project {
        val novel = guard { novelRepository.getNovel(novelId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在: ${novelId.value}"))
        return writeState(novel) { it.copy(activeTaskId = taskId) }
    }

    // ---- internals ----

    /** 读取-修改-写入运行态（同 projectId 覆盖，不产生第二行；`novelId` 不变式由构造保证）。 */
    private fun writeState(novel: Novel, mutate: (ProjectState) -> ProjectState): Project {
        val current = guard { states.get(novel.projectId) } ?: defaultState(novel)
        val updated = mutate(current).copy(
            projectId = novel.projectId,
            novelId = novel.novelId,
            updatedAt = now(),
        )
        guard { states.save(updated) }
        return Project(projectId = novel.projectId, novelId = novel.novelId, state = updated)
    }

    /** 默认运行态：无工作选择（不伪造进度）。 */
    private fun defaultState(novel: Novel): ProjectState = ProjectState(
        projectId = novel.projectId,
        novelId = novel.novelId,
        updatedAt = now(),
    )

    /** 运行态时间戳精度与存储一致（epoch 毫秒）：避免内存值与回读值因精度不同而不等价。 */
    private fun now(): Instant = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private fun assemble(novel: Novel, state: ProjectState?): Project = Project(
        projectId = novel.projectId,
        novelId = novel.novelId,
        state = state ?: defaultState(novel),
    )
}