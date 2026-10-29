package com.qianyan.application.usecase.commit

import com.qianyan.application.di.ApplicationContainer
import com.qianyan.application.error.ApplicationException
import com.qianyan.model.commit.CommitApproval
import com.qianyan.model.commit.CommitErrorCodes
import com.qianyan.model.commit.CommitResult
import com.qianyan.model.workingdraft.WorkingDraftTarget
import com.qianyan.model.workflow.HumanDecision
import com.qianyan.model.workflow.HumanGateStatus
import com.qianyan.model.workflow.Workflow
import com.qianyan.model.workflow.WorkflowHumanGate
import com.qianyan.model.workflow.WorkflowHumanGateId
import com.qianyan.model.workflow.WorkflowId
import com.qianyan.model.workflow.WorkflowKind
import com.qianyan.model.workflow.WorkflowStatus
import com.qianyan.model.workflow.WorkflowStep
import com.qianyan.model.workflow.WorkflowStepId
import com.qianyan.model.workflow.WorkflowStepPhase
import com.qianyan.model.workflow.WorkflowStepStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.qianyan.provider.ChatMessage
import com.qianyan.provider.ChatRole
import com.qianyan.provider.FinishReason
import com.qianyan.provider.ProviderResponse
import com.qianyan.provider.Usage
import com.qianyan.provider.impl.MockLLMGateway
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0-2.1 · Commit 的最小 Gate 绑定校验（只对生产 Workflow/Step 绑定的 Gate 生效）。
 *
 * 防止已批准的 Gate 被用于提交另一个 Artifact / 另一个章节；重复提交仍由既有
 * `ALREADY_COMMITTED` 保护。
 */
class CommitGateBindingTest {

    @Test
    fun `approved gate cannot commit another working artifact`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "绑定测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        val gate = approveGateWithoutCommit(app, chapter.chapterId)

        // 另一个工作产物（同一章节），以及它的 Change Artifact
        val other = app.workingDrafts.create(
            projectId = projectId,
            target = WorkingDraftTarget(novelId, chapter.chapterId),
            content = "另一个候选正文",
        )
        app.workingDrafts.validate(other.workingDraftId)
        val artifact = app.changes.prepare(other.workingDraftId)

        val error = assertFailsWith<ApplicationException> {
            app.commits.commit(projectId, artifact.artifactId, CommitApproval(gate))
        }

