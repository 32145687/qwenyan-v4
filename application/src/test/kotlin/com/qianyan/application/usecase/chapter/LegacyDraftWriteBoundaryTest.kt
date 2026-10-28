package com.qianyan.application.usecase.chapter

import com.qianyan.application.usecase.agent.novelAgentFixture
import com.qianyan.application.usecase.task.TaskRunner
import com.qianyan.application.usecase.writing.revision.RevisionExecutionUseCase
import com.qianyan.model.taskqueue.TaskKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v3.1 Phase 1 · Legacy AI 正文直写边界守卫。
 *
 * 覆盖范围：
 *  - 全部在 `settings.gradle.kts` 中声明的生产模块（`:test:e2e` 仅测试，显式豁免）；
 *  - 生产 Kotlin 源码（`src/main/kotlin` / `src/main/java` / `src/jvmMain/kotlin`）；
 *  - 旧编排符号、正文写入点、作者保存/格式标记、`WriterGateway.saveContent` 调用面、反射入口。
 *
 * 检查强度（按可靠性排序）：
 *  1. **类型边界**：`TaskRunner` / `RevisionExecutionUseCase` 构造参数（反射断言）；
 *     `WriterUseCases` 写入方法已为 `internal`，外部模块编译期不可见。
 *  2. **方法级白名单**：写入调用必须位于契约允许的**具体方法**内，而不是仅允许整个文件。
 *  3. **文本扫描**：去注释/去字符串/去 raw string 后的代码视图；覆盖空白变体、
 *     方法引用、简单局部别名、`WriterGateway.saveContent` 调用方、反射 token。
 *
 * 明确限制：
 *  - 这是文本级启发式守卫，**不能**证明反射、动态类名、参数/字段/集合/闭包别名、
 *    字符串拼接、原始 SQL、运行时 DI 或跨模块生成代码不存在；
 *  - 简单局部别名只识别 `val|var name = draftRepository/drafts/writerUseCases`；
 *  - Kotlin raw string 已按整体剥离，但 string template 中的代码也会被整体剥离；
 *  - 模块发现是 `settings.gradle.kts` + 显式根目录映射的确定性检查；新增模块或移动源码根
 *    必须同步更新映射，否则测试失败。
 */
class LegacyDraftWriteBoundaryTest {

    private val repoRoot = File("..").canonicalFile
    private val applicationMain = "application/src/main/kotlin/com/qianyan/application"

    private data class Source(val path: String, val code: String)
    private data class WriteFinding(val path: String, val line: Int, val kind: String, val function: String?)
    private data class ReflectionFinding(val path: String, val line: Int, val token: String)

    private val moduleSourceRoots = linkedMapOf(
        ":core:model" to listOf("core/model/src/main/kotlin"),
        ":core:engine" to listOf("core/engine/src/main/kotlin"),
        ":agent:tool" to listOf("agent/tool/src/main/kotlin"),
        ":agent:runtime" to listOf("agent/runtime/src/main/kotlin"),
        ":agent:agents" to listOf("agent/agents/src/main/kotlin"),
        ":agent:orchestration" to listOf("agent/orchestration/src/main/kotlin"),
        ":provider:api" to listOf("provider/api/src/main/kotlin"),
        ":provider:impl" to listOf("provider/impl/src/main/kotlin"),
        ":storage" to listOf("storage/src/main/kotlin"),
        ":application" to listOf("application/src/main/kotlin"),
        ":runtime" to listOf("runtime/src/main/kotlin"),
        ":app:android" to listOf("app/android/src/main/java"),
        ":app:desktop" to listOf("app/desktop/src/jvmMain/kotlin"),
    )

    /** 仅测试、无生产源码声明的模块；新增模块必须显式加入扫描或豁免清单。 */
    private val exemptModules = mapOf(":test:e2e" to "test-only module")

    /** 当前无生产 Kotlin 源码的模块；若未来出现生产源码，已配置的根目录会被自动扫描。 */
    private val emptyProductionModules = setOf(":agent:orchestration", ":runtime")

