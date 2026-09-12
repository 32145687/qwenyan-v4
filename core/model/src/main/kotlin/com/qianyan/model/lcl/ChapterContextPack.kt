package com.qianyan.model.lcl

import com.qianyan.model.ChapterId
import com.qianyan.model.NovelId
import com.qianyan.model.VariantId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.StoryWorldContext
import com.qianyan.model.story.Foreshadow
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import kotlinx.serialization.Serializable

/**
 * P13 LCL-B · ChapterContextPack 领域模型与确定性投影器。
 *
 * 定位：**确定性的、窗口化的章节上下文投影层**。目标是缓解 `StoryWorldContextResolver` 每次全量读取造成的
 * 上下文膨胀（100+ 章后 canon/events 线性增长）。
 *
 * 设计原则：
 *  - 本模型是**派生数据**，不作为新的事实源；不持有正文全文，不携带 API Key / Credential；
 *  - **可重新编译的派生结果**（不做复杂持久化缓存）：同一输入必然得到同一 [ChapterContextPack]；
 *  - 窗口/优先/预算规则**全部确定、无 LLM、无随机**；
 *  - 复用既有类型：[StoryWorldContext]、[NarrativeState]/[OpenThread]（LCL-A）、[Event]/[Foreshadow]（Story State）。
 *
 * 本阶段只覆盖 MVP 字段，不扩展完整三方知情 / Reveal Timeline / knowledgeSlice（后续 LCL 阶段）。
 */

/** 投影内容的分组（TokenBudgetGuard 裁剪优先级声明，序=优先级从高到低）。 */
@Serializable
enum class PackGroup { ACTIVE_NARRATIVE, ACTIVE_THREADS, ACTIVE_FORESHADOWS, RECENT_EVENTS, OTHER }

/** Token 预算护栏（确定性估算 + 固定优先级裁剪结果）。 */
@Serializable
data class TokenBudgetGuard(
    /** 预算上限（估算 token）。 */
    val budget: Long,
    /** 实际纳入的估算 token 和（确定性）。 */
    val estimatedTokens: Long,
    /** 是否发生裁剪（预算不足）。 */
    val isTruncated: Boolean,
    /** 被整段丢弃的低优先级分组（按上文 [PackGroup] 顺序记录）。 */
    val omittedGroups: List<PackGroup>,
    /** 被丢弃的元素总数。 */
    val omittedCount: Int,
)

/**
 * 章节上下文包（MVP）。
 *
 * @param horizonWindow 最近窗口内的章节 id（窗口，非内容；内容体积不受其拖累）。
 * @param activeNarrative 当前折叠的叙事状态（compact；顶优先级，预算再紧也保留）。
 * @param activeThreads 未解决线程（replace 语义，已由 NarrativeState 压缩）。
 * @param activeForeshadows 未兑现伏笔（窗口裁剪不丢失）。
 * @param recentEvents 窗口内 / 进行中事件（已关闭事件不逐条展开）。
 */
@Serializable
data class ChapterContextPack(
    val contextPackId: String,
    val novelId: NovelId,
    val variantId: VariantId?,
    val scope: VariantScope,
    val chapterId: ChapterId?,
    /** 输入指纹的确定性哈希（输入变化 ⇒ 变化）。 */
    val packVersion: Long,
    val horizonWindow: List<ChapterId> = emptyList(),
    val activeNarrative: NarrativeState? = null,
    val activeThreads: List<OpenThread> = emptyList(),
    val activeForeshadows: List<Foreshadow> = emptyList(),
    val recentEvents: List<Event> = emptyList(),
    val tokenBudgetGuard: TokenBudgetGuard,
)

/**
 * 确定性投影器（纯函数，无 storage / 无 AI）。
 *
 * 窗口规则：
 *  - `recentEvents`：chapterId ∈ `recentChapterIds` **且**未关闭（[EventStatus.COMPLETED]/[EventStatus.CANCELLED]），
 *    **叠加**未关闭且进行中（PLANNED/IN_PROGRESS）的跨窗口事件（进行中线不因窗口裁剪而丢失）；
 *  - `activeForeshadows`：未兑现（`resolved=false`），窗口裁剪不丢失；
 *  - `activeThreads`：直接取自 `NarrativeState.openThreads`（已压缩的未解决集合）；
 *  - 已关闭（completed/cancelled 事件 / resolved 伏笔）：**不逐条展开**，不进 recent 列表，仅计 `omittedCount`。
 *
 * Token 估算（确定性、无 tokenizer）：`tokenOf(text) = ceil(len/4)`（近似英文 token 下限；适用于 UTF-8 任意文本）。
 *
 * Token 裁剪优先级（固定、写死）：1) ACTIVE_NARRATIVE → 2) ACTIVE_THREADS → 3) ACTIVE_FORESHADOWS →
 * 4) RECENT_EVENTS。预算不足时按此顺序保留子集；NarrativeState 为顶优先级，预算再紧也整体保留（此时丢弃其余全部）。
 */
object ChapterContextProjector {

