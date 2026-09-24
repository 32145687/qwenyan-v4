package com.qianyan.application.usecase.decision

import com.qianyan.model.author.AuthorContext
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionType

/** P19 · DecisionModelGateway —— Android / 未来 Desktop 共用 Decision 的**用户层 Application seam**（薄委托）。 */
interface DecisionModelGateway {
    fun decide(authorContext: AuthorContext, vararg types: DecisionType): List<DecisionPolicy>
}

/** P19 · DecisionModelFacade —— [DecisionModelGateway] 的极薄委托实现（镜像 AuthorDnaFacade 模式）。 */
class DecisionModelFacade(
    private val useCases: DecisionModelUseCases,
) : DecisionModelGateway {
    override fun decide(authorContext: AuthorContext, vararg types: DecisionType): List<DecisionPolicy> =
        useCases.decide(authorContext, *types)
}
