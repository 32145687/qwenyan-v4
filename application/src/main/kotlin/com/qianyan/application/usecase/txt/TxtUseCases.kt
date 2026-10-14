package com.qianyan.application.usecase.txt

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.engine.txt.TxtException
import com.qianyan.engine.txt.TxtPipeline
import com.qianyan.engine.txt.TxtSource
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.ProjectSource
import com.qianyan.model.ProjectStatus
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.VariantScope
import com.qianyan.model.core.Novel
import com.qianyan.model.core.VariantContext
import com.qianyan.model.txt.TxtDocument
import com.qianyan.model.txt.TxtEncoding
import com.qianyan.storage.repository.NovelRepository
import com.qianyan.storage.repository.StorageException
import com.qianyan.storage.repository.TxtRepository
import kotlinx.datetime.Clock

/**
 * TXT 相关 Use Case（P5）：把 P4 确定性 TXT Pipeline 接入 Application 层。
 *
 * 职责边界（P5 决策）：
 *  - [TxtPipeline] 只做确定性解析：不创建 Novel、不访问 Repository、不访问 Application、不调用 LLM。
 *  - Novel 创建必须在此 Use Case 内完成（Application 层职责）。
 *  - 持久化一律经 [TxtRepository] / [NovelRepository] 接口注入；本类绝不直接触碰 Sqlite 实现。
 *  - 去重：contentHash 精确判断；重复时**不产生第二个 Original Novel、不产生重复 TXT 数据**，返回命中结果。
 *
 * VariantContext：TXT 导入 Original 时使用已有 [VariantContext]，scope=ORIGINAL、variantId=null、
 * baseNovelId=对应 Original Novel；不新建、不改 Variant 模型、不实现 Variant→Variant、不做字段级 Override。
 *
 * Phase 1 follow-up F1（导入闭环）：容器装配下导入成功后立即经
 * [TxtCanonicalInitializationUseCases] 生成 domain Chapter + Canonical（详见该类的语义/边界）。
 */
