package com.qianyan.app.desktop

import com.qianyan.app.desktop.adapter.FileProviderCredentialStore
import com.qianyan.app.desktop.di.DesktopGraph
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.pages.vocabularyEntryTarget
import com.qianyan.model.NovelId
import com.qianyan.model.VocabularyCandidateId
import com.qianyan.model.VocabularyEntryId
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyCandidateSource
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.vocabulary.VocabularyEntry
import com.qianyan.model.vocabulary.VocabularyEntryStatus
import com.qianyan.model.vocabulary.VocabularyEntryType
import com.qianyan.model.vocabulary.VocabularyScopeLevel
import com.qianyan.provider.impl.transport.JdkLlmHttpClient
import com.qianyan.provider.impl.transport.LlmHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock

/**
 * T-O71（O-71①）· 词库候选「编辑」入口。
 *
 * 30 号起的既有事实：候选行只有「确认 / 拒绝」，AI 提取错了只能拒绝重来。
 * 本单元把既有 `VocabularyUseCases.editCandidate` 接到 UI：
 *  - 编辑走 IO + 成功后 `refreshKey++`（候选行重读）；失败只 toast，不把异常抛进 Compose；
 *  - 词条 tab 的 from → to 目标由 `vocabularyEntryTarget` 纯投影决定。
 */
class VocabularyCandidateEditTest {

    private fun graphAt(dir: Path): DesktopGraph {
        val ctor = DesktopGraph::class.java.getDeclaredConstructor(
            Path::class.java,
            FileProviderCredentialStore::class.java,
            LlmHttpClient::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(dir, FileProviderCredentialStore(dir), JdkLlmHttpClient())
    }

    private fun await(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待超时（${timeoutMs}ms）")
    }

    /** 造一个 PENDING 候选（canonical=灵石，别名 灵矿）。 */
    private fun seedCandidate(graph: DesktopGraph): Pair<NovelId, VocabularyCandidateId> {
        val container = graph.container
        val novelId = container.novels.createOriginal(title = "T-O71 词库编辑")
        val vocabularyId = container.vocabularies.getOrCreateNovelVocabulary(novelId)
        val candidateId = VocabularyCandidateId("cand-t-o71")
        container.vocabularyRepository.saveCandidate(
            VocabularyCandidate(
                candidateId = candidateId,
                vocabularyId = vocabularyId,
                novelId = novelId,
                variantId = null,
                scopeLevel = VocabularyScopeLevel.NOVEL,
                suggested = VocabularyEntry(
                    entryId = VocabularyEntryId("entry-t-o71"),
                    vocabularyId = vocabularyId,
                    novelId = novelId,
                    scopeLevel = VocabularyScopeLevel.NOVEL,
                    canonical = "灵石",
                    aliases = listOf("灵矿"),
                    type = VocabularyEntryType.WORLD_TERM,
                    status = VocabularyEntryStatus.CANDIDATE,
                ),
                source = VocabularyCandidateSource.AUTO_EXTRACT,
                status = VocabularyCandidateStatus.PENDING,
                createdAt = Clock.System.now(),
            ),
        )
        return novelId to candidateId
    }

    @Test
    fun `editing a candidate persists the new canonical and refreshes the page`() {
        val graph = graphAt(Files.createTempDirectory("qianyan-o71-edit"))
        val (novelId, candidateId) = seedCandidate(graph)
        val state = DesktopAppState(graph)

        state.launchCandidateEdit(novelId, candidateId, "灵石", "星石")

        await { state.refreshKey > 0 }
        val saved = graph.container.vocabularyRepository.findCandidatesByNovel(novelId).single()
        assertEquals("星石", saved.suggested.canonical, "编辑必须落库（既有 editCandidate 语义）")
        assertEquals(listOf("灵矿"), saved.suggested.aliases, "未改别名时保持原值")
        assertEquals(VocabularyCandidateStatus.PENDING, saved.status, "编辑不得自动晋升状态")
        assertNotNull(state.toast, "成功必须有可见提示")
        assertTrue(state.toast!!.contains("已更新候选"), "提示必须可读：${state.toast}")
        assertTrue(state.toast!!.contains("灵石") && state.toast!!.contains("星石"), "提示含前后值")
    }

    @Test
    fun `edit failure surfaces a readable toast and never crashes compose`() {
        val graph = graphAt(Files.createTempDirectory("qianyan-o71-edit-fail"))
        val novelId = graph.container.novels.createOriginal(title = "T-O71 失败路径")
        val state = DesktopAppState(graph)

        state.launchCandidateEdit(novelId, VocabularyCandidateId("missing-candidate"), "旧词", "新词")

        await { state.toast != null }
        assertTrue(
            state.toast!!.contains("不存在"),
            "失败必须给出可读原因（EntityNotFound 文案），实际=${state.toast}",
        )
        assertTrue(!state.busy, "失败后 busy 必须收敛（否则整页按钮永久禁用）")
    }

    @Test
    fun `candidate row and entry tab are wired in the material pages`() {
        val pages = repoFile("app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/MaterialPages.kt").readText()
        assertTrue("ActionButton(\"编辑\"" in pages, "候选行必须提供「编辑」入口")
        assertTrue("词条 · " in pages, "词表 tabs 必须有「词条」tab")
        assertTrue("launchCandidateEdit(" in pages, "编辑对话框必须接到 launchCandidateEdit")
        assertTrue("vocabularyEntryTarget(" in pages, "词条行必须用纯投影渲染 from → to")
        assertTrue("还没有已确认词条" in pages, "词条 tab 必须有空态文案")

        val common = repoFile("app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/Common.kt").readText()
        assertTrue("fun launchCandidateEdit(" in common, "Common.kt 必须提供 launchCandidateEdit")
        assertTrue("editCandidate(" in common, "编辑必须复用既有 VocabularyUseCases.editCandidate")
        assertTrue("refreshKey++" in common, "编辑成功后必须刷新页面（候选行重读）")

        val useCases = repoFile(
            "application/src/main/kotlin/com/qianyan/application/usecase/vocabulary/VocabularyUseCases.kt",
        ).readText()
        assertTrue("fun listEntries(novelId: NovelId)" in useCases, "读侧必须有 listEntries")
        assertTrue("repo.findEntriesByNovel(novelId)" in useCases, "listEntries 必须委托既有仓储查询")
    }

    @Test
    fun `entry target projection prefers replacement then alias then blank`() {
        fun entry(canonical: String, replacement: String? = null, aliases: List<String> = emptyList()) =
            VocabularyEntry(
                entryId = VocabularyEntryId("e-$canonical"),
                vocabularyId = com.qianyan.model.VocabularyId("v"),
                canonical = canonical,
                aliases = aliases,
                replacement = replacement,
            )

        assertEquals("星石", vocabularyEntryTarget(entry("星石", replacement = "星石")), "有 replacement 用它")
        assertEquals("关外", vocabularyEntryTarget(entry("北境", aliases = listOf("关外"))), "无 replacement 用首个别名")
        assertEquals("", vocabularyEntryTarget(entry("孤词")), "都没有时返回空（UI 只显示 canonical）")
    }

    private fun repoFile(repoRelativePath: String): File {
        var dir: File? = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, repoRelativePath)
            if (candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        error("未找到仓库文件：$repoRelativePath")
    }
}
