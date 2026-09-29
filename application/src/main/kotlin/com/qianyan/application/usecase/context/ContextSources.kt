package com.qianyan.application.usecase.context

import com.qianyan.application.usecase.chapter.ChapterUseCases
import com.qianyan.application.usecase.novel.NovelUseCases
import com.qianyan.application.usecase.project.ProjectUseCases
import com.qianyan.application.usecase.vocabulary.VocabularyUseCases
import com.qianyan.application.usecase.writing.WriterUseCases
import com.qianyan.model.IntentType
import com.qianyan.model.context.ContextItem
import com.qianyan.model.context.ContextItemRef
import com.qianyan.model.context.ContextPriority
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.storage.repository.StoryFoundationRepository

/*
 * I6 · 第一批 Context Source（§20 / §21）。
 *
 * 原则：只为**真正进入 Context Engine 的语义能力**建立 Source（不为每张表建 Source）。
 * 全部只读：只调用既有 Application UseCase / Repository 读取能力，投影为稳定文本候选；
 * 不写状态、不调用 LLM / Agent / Tool、不引入 Project Index。
 */

// ---- 目的 → 来源基础优先级（确定性表） ----

/**
 * 目的 → 来源的基础优先级（确定性映射；表达"当前任务对该信息的需要程度"，非文学价值评分）。
 *
 * 只为目的相关的来源加权重（继续写 / 改写 → 当前状态与草稿；规划 → 故事基础），
 * 其余保持中性；章节级的焦点 / 邻接优先级由 [ChapterSource] 覆盖。
 */
internal object ContextPriorities {

    fun of(kind: ContextSourceKind, purpose: IntentType): ContextPriority = when (kind) {
        ContextSourceKind.PROJECT -> ContextPriority.MEDIUM
        ContextSourceKind.PROJECT_STATE ->
            if (purpose == IntentType.CONTINUE || purpose == IntentType.REWRITE) ContextPriority.HIGH else ContextPriority.MEDIUM
        ContextSourceKind.NOVEL -> ContextPriority.LOW
        ContextSourceKind.CHAPTER -> ContextPriority.MEDIUM
        ContextSourceKind.DRAFT ->
            if (purpose == IntentType.CONTINUE || purpose == IntentType.REWRITE) ContextPriority.HIGH else ContextPriority.MEDIUM
        ContextSourceKind.STORY_FOUNDATION ->
            if (purpose == IntentType.PLAN) ContextPriority.HIGH else ContextPriority.MEDIUM
        ContextSourceKind.VOCABULARY -> ContextPriority.LOW
    }
}

/** 构造候选条目（itemId / estimatedSize 由此统一生成，来源不各自拼装）。 */
internal fun contextItem(
    source: ContextSourceKind,
    refKey: String,
    content: String,
    priority: ContextPriority,
    ref: ContextItemRef? = null,
    sourceVersion: String = "",
    reason: String = "",
): ContextItem = ContextItem(
    itemId = "${source.name.lowercase()}:$refKey",
    source = source,
    content = content,
    priority = priority,
    estimatedSize = content.length,
    ref = ref,
    sourceVersion = sourceVersion,
    reason = reason,
)

// ---- PROJECT ----

/** 来源：Project 聚合（身份 + 当前运行态引用）。 */
class ProjectSource(private val projects: ProjectUseCases) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.PROJECT

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val project = projects.projectOf(scope.projectId) ?: return emptyList()
        return listOf(
            contextItem(
                source = kind,
                refKey = project.projectId.value,
                content = "Project ${project.projectId.value} → Novel ${project.novelId.value}",
                priority = ContextPriorities.of(kind, request.purpose),
                ref = ContextItemRef(novelId = project.novelId),
                sourceVersion = "novel=${project.novelId.value}",
                reason = "本次任务所属项目身份（长期事实的锚点）",
            ),
        )
    }
}

// ---- PROJECT_STATE ----