    /**
     * 契约允许的写入方法（文件级白名单 → 方法级白名单）。
     *
     * 依据 v3.1 Phase 1：正式 Commit、作者保存、TXT Canonical 初始化、受控回退、确认状态变更。
     * 任何新增写入方法必须先修改本表并说明契约依据。
     */
    private val allowedWriteMethods = mapOf(
        "$applicationMain/usecase/commit/CommitUseCases.kt" to setOf(
            "commit", // 正式 Commit：新 Canonical 载体
            "commitSameRowCanonical", // 作者保存 / TXT 导入 same-row
            "revert", // 受控回退载体
        ),
        "$applicationMain/usecase/txt/TxtCanonicalInitializationUseCases.kt" to setOf(
            "initializeCanonical", // TXT Canonical 初始化
        ),
        "$applicationMain/usecase/workflow/WorkflowOrchestrator.kt" to setOf(
            "commitApproval", // 批准 + 提交事实后的载体状态补齐
        ),
        "$applicationMain/usecase/writing/WriterUseCases.kt" to setOf(
            "saveContent",
            "save",
            "stampControlledMarkdown",
        ),
        "$applicationMain/usecase/writing/confirmation/ConfirmationExecutionUseCase.kt" to setOf(
            "confirmFinalDraft", // FINAL/PENDING → CONFIRMED
        ),
        "$applicationMain/usecase/writing/WriterFacade.kt" to setOf(
            "continueWriting", // Commit 后新载体的受控 Markdown 标记
        ),
        "app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/write/WriterController.kt" to setOf(
            "save", // 作者保存（经 WriterGateway）
        ),
        "app/android/src/main/java/com/qianyan/app/android/ui/writer/WriterViewModel.kt" to setOf(
            "save", // 作者保存（经 WriterGateway）
        ),
    )

    private val directSaveCall = Regex("""\b(?:draftRepository|drafts)\s*\.\s*save\s*\(""")
    private val writeMethodReference = Regex(
        """\b(?:draftRepository|drafts|writerUseCases)\s*::\s*(?:save|saveContent|stampControlledMarkdown)\b""",
    )
    private val authorSaveCall = Regex("""\.\s*(?:saveContent|stampControlledMarkdown)\s*\(""")
    private val saveContentCall = Regex("""\.\s*saveContent\s*\(""")
    private val writerUseCasesDirect = Regex(
        """\bwriterUseCases\s*\.\s*(?:saveContent|save|stampControlledMarkdown)\s*\(""",
    )
    private val aliasDeclaration = Regex(
        """\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[A-Za-z_][A-Za-z0-9_.<>,? ]*)?\s*=\s*(?:draftRepository|drafts|writerUseCases)\s*(?![.\w(])""",
    )
    private val reflectionPatterns = listOf(
        Regex("""\bClass\s*\.\s*forName\s*\("""),
        Regex("""\.\s*getDeclaredMethod\s*\("""),
        Regex("""\.\s*getMethod\s*\("""),
        Regex("""\.\s*loadClass\s*\("""),
    )

    @Test
    fun `retired legacy orchestration symbols do not exist`() {
        assertFalse(
            repoFile("$applicationMain/usecase/chapter/ChapterWritingUseCases.kt").exists(),
            "ChapterWritingUseCases/ChapterWritingSession 属已删除的 AI 直写链，不得恢复",
        )

        val taskRunner = codeView(repoFile("$applicationMain/usecase/task/TaskRunner.kt"))
        assertFalse("fun executeWriting" in taskRunner, "TaskRunner 不得重新暴露 AI 直写入口")
        assertFalse("fun executeRevision" in taskRunner, "TaskRunner 不得重新暴露 AI 直写修订入口")

        assertNoLegacyExecute("$applicationMain/usecase/writing/WritingExecutionUseCase.kt")
        assertNoLegacyExecute("$applicationMain/usecase/writing/revision/RevisionExecutionUseCase.kt")

        val revision = codeView(repoFile("$applicationMain/usecase/writing/revision/RevisionExecutionUseCase.kt"))
        assertFalse("DraftRepository" in revision, "Revision 执行不得持有 Draft 写仓储")

        val container = codeView(repoFile("$applicationMain/di/ApplicationContainer.kt"))
        assertFalse("chapterWriting" in container, "ApplicationContainer 不得重新暴露 legacy 写作链")

        val commitUseCases = codeView(repoFile("$applicationMain/usecase/commit/CommitUseCases.kt"))
        assertFalse("fun commitApprovedDraft" in commitUseCases, "legacy commitApprovedDraft 不得恢复；AI 提交必须走正式 commit(...)")
    }

