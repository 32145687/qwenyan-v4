package com.qianyan.application.usecase.analysis

import com.qianyan.application.error.AnalysisException
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.engine.analysis.AnalysisInputBuilder
import com.qianyan.model.BaseNovelId
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.analysis.AnalysisInput
import com.qianyan.model.analysis.AnalysisResult
import com.qianyan.model.analysis.AnalysisStatus
import com.qianyan.model.analysis.VocabularySuggestion
import com.qianyan.model.core.VariantContext
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.storage.repository.StorageException
import com.qianyan.storage.repository.TxtRepository
import com.qianyan.storage.repository.VocabularyRepository
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Analysis Use Case（P6.5）：把 P4 确定性 TXT + P4 引擎 + Mock Provider 接入应用层，
 * 形成最小 AI Analysis 全链路（分段说明见 [analyzeTxtOriginal]）。
 *
 * 执行层决策（P6.1）：Analysis 在此 Application Use Case 执行，**暂不走完整 Agent/Orchestration**。
 *
 * 职责边界：
 *  - 引擎 [AnalysisInputBuilder]：只做确定性 TXT→AnalysisInput（不调 LLM）。
 *  - Provider [LLMGateway]：只负责取回 AI 文本（经 :provider:api 契约；Mock 在 :provider:impl）。
 *  - 本 Use Case：解析并**校验** AI 输出 → 仅把验证通过的词汇**候选**写入 PENDING VocabularyCandidate，
 *    **绝不**直接写入正式 VocabularyEntry / 任何 Domain Entity。
 *  - 显式携带 [VariantContext]（P6 仅 Original：scope=ORIGINAL、variantId=null），禁止隐式/全局上下文。
 *
 * 原子性：先在内存完成解析+校验+构建候选列表，**全部成功后才**落库；解析/Provider 失败在写库前抛出，
 * 失败时不产生半成品 Candidate。
 */
