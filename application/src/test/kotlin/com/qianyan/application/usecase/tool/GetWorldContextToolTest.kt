package com.qianyan.application.usecase.tool

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.ActivityId
import com.qianyan.model.agent.ToolName
import com.qianyan.model.tool.ToolRequest
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2 Unit 2 · `get_world_context` 只读 Product Tool 测试（v3.1 §11.5「读侧契约（Tool 层能力）」）。
 *
 * 覆盖：真实数据读取（含 v27 字段）、跨 Project / 不存在作用域按 NOT_FOUND 拒绝（不泄漏存在性）、
 * 空数据稳定空结果、读取**零写入**（World Model / Canonical 行数不变）、经既有调度链（ActionPolicy + ToolCallLog）。
 */
class GetWorldContextToolTest {

    private val now = Clock.System.now()

    private class F(val app: ApplicationContainer, val handle: QianyanDbHandle) {
        fun close() = handle.driver.close()
    }

    private fun fixture(): F {
        val h = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        return F(ApplicationContainer.fromDriver(h.driver, MockLLMGateway()), h)
    }

    /** 作用域锚：Original Novel + Session + Activity（工具从 Activity 读 projectId）。 */
    private fun activity(f: F, title: String): Pair<ActivityId, String> {
        val novelId = f.app.novels.createOriginal(title = title)
        val session = f.app.agentSessions.startSession(novelId)
        val activityId = f.app.activities.start(session.sessionId, "WM_TOOL_TEST").activityId
        return activityId to novelId.value
    }

    private fun countRows(handle: QianyanDbHandle, table: String): Long =
        handle.driver.executeQuery(
            null, "SELECT COUNT(*) FROM $table",
            { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) }, 0,
        ).value

    private fun seedWorld(app: ApplicationContainer, novelId: String) {
        val n = com.qianyan.model.NovelId(novelId)
        app.storyState.saveCharacter(
            com.qianyan.model.character.Character(
                characterId = com.qianyan.model.CharacterId("t-c1"), novelId = n,
                scope = com.qianyan.model.VariantScope.ORIGINAL, name = "沈青梧",
                personality = listOf("冷静"), goals = listOf("夺回家产"),
                createdAt = now, updatedAt = now,
            ),
        )
        app.storyState.saveEvent(
            com.qianyan.model.timeline.Event(
                id = com.qianyan.model.EventId("t-e1"), novelId = n, name = "茶楼对质",
                type = com.qianyan.model.timeline.EventType.MAIN_PLOT, what = "出示真账本",
                factLevel = com.qianyan.model.knowledge.FactLevel.INFERRED,
                status = com.qianyan.model.timeline.EventStatus.CONFIRMED, createdAt = now,
            ),
        )
    }

    @Test
    fun `get_world_context returns persisted world data through the service chain`() {
        val f = fixture()
        val (activityId, novelId) = activity(f, "世界书")
        seedWorld(f.app, novelId)
        val tables = listOf("Character", "Event", "CommitHistory", "ChapterDraft")
        val countsBefore = tables.associateWith { countRows(f.handle, it) }

        val result = f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName(ProductToolNames.GET_WORLD_CONTEXT), buildJsonObject { put("novelId", novelId) }),
        )

        assertTrue(result.success, "读取应成功: ${result.error}")
        val out = result.output
        val characters = out["characters"]!!.jsonArray
        assertEquals(1, characters.size)
        assertEquals("沈青梧", characters[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            "夺回家产",
            characters[0].jsonObject["goals"]!!.jsonArray.single().jsonPrimitive.content,
            "v27 goals 字段经工具可读",
        )
        val events = out["events"]!!.jsonArray
        assertEquals(1, events.size)
        assertEquals("出示真账本", events[0].jsonObject["what"]!!.jsonPrimitive.content, "v27 what 字段经工具可读")

        // 读取零写入（ToolCallLog 是既有审计写入，属预期，不在断言表内）
        val countsAfter = tables.associateWith { countRows(f.handle, it) }
        assertEquals(countsBefore, countsAfter, "读工具不得产生任何 World Model / Canonical 写入")
        f.close()
    }

    @Test
    fun `empty world yields a successful empty result`() {
        val f = fixture()
        val (activityId, novelId) = activity(f, "空世界")

        val result = f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName(ProductToolNames.GET_WORLD_CONTEXT), buildJsonObject { put("novelId", novelId) }),
        )

        assertTrue(result.success)
        // 工具编码器 encodeDefaults=false：空列表（领域默认值）不出现在 JSON ⇒ 键缺省即空结果
        val out = result.output
        val characters = out["characters"]?.jsonArray
        val events = out["events"]?.jsonArray
        assertTrue(characters == null || characters.isEmpty(), "无数据 ⇒ characters 空或缺省键")
        assertTrue(events == null || events.isEmpty(), "无数据 ⇒ events 空或缺省键")
        f.close()
    }

    @Test
    fun `cross project novel is refused as not found without leaking existence`() {
        val f = fixture()
        val (activityId, _) = activity(f, "本家书")
        val (otherActivity, otherNovelId) = activity(f, "别家书")

        val result = f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName(ProductToolNames.GET_WORLD_CONTEXT), buildJsonObject { put("novelId", otherNovelId) }),
        )

        assertFalse(result.success, "跨 Project 读取必须拒绝")
        assertTrue(result.error!!.startsWith("NOT_FOUND"), "越界按不存在拒绝: ${result.error}")
        f.close()
    }

    @Test
    fun `unknown novel and unknown variant are refused with typed not found`() {
        val f = fixture()
        val (activityId, novelId) = activity(f, "作用域书")

        val missing = f.app.productTools.invoke(
            activityId,
            ToolRequest(ToolName(ProductToolNames.GET_WORLD_CONTEXT), buildJsonObject { put("novelId", "n-ghost") }),
        )
        assertFalse(missing.success)
        assertTrue(missing.error!!.startsWith("NOT_FOUND"), "不存在的 Novel 按不存在拒绝: ${missing.error}")

        val badVariant = f.app.productTools.invoke(
            activityId,
            ToolRequest(
                ToolName(ProductToolNames.GET_WORLD_CONTEXT),
                buildJsonObject { put("novelId", novelId); put("variantId", "v-ghost") },
            ),
        )
        assertFalse(badVariant.success)
        assertTrue(badVariant.error!!.startsWith("NOT_FOUND"), "不存在的 Variant 按不存在拒绝: ${badVariant.error}")
        f.close()
    }
}