/** 来源：Project State（IDE / Agent 运行态；≠ World Model）。 */
class ProjectStateSource(private val projects: ProjectUseCases) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.PROJECT_STATE

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val state = projects.state(scope.projectId) ?: return emptyList()
        return listOf(
            contextItem(
                source = kind,
                refKey = state.projectId.value,
                content = "variant=${state.activeVariantId?.value ?: "ORIGINAL"}; " +
                    "chapter=${state.activeChapterId?.value ?: "none"}; " +
                    "task=${state.activeTaskId?.value ?: "none"}",
                priority = ContextPriorities.of(kind, request.purpose),
                ref = ContextItemRef(novelId = state.novelId, variantId = state.activeVariantId, chapterId = state.activeChapterId),
                sourceVersion = "updatedAt=${state.updatedAt}",
                reason = "当前工作作用域（同一 Project 的不同任务可有不同运行态）",
            ),
        )
    }
}

// ---- NOVEL ----

/** 来源：Novel 元数据（标题 / 题材 / 简介 / 状态；不含正文）。 */
class NovelSource(private val novels: NovelUseCases) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.NOVEL

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val novel = novels.getNovel(scope.novelId) ?: return emptyList()
        // Project 隔离：作品必须属于本次解析出的 Novel 作用域。
        if (novel.novelId != scope.novelId) return emptyList()
        return listOf(
            contextItem(
                source = kind,
                refKey = novel.novelId.value,
                content = "《${novel.title}》 genre=${novel.genre.joinToString(",")} " +
                    "status=${novel.status.name} synopsis=${novel.synopsis}",
                priority = ContextPriorities.of(kind, request.purpose),
                ref = ContextItemRef(novelId = novel.novelId),
                sourceVersion = "updatedAt=${novel.updatedAt}",
                reason = "作品级背景（低优先，预算紧时先淘汰）",
            ),
        )
    }
}

// ---- CHAPTER ----

/**
 * 来源：章节组织信息。
 *
 * 候选生成只在 `candidateHorizon` 内（焦点章节及其之前若干章；无焦点时取最近若干章）——
 * **这是候选边界，不是选择规则**：真正取几章由 priority + budget 决定（§22）。
 */
class ChapterSource(private val chapters: ChapterUseCases) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.CHAPTER

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val ordered = chapters.listByNovel(scope.novelId, scope.activeVariantId)
            .sortedWith(compareBy({ it.order }, { it.chapterId.value }))
        if (ordered.isEmpty()) return emptyList()

        val focusIndex = ordered.indexOfFirst { it.chapterId == scope.focusChapterId }
        val window = if (focusIndex >= 0) {
            ordered.subList(maxOf(0, focusIndex - (request.candidateHorizon - 1)), focusIndex + 1)
        } else {
            ordered.takeLast(request.candidateHorizon)
        }
        val predecessorId = if (focusIndex >= 0) ordered.getOrNull(focusIndex - 1)?.chapterId else null

        return window.map { chapter ->
            val isFocus = chapter.chapterId == scope.focusChapterId
            val isPredecessor = predecessorId != null && chapter.chapterId == predecessorId
            contextItem(
                source = kind,
                refKey = chapter.chapterId.value,
                content = "第 ${chapter.order} 章《${chapter.title}》 status=${chapter.status.name}",
                priority = if (isFocus || isPredecessor) ContextPriority.HIGH else ContextPriority.LOW,
                ref = ContextItemRef(novelId = chapter.novelId, variantId = chapter.variantId, chapterId = chapter.chapterId),
                sourceVersion = "order=${chapter.order};status=${chapter.status.name};updatedAt=${chapter.updatedAt}",
                reason = when {
                    isFocus -> "任务焦点章节"
                    isPredecessor -> "焦点章节的前置章节"
                    else -> "同作品其他章节（低优先；超预算时淘汰）"
                },
            )
        }
    }
}

// ---- DRAFT ----

