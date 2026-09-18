package com.qianyan.application.usecase.writing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.model.BaseNovelId
import com.qianyan.model.GenreId
import com.qianyan.model.IntentType
import com.qianyan.model.NovelId
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.task.TaskType
import com.qianyan.model.author.AuthorEvidenceType
import com.qianyan.model.author.PreferenceDimension
import com.qianyan.model.author.PreferenceScope
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.application.usecase.foundation.FoundationProposal
import com.qianyan.model.foundation.NarrativeProfile
import com.qianyan.model.foundation.StoryDirection
import com.qianyan.model.foundation.WritingPolicy
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.LLMGateway
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.storage.db.QianyanDbFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REAL WRITING FUNCTIONAL E2E TEST（Qianyan 真实功能写作测试）
 *
 * 全链路用**真实生产代码**驱动：真实 ApplicationContainer、真实 IdeaFirstGateway、
 * 真实 FoundationDecisionGateway（Human Gate）、真实 TaskRunner → PlanningContextAssembly →
 * 真实 PlannerAgent / WriterAgent → 真实 DraftRepository → 真实 AuthorIntelligenceGateway。
 *
 * 说明：本机/环境无任何 Provider API Key（DeepSeek/MiMo 会抛 CredentialMissing），因此通过
 * provider:api 的 `LLMGateway` 接口注入一个“脚本化模型适配器”按 Agent 名返回确定性内容。
 * 这是项目既有 E2E 的一致做法（见 P11_7WritingSliceE2ETest）。生成的"正文"由适配器按提示编撰，
 * **不是真实模型生成**；真实模型接入为准入 Block。- 这是验证管线的唯一可行方式。
 */
class RealWritingFunctionalE2ETest {

    /** 记录各 agent 收到的 prompt 文本，用于断言 AuthorContext / Foundation 确实进入上下文。 */
    private val captured = mutableMapOf<String, String>()

    private val rawIdea = """
        我想写一部现代都市悬疑小说。
        主角是一名年轻记者，因为调查一桩看似普通的失踪案，逐渐发现案件背后牵涉到一家大型科技公司隐藏多年的秘密。
        故事重点放在调查、推理、人物关系和真相逐步揭露上。
        不要一开始就揭露幕后真相，要让读者随着主角调查逐步获得信息。
        整体节奏偏紧凑，但不要为了反转而强行反转。
    """.trimIndent()

    /** 脚本化模型适配器：按 Agent 名返回可被真实解析器接受的确定性内容。 */
    private fun gateway(): LLMGateway = object : LLMGateway {
        override fun chat(request: ProviderRequest): ProviderResponse {
            val messages = request.messages
            captured[messages.first { it.role == ChatRole.SYSTEM }.content] = messages.asString()
            val system = messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> planJson()
                "StoryWriterAgent" in system -> writeJson()
                else -> ideaJson() // IdeaUnderstandingAgent
            }
            return ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
    }

    private fun ideaJson() = """
        {"aiSummary":"现代都市悬疑：记者因调查一桩失踪案，逐步揭开大型科技公司藏匿多年的秘密，真相与人物关系随调查层层展开。",
         "proposal":{"genre":["现代","悬疑","都市"],
          "direction":{"theme":"在真相与人性的缝隙里寻找微光","conflict":"记者个人调查 vs 科技公司系统性隐瞒","promise":"随调查逐层逼近真相","storyType":"现代都市悬疑"},
          "audience":{"pov":"第三人称","readerTone":"悬疑、克制、节奏紧凑"},
          "policy":{"rules":["不提前揭露幕后真相","调查线索要请求细节可信","不为了反转而强行反转"]}}}
    """.trimIndent()

    private fun planJson() = """
        {"chapterGoal":"主角林越接手一桩被定性为普通失踪案的调查，在第一现场发现一个看似无关的小细节，并首次接触失踪者家属。",
         "characterGoals":{"c-linyue":"以记者身份介入失踪案","c-chenfa":"法医沈妗鉴定无名遗物"},
         "expectedEvents":["林越查看失踪者最后影像","发现桌上落下一枚不属于失踪者的铭牌","与法医沈妗初次合作，确定遗物来历"],
         "emotionalDirection":"悬疑、克制、隐隐不安",
         "endingHook":"铭牌背面刻着一个已注销的公司代号",
         "constraints":["前几章不要接近真相","线索逐步暴露","不强行反转"],
         "forbiddenEvents":["过早揭示幕后科技公司全貌"]}
    """.trimIndent()

