package com.qianyan.agent.agents

import com.qianyan.model.IntentType
import com.qianyan.model.agent.Capability
import com.qianyan.model.skill.Skill
import com.qianyan.model.skill.SkillId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I7 · Skill Registry 测试（§17 Registry / Matching）。
 *
 * 覆盖：注册成功、duplicate ID、get / list / contains、unknown Skill、确定性顺序、
 * purpose 匹配与多 Skill 稳定排序、无匹配、disabled、默认五项核心 Skill、源码边界守卫。
 *
 * 全部使用固定定义：不依赖时间 / UUID / 随机（§18）。
 */
class SkillRegistryTest {

    private fun skill(
        id: String,
        vararg purposes: IntentType,
        enabled: Boolean = true,
    ): Skill = Skill(
        skillId = SkillId(id),
        name = id,
        description = "测试 Skill $id",
        capabilities = listOf(Capability("test", "测试能力")),
        purposes = purposes.toList(),
        enabled = enabled,
    )

    @Test
    fun `register adds a skill and leaves the original registry unchanged`() {
        val base = SkillRegistry.of(emptyList())

        val withOne = base.register(skill("skill.a", IntentType.PLAN))

        assertEquals(0, base.size(), "Registry 不可变：register 返回新实例")
        assertEquals(1, withOne.size())
        assertNotNull(withOne.get(SkillId("skill.a")))
        assertTrue(withOne.contains(SkillId("skill.a")))
        assertNull(base.get(SkillId("skill.a")))
    }

    @Test
    fun `duplicate skill id is rejected`() {
        val ex = assertFailsWith<SkillException> {
            SkillRegistry.of(listOf(skill("skill.dup", IntentType.PLAN), skill("skill.dup", IntentType.PLAN)))
        }

        assertTrue(ex.error is SkillError.DuplicateSkillId, "重复 SkillId → 类型化拒绝")
        assertEquals(SkillId("skill.dup"), (ex.error as SkillError.DuplicateSkillId).skillId)
    }

    @Test
    fun `registering an already present id on a registry is rejected`() {
        val registry = SkillRegistry.of(listOf(skill("skill.a", IntentType.PLAN)))

        val ex = assertFailsWith<SkillException> { registry.register(skill("skill.a", IntentType.PLAN)) }

        assertTrue(ex.error is SkillError.DuplicateSkillId)
    }

    @Test
    fun `invalid skill definitions are rejected`() {
        val valid = skill("skill.a", IntentType.PLAN)
        val invalid = listOf(
            valid.copy(skillId = SkillId("  ")),
            valid.copy(name = "  "),
            valid.copy(capabilities = emptyList()),
            valid.copy(purposes = emptyList()),
            valid.copy(version = 0),
        )

        invalid.forEach { bad ->
            val ex = assertFailsWith<SkillException>("非法定义必须被拒绝: $bad") { SkillRegistry.of(listOf(bad)) }
            assertTrue(ex.error is SkillError.InvalidSkill)
        }
    }

    @Test
    fun `unknown skill is not found`() {
        val registry = SkillRegistry.of(listOf(skill("skill.a", IntentType.PLAN)))

        assertNull(registry.get(SkillId("ghost")), "未注册 Skill → null")
        assertFalse(registry.contains(SkillId("ghost")))
    }

    @Test
    fun `list is sorted by skill id and independent of registration order`() {
        val forward = SkillRegistry.of(
            listOf(
                skill("skill.c", IntentType.PLAN),
                skill("skill.a", IntentType.PLAN),
                skill("skill.b", IntentType.PLAN),
            ),
        )
        val reversed = SkillRegistry.of(
            listOf(
                skill("skill.b", IntentType.PLAN),
                skill("skill.c", IntentType.PLAN),
                skill("skill.a", IntentType.PLAN),
            ),
        )

        assertEquals(
            listOf("skill.a", "skill.b", "skill.c"),
            forward.list().map { it.skillId.value },
            "按 skillId 升序（不依赖 Map 迭代序）",
        )
        assertEquals(
            forward.list().map { it.skillId.value },
            reversed.list().map { it.skillId.value },
            "注册顺序不同 ⇒ 查询结果相同（确定性）",
        )
    }

