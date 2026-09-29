package com.qianyan.application.usecase.tool

import com.qianyan.agent.tool.ToolContext
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.agent.ToolName
import com.qianyan.model.core.Novel
import com.qianyan.model.story.Chapter
import com.qianyan.model.tool.ToolDefinition
import com.qianyan.model.tool.ToolParameterSpec
import com.qianyan.model.tool.ToolRequest
import com.qianyan.model.tool.ToolResult

/*
 * I5 · 只读 Product Tool（第一批 7 个）。
 *
 * 每个工具：既有 `Tool` 契约 + 强类型 `call(scope, input)` + 项目归属校验；
 * 只调用既有 Application UseCase 读取能力（结构守卫测试强制），绝不写入任何 Canonical 数据。
 */

// ---------- get_project ----------

/** 读取 Project 聚合（身份 + 运行态引用）。动作：READ。 */
class GetProjectTool(
    private val projects: ProjectUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.READ

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.GET_PROJECT),
        description = "读取 Project 项目聚合（项目身份 + 运行态引用）",
        parameters = listOf(ToolParameterSpec("projectId", "项目 ID", required = true)),
    )

    fun call(scope: ProjectId, input: GetProjectInput): ProjectView {
        val projectId = ProjectId(requireNotBlank(input.projectId, "projectId"))
        requireSameProject(scope, projectId, "Project")
        val project = projects.projectOf(projectId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Project 不存在"))
        return ProjectView(
            projectId = project.projectId,
            novelId = project.novelId,
            activeVariantId = project.state.activeVariantId,
            activeChapterId = project.state.activeChapterId,
            activeTaskId = project.state.activeTaskId,
        )
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: GetProjectInput -> call(scope, input) }
}

// ---------- get_project_state ----------

/** 读取 IDE / Agent 运行态（≠ World Model）。动作：READ。 */
class GetProjectStateTool(
    private val projects: ProjectUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.READ

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.GET_PROJECT_STATE),
        description = "读取 Project State（当前 variant / chapter / task 等 IDE 运行态）",
        parameters = listOf(ToolParameterSpec("projectId", "项目 ID", required = true)),
    )

    fun call(scope: ProjectId, input: GetProjectStateInput): GetProjectStateResult {
        val projectId = ProjectId(requireNotBlank(input.projectId, "projectId"))
        requireSameProject(scope, projectId, "Project")
        val state = projects.state(projectId)
            ?: return GetProjectStateResult(state = null)
        return GetProjectStateResult(
            state = ProjectStateView(
                projectId = state.projectId,
                novelId = state.novelId,
                activeVariantId = state.activeVariantId,
                activeChapterId = state.activeChapterId,
                activeTaskId = state.activeTaskId,
                updatedAt = state.updatedAt.toString(),
            ),
        )
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: GetProjectStateInput -> call(scope, input) }
}

// ---------- get_novel ----------

/** 读取 Novel 元数据（不含正文）。动作：READ。 */
class GetNovelTool(
    private val novels: NovelUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.READ

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.GET_NOVEL),
        description = "读取作品元数据（标题 / 题材 / 简介 / 状态）",
        parameters = listOf(ToolParameterSpec("novelId", "作品 ID", required = true)),
    )

    fun call(scope: ProjectId, input: GetNovelInput): NovelView {
        val novelId = NovelId(requireNotBlank(input.novelId, "novelId"))
        val novel = novels.getNovel(novelId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Novel 不存在"))
        requireSameProject(scope, novel.projectId, "Novel")
        return novel.toView()
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: GetNovelInput -> call(scope, input) }
}

// ---------- list_chapters ----------

/** 列出作品章节（Novel / Variant 范围）。动作：READ。 */
class ListChaptersTool(
    private val novels: NovelUseCases,
    private val chapters: ChapterUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.READ

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.LIST_CHAPTERS),
        description = "列出作品章节（可选 variantId；缺省为 Original 范围）",
        parameters = listOf(
            ToolParameterSpec("novelId", "作品 ID", required = true),
            ToolParameterSpec("variantId", "Variant ID（可选）", required = false),
        ),
    )

    fun call(scope: ProjectId, input: ListChaptersInput): ListChaptersResult {
        val novelId = NovelId(requireNotBlank(input.novelId, "novelId"))
        requireScopedNovel(novels, scope, novelId)
        val variantId = input.variantId?.takeIf { it.isNotBlank() }?.let { VariantId(it) }
        return ListChaptersResult(chapters = chapters.listByNovel(novelId, variantId).map { it.toView() })
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: ListChaptersInput -> call(scope, input) }
}

// ---------- get_chapter ----------

/** 读取章节组织信息（正文经 get_latest_draft 读取）。动作：READ。 */
class GetChapterTool(
    private val novels: NovelUseCases,
    private val chapters: ChapterUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.READ

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.GET_CHAPTER),
        description = "读取章节信息（标题 / 顺序 / 状态；正文经 get_latest_draft）",
        parameters = listOf(ToolParameterSpec("chapterId", "章节 ID", required = true)),
    )

    fun call(scope: ProjectId, input: GetChapterInput): ChapterView {
        val chapterId = ChapterId(requireNotBlank(input.chapterId, "chapterId"))
        val chapter = chapters.findById(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在"))
        requireScopedNovel(novels, scope, chapter.novelId, "Chapter")
        return chapter.toView()
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: GetChapterInput -> call(scope, input) }
}

