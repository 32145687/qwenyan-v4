package com.qianyan.application.usecase.txt

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.commit.CommitUseCases
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.VariantScope
import com.qianyan.model.story.Chapter
import com.qianyan.model.story.ChapterStatus
import com.qianyan.model.txt.TxtChapter
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.storage.repository.ChapterRepository
import com.qianyan.storage.repository.DraftRepository
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.WorkflowRepository
import kotlinx.datetime.Clock

/**
 * Phase 1 · **TXT 导入 → Chapter → Canonical 初始化**（v3.1 §10.5/§10.12）。
 *
 * 目标形态：
 * ```
 * 作者 TXT → Import（既有 TxtUseCases）→ Original Novel
 *           → 本 UseCase：TXT 章节 → domain Chapter + Canonical 载体（ChapterDraft）+ CommitHistory
 *           → CanonicalRead 可读
 * ```
 *
 * 语义/边界：
 *  - **不经** AI 变更链：无 WorkingDraft / Change / Human Gate；初始化即“已确认的正典”（沿用
 *    [CommitUseCases.commitImportedCanonical] 的 same-row 载体语义，来源以 CommitHistory.summary 区分）；
 *  - **不新建第二套** Chapter / Draft / Canonical 模型；复用既有 [ChapterRepository] / [DraftRepository] /
 *    [CommitUseCases]（唯一 Canonical 写入入口）；
 *  - **不迁移正文格式**：导入正文以 `format = null`（legacy plain text）落库，**不擅自**转受控 Markdown；
 *  - **幂等**：以 TXT 章节的**确定性 ID**（`TxtChapter.chapterId`，形如 `<documentId>#c<ordinal>`）映射为
 *    domain `ChapterId`；`Chapter` 已存在即视为该章节已初始化，跳过（不重建、不重复写 History）；
 *  - **原子**：整份文档的初始化在**同一事务**内完成；任一步失败整体回滚，不留"部分章节已初始化"的状态；
 *    重试安全（已完成的章节被幂等跳过）。
 *  - **显式文档**（F3）：调用方必须指定 `documentId`，本 Use Case 不猜"该 Novel 的哪一个 TXT 文档"
 *    （同 Novel 可绑定多份文档，`findByNovelId` 取首条属隐式决策）；文档存在性 + Novel 归属在任何写入前校验。
 *  - **跨来源保护**（F2）：目标 Novel 若已有不属于本次目标集合的 domain Chapter，**在任何持久化写入之前**
 *    整体拒绝（[ApplicationError.ChapterSourceConflict]）；不靠 `#c` 前缀/标题/正文/order 猜测来源，
 *    不做 `max(order)+1` 追加、不重排、不覆盖既有章节。
 */
