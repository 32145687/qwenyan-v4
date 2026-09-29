package com.qianyan.application.di

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.agent.agents.CoreSkills
import com.qianyan.model.IntentType
import com.qianyan.model.action.AgentActionKind
import com.qianyan.model.context.ContextSourceKind
import com.qianyan.model.skill.Skill
import com.qianyan.model.skill.SkillId
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I7 · Skill 绑定复用验证（§17 Existing capability reuse / §21 D）。
 *
 * 目标：证明 Skill 的 metadata / binding 指向**真实存在的既有能力**，而不是空壳：
 *  - 每项核心 Skill 绑定到既有五 Agent 之一（并与真实源码中的 `agentId` 逐字一致）；
 *  - 声明的 `allowedTools` 必须是真实已注册的 I5 Product Tool；
 *  - `requiredContext` / `expectedActions` 必须使用既有 I6 / I2 类型；
 *  - 访问 Registry 不写任何数据（§15：不需要数据库）。
 */
class SkillRegistryBindingTest {

    /** 绑定的 AgentId → 既有 Agent 源码（相对 application 模块）。 */
    private val agentSourcePath = mapOf(
        "story-planner" to "planning/PlannerAgent.kt",
        "story-writer" to "WriterAgent.kt",
        "story-writer-revision" to "revision/RevisionAgent.kt",
        "story-critic" to "critique/CritiqueAgent.kt",
        "knowledge-update" to "knowledgeupdate/KnowledgeUpdateAgent.kt",
    )

    private val writingSourceDir = "src/main/kotlin/com/qianyan/application/usecase/writing"

    private fun coreSkill(id: String): Skill = CoreSkills.all().single { it.skillId.value == id }

    @Test
    fun `container seam exposes the five core skills`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())

        assertEquals(5, app.skillRegistry.size(), "容器 seam 暴露五项核心 Skill")
        assertEquals(
            listOf(
                "skill.analysis-critique",
                "skill.knowledge-world-model",
                "skill.rewrite-revision",
                "skill.story-planning",
                "skill.writing",
            ),
            app.skillRegistry.list().map { it.skillId.value },
        )
        handle.driver.close()
    }

    @Test
    fun `each core skill binds to a real existing agent`() {
        val expected = mapOf(
            "skill.story-planning" to ("story-planner" to "PlannerAgent"),
            "skill.writing" to ("story-writer" to "WriterAgent"),
            "skill.rewrite-revision" to ("story-writer-revision" to "RevisionAgent"),
            "skill.analysis-critique" to ("story-critic" to "CritiqueAgent"),
            "skill.knowledge-world-model" to ("knowledge-update" to "KnowledgeUpdateAgent"),
        )

        expected.forEach { (skillId, pair) ->
            val (agentId, className) = pair
            val skill = coreSkill(skillId)

            assertEquals(1, skill.bindings.size, "$skillId 应有且仅有一个既有能力绑定")
            assertEquals(agentId, skill.bindings.single().agentId.value, "$skillId 绑定既有 Agent")
            assertTrue(skill.bindings.single().note.isNotBlank(), "$skillId 绑定说明非空（可追溯）")

            val source = File("$writingSourceDir/${agentSourcePath.getValue(agentId)}")
            assertTrue(source.isFile, "找不到既有 Agent 源码：${source.absolutePath}")
            val text = source.readText()
            assertTrue("class $className" in text, "$className 必须依然存在（绑定不得指向空壳）")
            assertTrue(
                "agentId = AgentId(\"$agentId\")" in text,
                "$className 的 agentId 必须依然为 $agentId（Skill 绑定与既有能力一致）",
            )
        }
    }

    @Test
    fun `declared tools and context kinds are real existing capabilities`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
        val registeredTools = app.productTools.availableTools().map { it.toolName.value }.toSet()

        CoreSkills.all().forEach { skill ->
            val id = skill.skillId.value
            assertTrue(skill.capabilities.isNotEmpty(), "$id 必须声明能力类别（复用既有 Capability）")
            assertTrue(skill.allowedTools.isNotEmpty(), "$id 应声明预期使用的既有 Tool")
            skill.allowedTools.forEach { tool ->
                assertTrue(tool.value in registeredTools, "$id 声明的 ${tool.value} 必须是真实已注册的 Product Tool")
            }
            assertTrue(skill.requiredContext.isNotEmpty(), "$id 应声明所需 Context 类型")
            assertTrue(
                skill.requiredContext.all { it in ContextSourceKind.entries },
                "$id 只使用既有 I6 Context 来源类型",
            )
            assertTrue(skill.expectedActions.isNotEmpty(), "$id 应声明预期动作类别")
            assertTrue(
                skill.expectedActions.all { it in AgentActionKind.entries },
                "$id 只使用既有 I2 动作类别",
            )
            assertTrue(skill.version > 0, "$id 版本必须为正（metadata）")
        }
        handle.driver.close()
    }

    @Test
    fun `matching by purpose covers the core skills deterministically`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())

        assertEquals(
            listOf("skill.story-planning"),
            app.skillRegistry.match(IntentType.PLAN).map { it.skillId.value },
        )
        assertEquals(
            listOf("skill.rewrite-revision"),
            app.skillRegistry.match(IntentType.REWRITE).map { it.skillId.value },
        )
        assertEquals(
            listOf("skill.knowledge-world-model", "skill.writing"),
            app.skillRegistry.match(IntentType.CONTINUE).map { it.skillId.value },
            "多 Skill 稳定排序（skillId 升序）",
        )
        assertEquals(5, app.skillRegistry.match(IntentType.CUSTOM).size, "CUSTOM 为兜底目的：五项全部可处理")
        assertEquals(
            app.skillRegistry.match(IntentType.CUSTOM).map { it.skillId.value }.sorted(),
            app.skillRegistry.match(IntentType.CUSTOM).map { it.skillId.value },
            "匹配结果确定性排序",
        )
        assertTrue(app.skillRegistry.match(IntentType.ANALYZE).isNotEmpty())
        handle.driver.close()
    }

    @Test
    fun `skill registry access writes nothing`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, MockLLMGateway())
        val tables = listOf("Activity", "ToolCallLog", "AgentSession", "Novel", "ChapterDraft", "ProjectState")
        val before = tables.associateWith { countRows(handle, it) }

        val registry = app.skillRegistry
        registry.list()
        registry.match(IntentType.CUSTOM)
        registry.get(SkillId("skill.writing"))

        before.forEach { (table, count) ->
            assertEquals(count, countRows(handle, table), "$table 不得被 Skill Registry 访问（§15：Registry 不需要数据库）")
        }
        handle.driver.close()
    }

    private fun countRows(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM $table",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
        ).value
}