    /** 类型边界：构造参数（而非源码文本）证明写入口没有被重新装配。 */
    @Test
    fun `task runner and revision constructors do not accept legacy write dependencies`() {
        val taskRunnerParams = TaskRunner::class.java.constructors.single().parameterTypes.map { it.simpleName }
        assertFalse("WritingExecutionUseCase" in taskRunnerParams, "TaskRunner 不得再接收 WritingExecutionUseCase")
        assertFalse("RevisionExecutionUseCase" in taskRunnerParams, "TaskRunner 不得再接收 RevisionExecutionUseCase")

        val revisionParams = RevisionExecutionUseCase::class.java.constructors.single().parameterTypes.map { it.simpleName }
        assertFalse("DraftRepository" in revisionParams, "RevisionExecutionUseCase 不得再接收 DraftRepository")
    }

    @Test
    fun `all production write primitives stay inside contract allowed methods`() {
        val findings = writeOffenders(productionSources())
        assertEquals(
            emptyList(),
            findings.map { "${it.path}:${it.line} -> ${it.kind} in ${it.function ?: "<none>"}" },
            "写入原语只能出现在契约允许的具体方法内；新增写入必须先改契约",
        )
    }

    @Test
    fun `saveContent callers are only declared author-save paths`() {
        val callers = productionSources()
            .filter { saveContentCall.containsMatchIn(it.code) }
            .map { it.path }
            .toSet()
        assertEquals(
            setOf(
                "$applicationMain/usecase/commit/CommitUseCases.kt",
                "app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/write/WriterController.kt",
                "app/android/src/main/java/com/qianyan/app/android/ui/writer/WriterViewModel.kt",
            ),
            callers,
            "saveContent 只能由正式 Commit 内部与 UI 作者保存入口调用",
        )
    }

    @Test
    fun `writerUseCases write primitives are not called directly by production`() {
        val offenders = productionSources()
            .filter { writerUseCasesDirect.containsMatchIn(it.code) }
            .map { it.path }
        assertEquals(emptyList(), offenders, "生产代码必须经 Commit / WriterFacade 调用写入原语")
    }

    @Test
    fun `retired orchestration is not referenced by production modules`() {
        val forbidden = listOf(
            "ChapterWritingUseCases",
            "ChapterWritingSession",
            "chapterWriting",
            "taskRunner.executeWriting",
            "taskRunner.executeRevision",
            "TaskRunner.executeWriting",
            "TaskRunner.executeRevision",
        )
        val offenders = productionSources().flatMap { (path, code) ->
            forbidden.filter { it in code }.map { "$path -> $it" }
        }
        assertEquals(emptyList(), offenders, "生产模块不得引用退役的 AI 直写编排")
    }

    @Test
    fun `formal workflow only uses working draft write and revision entries`() {
        val orchestrator = codeView(repoFile("$applicationMain/usecase/workflow/WorkflowOrchestrator.kt"))
        assertTrue("executeIntoWorkingDraft(" in orchestrator, "Workflow 写作必须走 WorkingDraft 安全入口")
        assertTrue("reviseWorking(" in orchestrator, "Workflow 修订必须走正文安全入口")
        assertFalse("writing.execute(" in orchestrator, "Workflow 不得调用已删除的 Draft 直写入口")
        assertFalse("revision.execute(" in orchestrator, "Workflow 不得调用已删除的 Draft 直写入口")
    }

    @Test
    fun `task worker only registers the non writing executor`() {
        val f = novelAgentFixture()
        assertEquals(
            setOf(TaskKind.PROJECT_INDEX_REBUILD),
            f.app.taskExecutors().keys,
            "TaskWorker 不得注册写作/修订执行器",
        )
        f.close()
    }

