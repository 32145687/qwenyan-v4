package com.qianyan.application.usecase.writing.planning

import com.qianyan.application.error.ErrorMapper
import com.qianyan.application.usecase.context.ContextFixture
import com.qianyan.application.usecase.context.SeededProject
import com.qianyan.application.usecase.context.contextFixture
import com.qianyan.application.usecase.context.seedProject
import com.qianyan.application.usecase.writing.WriterAgent
import com.qianyan.model.BaseNovelId
import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.IntentType
import com.qianyan.model.PlanningScope
import com.qianyan.model.RequestId
import com.qianyan.model.VariantScope
import com.qianyan.model.context.ContextPack
import com.qianyan.model.context.ContextRequest
import com.qianyan.model.context.TargetKind
import com.qianyan.model.context.TargetRef
import com.qianyan.model.context.TargetRefId
import com.qianyan.model.context.UserWritingRequest
import com.qianyan.model.story.ContinuationReference
import com.qianyan.model.writing.Draft
import com.qianyan.model.writing.DraftFormat
import com.qianyan.model.writing.DraftStatus
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ModelProfile
import com.qianyan.provider.ProviderRequest
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrentDraftPromptTest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private class Scene(
        val f: ContextFixture,
        val w: SeededProject,
        val pack: ContextPack,
    ) {
        fun request(): UserWritingRequest = UserWritingRequest(
            requestId = RequestId("req-current-draft"),
            intentType = IntentType.CONTINUE,
            target = TargetRef(TargetKind.CHAPTER, TargetRefId(w.chapter2.value)),
            planningScope = PlanningScope.CHAPTER,
            baseNovelId = BaseNovelId(w.novelId.value),
            variantId = null,
            scope = VariantScope.ORIGINAL,
            rawText = "继续写这一章 3000 字，保持人物状态和前文一致。",
        )

        fun close() = f.close()
    }

    private fun scene(): Scene {
        val f = contextFixture()
        val w = seedProject(f, "书A", "A")
        val pack = f.app.contextEngine.build(
            ContextRequest(
                projectId = w.projectId,
                purpose = IntentType.CONTINUE,
                focusChapterId = w.chapter2,
            ),
        )
        assertTrue(pack.items.any { it.source == com.qianyan.model.context.ContextSourceKind.DRAFT })
        return Scene(f, w, pack)
    }

    private fun draftIdOf(pack: ContextPack, chapterId: ChapterId): DraftId =
        assertNotNull(pack.items.single { it.source == com.qianyan.model.context.ContextSourceKind.DRAFT && it.ref?.chapterId == chapterId }.ref?.draftId)

    private fun draftContentOf(pack: ContextPack): String =
        pack.items.single { it.source == com.qianyan.model.context.ContextSourceKind.DRAFT }.content

    private fun capturePrompts(context: PlanningContext): List<ProviderRequest> {
        val records = mutableListOf<ProviderRequest>()
        val gateway = MockLLMGateway { request ->
            records += request
            val system = request.messages.first { it.role == ChatRole.SYSTEM }.content
            val body = when {
                "StoryPlannerAgent" in system -> """{"chapterGoal":"目标","mainConflict":"冲突"}"""
                "StoryWriterAgent" in system -> buildJsonObject { put("content", "新正文") }.toString()
                else -> buildJsonObject { put("content", "占位") }.toString()
            }
            ProviderResponse(
                message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
                usage = Usage(10, 10, 20),
                finishReason = FinishReason.STOP,
            )
        }
        val planner = PlannerAgent(gateway, ErrorMapper, ModelProfile.MOCK)
        val writer = WriterAgent(gateway, ErrorMapper, ModelProfile.MOCK)
        val plan = planner.plan(context, emptyList()).copy(
            chapterId = context.request.target.id?.let { ChapterId(it.value) },
            novelId = context.novelId,
            variantId = context.variantId,
            scope = context.scope,
        )
        writer.write(context, plan)
        return records
    }

    private fun promptText(request: ProviderRequest): String =
        request.messages.joinToString("\n") { it.content }

    private fun currentDraftSection(draftId: DraftId, content: String): String =
        "【本章当前稿】\ndraftId: ${draftId.value}\n$content"

    @Test
    fun `current draft from pack is rendered in planner and writer prompts`() {
        val s = scene()
        try {
            val context = s.f.app.planningContextAssembly.assemble(s.request(), s.pack)
            val draftId = draftIdOf(s.pack, s.w.chapter2)
            val content = draftContentOf(s.pack)
            val section = currentDraftSection(draftId, content)
            val prompts = capturePrompts(context)

            assertTrue(promptText(prompts.first { "StoryPlannerAgent" in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content }).contains(section), "Planner prompt 必须含 pack 当前稿")
            assertTrue(promptText(prompts.first { "StoryWriterAgent" in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content }).contains(section), "Writer prompt 必须含 pack 当前稿")
        } finally {
            s.close()
        }
    }

    @Test
    fun `missing draft item renders no current draft section`() {
        val s = scene()
        try {
            val packWithoutDraft = s.pack.copy(
                items = s.pack.items.filterNot { it.source == com.qianyan.model.context.ContextSourceKind.DRAFT },
            )
            val context = s.f.app.planningContextAssembly.assemble(s.request(), packWithoutDraft)
            val prompts = capturePrompts(context)
            assertTrue(prompts.all { "【本章当前稿】" !in promptText(it) }, "无 DRAFT item 时两处都不得渲染该节")
            assertEquals(2, prompts.size)
        } finally {
            s.close()
        }
    }

    @Test
    fun `assembly uses the pack draft content instead of the repository latest draft`() {
        val s = scene()
        try {
            val oldPackContent = draftContentOf(s.pack)
            val newer = Draft(
                draftId = DraftId("draft-newer-than-pack"),
                novelId = s.w.novelId,
                chapterId = s.w.chapter2,
                content = "仓库最新稿（不得进入 prompt）",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.WRITTEN,
                createdAt = Instant.parse("2026-01-02T00:00:00Z"),
                updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
            )
            s.f.app.draftRepository.save(newer)
            assertEquals(newer.content, s.f.app.writerUseCases.latestDraft(s.w.chapter2)?.content)

            val context = s.f.app.planningContextAssembly.assemble(s.request(), s.pack)
            val prompts = capturePrompts(context)
            prompts.forEach { request ->
                val text = promptText(request)
                assertTrue(oldPackContent in text, "必须使用 pack 中的旧稿内容")
                assertFalse(newer.content in text, "不得回退读取 latestByChapter")
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun `continuation source and current draft remain distinct`() {
        val s = scene()
        try {
            val sourceChapter = assertNotNull(s.f.app.chapters.findById(s.w.chapter1))
            val sourceDraft = Draft(
                draftId = DraftId("draft-source-ch1"),
                novelId = s.w.novelId,
                chapterId = s.w.chapter1,
                content = "第一章前文（续篇来源）",
                format = DraftFormat.CONTROLLED_MARKDOWN,
                status = DraftStatus.CONFIRMED,
                createdAt = fixed,
                updatedAt = fixed,
            )
            s.f.app.draftRepository.save(sourceDraft)
            val reference = ContinuationReference(
                sourceChapterId = s.w.chapter1,
                sourceDraftId = sourceDraft.draftId,
            )
            val resolved = ResolvedContinuation(
                reference = reference,
                sourceChapter = sourceChapter,
                sourceFinalDraft = sourceDraft,
            )
            val context = s.f.app.planningContextAssembly.assemble(
                s.request(),
                s.pack,
                continuationReference = reference,
                resolved = resolved,
            )
            val currentId = draftIdOf(s.pack, s.w.chapter2)
            val currentContent = draftContentOf(s.pack)
            val prompts = capturePrompts(context)
            val writer = promptText(prompts.first { "StoryWriterAgent" in it.messages.first { m -> m.role == ChatRole.SYSTEM }.content })
            assertTrue(currentDraftSection(currentId, currentContent) in writer, "本章当前稿节必须存在")
            assertTrue("【前章 Canonical 摘录】" in writer, "续篇来源节必须存在")
            assertTrue(sourceDraft.content in writer, "前章来源正文必须保留")
            assertTrue(currentContent in writer, "本章当前稿正文必须保留")
            assertEquals(sourceDraft.draftId, context.sourceFinalDraft?.draftId)
        } finally {
            s.close()
        }
    }
}
