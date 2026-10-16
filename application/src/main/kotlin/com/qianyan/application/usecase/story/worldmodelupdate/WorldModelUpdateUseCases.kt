package com.qianyan.application.usecase.story.worldmodelupdate

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.story.worldmodelupdate.WorldModelFactValidator.validate
import com.qianyan.model.change.TextDiffer
import com.qianyan.model.commit.CommitHistoryEntry
import com.qianyan.model.EventId
import com.qianyan.model.knowledge.FactLevel
import com.qianyan.model.timeline.Event
import com.qianyan.model.timeline.EventStatus
import com.qianyan.model.timeline.EventType
import com.qianyan.model.worldmodel.CandidateFact
import com.qianyan.model.worldmodel.CandidateFactKind
import com.qianyan.model.worldmodel.CandidateFactOperation
import com.qianyan.model.worldmodel.CandidateFactStatus
import com.qianyan.storage.repository.StoryStateRepository
import com.qianyan.storage.repository.WorldModelCandidateRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Phase 3 · World Model Update Pipeline（v3.1 §12）。
 *
 * 生产调用链（唯一触发点 = **已成功提交**的 Commit；挂在 `CommitUseCases` 四条提交路径的事务之后）：
 * ```
 * CommitHistoryEntry（resultingContent + previousContent）
 *   → 幂等重放检查（同 historyId 的候选已存在 ⇒ 原样返回，不重复抽取/写入）
 *   → TextDiffer 判定正文差异（无差异 ⇒ 零写入退出）
 *   → Index（现有事件身份引用）+ AI（WorldModelUpdateAgent → LLM）抽取候选
 *   → WorldModelFactValidator（确定性：来源片段必须逐字包含于提交后正文等）
 *   → 冲突分类（与现有事件按 novel+variant+scope+name 判定；同名 ⇒ CONFLICT，**绝不覆盖**）
 *   → 单事务落地：候选行（PENDING → ACCEPTED/REJECTED/CONFLICT）+ ACCEPTED 事件写六表
 * ```
 *
 * 边界（§12.6 + 架构约束）：
 *  - **只在 Commit 之后**：Draft / Proposal / 未提交内容永不进入本 Pipeline（挂载点唯一）；
 *  - **冲突零静默覆盖**：CONFLICT 只写候选表留待人审，既有事实不被触碰（Original 写保护触发器兜底）；
 *  - **来源可追溯**：候选行必带 historyId / commitId / sourceExcerpt；ACCEPTED 行另带 resultingFactId；
 *  - **事务**：候选与事件写入在同一 [StoryStateRepository.inTransaction] 内，任一失败整体回滚，
 *    不留半完成状态；Commit 本身在此前事务中已持久化。[onCommit] 对直接调用方保持类型化异常
 *    （[WorldModelUpdateException]）；Commit 挂载点捕获后转为**可追溯的 REJECTED 记录**
 *    （[recordFailure]）——已成功的提交不因旁路失败而被报告为失败，但失败也不静默；
 *  - **Original 不可改写**：本 Pipeline 只**新增**事实行（Original 新行插入不被触发器拦截），
 *    不 UPDATE / DELETE 任何既有行；Variant 写入遵守既有隔离语义（scope/variantId 与 Commit target 一致）；
 *  - **实现范围（非 §12 明文要求）**：仅 EVENT 类候选、仅 ADD 操作可落地，其余候选记录为 REJECTED
 *    可追溯；kind / operation 的扩展需架构裁决（Architecture Closure U3）。
 *
 * 失败语义（四类分开；其中「抽取失败后的处理策略」属 Architecture Closure U1/U8 待裁决项）：
 *  - **A. Commit 自身事务失败**：本 Pipeline 不执行（挂载点在事务成功之后）——不会产生任何知识更新记录；
 *  - **B. 抽取/解析/校验阶段失败**（[WorldModelUpdateException]）：`onCommit` 对直接调用方**保持类型化异常**；
 *    Commit 挂载点捕获后记录为一条可追溯的 REJECTED 记录（[recordFailure]，非候选事实：`fact_name` 为
 *    标记名、`source_excerpt` 为空、不写入任何 World Model 行），使已成功的提交不至于被报告为失败、
 *    失败亦不静默。**该策略是当前实现选择，§12 未定义旁路失败归属**（复用 I4 日志需 AgentSession，
 *    而作者手改 / TXT 初始化 / Revert 路径无 session ⇒ 无法在不伪造 session 的前提下复用）；
 *  - **C/D. 候选或事实持久化失败**：位于同一事务内，异常**不被捕获**、原样上抛给调用方（不得把真实
 *    持久化故障伪装成"模型输出被拒绝"），且整体回滚——不会出现"标记 ACCEPTED 但事实未写入"的假成功。
 */