    @Test
    fun `write detection handles whitespace variants and method references`() {
        val code = codeView(
            """
            fun synthetic() {
                draftRepository . save(draft)
                drafts . save (draft)
                draftRepository::save
                draftRepository::saveContent
                writerUseCases::stampControlledMarkdown
            }
            """.trimIndent(),
        )
        val findings = writeOffenders(listOf(Source("synthetic/WhitespaceAndRefs.kt", code)))
        assertEquals(
            5,
            findings.count { it.kind == "DraftRepository.save call" || it.kind == "write method reference" },
            "空白变体与方法引用都必须被识别",
        )
    }

    @Test
    fun `write detection handles simple local aliases only`() {
        val code = codeView(
            """
            fun synthetic() {
                val repo = draftRepository
                var writer = writerUseCases
                repo . save(draft)
                writer::saveContent
            }
            """.trimIndent(),
        )
        val findings = writeOffenders(listOf(Source("synthetic/SimpleAliases.kt", code)))
        assertEquals(2, findings.count { it.kind.startsWith("alias") }, "简单的 val/var 局部别名必须被识别")

        val notAnAlias = codeView(
            """
            fun synthetic() {
                val latest = draftRepository.latestByChapter(chapterId)
                latest.toString()
            }
            """.trimIndent(),
        )
        assertTrue(
            writeOffenders(listOf(Source("synthetic/NotAlias.kt", notAnAlias))).isEmpty(),
            "非 direct alias（方法调用结果）不得被误判",
        )
    }

    @Test
    fun `write detection ignores comments strings and raw strings`() {
        val raw = "\"\"\"draftRepository.save(d)\"\"\""
        val pseudoCode = codeView(
            listOf(
                "// draftRepository.save(a)",
                "/* drafts.save(b) */",
                "val s = \"draftRepository.save(c)\"",
                "val raw = $raw",
                "fun synthetic() { }",
            ).joinToString("\n"),
        )
        assertTrue(
            writeOffenders(listOf(Source("synthetic/PseudoCode.kt", pseudoCode))).isEmpty(),
            "注释 / 普通字符串 / raw string 中的写入文本不得被当作调用",
        )

        val mixed = codeView("$pseudoCode\nfun real() { draftRepository.save(d) }")
        assertTrue(
            writeOffenders(listOf(Source("synthetic/Mixed.kt", mixed))).any {
                it.kind == "DraftRepository.save call"
            },
            "真实代码中的写入必须仍被识别",
        )
    }

    @Test
    fun `method level allowlist rejects new write methods in allowed files`() {
        val path = "$applicationMain/usecase/commit/CommitUseCases.kt"
        val code = codeView(
            """
            class SyntheticCommitUseCases {
                fun commit() { drafts.saveContent(draftId, content) }
                fun newWrite() { drafts.saveContent(draftId, content) }
            }
            """.trimIndent(),
        )
        val findings = writeOffenders(listOf(Source(path, code)))
        assertEquals(
            listOf("newWrite"),
            findings.mapNotNull { it.function }.distinct(),
            "白名单文件内新增写入方法必须被拒绝",
        )
    }

    @Test
    fun `writer gateway saveContent is refused outside UI author save`() {
        val allowedPath = "app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/write/WriterController.kt"
        val blockedPath = "$applicationMain/usecase/agent/SyntheticAi.kt"
        val allowed = Source(
            allowedPath,
            codeView("class SyntheticWriter { fun save() { gateway.saveContent(draftId, content) } }"),
        )
        val blocked = Source(
            blockedPath,
            codeView("class SyntheticAi { fun write() { gateway.saveContent(draftId, content) } }"),
        )

        assertTrue(writeOffenders(listOf(allowed)).isEmpty(), "UI 作者保存方法应被允许")
        assertTrue(
            writeOffenders(listOf(blocked)).any { it.kind == "author-save/format call" },
            "AI 路径不得把 WriterGateway.saveContent 当通用写入入口",
        )
    }

