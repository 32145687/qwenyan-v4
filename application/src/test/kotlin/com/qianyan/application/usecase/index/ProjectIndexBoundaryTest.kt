package com.qianyan.application.usecase.index

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I12 · Boundary（§3 / §7 / §10 / §11 / §17 / §18 / §16 J）。
 *
 * 结构守卫证明：Project Index **只做派生定位** ——
 * 不直连 SQLite / 不建第二套存储 / 不调 LLM / 不执行 Tool / 不组装 Context / 不复制 Canonical Entity /
 * 不变成 Project State 或 World Model / 不建第二套搜索基础设施 / 不提前实现 I13+ / 不改 I11。
 */
class ProjectIndexBoundaryTest {

    private val indexDir = "src/main/kotlin/com/qianyan/application/usecase/index"
    private val modelDir = "../core/model/src/main/kotlin/com/qianyan/model/projectindex"

    @Test
    fun `project index never touches storage implementations canonical writes or other layers`() {
        val code = sourceOf(indexDir)
        listOf(
            // 不直连存储实现 / 不建第二套存储事实源（SQLite index 也不是 Project Index）
            "SqlDriver", "QianyanDb", "DatabaseInitializer", "Sqlite", "CREATE INDEX", "Room",
            // 不写 Canonical 表 / 不复制 Canonical Entity
            "ChapterDraft", "ProjectState", "CommitHistory",
            // 不调 LLM / Provider / Agent runtime / Agent 编排
            "LLMGateway", "ModelProfile", "AgentRuntime", "NovelAgent", "ToolExecutor", "ToolRegistry",
            "ProductToolService", "WorkflowOrchestrator",
            // 不做 Context 选择（Index ≠ Context Engine）
            "ContextEngineUseCases", "ContextPack", "ContextSelector", "ContextSource",
            // 不建第二套 Search / World Model / 状态机
            "Lucene", "Elasticsearch", "Embedding", "Vector", "FTS", "Rag", "WorldModel", "StateMachine",
            // 不提前实现 I13+
            "Queue", "BackgroundTask", "Inspector",
        ).forEach { token -> assertTrue(token !in code, "Project Index 不得出现 '$token'（只做派生定位）") }

        // 复用证据（正面断言）
        listOf(
            "ProjectUseCases", "NovelUseCases", "ChapterUseCases", "WriterUseCases", "VocabularyUseCases",
            "rebuild", "isStale", "discard", "ProjectIndexEntries", "ProjectIndexVersioning",
        ).forEach { token -> assertTrue(token in code, "Project Index 必须复用既有能力 / 契约：'$token'") }
    }

    @Test
    fun `project index depends on exactly one existing repository and never on sqlite`() {
        val code = sourceOf(indexDir)
        val storageImports = code.lines()
            .map { it.trim() }
            .filter { it.startsWith("import com.qianyan.storage") }
            .map { it.removePrefix("import ").trim() }

        // 与 I6 StoryFoundationSource 同一范式：唯一复用的既有 Repository 是本书级基础读取
        assertEquals(
            listOf("com.qianyan.storage.repository.StoryFoundationRepository"),
            storageImports.sorted(),
            "索引只允许复用这一个既有 Repository（不新建、不直连 SQLDelight/SQLite）",
        )
        listOf(
            "DraftRepository", "ChapterRepository", "NovelRepository", "WorkflowRepository",
            "CommitHistoryRepository", "ActivityRepository", "AgentSessionRepository",
            "ProjectStateRepository", "MemoryRepository", "VocabularyRepository", "TaskRepository",
        ).forEach { token -> assertTrue(token !in code, "索引不得直连 '$token'（必须经 Application 能力）") }
    }

    @Test
    fun `project index models stay pure domain and do not redefine existing models`() {
        val code = sourceOf(modelDir)
        listOf(
            "SqlDriver", "QianyanDb", "storage", "LLMGateway", "Repository", "ContextPack", "ToolName",
            "Workflow", "AgentAction", "StateMachine",
        ).forEach { token -> assertTrue(token !in code, "I12 领域契约不得依赖 / 复制 '$token'") }

        listOf(
            "ProjectIndex", "ProjectIndexEntry", "ProjectIndexEntryType", "ProjectIndexReference",
            "ProjectIndexVersion", "ProjectIndexErrorCodes",
        ).forEach { token -> assertTrue(token in code, "缺少 I12 契约：'$token'") }
    }

    @Test
    fun `canonical sources and earlier phases stay untouched by index wiring`() {
        // 复用既有 Canonical 读取能力（不得重写）
        listOf(
            "src/main/kotlin/com/qianyan/application/usecase/project/ProjectUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/novel/NovelUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/chapter/ChapterUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/writing/WriterUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/vocabulary/VocabularyUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/context/ContextEngineUseCases.kt",
            "src/main/kotlin/com/qianyan/application/usecase/commit/CommitUseCases.kt",
        ).forEach { path -> assertTrue(File(path).isFile, "既有能力不得被删除 / 重写：$path") }

        // I11 NovelAgent 保持独立：索引不得被塞进编排层（I11 结构守卫亦禁止 ProjectIndex 出现）
        val agentSource = sourceOf("src/main/kotlin/com/qianyan/application/usecase/agent")
        assertTrue("ProjectIndex" !in agentSource, "I12 不得改动 I11 NovelAgent 编排逻辑")
    }

    @Test
    fun `index is rebuilt from canonical data and never becomes its source`() {
        val f = indexFixture()
        val w = seedIndexWorld(f)

        // 丢弃索引 ⇒ Canonical 完整无损；重建 ⇒ 得到相同索引（单向派生）
        val first = f.app.projectIndex.rebuild(w.projectId)
        f.app.projectIndex.discard(w.projectId)
        assertEquals(1, f.app.draftRepository.listByChapter(w.chapter1).size, "索引丢失不影响 Canonical 正文")
        val second = f.app.projectIndex.rebuild(w.projectId)
        assertEquals(first.entries, second.entries)
        assertEquals(first.version, second.version)
        // 索引中的条目只是"引用"，可以被 Product Tool（I5）按引用读回真实正文
        val draftEntry = second.ofType(com.qianyan.model.projectindex.ProjectIndexEntryType.DRAFT)
            .first { it.reference.draftId == w.draft1 }
        assertEquals("第一章正文（短）。", f.app.writerUseCases.draft(draftEntry.reference.draftId!!)?.content)
        f.close()
    }

    private fun sourceOf(relativeDir: String): String {
        val dir = File(relativeDir)
        assertTrue(dir.isDirectory, "找不到源码目录：${dir.absolutePath}")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
    }
}