/** 来源：焦点章节的最新草稿（正文**原文保留**，不摘要、不截断）。 */
class DraftSource(
    private val chapters: ChapterUseCases,
    private val writer: WriterUseCases,
) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.DRAFT

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val chapterId = scope.focusChapterId ?: return emptyList()
        val chapter = chapters.findById(chapterId) ?: return emptyList()
        // Project 隔离：焦点章节必须属于本次作用域的 Novel。
        if (chapter.novelId != scope.novelId) return emptyList()
        val draft = writer.latestDraft(chapterId) ?: return emptyList()
        return listOf(
            contextItem(
                source = kind,
                refKey = draft.draftId.value,
                content = draft.content,
                priority = ContextPriorities.of(kind, request.purpose),
                ref = ContextItemRef(
                    novelId = draft.novelId,
                    variantId = draft.variantId,
                    chapterId = draft.chapterId,
                    draftId = draft.draftId,
                ),
                sourceVersion = "status=${draft.status.name};format=${draft.format ?: "plain"};updatedAt=${draft.updatedAt}",
                reason = "焦点章节当前稿（正文原文；压缩属后续阶段，I6 不做 LLM 摘要）",
            ),
        )
    }
}

// ---- STORY_FOUNDATION ----

/** 来源：用户已确认的本书级 Story Foundation（只读投影；复用其真实 `version` 作为来源版本）。 */
class StoryFoundationSource(private val foundations: StoryFoundationRepository) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.STORY_FOUNDATION

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val foundation = foundations.getStoryFoundation(scope.novelId) ?: return emptyList()
        if (foundation.novelId != scope.novelId) return emptyList()
        return listOf(
            contextItem(
                source = kind,
                refKey = "${foundation.novelId.value}@v${foundation.version}",
                content = "genre=${foundation.genre.joinToString(",") { it.value }}; " +
                    "theme=${foundation.direction.theme}; conflict=${foundation.direction.conflict}; " +
                    "promise=${foundation.direction.promise}; pov=${foundation.audience.pov}; " +
                    "tone=${foundation.audience.readerTone}; rules=${foundation.policy.rules.joinToString("|")}",
                priority = ContextPriorities.of(kind, request.purpose),
                ref = ContextItemRef(novelId = foundation.novelId),
                sourceVersion = "version=${foundation.version}",
                reason = "已确认的故事基础（本书级创作约束）",
            ),
        )
    }
}

// ---- VOCABULARY ----

/** 来源：作品词汇候选（命名 / 术语一致性；按 canonical 确定性排序）。 */
class VocabularySource(private val vocabularies: VocabularyUseCases) : ContextSource {

    override val kind: ContextSourceKind = ContextSourceKind.VOCABULARY

    override fun candidates(scope: ContextBuildScope, request: ContextRequest): List<ContextItem> {
        val candidates = vocabularies.findCandidatesByNovel(scope.novelId)
            .filter { it.novelId == scope.novelId }
            .sortedWith(compareBy({ it.suggested.canonical }, { it.candidateId.value }))
            .take(request.candidateHorizon)
        return candidates.map { candidate ->
            contextItem(
                source = kind,
                refKey = candidate.candidateId.value,
                content = buildString {
                    append(candidate.suggested.canonical)
                    if (candidate.suggested.aliases.isNotEmpty()) {
                        append(" / aliases=").append(candidate.suggested.aliases.joinToString("|"))
                    }
                    candidate.suggested.replacement?.let { append(" / replacement=").append(it) }
                },
                priority = ContextPriorities.of(kind, request.purpose),
                ref = ContextItemRef(novelId = candidate.novelId, variantId = candidate.variantId),
                sourceVersion = "status=${candidate.status.name};source=${candidate.source.name}",
                reason = "作品词汇候选（术语一致性；低优先）",
            )
        }
    }
}

// ---- 工厂（ApplicationContainer 组装用） ----

/** I6 第一批 Context Source（声明顺序 = 同级候选的稳定次序）。 */
fun defaultContextSources(
    projects: ProjectUseCases,
    novels: NovelUseCases,
    chapters: ChapterUseCases,
    writer: WriterUseCases,
    vocabularies: VocabularyUseCases,
    foundations: StoryFoundationRepository,
): List<ContextSource> = listOf(
    ProjectSource(projects),
    ProjectStateSource(projects),
    NovelSource(novels),
    ChapterSource(chapters),
    DraftSource(chapters, writer),
    StoryFoundationSource(foundations),
    VocabularySource(vocabularies),
)