class TxtUseCases(
    private val txtPipeline: TxtPipeline,
    private val txtRepository: TxtRepository,
    private val novelRepository: NovelRepository,
    errorMapper: ErrorMapper,
    /**
     * F1 闭环协作者（Application 层装配注入）。**分阶段**执行：`saveImport` 与初始化各自成事务
     * （见 [com.qianyan.storage.repository.TxtRepository.saveImport] 内部单事务），因此：
     *  - 初始化失败时 TXT 导入结果已持久化，异常原样抛出（不吞、不伪装成功）；
     *  - 重试路径 = 重新导入同一内容 → contentHash 命中去重分支 → 再次初始化（幂等，已完成章节跳过）。
     * 未注入时只做导入（TXT 单测等无 Canonical 仓储的装配），结果中 `canonicalInit = null`。
     */
    private val canonicalInitialization: TxtCanonicalInitializationUseCases? = null,
) : UseCase(errorMapper) {

    /**
     * ImportTxtUseCase：原始 TXT bytes → 确定性解析 → 去重 → 创建 Original Novel → 绑定 novelId
     * → 原子持久化 Document / Chapter / TextBlock → 返回结构化结果。
     *
     * 流程保证：任何解析/编码错误都在写库**之前**抛出（见 [guardEngine]），导入失败时不产生半成品数据。
     * 重复内容（contentHash 命中）返回 isDuplicate=true，复用既有 Novel 绑定，不重复写入。
     */
    fun importTxtAsOriginal(
        source: TxtSource,
        title: String = "",
    ): TxtImportOutput {
        // 1) 确定性解析（引擎层；不触库、不建 Novel、不调 LLM）
        val documentId = TxtDocumentId(nextId())
        val parsed = guardEngine { txtPipeline.import(source, documentId, novelId = null, title = title) }

        // 2) 去重：按规范化文本的 contentHash 精确判断是否已导入
        val existing = guardEngine { txtRepository.findByContentHash(parsed.document.contentHash) }
        if (existing != null) {
            val novelId = existing.novelId
                ?: throw ApplicationException(
                    ApplicationError.TxtImportFailed(
                        "该 TXT 内容已导入（contentHash=${existing.contentHash}）但未绑定到任何 Novel，无法重复关联",
                    ),
                )
            // 复用既有绑定：不创建第二个 Novel、不写入重复 TXT。补读章节/块计数使结果字段完整。
            val chapterCount = guardEngine { txtRepository.getChapters(existing.documentId).size }
            val blockCount = guardEngine { txtRepository.getBlocks(existing.documentId).size }
            // F1 闭环（去重分支）：命中重复即"导入已完成过"，此处补做/重试 Canonical 初始化。
            // 幂等：已初始化的章节全部跳过 ⇒ 首次导入成功后重复导入不产生任何新写入；
            // 若上次初始化失败（导入已落库、章节未生成），重复导入即安全重试。
            val canonicalInit = initializeAfterImport(novelId, existing.documentId)
            return TxtImportOutput(
                documentId = existing.documentId,
                novelId = novelId,
                variantContext = VariantContext(baseNovelId = BaseNovelId(novelId.value)),
                isDuplicate = true,
                contentHash = existing.contentHash,
                encoding = existing.encoding,
                charCount = existing.charCount,
                chapterCount = chapterCount,
                blockCount = blockCount,
                canonicalInit = canonicalInit,
            )
        }

        // 3) 创建 Original Novel（Application Use Case 负责创建，Repository 经接口注入）
        val now = Clock.System.now()
        val novelId = guardEngine {
            novelRepository.createOriginal(
                Novel(
                    novelId = NovelId(nextId()),
                    projectId = ProjectId(nextId()),
                    title = title.ifBlank { source.displayName },
                    source = ProjectSource.ORIGINAL_NOVEL,
                    scope = VariantScope.ORIGINAL,
                    status = ProjectStatus.DRAFT,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        // 4) 绑定：把 NovelId 回填到 document / chapters / blocks（确定性结构，仅补 novelId）
        val document = parsed.document.copy(novelId = novelId)
        val chapters = parsed.parse.chapters.map { it.copy(novelId = novelId) }
        val blocks = parsed.parse.blocks.map { it.copy(novelId = novelId) }

        // 5) 持久化：文档 + 章节 + 段落块（Repository 内单事务原子写入）
        guardEngine { txtRepository.saveImport(document, chapters, blocks) }

        // 6) F1 闭环：导入成功后立即将本文档初始化为 domain Chapter + Canonical（分阶段、独立事务）。
        //    失败即抛出类型化错误（不吞、不回滚已落库的 TXT 导入），重导同一内容可幂等重试初始化。
        val canonicalInit = initializeAfterImport(novelId, document.documentId)

        // 7) 结构化结果 + Original 上下文
        return TxtImportOutput(
            documentId = document.documentId,
            novelId = novelId,
            variantContext = VariantContext(baseNovelId = BaseNovelId(novelId.value)),
            isDuplicate = false,
            contentHash = document.contentHash,
            encoding = document.encoding,
            charCount = document.charCount,
            chapterCount = chapters.size,
            blockCount = blocks.size,
            canonicalInit = canonicalInit,
        )
    }

    /**
     * FindDocumentsByNovel（P7.6 最小 UI 查询入口）：查询绑定到某 Novel 的 TXT 文档。
     *  - 供 Android Analysis 流程进入前取得 documentId（Application 仍只接收平台无关数据）；
     *  - 确定性顺序：created_at 升序（仓储语义）；无绑定返回空列表；
     *  - 只包装既有 [TxtRepository.findByNovelId]，不改 Schema / core:model。
     */
    fun findDocumentsByNovel(novelId: NovelId): List<TxtDocument> = guard { txtRepository.findByNovelId(novelId) }

    /**
     * guard 的引擎扩展：把引擎层 [TxtException] 与 Storage 层异常统一归一为 [ApplicationException]。
     *  - 引擎异常不是 StorageException，基类 [guard] 不会拦截，故在此单独归一到 ApplicationError（P5.2）。
     *  - 错误在写库之前抛出 → 导入失败时不产生半成品数据。
     */
    private fun <T> guardEngine(block: () -> T): T = try {
        block()
    } catch (e: ApplicationException) {
        throw e
    } catch (e: TxtException) {
        throw errorMapper.map(e)
    } catch (e: StorageException) {
        throw errorMapper.map(e)
    }

    /**
     * U2：初始化阶段的类型化失败必须保留"TXT 已导入"这一事实与原始原因。
     * 只包装 [ApplicationException]（领域失败）为 [ApplicationError.TxtCanonicalInitIncomplete]；
     * 非领域异常（如驱动级错误）原样传播，不伪装成业务状态。
     */
    private fun initializeAfterImport(novelId: NovelId, documentId: TxtDocumentId): TxtCanonicalInitOutcome? {
        val init = canonicalInitialization ?: return null
        return try {
            guardEngine { init.initializeCanonical(novelId, documentId) }
        } catch (e: ApplicationException) {
            throw ApplicationException(ApplicationError.TxtCanonicalInitIncomplete(documentId, e.error))
        }
    }

    /** TXT 导入的 Application 层结构化结果。 */
    data class TxtImportOutput(
        val documentId: TxtDocumentId,
        val novelId: NovelId,
        val variantContext: VariantContext,
        val isDuplicate: Boolean,
        val contentHash: String,
        val encoding: TxtEncoding,
        val charCount: Int,
        val chapterCount: Int,
        val blockCount: Int,
        /** F1 闭环结果：本次导入后 Canonical 初始化的实际计数（null = 该装配未注入初始化器，仅完成导入）。 */
        val canonicalInit: TxtCanonicalInitOutcome? = null,
    )
}