    /** 默认最近章节窗口数。 */
    const val DEFAULT_WINDOW: Int = 5

    /** 默认估算 token 预算。 */
    const val DEFAULT_BUDGET_TOKENS: Int = 1200

    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L
    private const val FNV_PRIME_64 = 1099511628211L

    /**
     * 编译章节上下文包（确定性）。
     *
     * @param recentChapterIds 最近窗口的章节 id（按 order 升序、已截断到 windowSize，由调用方给入）。
     */
    fun compile(
        worldContext: StoryWorldContext,
        narrative: NarrativeState,
        chapterId: ChapterId?,
        recentChapterIds: List<ChapterId>,
        budget: Int = DEFAULT_BUDGET_TOKENS,
    ): ChapterContextPack {
        val windowSet = recentChapterIds.toSet()
        val closed = EVENT_CLOSED

        // 1) 事件：窗口未关闭 + 进行中跨窗口（去重按 id，稳序按 createdAt,id）
        val activeEvents = worldContext.events
            .filter { e -> e.status !in closed && (windowSet.contains(e.chapterId) || e.status == EventStatus.PLANNED || e.status == EventStatus.IN_PROGRESS) }
            .sortedWith(compareBy({ it.createdAt }, { it.id.value }))

        // 2) 未兑现伏笔（活跃；窗口不裁剪）
        val activeForeshadows = worldContext.foreshadows
            .filter { !it.resolved }
            .sortedWith(compareBy({ it.createdAt }, { it.foreshadowId.value }))

        // 3) 未解决线程
        val threads = narrative.openThreads

        // 4) Token 估算（确定性）
        val narrativeTokens = narrativeTokensOf(narrative)
        val threadTokens = threads.map { tokenOf(it.description) }
        val foreshadowTokens = activeForeshadows.map { tokenOf(it.content) }
        val eventTokens = activeEvents.map { eventTokensOf(it) }

        // 5) 固定优先级裁剪
        var remaining = budget.toLong()
        var truncated = false
        var omittedCount = 0
        val omittedGroups = mutableListOf<PackGroup>()
        val keptThreads = mutableListOf<OpenThread>()
        val keptForeshadows = mutableListOf<Foreshadow>()
        val keptEvents = mutableListOf<Event>()

        if (narrativeTokens > remaining) {
            // 顶优先级：NarrativeState 整体保留（截断标记），丢弃其余全部
            truncated = true
            remaining = 0L
            if (threads.isNotEmpty()) omittedGroups += PackGroup.ACTIVE_THREADS
            if (activeForeshadows.isNotEmpty()) omittedGroups += PackGroup.ACTIVE_FORESHADOWS
            if (activeEvents.isNotEmpty()) omittedGroups += PackGroup.RECENT_EVENTS
            omittedCount = threads.size + activeForeshadows.size + activeEvents.size
        } else {
            remaining -= narrativeTokens
            for ((i, t) in threads.withIndex()) {
                val tok = threadTokens[i]
                if (tok > remaining) { truncated = true; omittedCount += (threads.size - i) + activeForeshadows.size + activeEvents.size; omittedGroups += PackGroup.ACTIVE_THREADS; if (activeForeshadows.isNotEmpty()) omittedGroups += PackGroup.ACTIVE_FORESHADOWS; if (activeEvents.isNotEmpty()) omittedGroups += PackGroup.RECENT_EVENTS; break }
                keptThreads += t; remaining -= tok
            }
            if (!truncated) {
                for ((i, f) in activeForeshadows.withIndex()) {
                    val tok = foreshadowTokens[i]
                    if (tok > remaining) { truncated = true; omittedCount += (activeForeshadows.size - i) + activeEvents.size; omittedGroups += PackGroup.ACTIVE_FORESHADOWS; if (activeEvents.isNotEmpty()) omittedGroups += PackGroup.RECENT_EVENTS; break }
                    keptForeshadows += f; remaining -= tok
                }
            }
            if (!truncated) {
                for ((i, e) in activeEvents.withIndex()) {
                    val tok = eventTokens[i]
                    if (tok > remaining) { truncated = true; omittedCount += (activeEvents.size - i); omittedGroups += PackGroup.RECENT_EVENTS; break }
                    keptEvents += e; remaining -= tok
                }
            }
        }

        val estimatedTokens = budget - remaining
        val scope = if (narrative.variantId == null) VariantScope.ORIGINAL else VariantScope.VARIANT
        return ChapterContextPack(
            contextPackId = "pack-${narrative.novelId.value}-${narrative.variantId?.value ?: "orig"}-${chapterId?.value ?: "last"}",
            novelId = narrative.novelId,
            variantId = narrative.variantId,
            scope = scope,
            chapterId = chapterId,
            packVersion = inputVersion(worldContext, narrative, chapterId, recentChapterIds, budget),
            horizonWindow = recentChapterIds,
            activeNarrative = narrative,
            activeThreads = keptThreads,
            activeForeshadows = keptForeshadows,
            recentEvents = keptEvents,
            tokenBudgetGuard = TokenBudgetGuard(
                budget = budget.toLong(),
                estimatedTokens = estimatedTokens,
                isTruncated = truncated,
                omittedGroups = omittedGroups,
                omittedCount = omittedCount,
            ),
        )
    }