class WorldModelUpdateUseCases(
    private val agent: WorldModelUpdateAgent,
    private val candidates: WorldModelCandidateRepository,
    private val storyState: StoryStateRepository,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    /**
     * 对一次已成功的 [CommitHistoryEntry] 执行 World Model Update。
     *
     * 幂等：同一 [CommitHistoryEntry.historyId] 重复进入 ⇒ 返回既有候选，不重复抽取/写入。
     */
    fun onCommit(entry: CommitHistoryEntry): CandidateOutcome {
        // 1) 幂等重放：该 Commit 已产生过候选 ⇒ 原样返回
        val existing = guard { candidates.listByHistory(entry.historyId.value) }
        if (existing.isNotEmpty()) {
            return CandidateOutcome(entry.historyId.value, existing, replay = true)
        }

        // 2) 正文差异判定：无差异 ⇒ 零写入退出（不做无意义更新）
        val diff = TextDiffer.diff(entry.previousContent, entry.resultingContent)
        if (!diff.hasChanges) {
            return CandidateOutcome(entry.historyId.value, emptyList(), replay = false)
        }

        // 3) Index 腿：现有事件身份引用（同作用域；冲突判定与 LLM 提示都用它）
        val existingEvents = guard { storyState.listEvents(entry.target.novelId, entry.target.variantId) }

        // 4) AI 腿：抽取候选（作用域 / Commit 来源由上下文注入，LLM 不提供）
        val context = WorldModelUpdateContext(
            historyId = entry.historyId.value,
            commitId = entry.commitId.value,
            novelId = entry.target.novelId,
            variantId = entry.target.variantId,
            scope = entry.target.let { if (it.variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT },
            chapterId = entry.target.chapterId,
            previousContent = entry.previousContent,
            resultingContent = entry.resultingContent,
        )
        val extracted = agent.propose(context, existingEvents.map { it.name })

        // 5) 确定性校验（Truth Enforcement：来源片段必须逐字包含于提交后正文）
        val validated = validate(extracted, entry.resultingContent)

        // 6) 单事务落地：候选（PENDING → 终态）+ ACCEPTED 事件写六表（冲突不覆盖）。
        //    状态回写必须用**生成的 candidateId**（行主键），而不是 LLM 提供的 factId（仅批次内身份）。
        val now = Clock.System.now()
        val staged = extracted.map { it to toCandidate(it, entry, CandidateFactStatus.PENDING, now) }
        storyState.inTransaction {
            staged.forEach { (_, row) -> candidates.save(row) }
            staged.forEach { (f, row) ->
                val rejection = validated.rejected.firstOrNull { it.fact === f }
                // Validator 对输入做完整划分：未被拒绝的候选即 accepted（同一实例引用）。
                if (rejection != null) {
                    candidates.updateStatus(row.candidateId, CandidateFactStatus.REJECTED, rejection.reason, null, null)
                } else {
                    val conflict = existingEvents.firstOrNull { it.name == f.name }
                    if (conflict == null) {
                        val event = toEvent(f, entry, now)
                        storyState.saveEvent(event)
                        candidates.updateStatus(row.candidateId, CandidateFactStatus.ACCEPTED, null, null, event.id.value)
                    } else {
                        candidates.updateStatus(
                            row.candidateId, CandidateFactStatus.CONFLICT,
                            "与既有同名事件冲突（${conflict.id.value}），不得覆盖，留待人审",
                            conflict.id.value, null,
                        )
                    }
                }
            }
        }

        val rows = guard { candidates.listByHistory(entry.historyId.value) }
        return CandidateOutcome(entry.historyId.value, rows, replay = false)
    }

    /**
     * 挂载点策略（**Commit 已成功**但本次抽取/落地流程失败）：写入一条 REJECTED 追溯记录，
     * 使失败可查询、不静默；不回滚 Commit，也不写任何 World Model 事实。
     *
     * 该行是**失败标记**而非事实候选：`kind` / `operation` 仅因列非空取默认值，
     * `sourceExcerpt` 留空（没有可追溯的事实来源片段），识别方式为
     * [FAILURE_MARKER_NAME] + decisionReason 前缀；任何读取方不得把它当作候选事实处理。
     * 幂等：同一 [CommitHistoryEntry.historyId] 已有任何候选记录 ⇒ 不重复写标记。
     * 该机制的服务端策略见类 KDoc「失败语义 B」，属 Architecture Closure U1/U8 待裁决项。
     */
    fun recordFailure(entry: CommitHistoryEntry, detail: String): CandidateFact {
        val existing = guard { candidates.listByHistory(entry.historyId.value) }
        if (existing.isNotEmpty()) return existing.first()
        val row = CandidateFact(
            candidateId = nextId(),
            historyId = entry.historyId.value,
            commitId = entry.commitId.value,
            novelId = entry.target.novelId,
            variantId = entry.target.variantId,
            scope = if (entry.target.variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
            chapterId = entry.target.chapterId,
            kind = CandidateFactKind.EVENT,
            operation = CandidateFactOperation.ADD,
            factName = FAILURE_MARKER_NAME,
            summary = detail,
            sourceExcerpt = "",
            reason = "",
            status = CandidateFactStatus.REJECTED,
            decisionReason = "$FAILURE_MARKER_REASON: $detail",
            createdAt = Clock.System.now(),
        )
        guard { candidates.save(row) }
        return row
    }

    private fun toCandidate(
        f: ExtractedFact,
        entry: CommitHistoryEntry,
        status: CandidateFactStatus,
        now: Instant,
    ) = CandidateFact(
        candidateId = nextId(),
        historyId = entry.historyId.value,
        commitId = entry.commitId.value,
        novelId = entry.target.novelId,
        variantId = entry.target.variantId,
        scope = if (entry.target.variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
        chapterId = entry.target.chapterId,
        kind = f.kind,
        operation = f.operation,
        factName = f.name,
        summary = f.summary,
        sourceExcerpt = f.excerpt,
        reason = f.reason,
        status = status,
        createdAt = now,
    )

    /** ACCEPTED 候选 → 新事件行（只新增；factLevel=GENERATED 标记来源为提取生成）。 */
    private fun toEvent(f: ExtractedFact, entry: CommitHistoryEntry, now: Instant) = Event(
        id = EventId(nextId()),
        novelId = entry.target.novelId,
        variantId = entry.target.variantId,
        scope = if (entry.target.variantId == null) com.qianyan.model.VariantScope.ORIGINAL else com.qianyan.model.VariantScope.VARIANT,
        name = f.name,
        description = f.summary,
        type = EventType.MAIN_PLOT,
        what = f.summary,
        factLevel = FactLevel.GENERATED,
        status = EventStatus.CONFIRMED,
        chapterId = entry.target.chapterId,
        createdAt = now,
    )

    private companion object {
        /**
         * 失败标记行的 `fact_name` 值：显式声明它**不是候选事实**（不是任何事实身份），
         * 以免任何读取方把它当作 LLM 提出的候选或既有事实。
         */
        const val FAILURE_MARKER_NAME = "(非候选事实：抽取流程失败标记)"

        /** 失败标记行的 `decision_reason` 前缀。 */
        const val FAILURE_MARKER_REASON = "World Model Update 流程失败，本次提交未产生候选"
    }
}

/** 一次 onCommit 的结果：该 Commit 的全部候选（含终态）与是否为幂等重放。 */
data class CandidateOutcome(
    val historyId: String,
    val candidates: List<CandidateFact>,
    val replay: Boolean,
) {
    val accepted: List<CandidateFact> get() = candidates.filter { it.status == CandidateFactStatus.ACCEPTED }
    val rejected: List<CandidateFact> get() = candidates.filter { it.status == CandidateFactStatus.REJECTED }
    val conflicts: List<CandidateFact> get() = candidates.filter { it.status == CandidateFactStatus.CONFLICT }
}