    /** 真实小说正文（由脚本化适配器按创作意图与修改要求编撰）。 */
    private fun writeJson() = """
        {"content":"城市在深夜下了一场没来由的雨。林越走出报社大门时，雨已经停了，只留下满地湿漉漉的碎光。他捏着那封一周前匿名寄来的信，信纸上只有一行字：她把很重要的东西，留在了梧桐街13号。\\n\\n梧桐街13号是一栋快要拆迁的老楼，门牌的漆掉了大半。林越在二楼最里面的房间，见到了失踪者周然留下的房间：整洁得近乎刻意，床头柜上放着一杯没喝完的温水，电脑屏幕停在凌晨两点零七分。一切看起来都像一个临时出门、很快就会回来的人。\\n\\n他顺手拿起桌上那枚落着薄灰的金属铭牌，翻过来时，背面一行细小的冲压编号让他愣了片刻。编号的格式，不属于这个街区任何一支消防梯标号，也不属于市政管线。它干净、陌生，却让他想起某个早已消失的机构名称。林越没有急着打电话，只是把铭牌放回原处，像什么都没发生过一样，记下了那个编号。\\n\\n那天下午，他见到来认领遗物的女法医沈妗。沈妗戴着无框眼镜，说话很慢，她们在咖啡桌旁核对周然的衣物时，几乎只谈工作；只是在临走前，沈妗忽然抬头，压低了声音：“那个编号，如果你查到了什么，不要一个人去。”\\n\\n林越点点头，把这句话和那枚铭牌一起，收进了记忆最暗的角落。\\n\\n（本章完）"}
    """.trimIndent()

    private fun request(novelId: NovelId) = UserWritingRequest(
        requestId = RequestId("req-func-e2e"),
        intentType = IntentType.CONTINUE,
        target = TargetRef(TargetKind.CHAPTER, null),
        planningScope = PlanningScope.CHAPTER,
        baseNovelId = BaseNovelId(novelId.value),
    )

    @Test
    fun `real writing functional flow idea foundation modify planning writing author`() {
        val handle = QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY)
        val app = ApplicationContainer.fromDriver(handle.driver, analysisGateway = gateway())

        // ============ 1. Idea / Story Intent ============
        val novelId = app.novels.createOriginal(title = "雾中来信")
        val ideaResult = app.ideaFirstGateway.startFromIdea(novelId, rawIdea)
        // 用户原文原样保留
        assertEquals(rawIdea, ideaResult.storyIntent.rawIdea)
        assertTrue(ideaResult.storyIntent.aiSummary!!.isNotBlank(), "AI 理解应生成摘要")
        assertEquals(listOf("现代", "悬疑", "都市"), ideaResult.proposal.proposal.genre.map { it.value })
        // 不跳过用户决策：应存在 PENDING Gate
        assertEquals("PENDING", ideaResult.proposal.gate.status.name)
        // 提案≠事实：确认前不得污染正式 Foundation
        assertEquals(null, app.storyFoundationRepository.getStoryFoundation(novelId))

        // ============ 2+3. 用户选择/修改 → 新 Revision, 保留原 Revision ============
        val modified = FoundationProposal(
            genre = listOf("现代", "悬疑", "都市").map { GenreId(it) },
            direction = StoryDirection(theme = "悬疑感更强，真相逐步揭露", conflict = "记者 vs 科技公司", promise = "随调查逼近真相", storyType = "现代都市悬疑"),
            audience = NarrativeProfile(pov = "第三人称", readerTone = "悬疑、克制"),
            policy = WritingPolicy(listOf("前几章不让主角太快接近真相", "主角与女法医长期合作但不发展为爱情线", "不强行反转")),
        )
        app.foundationDecisionGateway.modifyProposal(novelId, modified)
        val view = app.foundationDecisionGateway.presentProposal(novelId)
        assertTrue(view.revision >= 2, "修改应产生新 Revision")
        // 确认前 Foundation 仍未污染
        assertEquals(null, app.storyFoundationRepository.getStoryFoundation(novelId))

        // 用户确认最新 Proposal → 生成正式 StoryFoundation（Human Gate 不绕过）
        val gate = app.workflowRepository.getGateByKey("FOUNDATION:foundation-${novelId.value}:${view.revision}")!!
        val confirmed = app.foundationDecisionGateway.confirmFoundation(gate.gateId, novelId)
        assertNotNull(app.storyFoundationRepository.getStoryFoundation(novelId), "确认后应写入 StoryFoundation")
        assertTrue(confirmed.gate.decision.name in setOf("APPROVED"), "确认 Gate 决策为 APPROVED")