class TxtCanonicalInitializationUseCases(
    private val txtRepository: TxtRepository,
    private val chapterRepository: ChapterRepository,
    private val draftRepository: DraftRepository,
    private val commits: CommitUseCases,
    private val workflows: WorkflowRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 把**指定** TXT 文档初始化为该 Novel 的 Canonical（幂等、原子）。
     *
     * @param novelId 目标 Original Novel（文档必须已绑定到它）。
     * @param documentId 本次初始化的唯一 TXT 文档来源（初始化目标集合由它的章节确定性 ID 决定）。
     * @throws ApplicationError.EntityNotFound 文档不存在。
     * @throws ApplicationError.InvalidOperation 文档未绑定到该 Novel。
     * @throws ApplicationError.ChapterSourceConflict Novel 已有非本文档来源的章节（跨来源混排）。
     */
    fun initializeCanonical(novelId: NovelId, documentId: TxtDocumentId): TxtCanonicalInitOutcome {
        val document = guard { txtRepository.getDocument(documentId) }
            ?: throw ApplicationException(
                ApplicationError.EntityNotFound("TXT 文档 ${documentId.value} 不存在，无法初始化 Canonical"),
            )
        if (document.novelId != novelId) {
            throw ApplicationException(
                ApplicationError.InvalidOperation(
                    "TXT 文档 ${documentId.value} 绑定到 Novel ${document.novelId?.value ?: "(未绑定)"}，" +
                        "不属于 Novel ${novelId.value}，拒绝初始化",
                ),
            )
        }
        val chapters = guard { txtRepository.getChapters(documentId) }.sortedBy { it.ordinal }
        val now = Clock.System.now()

        var initialized = 0
        var skipped = 0
        guard {
            workflows.inTransaction {
                // F2：一致性检查先于任何写入（同一事务内读取，检查与写入之间不存在窗口）。
                assertNoCrossSourceChapter(novelId, documentId, chapters)
                chapters.forEach { tc ->
                    // 幂等依据：TXT 章节**确定性 ID** → domain ChapterId（稳定、非标题/正文猜测）。
                    val chapterId = ChapterId(tc.chapterId.value)
                    if (chapterRepository.findById(chapterId) != null) {
                        skipped++
                        return@forEach
                    }
                    // 1) 正式 domain Chapter（order 稳定 = TXT ordinal + 1；标题原样；正文在 Draft 中）
                    chapterRepository.save(
                        Chapter(
                            chapterId = chapterId,
                            novelId = novelId,
                            variantId = null,
                            scope = VariantScope.ORIGINAL,
                            title = tc.title,
                            order = tc.ordinal + 1,
                            status = ChapterStatus.WRITTEN,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    // 2) 正文（本章 TextBlock 按 ordinal 升序、以空行分隔）——不转格式（format = null）
                    //    标题只进 Chapter.title，绝不并入 Draft.content。
                    val body = txtRepository.getBlocksOfChapter(tc.chapterId)
                        .sortedBy { it.ordinal }
                        .joinToString("\n\n") { it.text }
                    // 3) Canonical 载体（same-row：该 Draft 自身即正典载体，确定性 ID ⇒ 幂等）
                    val draftId = DraftId("${chapterId.value}#canonical")
                    draftRepository.save(
                        Draft(
                            draftId = draftId,
                            novelId = novelId,
                            variantId = null,
                            scope = VariantScope.ORIGINAL,
                            chapterId = chapterId,
                            planId = null,
                            previousDraftId = null,
                            content = body,
                            format = null,
                            status = DraftStatus.WRITTEN,
                            sourceModel = "",
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    // 4) Canonical 事实记录（唯一写入入口；artifactId=null、gateId=null、summary 标注来源）
                    commits.commitImportedCanonical(draftId, body)
                    initialized++
                }
            }
        }
        return TxtCanonicalInitOutcome(
            documentId = document.documentId,
            initializedChapterCount = initialized,
            skippedChapterCount = skipped,
            totalChapterCount = chapters.size,
        )
    }

    /**
     * F2 跨来源混排前置检查（只读，任何写入之前）：
     *  - **目标集合** = 本文档全部 TXT 章节的确定性 ID（`ChapterId(tc.chapterId.value)`）；
     *  - 同 Novel（Original 作用域 = 本 Use Case 的写入作用域）内存在 ID 不在目标集合中的章节 ⇒ 拒绝
     *    （这些章节来自 AI 写作 / 手工创建 / 另一份 TXT 文档，混排会让 order 与章节集合失去唯一来源）；
     *  - 目标集合中的 ID 已被**其它 Novel / Variant 作用域**的章节占用 ⇒ 同样拒绝
     *    （否则幂等跳过会静默留下缺章，而覆盖则会改写其它来源的章节）。
     */
    private fun assertNoCrossSourceChapter(novelId: NovelId, documentId: TxtDocumentId, chapters: List<TxtChapter>) {
        val targetIds = chapters.map { ChapterId(it.chapterId.value) }.toSet()
        val foreign = chapterRepository
            .listByNovel(novelId, null)
            .filter { it.chapterId !in targetIds }
        val occupied = targetIds
            .mapNotNull { chapterRepository.findById(it) }
            .filter { it.novelId != novelId || it.variantId != null }
        if (foreign.isEmpty() && occupied.isEmpty()) return
        throw ApplicationException(
            ApplicationError.ChapterSourceConflict(
                buildString {
                    append("Novel ${novelId.value} 已有 ${foreign.size} 个不属于 TXT 文档 ${documentId.value} 初始化目标集合的章节")
                    if (foreign.isNotEmpty()) append("（${foreign.take(5).joinToString(", ") { it.chapterId.value }}）")
                    if (occupied.isNotEmpty()) {
                        append("；另有 ${occupied.size} 个目标章节 ID 被其它来源占用")
                        append("（${occupied.take(5).joinToString(", ") { "${it.chapterId.value}→Novel ${it.novelId.value}" }}）")
                    }
                    append("；拒绝跨来源混排初始化")
                },
            ),
        )
    }
}

/** TXT 导入 Canonical 初始化的结构化结果（本次调用实际初始化/跳过的章节数）。 */
data class TxtCanonicalInitOutcome(
    val documentId: TxtDocumentId,
    val initializedChapterCount: Int,
    val skippedChapterCount: Int,
    val totalChapterCount: Int,
)
