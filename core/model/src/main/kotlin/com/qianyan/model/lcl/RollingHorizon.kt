package com.qianyan.model.lcl

import com.qianyan.model.ChapterId
import kotlinx.serialization.Serializable

/**
 * P13 LCL-D · Rolling Horizon 候选方向（Workflow-local planning projection）。
 *
 * 冻结决策：
 *  - **不是 Story State / NarrativeState / Reveal / Foreshadow**；
 *  - **无正文**：只有方向性摘要/目标/冲突方向/未来窗口引用；
 *  - **有界**：`expectedChapterRange` 限制在窗口内、`references` 明确有上限；
 *  - 生命周期 created → HumanGate（复用 WorkflowHumanGate）→ approved → Planning input（或 rejected / revision）；
 *  - 存储于 Task Checkpoint（复用既有 snapshot 机制，不新建 HorizonCandidate 表）。
 */
@Serializable
data class RollingHorizonCandidate(
    /** 确定性方向 ID（由来源键派生，可复现）。 */
    val directionId: String,
    val summary: String,
    val goal: String,
    val conflictDirection: String,
    /** 未来章节窗口（有界投影，非全量规划）。 */
    val expectedChapterRange: List<ChapterId>,
    /** 引用（线程/冲突/伏笔 id 等），有上限。 */
    val references: List<String>,
)

/**
 * Rolling Horizon 确定性投影器（纯函数，无 storage / 无 AI）。
 *
 * 输入一个**已有界**的 [ChapterContextPack]，输出**有界**候选方向集：
 *  - 每个活跃 openThread 推导一个候选（goal 取自线程描述、「未解决线」），窗口落在 pack.horizonWindow；
 *  - 无活跃线程时，以当前冲突/主线为目标派生一个 fallback 候选；
 *  - `MAX_CANDIDATES` 上限；引用仅取线程 id + 未兑现伏笔 id（截断）；
 *  - 绝不扫描全量历史、绝不携带正文。
 */
object RollingHorizonProjector {

    /** 单次候选方向数量上限。 */
    const val MAX_CANDIDATES: Int = 3

    /** 单候选引用上限。 */
    const val MAX_REFERENCES: Int = 5

    fun project(pack: ChapterContextPack): List<RollingHorizonCandidate> {
        val window = pack.horizonWindow.take(WINDOW_LIMIT)
        val narrative = pack.activeNarrative
        val conflict = narrative?.currentConflict

        val threads = pack.activeThreads
            .sortedWith(compareBy({ it.id }, { it.description }))
        val base: MutableList<RollingHorizonCandidate> = mutableListOf()

        // 每个活跃线程一条候选（有界）
        threads.forEach { t ->
            base += RollingHorizonCandidate(
                directionId = "rh-" + sanitize(t.id),
                summary = "围绕${t.description}推进未解决线",
                goal = t.description.ifBlank { "推进剧情" },
                conflictDirection = conflict?.value?.take(40) ?: "未知冲突",
                expectedChapterRange = window,
                references = referencesFrom(pack).take(MAX_REFERENCES),
            )
            if (base.size >= MAX_CANDIDATES) return base
        }

        if (base.isEmpty()) {
            // fallback：以当前主线/冲突派生单条候选，确保输出非空且决定性
            base += RollingHorizonCandidate(
                directionId = "rh-main-" + sanitize(narrative?.mainGoal.orEmpty().take(24)),
                summary = "推进当前主线：${narrative?.mainGoal.orEmpty().ifBlank { "继续剧情" }}",
                goal = narrative?.mainGoal.orEmpty().ifBlank { "继续当前篇章" },
                conflictDirection = conflict?.value?.take(40) ?: "开放",
                expectedChapterRange = window,
                references = referencesFrom(pack).take(MAX_REFERENCES),
            )
        }
        return base
    }

    private fun referencesFrom(pack: ChapterContextPack): List<String> =
        pack.activeForeshadows.sortedBy { it.foreshadowId.value }.map { it.foreshadowId.value }

    private fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)

    private const val WINDOW_LIMIT: Int = 8
}