        // ============ Author Preference：Explicit（受控创作偏好） ============
        app.authorIntelligenceGateway.addExplicitPreference(
            dimension = PreferenceDimension.CONFLICT,
            statement = "作者偏好调查推理：线索逐步揭露，不提前剧透",
            scope = PreferenceScope.GLOBAL,
        )

        // ============ 4. Planning（真实 PlannerAgent，经真实 PlanningContextAssembly） ============
        val planTaskId = app.tasks.create(TaskType.PLANNING)
        val plan = app.taskRunner.executePlanning(planTaskId, request(novelId))
        assertTrue(plan.chapterGoal.isNotBlank(), "Planner 应产出章节目标")
        // 断言：Planner 收到的提示里同时包含"已确认 StoryFoundation"与"AuthorContext 作者偏好"
        val plannerPrompt = captured.entries.first { it.key.contains("StoryPlannerAgent") }.value
        assertTrue(plannerPrompt.contains("已确认的故事基础"), "Planner 应读到确认后的 Story Foundation")
        assertTrue(plannerPrompt.contains("作者偏好"), "AuthorContext 应注入 PlanningContext,不经 Orchestrator/Repository")

        // ============ 5. Writing：真实 WriterAgent → DraftRepository ============
        val writeTaskId = app.tasks.create(TaskType.WRITING)
        val v1 = app.taskRunner.executeWriting(writeTaskId, request(novelId), plan)
        assertEquals(DraftStatus.WRITTEN, v1.status)
        assertTrue(v1.content.length > 120, "应产出足够篇幅的真实小说正文")
        // 贴出真实正文供核验
        println("\n===== 第一章正文（Draft v1） =====\n${v1.content}\n==============================\n")

        // ============ 6. 用户修改正文 → 记录 Author Evidence（不自动变 Stable）= ============
        // 模拟用户修改正文：写一条修订版草稿
        val edited = v1.copy(
            draftId = com.qianyan.model.DraftId("d-edited"),
            content = v1.content + "\n\n后来林越才明白，正是那枚他几乎要放下的铭牌，把整件事拖回了正轨。",
            status = DraftStatus.REVISED,
        )
        app.draftRepository.save(edited)
        // 用户修改 → 记录一条 Author MODIFY Evidence（本次真实进入 Author Intelligence）
        app.authorIntelligenceGateway.recordEvidence(
            type = AuthorEvidenceType.MODIFY,
            detail = "用户修改正文：把线索藏得更深，让主角先发现一个看似无关的小细节",
            source = "user-prose-edit",
            novelId = novelId,
        )
        // 一次修改≠永久偏好：未确认 Candidate 不得进入 AuthorContext
        val before = app.authorIntelligenceGateway.buildAuthorContext(novelId)
        assertTrue(before.preferences.none { it.preferenceId.value.startsWith("cand") || it.statement.contains("小细节") }, "未确认 Candidate 不得进入 AuthorContext")
        val cands = app.authorIntelligenceGateway.listCandidates()
        assertTrue(cands.isNotEmpty(), "应产生 Inferred Candidate")

        // ============ 7. 重复采集幂等（M-1） ============
        // 第一次采集（读 P15 信号：MODIFY 决策 + Gate 决策）
        val firstApplied = app.authorIntelligenceGateway.collectFoundationEvidence(novelId)
        val confAfterFirst = app.authorIntelligenceGateway.listCandidates().firstOrNull()?.confidence?.value
        val firstCount = app.authorPreferenceRepository.listEvidence(novelId).size
        // 第二次采集同一批 P15 信号 → 幂等：0 新增,confidence 不变,Evidence 数不增
        val secondApplied = app.authorIntelligenceGateway.collectFoundationEvidence(novelId)
        assertEquals(0, secondApplied, "重复采集相同 Evidence 应为 0 新增")
        assertEquals(confAfterFirst, app.authorIntelligenceGateway.listCandidates().firstOrNull()?.confidence?.value, "重复采集 confidence 不得变化")
        assertEquals(firstCount, app.authorPreferenceRepository.listEvidence(novelId).size, "重复采集 Evidence 数不得增加")

        // Need Confirmation：确认后才晋升 Stable 并进入 AuthorContext
        val cand = app.authorIntelligenceGateway.listCandidates().first()
        assertEquals(false, cand.isStable, "确认前只是 Candidate")
        val stable = app.authorIntelligenceGateway.confirmPreference(cand.preferenceId)
        assertTrue(stable.isStable, "确认后 Candidate → Stable")
        assertTrue(app.authorIntelligenceGateway.buildAuthorContext(novelId).preferences.isNotEmpty(), "确认后进入 AuthorContext")
    }

    private fun List<ChatMessage>.asString(): String = joinToString("\n") {
        "${it.role}: ${it.content}"
    }
}