    @Test
    fun `every declared Gradle module is explicitly classified for production scanning`() {
        val declared = declaredModules()
        val classified = moduleSourceRoots.keys + exemptModules.keys
        assertEquals(
            emptySet(),
            declared - classified,
            "新增 Gradle 模块未分类；必须加入扫描或显式豁免（${declared - classified}）",
        )
        assertEquals(
            emptySet(),
            classified - declared,
            "分类清单包含已移除模块；必须同步（${classified - declared}）",
        )

        moduleSourceRoots.forEach { (module, roots) ->
            val moduleDir = repoFile(moduleDirOf(module, roots))
            assertTrue(moduleDir.isDirectory, "模块目录不存在: ${moduleDir.absolutePath}")
            if (module !in emptyProductionModules) {
                assertTrue(
                    roots.any { repoFile(it).isDirectory },
                    "模块 $module 的生产源码根不存在（布局变化，扫描需同步）: $roots",
                )
            }
        }
    }

    @Test
    fun `production has no reflection based write bypass`() {
        val findings = reflectionFindings(productionSources())
        assertEquals(
            emptyList(),
            findings.map { "${it.path}:${it.line} -> ${it.token}" },
            "生产源码不得使用反射查找写入方法",
        )
    }

    @Test
    fun `reflection scanner sees code but ignores comments and strings`() {
        val code = codeView(
            listOf(
                "// Class.forName(\"x\")",
                "val s = \"Class.forName(\\\"x\\\")\"",
                "fun synthetic() { Class.forName(\"real\").getDeclaredMethod(\"save\") }",
            ).joinToString("\n"),
        )
        val findings = reflectionFindings(listOf(Source("synthetic/Reflection.kt", code)))
        assertEquals(2, findings.size, "真实反射 token 应命中，注释/字符串不应命中")
    }

    private fun assertNoLegacyExecute(path: String) {
        val code = codeView(repoFile(path))
        assertFalse(
            Regex("""\bfun execute\s*\(""").containsMatchIn(code),
            "$path 不得重新引入 Draft 直写 execute(...) 入口",
        )
    }

    private fun writeOffenders(sources: List<Source>): List<WriteFinding> {
        val findings = mutableListOf<WriteFinding>()
        for (source in sources) {
            val code = source.code
            val ranges = functionRanges(code)
            val aliases = aliasesFor(code)

            fun check(regex: Regex, kind: String) {
                for (match in regex.findAll(code)) {
                    val function = enclosingFunction(ranges, match.range.first)?.name
                    if (allowedWriteMethods[source.path]?.contains(function) == true) continue
                    findings += WriteFinding(source.path, lineOf(code, match.range.first), kind, function)
                }
            }

            check(directSaveCall, "DraftRepository.save call")
            check(writeMethodReference, "write method reference")
            check(authorSaveCall, "author-save/format call")
            check(writerUseCasesDirect, "writerUseCases write primitive call")

            for (alias in aliases) {
                check(
                    Regex("""\b${Regex.escape(alias)}\s*\.\s*(?:save|saveContent|stampControlledMarkdown)\s*\("""),
                    "alias write call",
                )
                check(
                    Regex("""\b${Regex.escape(alias)}\s*::\s*(?:save|saveContent|stampControlledMarkdown)\b"""),
                    "alias write reference",
                )
            }
        }
        return findings.distinctBy { Triple(it.path, it.line, it.kind) }
    }

    private fun reflectionFindings(sources: List<Source>): List<ReflectionFinding> {
        val findings = mutableListOf<ReflectionFinding>()
        for (source in sources) {
            for (pattern in reflectionPatterns) {
                for (match in pattern.findAll(source.code)) {
                    findings += ReflectionFinding(
                        source.path,
                        lineOf(source.code, match.range.first),
                        match.value.trim(),
                    )
                }
            }
        }
        return findings.distinctBy { Triple(it.path, it.line, it.token) }
    }

    private fun aliasesFor(code: String): Set<String> =
        aliasDeclaration.findAll(code).map { it.groupValues[1] }.toSet()

    private data class FunctionRange(val name: String, val start: Int, val end: Int)