// ---------- get_latest_draft ----------

/** 读取章节最新草稿（正文只读）。动作：READ。 */
class GetLatestDraftTool(
    private val novels: NovelUseCases,
    private val chapters: ChapterUseCases,
    private val writer: WriterUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.READ

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.GET_LATEST_DRAFT),
        description = "读取章节最新草稿（正文 / 格式 / 状态，只读）",
        parameters = listOf(ToolParameterSpec("chapterId", "章节 ID", required = true)),
    )

    fun call(scope: ProjectId, input: GetLatestDraftInput): DraftView {
        val chapterId = ChapterId(requireNotBlank(input.chapterId, "chapterId"))
        val chapter = chapters.findById(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("Chapter 不存在"))
        requireScopedNovel(novels, scope, chapter.novelId, "Chapter")
        val draft = writer.latestDraft(chapterId)
            ?: throw ApplicationException(ApplicationError.EntityNotFound("该章节尚无草稿"))
        return DraftView(
            draftId = draft.draftId,
            novelId = draft.novelId,
            variantId = draft.variantId,
            chapterId = draft.chapterId,
            previousDraftId = draft.previousDraftId,
            content = draft.content,
            format = draft.format,
            status = draft.status.name,
            sourceModel = draft.sourceModel,
            createdAt = draft.createdAt.toString(),
            updatedAt = draft.updatedAt.toString(),
        )
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: GetLatestDraftInput -> call(scope, input) }
}

// ---------- search_vocabulary ----------

/** 检索作品词汇候选（query 缺省返回全部）。动作：SEARCH。 */
class SearchVocabularyTool(
    private val novels: NovelUseCases,
    private val vocabularies: VocabularyUseCases,
) : ReadOnlyProductTool {

    override val actionKind: AgentActionKind = AgentActionKind.SEARCH

    override val definition: ToolDefinition = ToolDefinition(
        toolName = ToolName(ProductToolNames.SEARCH_VOCABULARY),
        description = "检索作品词汇候选（名称/别名包含查询词；query 可选）",
        parameters = listOf(
            ToolParameterSpec("novelId", "作品 ID", required = true),
            ToolParameterSpec("query", "查询词（可选）", required = false),
        ),
    )

    fun call(scope: ProjectId, input: SearchVocabularyInput): SearchVocabularyResult {
        val novelId = NovelId(requireNotBlank(input.novelId, "novelId"))
        requireScopedNovel(novels, scope, novelId)
        val query = input.query?.trim().orEmpty()
        val matched = vocabularies.findCandidatesByNovel(novelId).filter { candidate ->
            query.isEmpty() ||
                candidate.suggested.canonical.contains(query, ignoreCase = true) ||
                candidate.suggested.aliases.any { it.contains(query, ignoreCase = true) }
        }
        return SearchVocabularyResult(
            candidates = matched.map {
                VocabularyCandidateView(
                    candidateId = it.candidateId,
                    vocabularyId = it.vocabularyId,
                    novelId = it.novelId,
                    canonical = it.suggested.canonical,
                    aliases = it.suggested.aliases,
                    type = it.suggested.type.name,
                    source = it.source.name,
                    status = it.status.name,
                    createdAt = it.createdAt.toString(),
                )
            },
        )
    }

    override fun execute(request: ToolRequest, context: ToolContext): ToolResult =
        runReadOnly(definition.toolName, request, context) { scope, input: SearchVocabularyInput -> call(scope, input) }
}

// ---------- 工厂（ApplicationContainer 组装用） ----------

/** I5 第一批只读 Product Tool（7 个；注册顺序即声明顺序）。 */
fun readOnlyProductTools(
    projects: ProjectUseCases,
    novels: NovelUseCases,
    chapters: ChapterUseCases,
    writer: WriterUseCases,
    vocabularies: VocabularyUseCases,
): List<ReadOnlyProductTool> = listOf(
    GetProjectTool(projects),
    GetProjectStateTool(projects),
    GetNovelTool(novels),
    ListChaptersTool(novels, chapters),
    GetChapterTool(novels, chapters),
    GetLatestDraftTool(novels, chapters, writer),
    SearchVocabularyTool(novels, vocabularies),
)

// ---------- 内部视图映射 / 归属校验 ----------

private fun Novel.toView(): NovelView = NovelView(
    novelId = novelId,
    projectId = projectId,
    title = title,
    source = source.name,
    genre = genre,
    synopsis = synopsis,
    scope = scope.name,
    status = status.name,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun Chapter.toView(): ChapterView = ChapterView(
    chapterId = chapterId,
    novelId = novelId,
    variantId = variantId,
    scope = scope.name,
    title = title,
    order = order,
    status = status.name,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/**
 * 按作品的项目锚做归属校验（越界 → 按不存在拒绝）。
 *
 * 归属来自既有 [NovelUseCases.getNovel] 的 `Novel.projectId`，不新增项目范围管理系统。
 */
private fun requireScopedNovel(
    novels: NovelUseCases,
    scope: ProjectId,
    novelId: NovelId,
    what: String = "Novel",
) {
    val novel = novels.getNovel(novelId)
        ?: throw ApplicationException(ApplicationError.EntityNotFound("$what 不存在"))
    requireSameProject(scope, novel.projectId, what)
}