package com.qianyan.model.context

import com.qianyan.model.AgentSessionId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.TaskId
import com.qianyan.model.VariantId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I6 · Context Engine：任务级 Context 契约（纯领域，无 storage / provider / agent / UI 依赖）。
 *
 * 定位（architecture §9 / §25）：
 *   Project（长期）→ Workspace（长期）→ World Model / ProjectState（长期）
 *   → **Context（当前任务的工作记忆：短、易失、可重建）** → 未来 Novel Agent / LLM
 *
 * 严格边界：
 *  - Context **不是**第二个 Workspace / World Model / Knowledge Base / ProjectState：
 *      不持久化、不新增事实源、不复制长期数据，只做当前任务的投影；
 *  - [ContextPack] 是**一次 Context 构建的冻结结果**：内容已物化，底层数据变化不会悄悄改变它；
 *  - 选择全部**确定性**（无 LLM / 无随机 / 不用当前时间决定顺序与取舍）；
 *  - 不负责 LLM Request / Model Selection / Provider / Gateway / Prompt 渲染（§15）；
 *  - 不是 Tool：Context 决定"模型应该看到什么"，Tool 决定"能取什么"（§16）。
 *
 * 本文件只放契约与纯函数（Selection / 版本指纹 / 尺寸估算），复用既有 `ChapterContextProjector`
 * 的确定性范式（`packVersion` + 固定优先级 + 确定性尺寸估算）。
 */

// ---- 来源 / 优先级 ----

/** Context 信息来源（每个取值对应一个真实既有读取能力，不为每张表建 Source —— §20）。 */
@Serializable
enum class ContextSourceKind {
    PROJECT,
    PROJECT_STATE,
    NOVEL,
    CHAPTER,
    DRAFT,
    STORY_FOUNDATION,
    VOCABULARY,
}

/**
 * 当前任务对该信息的**需要程度**（不是文学价值评分）。
 * 序 = 优先级从高到低（HIGH → MEDIUM → LOW），供确定性排序；不复用 V4.2 未使用的 `ContextCandidate.priority` 浮点评分。
 */
@Serializable
enum class ContextPriority {
    HIGH,
    MEDIUM,
    LOW,
    ;

    /** 排序秩（越小越优先）。 */
    val rank: Int get() = ordinal
}

// ---- 条目 ----

/** 条目来源的稳定引用（可追踪"这条来自哪个对象"；不含时间 / 随机值）。 */
@Serializable
data class ContextItemRef(
    val novelId: NovelId? = null,
    val variantId: VariantId? = null,
    val chapterId: ChapterId? = null,
    val draftId: DraftId? = null,
)

/**
 * 一个可进入 Context 的信息单元。
 *
 * @param itemId 确定性 ID（`source:refKey`，由来源声明；不含 UUID / 时间）。
 * @param content 稳定内容投影（结构化文本；Draft 为正文原文，**不做摘要 / 不截断**）。
 * @param estimatedSize 内容字符数（原始尺寸；token 估算由 `ContextBudget` 的估算器负责）。
 * @param sourceVersion 来源记录的版本 / 状态指纹（可追踪"当时是什么状态"；如 Foundation 的 `version`、Draft 的 status/format）。
 * @param reason 入选 / 排序依据的确定性文案（供未来 Context Inspector 解释"为什么选它"）。
 */
@Serializable
data class ContextItem(
    val itemId: String,
    val source: ContextSourceKind,
    val content: String,
    val priority: ContextPriority,
    val estimatedSize: Int,
    val ref: ContextItemRef? = null,
    val sourceVersion: String = "",
    val reason: String = "",
)

// ---- 预算 ----

/** 尺寸估算器（可替换；Context Engine 不绑定任何具体模型的 tokenizer —— §14）。 */
fun interface ContextSizeEstimator {
    fun estimate(text: String): Long
}

/** 默认估算：token ≈ ceil(len / 4)（与既有 `ChapterContextProjector` 同一近似；确定性、无 tokenizer）。 */
object ApproxTokenSizeEstimator : ContextSizeEstimator {
    const val ID: String = "approx-chars-per-4"
    override fun estimate(text: String): Long = ((text.length + 3) / 4).toLong()
}

/** 已知估算器登记（未知 id 由 Context Engine 类型化拒绝，不静默降级）。 */
object ContextSizeEstimators {
    fun of(id: String): ContextSizeEstimator? = when (id) {
        ApproxTokenSizeEstimator.ID -> ApproxTokenSizeEstimator
        else -> null
    }
}

/**
 * Context 预算（最小、可替换；不绑定模型供应商 —— §13 / §14）。
 *
 * @param maxTokens 预算上限（估算 token）。
 * @param estimator 估算方式标识（默认 [ApproxTokenSizeEstimator.ID]）。
 */
@Serializable
data class ContextBudget(
    val maxTokens: Long = DEFAULT_MAX_TOKENS,
    val estimator: String = ApproxTokenSizeEstimator.ID,
) {
    companion object {
        /** 默认预算（与既有 `ChapterContextProjector.DEFAULT_BUDGET_TOKENS` 对齐）。 */
        const val DEFAULT_MAX_TOKENS: Long = 1200L
    }
}

/** 预算执行结果（确定性；含淘汰记录 ⇒ 可解释"为什么它没进去"）。 */
@Serializable
data class ContextBudgetGuard(
    val budgetTokens: Long,
    /** 实际纳入条目的估算 token 和。 */
    val estimatedTokens: Long,
    val isTruncated: Boolean,
    /** 因预算不足被淘汰的条目 id（按淘汰顺序）。 */
    val omittedItemIds: List<String> = emptyList(),
    val omittedCount: Int = 0,
)

// ---- 请求 ----

