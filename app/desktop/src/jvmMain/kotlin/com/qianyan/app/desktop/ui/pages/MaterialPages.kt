package com.qianyan.app.desktop.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.InfoBlock
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.PageHeader
import com.qianyan.app.desktop.ui.PendingNote
import com.qianyan.app.desktop.ui.QianyanTabs
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.SourceNote
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.formatCount
import com.qianyan.app.desktop.ui.preview
import com.qianyan.app.desktop.ui.shortTime
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.model.NovelId
import com.qianyan.app.desktop.ui.ActionButton
import com.qianyan.app.desktop.ui.ActionLevel
import com.qianyan.model.vocabulary.VocabularyCandidateStatus
import com.qianyan.model.BaseNovelId
import com.qianyan.model.core.VariantContext
import com.qianyan.model.memory.MemoryEntry
import com.qianyan.model.vocabulary.Vocabulary
import com.qianyan.model.vocabulary.VocabularyCandidate
import com.qianyan.model.vocabulary.VocabularyScopeLevel

/**
 * 词库（`vocabularies` · P1/P6 已交付；新执行链路任务 C 已接入写路径）。
 *
 * **真实数据**：`vocabularies.query(VocabularyScopeLevel.NOVEL)`、`findCandidatesByNovel(novelId)`、
 * 「分析 TXT」→ `analysis.analyzeTxtOriginal`（经 [DesktopAppState.launchTxtAnalysis]，产出 PENDING 候选）、
 * 候选确认/拒绝 → `vocabularies.confirmCandidate / rejectCandidate`（经 [DesktopAppState.launchCandidateDecision]）。
 * 词条逐条明细仍无只读 UseCase（PC-5 前置），界面如实标注。
 */
@Composable
fun VocabularyPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var tab by remember { mutableStateOf(0) }
    var vocabularies by remember { mutableStateOf<List<Vocabulary>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<VocabularyCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val novel = state.novel
    LaunchedEffect(novel?.novelId, state.refreshKey) {
        val novelId = novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            vocabularies = it.vocabularies.query(VocabularyScopeLevel.NOVEL)
                .filter { v -> v.novelId == null || v.novelId == novelId }
            candidates = it.vocabularies.findCandidatesByNovel(novelId)
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "词库",
            meta = novel?.title,
            actions = {
                ActionButton(
                    "分析 TXT",
                    ActionLevel.PRIMARY,
                    enabled = novel != null,
                ) {
                    val novelId = novel?.novelId
                    if (novelId == null) state.toast("请先打开作品") else state.launchTxtAnalysis(novelId)
                }
            },
        )
        QianyanTabs(listOf("词表 · ${vocabularies.size}", "候选 · ${candidates.size}"), tab) { tab = it }

        when {
            novel == null -> Box(Modifier.padding(QianyanDimens.PageX)) {
                EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
            }

            loading -> Box(Modifier.padding(QianyanDimens.PageX)) { LoadingState() }

            tab == 0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
                if (vocabularies.isEmpty()) {
                    EmptyState("还没有词库", "该作品在既有词库中没有 NOVEL 级词库")
                    PendingNote(
                        "vocabularies.getOrCreateNovelVocabulary(novelId)",
                        "创建作品词库是写操作，本轮未接入。",
                    )
                } else {
                    vocabularies.forEach { v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(v.name.ifBlank { v.vocabularyId.value }, style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
                            Tag(statusLabel(v.scopeLevel.name))
                        }
                        HLine()
                    }
                    Spacer(Modifier.height(12.dp))
                    PendingNote(
                        "词条明细读取（当前 Application 层无 listEntries 只读 UseCase）",
                        "词库容器与候选词是真数据；词条逐条明细需先补一个只读 UseCase（PC-5 前置），本轮不直连仓储。",
                    )
                }
                SourceNote("来源：vocabularies.query + findCandidatesByNovel（只读）")
            }

            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
                val novelId = novel?.novelId
                if (candidates.isEmpty()) {
                    EmptyState("还没有候选词", "点上方「分析 TXT」从导入的原文提炼候选词库")
                } else {
                    candidates.forEach { c ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    c.suggested.canonical.ifBlank { c.candidateId.value },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.text,
                                    modifier = Modifier.weight(1f),
                                )
                                if (c.status == VocabularyCandidateStatus.PENDING && novelId != null) {
                                    ActionButton("确认", ActionLevel.TEXT, true) {
                                        state.launchCandidateDecision(novelId, c.candidateId, confirm = true)
                                    }
                                    ActionButton("拒绝", ActionLevel.TEXT, true) {
                                        state.launchCandidateDecision(novelId, c.candidateId, confirm = false)
                                    }
                                }
                                Tag(c.suggested.type.name, palette.text3)
                                Spacer(Modifier.width(6.dp))
                                Tag(statusLabel(c.status.name), if (c.status.name == "PENDING") palette.accent else palette.text3)
                            }
                            c.suggested.aliases.takeIf { it.isNotEmpty() }?.let { aliases ->
                                Spacer(Modifier.height(2.dp))
                                Text("别名：${aliases.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = palette.text3)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${c.source.name} · ${shortTime(c.createdAt)}",
                                style = QianyanCodeStyle,
                                color = palette.text3,
                            )
                        }
                        HLine()
                    }
                }
                Spacer(Modifier.height(12.dp))
                SourceNote("确认 / 拒绝经 vocabularies.confirmCandidate / rejectCandidate（幂等、PENDING→APPROVED 由后端保证）")
            }
        }
    }
}

