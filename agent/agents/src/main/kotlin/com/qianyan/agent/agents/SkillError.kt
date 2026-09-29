package com.qianyan.agent.agents

import com.qianyan.model.skill.SkillId

/**
 * Skill 层类型化错误（与 `:agent:tool` 的 `ToolError` / `ToolException` 同一惯例，不新建错误体系）。
 *
 * 禁止经 String.contains 判断错误类型；调用方按类型捕获。
 */
sealed interface SkillError {

    /** 同一 [SkillId] 被注册两次：Skill 身份必须唯一且稳定。 */
    data class DuplicateSkillId(val skillId: SkillId) : SkillError

    /** Skill 定义非法（空 id / 空名称 / 未声明能力或适用目的 / 非法版本）。 */
    data class InvalidSkill(val detail: String) : SkillError
}

/** Skill 层运行时异常载体；携带唯一类型化错误 [error]。 */
class SkillException(
    val error: SkillError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)