class AnalysisUseCases(
    private val txtRepository: TxtRepository,
    private val vocabularyRepository: VocabularyRepository,
    private val inputBuilder: AnalysisInputBuilder = AnalysisInputBuilder,
    private val gateway: LLMGateway,
    errorMapper: ErrorMapper,
    /** P9：Analysis 请求的模型 Profile（默认 Mock；真实 Provider 由装配方注入 DEEPSEEK_V4_FLASH / MIMO_V2_5）。 */
    private val model: ModelProfile = ModelProfile.MOCK,
) : UseCase(errorMapper) {

    /**
     * P6 全链路：TXT(documentId) → AnalysisInput(章节级, 保留 sourceLocation) →
     * VariantContext(ORIGINAL) → Provider(Mock) → AnalysisResult → Validation → VocabularyCandidate(PENDING)。
     *
     * @param documentId 已导入并绑定的 TXT 文档
     * @param vocabularyId 目标 NOVEL 词库容器（AI 候选写入该 scope）
     * @param variantContext 仅支持 ORIGINAL（scope=ORIGINAL, variantId=null）
     */
    fun analyzeTxtOriginal(
        documentId: TxtDocumentId,
        vocabularyId: com.qianyan.model.VocabularyId,
        variantContext: VariantContext,
    ): AnalysisOutput {
        // 0) 显式 VariantContext 校验：P6 仅分析 Original
        if (!variantContext.isOriginal || variantContext.variantId != null) {
            throw ApplicationException(
                ApplicationError.InvalidOperation("P6 Analysis 仅支持 Original(VariantContext scope=ORIGINAL, variantId=null)"),
            )
        }

        // 1) 读取已绑定 TXT（Repository 经接口注入）
        val document = guard { txtRepository.getDocument(documentId) }
            ?: throw ApplicationException(ApplicationError.EntityNotFound("TXT 文档不存在: $documentId"))
        val novelId = document.novelId
            ?: throw ApplicationException(ApplicationError.TxtImportFailed("TXT 未绑定 Novel，无法分析: $documentId"))
        if (novelId.value != variantContext.baseNovelId.value) {
            throw ApplicationException(ApplicationError.VariantMismatch("TXT 所属 Novel 与 VariantContext.baseNovelId 不一致"))
        }

        val chapters = guard { txtRepository.getChapters(documentId) }
        val blocks = guard { txtRepository.getBlocks(documentId) }

        // 2) 确定性 TXT→AnalysisInput（引擎，章节级，保留 sourceLocation；不调 LLM）
        val input = inputBuilder.build(document, chapters, blocks)

        // 3) 经 Provider 契约取回 AI 文本（Mock；失败归一为 ProviderUnavailable 等）
        val request = ProviderRequest(
            model = model,
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatRole.USER, render(input)),
            ),
            temperature = 0.0,
        )
        val response = guardAnalysis { gateway.chat(request) }

        // 4) 解析 + 校验 AI 输出 → 结构化建议（经 guardAnalysis 归一：解析失败 → InvalidAnalysisOutput）
        val suggestions = guardAnalysis { parseSuggestions(response.content) }
        val valid = suggestions.filter { it.canonical.isNotBlank() }

        // 5) 构建（transient）AnalysisResult：verified intermediate，不入库
        val analysisResult = AnalysisResult(
            novelId = novelId,
            documentId = documentId,
            status = if (valid.isNotEmpty()) AnalysisStatus.SUCCESS else AnalysisStatus.SUCCESS_WITH_WARNINGS,
            vocabularySuggestions = valid,
            warning = if (valid.isEmpty()) "未从 AI 输出中提取到有效词汇" else null,
        )

        // 6) 先在内存完成全部候选构建（不写库）→ 成功后统一落库（原子边界：写库前任何失败都无半成品）
        val now = Clock.System.now()
        val candidates = valid.map { s -> candidateFromSuggestion(s, vocabularyId, novelId, now) }

        // 7) 持久化 PENDING VocabularyCandidate（复用 VocabularyCandidate 表；不覆盖正式词条）
        guardAnalysis { candidates.forEach { vocabularyRepository.saveCandidate(it) } }

        return AnalysisOutput(
            documentId = documentId,
            novelId = novelId,
            variantContext = VariantContext(baseNovelId = BaseNovelId(novelId.value)),
            analysisResult = analysisResult,
            candidateIds = candidates.map { it.candidateId },
            chapterCount = chapters.size,
            blockCount = blocks.size,
        )
    }

    private fun candidateFromSuggestion(
        s: VocabularySuggestion,
        vocabularyId: com.qianyan.model.VocabularyId,
        novelId: NovelId,
        now: kotlinx.datetime.Instant,
    ): VocabularyCandidate {
        val entry = VocabularyEntry(
            entryId = VocabularyEntryId(nextId()),
            vocabularyId = vocabularyId,
            novelId = novelId,
            variantId = null,
            scopeLevel = VocabularyScopeLevel.NOVEL,
            canonical = s.canonical,
            aliases = s.aliases,
            type = s.type,
            status = VocabularyEntryStatus.CANDIDATE,   // 候选，非正式 APPROVED
        )
        return VocabularyCandidate(
            candidateId = VocabularyCandidateId(nextId()),
            vocabularyId = vocabularyId,
            novelId = novelId,
            variantId = null,
            scopeLevel = VocabularyScopeLevel.NOVEL,
            suggested = entry,
            source = VocabularyCandidateSource.AUTO_EXTRACT,
            status = VocabularyCandidateStatus.PENDING,
            createdAt = now,
        )
    }

    private fun parseSuggestions(content: String): List<VocabularySuggestion> {
        val payload = try {
            AiJson.decodeFromString<VocabularyPayload>(content)
        } catch (e: Exception) {
            throw AnalysisException.InvalidOutput("AI 输出不是合法 JSON: ${e.message}")
        }
        return payload.vocabulary.map { dto ->
            val type = try {
                VocabularyEntryType.valueOf(dto.type)
            } catch (e: IllegalArgumentException) {
                VocabularyEntryType.PROPER_NOUN
            }
            VocabularySuggestion(canonical = dto.canonical, type = type, aliases = dto.aliases)
        }
    }

    /** guard 的 Analysis 扩展：把 Provider / Analysis / Storage 异常统一归一为领域错误（P6.6）。 */
    private fun <T> guardAnalysis(block: () -> T): T = try {
        block()
    } catch (e: ApplicationException) {
        throw e
    } catch (e: ProviderException) {
        throw errorMapper.map(e)
    } catch (e: AnalysisException) {
        throw errorMapper.map(e)
    } catch (e: StorageException) {
        throw errorMapper.map(e)
    }

    private fun render(input: AnalysisInput): String = buildString {
        appendLine("【文档】${input.title}")
        for (ch in input.chapters) {
            appendLine("【第${ch.ordinal + 1}章】${ch.title}")
            for (b in ch.blocks) {
                // 保留 sourceLocation 作锚点，确保可追溯到原 TXT
                appendLine("[${b.sourceLocation.startOffset}-${b.sourceLocation.endOffset}] ${b.text}")
            }
        }
    }

    /** P6 Analysis 的 Application 层结构化输出。 */
    data class AnalysisOutput(
        val documentId: TxtDocumentId,
        val novelId: NovelId,
        val variantContext: VariantContext,
        val analysisResult: AnalysisResult,
        val candidateIds: List<VocabularyCandidateId>,
        val chapterCount: Int,
        val blockCount: Int,
    )

    @Serializable
    private data class VocabularyPayload(val vocabulary: List<SuggestionDto> = emptyList())

    @Serializable
    private data class SuggestionDto(
        val canonical: String = "",
        val type: String = "PROPER_NOUN",
        val aliases: List<String> = emptyList(),
    )

    private companion object {
        val AiJson = Json { ignoreUnknownKeys = true }
        const val SYSTEM_PROMPT: String =
            "你是资深小说设定编辑。从下面章节文本中提取小说专用词汇（世界观术语 / 专有名词 / 修炼体系等）。" +
                "只输出 JSON，不要任何其它文字或代码块围栏。JSON 结构：" +
                "{\"vocabulary\":[{\"canonical\":\"词\",\"type\":\"WORLD_TERM\",\"aliases\":[]}]}。" +
                "type 合法取值：CHARACTER_APPELLATION, PROPER_NOUN, WORLD_TERM, PLACE, FACTION, REALM, ITEM, SKILL, FIXED_EXPRESSION, FORBIDDEN, STYLE_EXPRESSION。" +
                "aliases 为同义词数组（可为空）。"
    }
}