    /**
     * 输入指纹的确定性版本号（输入变化 ⇒ 版本变化）；供 `invalidatePack` 比对。
     *
     * **覆盖所有影响 [ChapterContextPack] 输出的字段**，且全部采用**稳定、确定顺序**：
     *  - 范围标识：novelId / variantId / chapterId / horizonWindow / budget；
     *  - activeNarrative：NarrativeState 全部影响字段（version/mainGoal/currentConflict/lastChapterDelta/pacing/
     *    openThreads 及 characterStages / relationshipDeltas / foreshadowPressures）；
     *  - activeThreads：线程 id+description；
     *  - activeForeshadows：未兑现伏笔 id+content；
     *  - recentEvents：全部事件的 id+status+chapterId+name+description+who（覆盖过滤(status)、窗口(chapterId)、排序(createdAt,id)依据）。
     * 注：FNV-1a 仅作确定性 fingerprint，不作安全哈希；所有集合按显式比较器排序，绝不依赖 HashMap/Set 遍历序。
     */
    fun inputVersion(
        worldContext: StoryWorldContext,
        narrative: NarrativeState,
        chapterId: ChapterId?,
        recentChapterIds: List<ChapterId>,
        budget: Int,
    ): Long {
        val stageEntries = narrative.characterStages.entries.sortedBy { it.key.value }
        val relationships = narrative.relationshipDeltas.sortedWith(
            compareBy({ it.participants.joinToString(",") { p -> p.value } }, { it.change }),
        )
        val pressures = narrative.foreshadowPressures.sortedBy { it.foreshadowId.value }
        val openThreads = narrative.openThreads.sortedBy { it.id }
        val activeForeshadows = worldContext.foreshadows
            .filter { !it.resolved }
            .sortedWith(compareBy({ it.createdAt }, { it.foreshadowId.value }))
        val events = worldContext.events.sortedWith(compareBy({ it.createdAt }, { it.id.value }))

        val fp = buildString {
            append(narrative.novelId.value).append('|')
            append(narrative.variantId?.value).append('|')
            append(chapterId?.value).append('|')
            append(recentChapterIds.joinToString(",") { it.value }).append('|')
            append(budget).append('|')
            // activeNarrative
            append(narrative.version).append('|')
            append(narrative.mainGoal).append('|')
            append(narrative.currentConflict?.value).append('|')
            append(narrative.lastChapterDelta).append('|')
            append(narrative.currentPacing?.style).append('#').append(narrative.currentPacing?.note).append('|')
            openThreads.forEach { append(it.id).append('#').append(it.description).append(';') }
            append('|')
            stageEntries.forEach { (id, st) -> append(id.value).append('#').append(st.stage).append('#').append(st.goal).append(';') }
            append('|')
            relationships.forEach { append(it.participants.joinToString(",") { p -> p.value }).append('#').append(it.change).append(';') }
            append('|')
            pressures.forEach { append(it.foreshadowId.value).append('#').append(it.pressure).append(';') }
            append('|')
            // activeForeshadows
            activeForeshadows.forEach { append(it.foreshadowId.value).append('#').append(it.content).append(';') }
            append('|')
            // recentEvents（含过滤 status、窗口 chapterId、排序 createdAt+id 依据）
            events.forEach { e ->
                append(e.id.value).append('#').append(e.status.name).append('#').append(e.chapterId?.value).append('#')
                    .append(e.name).append('#').append(e.description).append('#')
                    .append(e.who.joinToString(",") { w -> w.value }).append(';')
            }
        }
        return fnv1a64(fp)
    }

    /** 确定性估算：token ≈ ceil(len/4)。 */
    private fun tokenOf(text: String): Long = ((text.length + 3) / 4).toLong()

    private fun eventTokensOf(e: Event): Long =
        tokenOf(listOfNotNull(e.name, e.description).joinToString(" ") + " " + e.who.joinToString(","))

    /** NarrativeState 的估算 token：主目标/末章摘要/线程描述/角色阶段目标/关系变化。 */
    private fun narrativeTokensOf(n: NarrativeState): Long {
        var sum = tokenOf(n.mainGoal) + tokenOf(n.lastChapterDelta)
        n.openThreads.forEach { sum += tokenOf(it.description) }
        n.characterStages.entries.sortedBy { it.key.value }.forEach { (_, stage) ->
            sum += tokenOf(stage.stage) + tokenOf(stage.goal)
        }
        n.relationshipDeltas.forEach { sum += tokenOf(it.change) }
        return sum
    }

    private fun fnv1a64(s: String): Long {
        var h = FNV_OFFSET_BASIS_64
        for (c in s) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME_64
        }
        return h
    }

    private val EVENT_CLOSED: Set<EventStatus> = setOf(EventStatus.COMPLETED, EventStatus.CANCELLED)
}