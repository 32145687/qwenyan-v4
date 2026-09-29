package com.qianyan.agent.agents

import com.qianyan.model.IntentType
import com.qianyan.model.skill.Skill
import com.qianyan.model.skill.SkillId
import com.qianyan.model.skill.SkillMatch

/**
 * Skill Registry（I7）：Skill 的注册与发现（architecture §11）。
 *
 * ```
 * register   注册（重复 SkillId → 类型化拒绝）
 * get        按 SkillId 查询（不存在 → null）
 * list       全部 Skill（按 SkillId 升序）
 * match      按任务目的匹配可处理的 Skill（确定性排序）
 * ```
 *
 * 职责边界（§5）：
 *  - **只有一套 Registry**：不创建 `SkillToolRegistry`、不包装 `ToolRegistry`、不建第二套注册中心；
 *  - **Registry 不执行 Skill**、**不调用 LLM**、**不执行 Tool**、**不推进 Workflow 状态**、**不涉及 Human Gate**；
 *  - **不可变快照**：注册返回新实例（无共享可变状态）；[list] / [match] 均为纯查询，无 IO、无随机、无时间。
 *
 * 确定性（§12）：
 *  - [list] 一律按 `skillId` 升序 → **不依赖 Map 迭代序**，也与注册顺序无关；
 *  - [match] 一律按 `skillId` 升序 → 不使用 random / 当前时间 / UUID（`matchRank` 等价于该稳定次序）。
 */
class SkillRegistry private constructor(private val byId: Map<SkillId, Skill>) {

    /** 按 SkillId 查询；不存在返回 null（与 `ToolRegistry.find` 同一惯例）。 */
    fun get(skillId: SkillId): Skill? = byId[skillId]

    fun contains(skillId: SkillId): Boolean = byId.containsKey(skillId)

    fun size(): Int = byId.size

    /** 全部 Skill（按 SkillId 升序；确定性，不依赖 Map 迭代序）。 */
    fun list(): List<Skill> = byId.values.sortedBy { it.skillId.value }

    /** 注册一个 Skill，返回**新的不可变快照**；重复 SkillId → [SkillError.DuplicateSkillId]。 */
    fun register(skill: Skill): SkillRegistry = of(list() + skill)

    /**
     * 匹配能够处理该任务目的的 Skill（只回答"有哪些可处理"，不决定执行哪个 —— §13）。
     *
     * 规则（确定性）：`enabled == true` 且 `purpose ∈ skill.purposes`；
     * 结果按 `skillId` 升序（等价于 `matchRank` 稳定次序，不依赖注册顺序）。
     */
    fun match(purpose: IntentType): List<SkillMatch> = list()
        .filter { it.enabled && purpose in it.purposes }
        .map { skill ->
            SkillMatch(
                skillId = skill.skillId,
                matchedPurpose = purpose,
                reason = "${skill.name} 声明处理 ${purpose.name}（能力：${skill.capabilities.joinToString(",") { it.name }}）",
            )
        }
        .sortedBy { it.skillId.value }

    companion object {

        /**
         * 构造不可变 Registry：校验定义 + 拒绝重复 SkillId。
         *
         * @throws SkillException [SkillError.DuplicateSkillId] / [SkillError.InvalidSkill]
         */
        fun of(skills: List<Skill>): SkillRegistry {
            val map = LinkedHashMap<SkillId, Skill>()
            skills.forEach { skill ->
                validate(skill)
                if (map.containsKey(skill.skillId)) {
                    throw SkillException(SkillError.DuplicateSkillId(skill.skillId))
                }
                map[skill.skillId] = skill
            }
            return SkillRegistry(map.toMap())
        }

        /** I7 五项核心 Skill 的默认注册表（纯构造，无 IO —— §16）。 */
        fun default(): SkillRegistry = of(CoreSkills.all())

        private fun validate(skill: Skill) {
            if (skill.skillId.value.isBlank()) {
                throw SkillException(SkillError.InvalidSkill("skillId 不能为空"))
            }
            if (skill.name.isBlank()) {
                throw SkillException(SkillError.InvalidSkill("name 不能为空: ${skill.skillId.value}"))
            }
            if (skill.capabilities.isEmpty()) {
                throw SkillException(SkillError.InvalidSkill("capabilities 不能为空（Skill 必须声明能力类别）: ${skill.skillId.value}"))
            }
            if (skill.purposes.isEmpty()) {
                throw SkillException(SkillError.InvalidSkill("purposes 不能为空（否则无法参与匹配）: ${skill.skillId.value}"))
            }
            if (skill.version <= 0) {
                throw SkillException(SkillError.InvalidSkill("version 必须为正: ${skill.skillId.value}"))
            }
        }
    }
}