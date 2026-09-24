package com.qianyan.application.usecase.decision

import com.qianyan.model.author.AuthorContext
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionType

/**
 * P19 · DecisionModelUseCases —— P19 public entry（deterministic evaluation）。
 * 只接收调用方传入的 [AuthorContext]；不访问 Repository / Storage / Provider / LLM / Agent / Orchestrator。
 */
class DecisionModelUseCases(
    private val decisionModel: DecisionModel = DecisionModel,
) {
    fun decide(
        authorContext: AuthorContext,
        vararg types: DecisionType,
    ): List<DecisionPolicy> = decisionModel.decide(authorContext, *types)
}