    @Test
    fun `match returns skills declaring the purpose in stable order`() {
        val registry = SkillRegistry.of(
            listOf(
                skill("skill.b", IntentType.CONTINUE),
                skill("skill.a", IntentType.CONTINUE),
                skill("skill.c", IntentType.PLAN),
            ),
        )

        val matches = registry.match(IntentType.CONTINUE)

        assertEquals(listOf("skill.a", "skill.b"), matches.map { it.skillId.value }, "多匹配稳定排序（skillId 升序）")
        assertTrue(matches.all { it.matchedPurpose == IntentType.CONTINUE })
        assertTrue(matches.all { it.reason.isNotBlank() }, "匹配理由非空（可解释）")
    }

    @Test
    fun `match returns empty when nothing declares the purpose`() {
        val registry = SkillRegistry.of(listOf(skill("skill.a", IntentType.PLAN)))

        assertTrue(registry.match(IntentType.REWRITE).isEmpty(), "无匹配 ⇒ 空列表（不是错误）")
    }

    @Test
    fun `disabled skills stay registered but never match`() {
        val registry = SkillRegistry.of(listOf(skill("skill.a", IntentType.PLAN, enabled = false)))

        assertTrue(registry.match(IntentType.PLAN).isEmpty(), "disabled Skill 不参与匹配")
        assertEquals(1, registry.size(), "仍然注册（只是不可用）")
    }

    @Test
    fun `match is repeatable for the same registry and purpose`() {
        val registry = SkillRegistry.default()
        val first = registry.match(IntentType.CUSTOM)

        repeat(3) { assertEquals(first, registry.match(IntentType.CUSTOM), "同输入 ⇒ 同匹配结果（无随机 / 无时间）") }
        assertTrue(first.isNotEmpty())
    }

    @Test
    fun `default registry registers the five core skills`() {
        val registry = SkillRegistry.default()

        assertEquals(5, registry.size(), "I7 五项核心 Skill")
        assertEquals(
            listOf(
                "skill.analysis-critique",
                "skill.knowledge-world-model",
                "skill.rewrite-revision",
                "skill.story-planning",
                "skill.writing",
            ),
            registry.list().map { it.skillId.value },
        )
        assertEquals(5, CoreSkills.all().map { it.skillId }.toSet().size, "五项 Skill ID 互不重复")
        assertEquals(
            setOf(
                CoreSkills.CAPABILITY_STORY_PLANNING,
                CoreSkills.CAPABILITY_WRITING,
                CoreSkills.CAPABILITY_REWRITE_REVISION,
                CoreSkills.CAPABILITY_ANALYSIS_CRITIQUE,
                CoreSkills.CAPABILITY_KNOWLEDGE_WORLD_MODEL,
            ),
            CoreSkills.all().flatMap { skill -> skill.capabilities.map { it.name } }.toSet(),
            "五类核心能力全部声明",
        )
    }

    @Test
    fun `skill sources stay declaration only and layered`() {
        val dir = File("src/main/kotlin/com/qianyan/agent/agents")
        assertTrue(dir.isDirectory, "找不到 Skill 源码目录：${dir.absolutePath}")
        val code = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
        listOf(
            // 不直连存储 / 不建第二套长期数据
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "storage.repository", "Repository",
            // 不调用 LLM / Provider / Agent runtime
            "LLMGateway", "ModelProfile", "provider.api", "provider.impl", "AgentRuntime",
            // 不执行 Tool / 不构建 Context / 不推进 Workflow / 不判定权限
            "ToolExecutor", "ToolRegistry", "ContextEngine", "WorkflowOrchestrator", "ActionPolicy",
            // 不吸收后续阶段职责
            "WorkingDraft", "Artifact", "Inspector", "ProjectIndex",
        ).forEach { token -> assertTrue(token !in code, "Skill Registry 不得出现 '$token'（只读声明 + 分层）") }
    }
}