/**
 * 记忆（`memories`）。
 *
 * **真实只读**：`memories.query(VariantContext(baseNovelId, variantId))`。
 * `baseNovelId` 只能从既有 Story Foundation 取得（`storyFoundationRepository.getStoryFoundation`），
 * 未确认故事基础时无法构造 VariantContext ⇒ 如实提示，不伪造。
 */
@Composable
fun MemoryPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var baseId by remember { mutableStateOf<BaseNovelId?>(null) }
    var entries by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val novel = state.novel
    val variantId = state.workspace.selection?.variantId
    LaunchedEffect(novel?.novelId, variantId, state.refreshKey) {
        val novelId = novel?.novelId
        if (novelId == null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        state.load {
            val base = it.storyFoundationRepository.getStoryFoundation(novelId)?.baseNovelId
            baseId = base
            entries = if (base == null) emptyList() else it.memories.query(VariantContext(base, variantId))
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("记忆", meta = novel?.title)

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
            when {
                novel == null -> EmptyState("尚未打开作品", "在「Qianyan Agent」中打开或新建作品")
                loading -> LoadingState()
                baseId == null -> Column {
                    EmptyState("无法构造记忆作用域", "记忆查询需要 VariantContext(baseNovelId, variantId)，而 baseNovelId 来自已确认的 Story Foundation")
                    PendingNote(
                        "storyFoundationRepository.getStoryFoundation(novelId).baseNovelId",
                        "先在「故事 · 故事基础」确认故事基础，本页即可显示真实记忆条目。",
                    )
                }

                entries.isEmpty() -> Column {
                    EmptyState("还没有记忆条目", "记忆由知识更新（knowledgeUpdate.execute）写入，或经 memories.saveEntry 手工写入")
                    PendingNote("memories.saveEntry(context, content, layer, source, ...)", "写入本轮未接入。")
                }

                else -> Column {
                    entries.forEach { m ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(preview(m.content, 80), style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
                                Tag(statusLabel(m.layer.name), if (m.effective) palette.add else palette.text3)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${m.target.ifBlank { "—" }} · ${m.source.ifBlank { "—" }} · ${shortTime(m.createdAt)}",
                                style = QianyanCodeStyle,
                                color = palette.text3,
                            )
                        }
                        HLine()
                    }
                    SourceNote("来源：memories.query(VariantContext)（只读）")
                }
            }
        }
    }
}

/**
 * 备份 / 库管理（`backupStore`）。
 *
 * 只显示真实信息（schema 版本、库路径）；导出与恢复都是**重操作**
 * （`exportBackup()` 读全库、`restoreBackup(pkg)` 单事务清空并恢复），本轮未接入。
 */
@Composable
fun BackupPage(state: DesktopAppState) {
    var schemaVersion by remember { mutableStateOf("—") }
    LaunchedEffect(state.refreshKey) {
        schemaVersion = runCatching { state.load { it.backupStore.schemaVersion } }.getOrDefault("—")
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader("备份", meta = state.graph.appDir.fileName?.toString())

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
            Column(Modifier.fillMaxWidth()) {
                InfoBlock(
                    "本地库",
                    listOf(
                        "数据目录" to state.graph.appDir.toString(),
                        "数据库" to state.graph.appDir.resolve("qianyan.db").toString(),
                        "Schema" to schemaVersion,
                        "Provider 选择" to state.graph.provider.value.name,
                    ),
                )
                Spacer(Modifier.height(18.dp))
                SectionLabel("导出 / 恢复")
                PendingNote(
                    "backupStore.exportBackup() / backupStore.restoreBackup(package_)",
                    "导出会读取整库、恢复是单事务清空并覆盖——两者都需要先确定落盘位置与确认流程，本轮未接入。",
                )
                Spacer(Modifier.height(14.dp))
                SectionLabel("库管理")
                PendingNote(
                    "shelf.properties / deleted-backups（PC-7 决策项）",
                    "作品删除与归档按 R2 决策不提供入口；Original 受 novel_original_delete_protect 保护。",
                )
                SourceNote("来源：backupStore.schemaVersion + DesktopGraph.appDir（真实本地路径）")
            }
        }
    }
}

/* ---------------- 共享：作用域构造（只读） ---------------- */

/**
 * 从既有 Story Foundation 取 `baseNovelId` —— 构造 [VariantContext] 的唯一真实来源。
 *
 * 不用 Novel 猜 ID：`Novel` 没有 baseNovelId 字段，猜出来就是伪造。
 */
internal suspend fun baseNovelIdOf(state: DesktopAppState, novelId: NovelId): BaseNovelId? =
    runCatching { state.load { it.storyFoundationRepository.getStoryFoundation(novelId)?.baseNovelId } }.getOrNull()

/* ---------------- 小件 ---------------- */

@Composable
private fun PendingNoteInline(useCase: String) {
    Text(
        "分析入口未接入 · $useCase",
        style = QianyanCodeStyle,
        color = QianyanTheme.palette.text3,
    )
}

@Composable
internal fun CountRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = QianyanTheme.palette.text2, modifier = Modifier.weight(1f))
        Text(formatCount(value), style = QianyanCodeStyle, color = QianyanTheme.palette.text3)
    }
}
