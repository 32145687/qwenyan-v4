package com.qianyan.application.usecase.lcl

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.UseCase
import com.qianyan.application.usecase.task.TaskManagerUseCases
import com.qianyan.model.TaskId
import com.qianyan.model.lcl.ChapterContextPack
import com.qianyan.model.lcl.RollingHorizonCandidate
import com.qianyan.model.lcl.RollingHorizonProjector
import com.qianyan.model.task.Checkpoint
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * P13 LCL-D · Rolling Horizon Use Case（Workflow-local，bounded）。
 *
 * 冻结边界：
 *  - [proposeCandidates]：输入**已有界**的 [ChapterContextPack]，经 [RollingHorizonProjector] 输出**有界**候选；
 *    deterministic、无 LLM、不扫全量历史、不携带正文。
 *  - [storeCandidates]：候选作为 **Workflow-local planning projection** 存入 **Task Checkpoint**
 *    （复用既有 snapshot 机制，不新建 HorizonCandidate 表 / 不入 Story State / NarrativeState / Reveal / Foreshadow）。
 *  - 生命周期 created → HumanGate（复用 WorkflowHumanGate）→ approved → Planning input（或 rejected / revision）；
 *    不新建 Gate / Agent。
 */
class RollingHorizonUseCases(
    private val taskManager: TaskManagerUseCases,
    errorMapper: ErrorMapper,
) : UseCase(errorMapper) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 由过程包确定性产生有界候选方向集。 */
    fun proposeCandidates(pack: ChapterContextPack): List<RollingHorizonCandidate> =
        RollingHorizonProjector.project(pack)

    /** 把候选写入某（PLANNING）Task 的 Checkpoint（有界 payload）。 */
    fun storeCandidates(taskId: TaskId, candidates: List<RollingHorizonCandidate>, stage: String = STAGE) {
        val snapshot: JsonObject = JsonObject(
            mapOf(KEY to json.encodeToJsonElement(ListSerializer(RollingHorizonCandidate.serializer()), candidates)),
        )
        guard { taskManager.saveCheckpoint(taskId, stage, snapshot) }
    }

    /** 从 Checkpoint 恢复候选（只读投影，不重新执行）。 */
    fun candidatesFrom(checkpoint: Checkpoint): List<RollingHorizonCandidate>? {
        val list = checkpoint.snapshot?.get(KEY) ?: return null
        return json.decodeFromJsonElement(ListSerializer(RollingHorizonCandidate.serializer()), list)
    }

    private companion object {
        const val STAGE: String = "ROLLING_HORIZON"
        const val KEY: String = "candidates"
    }
}