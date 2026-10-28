package com.qianyan.application.usecase.agent

import com.qianyan.application.usecase.action.ActionPolicyUseCases
import com.qianyan.application.usecase.context.ContextEngineUseCases
import com.qianyan.application.usecase.tool.ProductToolService
import com.qianyan.model.AgentId
import com.qianyan.model.action.ActionDecision
import com.qianyan.model.action.AgentAction
import com.qianyan.model.skill.Skill

/**
 * Phase 6 · Skill 契约字段的生产读取与执行前一致性校验。
 *
 * 只做校验，不执行 Skill、不改权限、不构建 Context、不调用 LLM：
 *  - `allowedTools`：每个声明工具必须已在 ProductToolService 注册；
 *  - `requiredContext`：每个声明来源必须是 ContextEngine 当前配置的 Source 类型；
 *  - `expectedActions`：必须能由 ActionPolicy 处理；Denied / NeedsHuman 不得自主执行；
 *  - `bindings`：必须非空，且可执行 Skill 的绑定必须包含实际执行 Agent；
 *  - `purposes` / `capabilities`：由 SkillRegistry 匹配与注册校验读取。
 */
class SkillExecutionGuard(
    private val productTools: ProductToolService,
    private val actionPolicy: ActionPolicyUseCases,
    private val contextEngine: ContextEngineUseCases,
) {

    fun violations(skill: Skill, expectedAgentId: AgentId?): List<String> = buildList {
        skill.allowedTools
            .filter { productTools.find(it) == null }
            .forEach { add("allowedTools 声明了未注册工具: ${it.value}") }

        skill.requiredContext
            .filter { it !in contextEngine.sourceKinds }
            .forEach { add("requiredContext 声明了未配置的 Context 来源: ${it.name}") }

        if (skill.bindings.isEmpty()) {
            add("bindings 不能为空（Skill 必须声明实现绑定）")
        }

        skill.expectedActions.forEach { kind ->
            when (val decision = actionPolicy.evaluate(AgentAction(kind))) {
                is ActionDecision.Denied ->
                    add("expectedActions 含被拒绝动作: ${kind.name}（${decision.reason}）")
                is ActionDecision.NeedsHuman ->
                    add("expectedActions 含需人工确认动作，不能自主执行: ${kind.name}（${decision.reason}）")
                is ActionDecision.Allowed -> Unit
            }
        }

        if (expectedAgentId != null && skill.bindings.none { it.agentId == expectedAgentId }) {
            add("bindings 未包含实际执行 Agent: ${expectedAgentId.value}")
        }
    }
}