        assertTrue(
            error.message.orEmpty().contains(CommitErrorCodes.GATE_TARGET_MISMATCH),
            "Gate 只能提交其绑定的工作产物：${error.message}",
        )
        assertTrue(app.commits.history(projectId).isEmpty(), "拒绝时零写入")
        assertNull(app.canonicalRead.committedContent(chapter.chapterId))
    }

    @Test
    fun `gate from another chapter cannot commit this chapter`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "绑定测试书")
        val chapter1 = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val chapter2 = app.chapters.createNextChapter(title = "第二章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter1.chapterId)
        val chapter1Gate = approveGateWithoutCommit(app, chapter1.chapterId)

        val other = app.workingDrafts.create(
            projectId = projectId,
            target = WorkingDraftTarget(novelId, chapter2.chapterId),
            content = "第二章候选正文",
        )
        app.workingDrafts.validate(other.workingDraftId)
        val artifact = app.changes.prepare(other.workingDraftId)

        val error = assertFailsWith<ApplicationException> {
            app.commits.commit(projectId, artifact.artifactId, CommitApproval(chapter1Gate))
        }

        assertTrue(error.message.orEmpty().contains(CommitErrorCodes.GATE_TARGET_MISMATCH), error.message.orEmpty())
        assertTrue(app.commits.history(projectId).isEmpty())
        assertNull(app.canonicalRead.committedContent(chapter2.chapterId))
    }

    @Test
    fun `duplicate commit of the same artifact stays rejected`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "绑定测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)

        app.workflowFacade.approve(chapter.chapterId)

        val entry = app.commits.history(projectId).single()
        val artifactId = assertNotNull(entry.artifactId)
        val gateId = assertNotNull(entry.gateId)
        val error = assertFailsWith<ApplicationException> {
            app.commits.commit(projectId, artifactId, CommitApproval(gateId))
        }

        assertTrue(error.message.orEmpty().contains(CommitErrorCodes.ALREADY_COMMITTED), error.message.orEmpty())
        assertEquals(1, app.commits.history(projectId).size, "不得产生重复 Canonical / History")
        assertEquals(entry.resultingContent, app.canonicalRead.committedContent(chapter.chapterId))
    }

    /* ---------------- P0-3B D8：无 stepId Gate ---------------- */

    @Test
    fun `gate without a step cannot be used for production commit`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val stepless = steplessApprovedGate(f, w, gateId = "gate-no-step-d8")
            val artifact = artifactFor(f, w, "无 step 候选正文")

            val error = assertFailsWith<ApplicationException> {
                f.app.commits.commit(w.projectId, artifact.artifactId, stepless)
            }
            assertTrue(error.message.orEmpty().contains(CommitErrorCodes.GATE_TARGET_MISMATCH), error.message.orEmpty())
            assertTrue(f.app.commits.history(w.projectId).isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun `bound gate cannot commit a second artifact`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val firstArtifact = artifactFor(f, w, "第一份候选")
            val approval = approvedGate(f, w, gateId = "gate-bound-reuse")
            val first = f.app.commits.commit(w.projectId, firstArtifact.artifactId, approval)
            val secondArtifact = artifactFor(f, w, "第二份候选", baseDraftId = first.resultingDraftId)

            val error = assertFailsWith<ApplicationException> {
                f.app.commits.commit(w.projectId, secondArtifact.artifactId, approval)
            }
            assertTrue(error.message.orEmpty().contains(CommitErrorCodes.GATE_TARGET_MISMATCH), error.message.orEmpty())
            assertEquals(1, f.app.commits.history(w.projectId).size, "同一 Gate 不得提交第二个 Artifact")
        } finally {
            f.close()
        }
    }

    @Test
    fun `gate bound to a non confirmation step is rejected`() {
        val f = commitFixture()
        try {
            val w = commitWorld(f)
            val now = Clock.System.now()
            val wfId = WorkflowId("wf-non-confirmation")
            f.app.workflows.createWorkflow(
                Workflow(
                    workflowId = wfId,
                    novelId = w.novelId,
                    kind = WorkflowKind.WRITE_NOVEL,
                    status = WorkflowStatus.RUNNING,
                    activeChapterId = w.chapter2,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val step = WorkflowStep(
                stepId = WorkflowStepId("step-writing-not-confirmation"),
                workflowId = wfId,
                chapterId = w.chapter2,
                phase = WorkflowStepPhase.WRITING,
                logicalStepKey = "$wfId:${w.chapter2.value}:WRITING",
                status = WorkflowStepStatus.COMPLETED,
                createdAt = now,
                completedAt = now,
            )
            f.app.workflows.createStep(step)
            val gateId = WorkflowHumanGateId("gate-non-confirmation")
            f.app.workflows.createGate(
                WorkflowHumanGate(
                    gateId = gateId,
                    workflowId = wfId,
                    stepId = step.stepId,
                    gateKey = "gate-non-confirmation-key",
                    status = HumanGateStatus.RESOLVED,
                    decision = HumanDecision.APPROVED,
                    createdAt = now,
                    resolvedAt = now,
                    resolvedBy = "test",
                ),
            )
            val artifact = artifactFor(f, w, "非 CONFIRMATION 候选")

            val error = assertFailsWith<ApplicationException> {
                f.app.commits.commit(w.projectId, artifact.artifactId, CommitApproval(gateId))
            }
            assertTrue(error.message.orEmpty().contains(CommitErrorCodes.GATE_TARGET_MISMATCH), error.message.orEmpty())
            assertTrue(f.app.commits.history(w.projectId).isEmpty())
        } finally {
            f.close()
        }
    }

    @Test
    fun `concurrent commit with the same artifact commits exactly once`() {
        val app = ApplicationContainer.open(analysisGateway = gateway())
        val novelId = app.novels.createOriginal(title = "并发测试书")
        val chapter = app.chapters.createNextChapter(title = "第一章", novelId = novelId)
        val projectId = app.projects.projectOf(novelId).projectId
        app.writerGateway.continueWriting(novelId, null, chapter.chapterId)
        val working = app.workingDrafts.list(projectId).single { it.target.chapterId == chapter.chapterId }
        val artifact = app.changes.prepare(working.workingDraftId)
        val gateId = approveGateWithoutCommit(app, chapter.chapterId)

        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit<CommitResult?> {
                    start.await()
                    runCatching {
                        app.commits.commit(projectId, artifact.artifactId, CommitApproval(gateId))
                    }.getOrNull()
                }
            }
            start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it != null }, "并发提交只能成功一次")
            assertEquals(1, app.commits.history(projectId).size, "只能产生一条 CommitHistory")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `production sources have no unbound gate compatibility surface`() {
        val roots = listOf(
            File("src/main/kotlin"),
            File("../core/model/src/main/kotlin"),
            File("../app/desktop/src/jvmMain/kotlin"),
            File("../app/android/src/main/java"),
        ).filter { it.exists() }
        assertTrue(roots.isNotEmpty(), "必须能定位生产源码目录")

        val forbidden = "allow" + "UnboundGate"
        val namedFlag = Regex(Regex.escape(forbidden))
        val positionalFlag = Regex("""CommitApproval\([^)]*,\s*true\s*\)""")
        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val code = file.readText()
                    buildList {
                        if (namedFlag.containsMatchIn(code)) add("${file.path}: unbound gate flag")
                        if (positionalFlag.containsMatchIn(code)) add("${file.path}: positional unbound gate")
                    }.asSequence()
                }
                .toList()
        }
        assertEquals(emptyList(), offenders, "生产代码不得保留无 Step Gate 兼容面（D8）")

        val model = File("../core/model/src/main/kotlin/com/qianyan/model/commit/CommitModels.kt").readText()
        assertFalse(forbidden in model, "CommitApproval 不得再包含兼容开关")
    }

    /** 把既有生产 Gate 标记为已批准（不执行提交），用于绑定校验的负向测试。 */
    private fun approveGateWithoutCommit(app: ApplicationContainer, chapterId: com.qianyan.model.ChapterId): com.qianyan.model.workflow.WorkflowHumanGateId {
        val wf = assertNotNull(app.workflowRepository.getWorkflowByActiveChapter(chapterId))
        val gate = assertNotNull(app.workflowRepository.getGate(assertNotNull(wf.pendingGateId)))
        app.workflowRepository.updateGate(
            gate.copy(
                status = HumanGateStatus.RESOLVED,
                decision = HumanDecision.APPROVED,
                resolvedAt = Clock.System.now(),
                resolvedBy = "test",
            ),
        )
        return gate.gateId
    }

    private fun gateway(): MockLLMGateway = MockLLMGateway { req ->
        val system = req.messages.first { it.role == ChatRole.SYSTEM }.content
        val body = when {
            "StoryWriterAgent" in system -> """{"content":"AI 生成正文"}"""
            "StoryCriticAgent" in system -> """{"passed":true}"""
            "KnowledgeUpdateAgent" in system -> """{"changes":[]}"""
            else -> """{"chapterGoal":"推进主线"}"""
        }
        ProviderResponse(
            message = ChatMessage(ChatRole.ASSISTANT, buildJsonObject { put("answer", body) }.toString()),
            usage = Usage(1, 1, 2),
            finishReason = FinishReason.STOP,
        )
    }
}