/**
 * Context 构建请求（一次明确的任务级请求 —— §7）。
 *
 * @param purpose 任务目的：**复用既有领域任务类型** [IntentType]，不新造平行词汇。
 * @param focusChapterId 任务焦点章节（如"继续写第 N 章"）；null = 全书 / 规划级任务。
 * @param candidateHorizon 候选生成边界（**只限制候选数量**；最终取舍由 priority + budget 决定 —— §22）。
 */
@Serializable
data class ContextRequest(
    val projectId: ProjectId,
    val purpose: IntentType,
    val sessionId: AgentSessionId? = null,
    val taskId: TaskId? = null,
    val focusChapterId: ChapterId? = null,
    val budget: ContextBudget = ContextBudget(),
    val candidateHorizon: Int = DEFAULT_CANDIDATE_HORIZON,
) {
    companion object {
        /** 默认候选生成边界（非选择规则；仅避免候选集随章节数无限增长）。 */
        const val DEFAULT_CANDIDATE_HORIZON: Int = 10
    }
}

// ---- 结果 ----

/** 选择结果（入选条目 + 预算执行情况）。 */
@Serializable
data class ContextSelection(
    /** 入选条目：优先级降序，同级保持候选声明顺序（稳定）。 */
    val items: List<ContextItem>,
    val budgetGuard: ContextBudgetGuard,
)

/**
 * Context Pack：一次 Context 构建的**冻结结果**（可重建、可审计 —— §9 / §10 / §18）。
 *
 * @param packId 确定性 pack 标识（不含 UUID）。
 * @param novelId 构建时解析出的作用域快照（可追踪"当时作用在哪个 Novel / Variant"）。
 * @param items 冻结的入选条目（内容已物化；底层数据变化不影响本 pack）。
 * @param packVersion 输入指纹（确定性；输入变化 ⇒ 版本变化 ⇒ 由 `isStale` 判定失效）。
 * @param createdAt 构建时间（由调用方注入 Clock；**不参与**选择与版本指纹）。
 */
@Serializable
data class ContextPack(
    val packId: String,
    val request: ContextRequest,
    val novelId: NovelId,
    val activeVariantId: VariantId? = null,
    val items: List<ContextItem> = emptyList(),
    val budgetGuard: ContextBudgetGuard,
    val packVersion: Long,
    val createdAt: Instant,
)

// ---- 纯函数：选择 ----

/**
 * 确定性选择器（§11 / §12 / §13）：候选 → 优先级排序 → 预算裁剪 → 入选。
 *
 * 规则（全部确定性、无 LLM / 无随机 / 无时间）：
 *  1. 依据 [ContextPriority.rank] **稳定排序**（同级保持候选声明顺序）；
 *  2. 逐条按序累加估算尺寸；**单条超预算 → 淘汰该条并继续**（不中断：小体积高价值条目仍可入选）；
 *  3. 淘汰条目全部记录在 [ContextBudgetGuard.omittedItemIds]（可解释）。
 */
object ContextSelector {

    fun select(
        candidates: List<ContextItem>,
        budget: ContextBudget,
        estimator: ContextSizeEstimator = ApproxTokenSizeEstimator,
    ): ContextSelection {
        val ordered = candidates.sortedBy { it.priority.rank }
        var remaining = budget.maxTokens
        val kept = mutableListOf<ContextItem>()
        val omitted = mutableListOf<String>()

        for (item in ordered) {
            val size = estimator.estimate(item.content)
            if (size in 0..remaining) {
                kept += item
                remaining -= size
            } else {
                omitted += item.itemId
            }
        }

        return ContextSelection(
            items = kept,
            budgetGuard = ContextBudgetGuard(
                budgetTokens = budget.maxTokens,
                estimatedTokens = budget.maxTokens - remaining,
                isTruncated = omitted.isNotEmpty(),
                omittedItemIds = omitted,
                omittedCount = omitted.size,
            ),
        )
    }
}

// ---- 纯函数：版本指纹 ----

/**
 * 确定性输入指纹（FNV-1a，仅作 fingerprint 不作安全哈希；与既有 `ChapterContextProjector.inputVersion` 同一范式）。
 *
 * 覆盖所有影响输出的输入：请求范围（project / novel / variant / purpose / session / task / focus）、
 * 预算与候选边界、**入选条目**（id / 来源 / 优先级 / 来源版本 / 内容）与淘汰集合。
 * 不覆盖 `createdAt` / `packId`（它们是输出，且 packId 由版本派生 ⇒ 同输入 ⇒ 同 packId）。
 */
object ContextPackVersion {

    fun of(
        request: ContextRequest,
        novelId: NovelId,
        activeVariantId: VariantId?,
        selection: ContextSelection,
    ): Long {
        val fp = buildString {
            append(request.projectId.value).append('|')
            append(novelId.value).append('|')
            append(activeVariantId?.value).append('|')
            append(request.purpose.name).append('|')
            append(request.sessionId?.value).append('|')
            append(request.taskId?.value).append('|')
            append(request.focusChapterId?.value).append('|')
            append(request.budget.maxTokens).append('|')
            append(request.budget.estimator).append('|')
            append(request.candidateHorizon).append('|')
            selection.items.forEach { item ->
                append(item.itemId).append('#')
                    .append(item.source.name).append('#')
                    .append(item.priority.name).append('#')
                    .append(item.sourceVersion).append('#')
                    .append(item.content).append(';')
            }
            append('|')
            append(selection.budgetGuard.omittedItemIds.joinToString(","))
        }
        return fnv1a64(fp)
    }

    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L
    private const val FNV_PRIME_64 = 1099511628211L

    private fun fnv1a64(s: String): Long {
        var h = FNV_OFFSET_BASIS_64
        for (c in s) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME_64
        }
        return h
    }
}