    private fun functionRanges(code: String): List<FunctionRange> {
        val ranges = mutableListOf<FunctionRange>()
        for (match in Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""").findAll(code)) {
            val openParen = match.range.last
            val closeParen = matching(code, openParen, '(', ')') ?: continue
            var bodyStart = closeParen + 1
            while (bodyStart < code.length && code[bodyStart] != '\n' &&
                code[bodyStart] != '{' && code[bodyStart] != '='
            ) {
                bodyStart++
            }
            if (bodyStart >= code.length || code[bodyStart] == '=') continue
            if (code[bodyStart] == '\n') {
                var next = bodyStart + 1
                while (next < code.length && code[next].isWhitespace()) next++
                if (next >= code.length || code[next] != '{') continue
                bodyStart = next
            }
            if (code[bodyStart] != '{') continue
            val end = matching(code, bodyStart, '{', '}') ?: continue
            ranges += FunctionRange(match.groupValues[1], match.range.first, end)
        }
        return ranges
    }

    private fun enclosingFunction(ranges: List<FunctionRange>, offset: Int): FunctionRange? =
        ranges.filter { offset in it.start..it.end }.minByOrNull { it.end - it.start }

    private fun matching(code: String, open: Int, openChar: Char, closeChar: Char): Int? {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun lineOf(code: String, offset: Int): Int =
        code.substring(0, offset.coerceAtMost(code.length)).count { it == '\n' } + 1

    private fun declaredModules(): Set<String> {
        val settings = repoFile("settings.gradle.kts")
        assertTrue(settings.isFile, "找不到 settings.gradle.kts: ${settings.absolutePath}")
        return Regex("""include\("([^"]+)"\)""").findAll(settings.readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun moduleDirOf(module: String, roots: List<String>): String =
        roots.firstOrNull()?.substringBefore("/src/")
            ?: module.removePrefix(":").replace(':', '/')

    /** 生产源码集合（相对路径 + 去注释/去字符串代码）；测试、docs、build 不在范围内。 */
    private fun productionSources(): List<Source> =
        moduleSourceRoots.flatMap { (_, roots) ->
            roots.flatMap { root ->
                val dir = repoFile(root)
                if (!dir.isDirectory) {
                    emptyList()
                } else {
                    dir.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .map { Source(relativePath(it), codeView(it.readText())) }
                        .toList()
                }
            }
        }

    private fun repoFile(path: String): File = File(repoRoot, path)

    private fun relativePath(file: File): String =
        file.relativeTo(repoRoot).invariantSeparatorsPath

    private fun codeView(file: File): String = codeView(file.readText())

    /**
     * 去注释 / 去字符串 / 去 raw string，保留换行与偏移。
     *
     * 字符串整体置空（包括 `${...}` 模板），避免把字符串内容当作代码；这也会漏掉模板内的动态调用，
     * 已在类 KDoc 中声明为文本级限制。
     */
    private fun codeView(text: String): String {
        val out = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    val start = i
                    i += 2
                    while (i < text.length && text[i] != '\n') i++
                    blank(out, start, i)
                }
                c == '/' && next == '*' -> {
                    val start = i
                    var depth = 1
                    i += 2
                    while (i < text.length && depth > 0) {
                        val n = text.getOrNull(i + 1)
                        if (text[i] == '/' && n == '*') {
                            depth++
                            i += 2
                        } else if (text[i] == '*' && n == '/') {
                            depth--
                            i += 2
                        } else {
                            i++
                        }
                    }
                    blank(out, start, i)
                }
                text.startsWith("\"\"\"", i) -> {
                    val start = i
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) i++
                    if (i < text.length) i += 3
                    blank(out, start, i)
                }
                c == '"' -> {
                    val start = i
                    i++
                    while (i < text.length) {
                        when {
                            text[i] == '\\' -> i += 2
                            text[i] == '"' -> {
                                i++
                                break
                            }
                            else -> i++
                        }
                    }
                    blank(out, start, i)
                }
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < text.length) {
                        when {
                            text[i] == '\\' -> i += 2
                            text[i] == '\'' -> {
                                i++
                                break
                            }
                            else -> i++
                        }
                    }
                    blank(out, start, i)
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private fun blank(out: StringBuilder, start: Int, end: Int) {
        for (j in start until end.coerceAtMost(out.length)) {
            if (out[j] != '\n' && out[j] != '\r') out.setCharAt(j, ' ')
        }
    }
}
