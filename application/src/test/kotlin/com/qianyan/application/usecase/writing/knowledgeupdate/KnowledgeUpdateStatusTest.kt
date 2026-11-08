package com.qianyan.application.usecase.writing.knowledgeupdate

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.NovelId
import com.qianyan.model.knowledge.CandidateKnowledgeChange
import com.qianyan.model.knowledge.KnowledgeOperation
import com.qianyan.model.task.TaskStatus
import com.qianyan.model.task.TaskType
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * T-O08-S · 按作品读取最近一次 KU 状态（Checkpoint 快照 → Task.status）。
 *
 * 覆盖：旧快照（无 novelId）解码兼容；两作品各自取到自己的记录；COMPLETED / FAILED 状态映射。
 */
class KnowledgeUpdateStatusTest {

    private fun open(): ApplicationContainer =
        ApplicationContainer.open(analysisGateway = MockLLMGateway())

    private fun novelId(app: ApplicationContainer, title: String): NovelId =
        app.novels.createOriginal(title = title)

    private fun change(id: String, novelId: NovelId): CandidateKnowledgeChange =
        CandidateKnowledgeChange(
            changeId = id,
            novelId = novelId,
            operation = KnowledgeOperation.ADD,
            target = "主角",
            content = "事实 $id",
        )

    private fun validated(vararg accepted: CandidateKnowledgeChange): ValidatedKnowledgeUpdate =
        ValidatedKnowledgeUpdate(accepted = accepted.toList(), rejected = emptyList())

    /** 造一条 KU Checkpoint 并把 Task 推到指定终态（成功链 = start → checkpoint → complete）。 */
    private fun seedKu(
        app: ApplicationContainer,
        novelId: NovelId,
        result: ValidatedKnowledgeUpdate,
        failWith: String? = null,
    ) {
        val taskId = app.tasks.create(TaskType.KNOWLEDGE_UPDATE)
        app.tasks.start(taskId)
        app.tasks.saveCheckpoint(
            taskId,
            KnowledgeUpdateSnapshot.STAGE,
            KnowledgeUpdateSnapshot.encode(result, novelId),
        )
        if (failWith == null) app.tasks.complete(taskId) else app.tasks.fail(taskId, failWith)
    }

    /** 旧格式（无 novelId 键）仍可解码出结果；decodePayload 的 novelId 为 null，不崩。 */
    @Test
    fun `legacy snapshot without novelId still decodes`() {
        val novelId = NovelId("novel-legacy")
        val encoded = KnowledgeUpdateSnapshot.encode(validated(change("k1", novelId)), novelId)
        val legacy: JsonObject = JsonObject(encoded.filterKeys { it != "novelId" })
        assertNull(legacy["novelId"], "前置：构造的是旧格式快照")

        val restored = assertNotNull(KnowledgeUpdateSnapshot.decode(legacy), "旧快照必须能解码出校验结果")
        assertEquals(1, restored.accepted.size)
        assertEquals("k1", restored.accepted.single().changeId)

        val payload = assertNotNull(KnowledgeUpdateSnapshot.decodePayload(legacy))
        assertNull(payload.novelId, "旧快照无作品归属，必须为 null 而不是崩溃")
        assertEquals(1, payload.result.accepted.size)
    }

    /** 两个作品各有一条 KU：latestForNovel 必须各自取到自己的那条。 */
    @Test
    fun `latest knowledge update is resolved per novel`() {
        val app = open()
        val a = novelId(app, "作品 A")
        val b = novelId(app, "作品 B")
        seedKu(app, a, validated(change("a1", a)))
        seedKu(app, b, validated(change("b1", b), change("b2", b), change("b3", b)))

        val latestA = assertNotNull(app.knowledgeUpdateStatus.latestForNovel(a))
        val latestB = assertNotNull(app.knowledgeUpdateStatus.latestForNovel(b))
        assertEquals(TaskStatus.COMPLETED, latestA.status)
        assertEquals(TaskStatus.COMPLETED, latestB.status)
        assertEquals(1, latestA.accepted, "作品 A 的采纳数必须来自它自己的快照")
        assertEquals(3, latestB.accepted, "作品 B 的采纳数必须来自它自己的快照")
        assertEquals(0, latestA.rejected)
    }

    /** 状态映射：COMPLETED 无错误；FAILED 带 error 文案。 */
    @Test
    fun `latest knowledge update maps task status`() {
        val app = open()
        val ok = novelId(app, "成功作品")
        val bad = novelId(app, "失败作品")
        seedKu(app, ok, validated(change("ok1", ok)))
        seedKu(app, bad, validated(change("bad1", bad)), failWith = "ProviderUnavailable: 模拟失败")

        val done = assertNotNull(app.knowledgeUpdateStatus.latestForNovel(ok))
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertNull(done.error, "成功任务不应带失败原因")

        val failed = assertNotNull(app.knowledgeUpdateStatus.latestForNovel(bad))
        assertEquals(TaskStatus.FAILED, failed.status)
        assertEquals("ProviderUnavailable: 模拟失败", failed.error)
        assertEquals(1, failed.accepted, "失败记录的采纳数仍取自已写出的快照")

        assertNull(app.knowledgeUpdateStatus.latestForNovel(NovelId("novel-without-ku")), "无记录必须返回 null（不伪造状态）")
    }
}
