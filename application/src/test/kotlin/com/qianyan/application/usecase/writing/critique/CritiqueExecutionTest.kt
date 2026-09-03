package com.qianyan.application.usecase.writing.critique

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.task.TaskType
import com.qianyan.model.spec.ValidationResult
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderException
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.db.QianyanDbHandle
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * P11.4 Critique 测试（Agent 级 + Use Case 集成）。
 *
 * Agent 级：CritiqueAgent 经注入 Mock LLM（`{"answer":"<评审 JSON>"}`）验证：
 *  - 合法评审 → ValidationResult；
 *  - 非法 JSON / 缺字段 / Provider 故障 → 类型化 [ApplicationError]。
 * Use Case 级（经 ApplicationContainer）：CRITIQUE Checkpoint 可 restore，reopen 后一致。
 * 全程 Mock LLM，无网络，不触碰 Repository / Storage 之外的实现。
 */
class CritiqueExecutionTest {

    private fun validCritiqueJson() =
        """{"passed":false,"issues":[{"field":"逻辑","severity":"ERROR","message":"前后矛盾"}]}"""

    /** 注入的 fake LLM：固定正文 / 抛 Provider 异常。 */
    private class MockGateway(private val content: String?, private val failure: ProviderException? = null) : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse {
            failure?.let { throw it }
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, content!!),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    /** 把 Critic 评审 JSON 按 Agent 协议 `{"answer":"<JSON>"}` 封装。 */
    private fun answer(critiqueJson: String): String =
        buildJsonObject { put("answer", critiqueJson) }.toString()

    private fun critic(gateway: LLMGateway): CritiqueAgent = CritiqueAgent(gateway, ErrorMapper, ModelProfile.MOCK)

    private fun draft(): Draft = Draft(
        draftId = DraftId("d-critique-1"),
        novelId = NovelId("novel-c"),
        content = "主角推开古碑，天地异变。",
        status = DraftStatus.WRITTEN,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    /* --- Agent 级 --- */

    /* 合法评审输出 → ValidationResult */
    @Test
    fun `valid critique output maps to validation result`() {
        val result = critic(MockGateway(answer(validCritiqueJson()))).critique(draft())
        assertIs<ValidationResult>(result)
        assertEquals(false, result.passed)
        assertEquals(1, result.issues.size)
        assertEquals("前后矛盾", result.issues[0].message)
    }

    /* 非法 JSON → InvalidCritiqueOutput（不伪装成功） */
    @Test
    fun `illegal critique output fails typed`() {
        val ex = assertFailsWith<ApplicationException> {
            critic(MockGateway("这不是评审 JSON")).critique(draft())
        }
        assertIs<ApplicationError.InvalidCritiqueOutput>(ex.error)
    }

    /* 缺 passed 字段 → InvalidCritiqueOutput */
    @Test
    fun `missing passed field fails typed`() {
        val ex = assertFailsWith<ApplicationException> {
            critic(MockGateway(answer("{\"issues\":[]}"))).critique(draft())
        }
        assertIs<ApplicationError.InvalidCritiqueOutput>(ex.error)
    }

    /* Provider 故障（超时）→ ProviderUnavailable（不泄漏底层） */
    @Test
    fun `provider failure fails typed`() {
        val ex = assertFailsWith<ApplicationException> {
            critic(MockGateway(null, failure = ProviderException.Timeout("timeout"))).critique(draft())
        }
        assertIs<ApplicationError.ProviderUnavailable>(ex.error)
    }

    /* --- Use Case 级（经 ApplicationContainer）--- */

    private fun container(): ApplicationContainer {
        val gateway = MockGateway(answer(validCritiqueJson()))
        return ApplicationContainer.open(analysisGateway = gateway)
    }

    private fun openFile(url: String, content: String, handles: MutableList<QianyanDbHandle>): ApplicationContainer {
        val handle = QianyanDbFactory.open(url)
        handles += handle
        return ApplicationContainer.fromDriver(handle.driver, analysisGateway = MockGateway(content))
    }

    /* 评审通过：create WRITING Task → CRITIQUE Checkpoint，reopen 后仍可恢复 */
    @Test
    fun `critique persists CRITIQUE checkpoint across reopen`() {
        val tmp = Files.createTempFile("qianyan-critique", ".db").toString()
        val handles = mutableListOf<QianyanDbHandle>()
        try {
            var app = openFile("jdbc:sqlite:$tmp", answer(validCritiqueJson()), handles)
            val id = app.tasks.create(TaskType.WRITING)
            app.taskRunner.executeCritique(id, draft())

            val cp = app.tasks.restoreCheckpoint(id)
            assertEquals(CritiqueSnapshot.STAGE, cp.stage)
            val restored = app.critique.critiqueFrom(cp)
            assertNotNull(restored)
            assertEquals(false, restored.passed)

            // 关闭后重开：CRITIQUE checkpoint 仍可恢复，结果一致
            app = openFile("jdbc:sqlite:$tmp", answer(validCritiqueJson()), handles)
            val reopenedCp = app.tasks.restoreCheckpoint(id)
            assertEquals("CRITIQUE", reopenedCp.stage)
            val reopened = app.critique.critiqueFrom(reopenedCp)
            assertNotNull(reopened)
            assertEquals(false, reopened.passed)
            assertEquals(1, reopened.issues.size)
        } finally {
            // 显式关闭底层 JDBC Connection 释放 Windows 文件句柄后再删除临时文件。
            handles.forEach { (it.driver as JdbcSqliteDriver?)?.getConnection()?.close() }
            Files.deleteIfExists(Path(tmp))
        }
    }

    /* 非 WRITING Task 经 executeCritique → InvalidOperation */
    @Test
    fun `non writing task rejected by critique`() {
        val app = container()
        val id = app.tasks.create(TaskType.PLANNING)
        val ex = assertFailsWith<ApplicationException> {
            app.taskRunner.executeCritique(id, draft())
        }
        assertIs<ApplicationError.InvalidOperation>(ex.error)
    }
}