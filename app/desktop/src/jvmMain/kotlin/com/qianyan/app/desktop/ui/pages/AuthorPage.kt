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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qianyan.app.desktop.ui.DesktopAppState
import com.qianyan.app.desktop.ui.EmptyState
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.LoadingState
import com.qianyan.app.desktop.ui.PageHeader
import com.qianyan.app.desktop.ui.PendingNote
import com.qianyan.app.desktop.ui.QianyanTabs
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.SourceNote
import com.qianyan.app.desktop.ui.Tag
import com.qianyan.app.desktop.ui.preview
import com.qianyan.app.desktop.ui.statusLabel
import com.qianyan.app.desktop.ui.theme.QianyanCodeStyle
import com.qianyan.app.desktop.ui.theme.QianyanDimens
import com.qianyan.app.desktop.ui.theme.QianyanTheme
import com.qianyan.model.author.AuthorContext
import com.qianyan.model.decision.DecisionPolicy
import com.qianyan.model.decision.DecisionType

/**
 * 作者智能（P16–P19 · `authorContextProjection` + `decisionModelUseCases`）。
 *
 * **真实只读**：`authorContextProjection.project(novelId)` → `AuthorContext`
 * （偏好 / 风格核心 / Author DNA 三份投影一次取回，无副作用）；
 * `decisionModelUseCases.decide(authorContext, types)` → `List<DecisionPolicy>`（stateless，不调 LLM）。
 *
 * 写入与学习类动作（`addExplicitPreference` / `confirmPreference` / `confirmCoreCandidate` /
 * `authorDnaUseCases.rebuild` 等）本轮**未接入**，界面标注接入点。
 */
@Composable
fun AuthorPage(state: DesktopAppState) {
    val palette = QianyanTheme.palette
    var tab by remember { mutableStateOf(0) }
    var context by remember { mutableStateOf<AuthorContext?>(null) }
    var policies by remember { mutableStateOf<List<DecisionPolicy>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val novel = state.novel
    LaunchedEffect(novel?.novelId, state.refreshKey) {
        loading = true
        state.load {
            val ctx = it.authorContextProjection.project(novel?.novelId)
            context = ctx
            policies = runCatching {
                it.decisionModelUseCases.decide(ctx, DecisionType.STORY_DIRECTION, DecisionType.WRITING_STYLE)
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    val ctx = context
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "作者智能",
            meta = novel?.title ?: "全局",
            actions = {
                Text(
                    "读取：authorContextProjection.project(${if (novel == null) "null" else "novelId"})",
                    style = QianyanCodeStyle,
                    color = palette.text3,
                )
            },
        )
        QianyanTabs(
            listOf(
                "偏好 · ${ctx?.preferences?.size ?: 0}",
                "风格核心 · ${ctx?.cores?.size ?: 0}",
                "Author DNA · ${ctx?.dna?.size ?: 0}",
                "决策模型 · ${policies.size}",
            ),
            tab,
        ) { tab = it }

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QianyanDimens.PageX)) {
            when {
                loading -> LoadingState()

                tab == 0 -> Column(Modifier.fillMaxWidth()) {
                    if (ctx == null || ctx.preferences.isEmpty()) {
                        EmptyState("还没有作者偏好", "偏好来自作者长期创作决策的沉淀（P16–P17 学习链路）")
                    } else {
                        ctx.preferences.forEach { p ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.statement, style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
                                    Tag(statusLabel(p.dimension.name), palette.text3)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "置信 ${(p.confidence.value * 100).toInt()}%",
                                        style = QianyanCodeStyle,
                                        color = palette.text3,
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "作用域 ${statusLabel(p.scope.name)}${p.revocable.let { if (it) " · 可撤销" else "" }}",
                                    style = QianyanCodeStyle,
                                    color = palette.text3,
                                )
                            }
                            HLine()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PendingNote(
                        "authorPreferenceUseCases.addExplicitPreference / confirmPreference / rejectPreference",
                        "手工补充与确认偏好本轮未接入。",
                    )
                    SourceNote("来源：authorContextProjection.project（只读投影）")
                }

                tab == 1 -> Column(Modifier.fillMaxWidth()) {
                    if (ctx == null || ctx.cores.isEmpty()) {
                        EmptyState("还没有风格核心", "风格核心由长期证据沉淀并确认（authorCoreUseCases）")
                    } else {
                        ctx.cores.forEach { c ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(c.statement, style = MaterialTheme.typography.bodyLarge, color = palette.text)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    listOfNotNull(
                                        "pattern ${c.patternKey}",
                                        statusLabel(c.scope.name),
                                        "置信 ${(c.confidence.value * 100).toInt()}%",
                                        c.condition?.let { "条件 $it" },
                                    ).joinToString("  ·  "),
                                    style = QianyanCodeStyle,
                                    color = palette.text3,
                                )
                            }
                            HLine()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PendingNote(
                        "authorCoreUseCases.viewCandidates / confirmCoreCandidate / pauseCoreLearning",
                        "候选核心与学习开关本轮未接入。",
                    )
                }

                tab == 2 -> Column(Modifier.fillMaxWidth()) {
                    if (ctx == null || ctx.dna.isEmpty()) {
                        EmptyState("还没有 Author DNA", "DNA 从作者样本中提炼（authorDnaUseCases.rebuild，需 LLM）")
                    } else {
                        ctx.dna.forEach { f ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(f.featureKey, style = QianyanCodeStyle, color = palette.text3)
                                    Spacer(Modifier.width(8.dp))
                                    Text(f.value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, color = palette.text, modifier = Modifier.weight(1f))
                                    Text("${(f.confidence.value * 100).toInt()}%", style = QianyanCodeStyle, color = palette.text3)
                                }
                                if (f.statement.isNotBlank()) {
                                    Text(preview(f.statement, 90), style = MaterialTheme.typography.bodySmall, color = palette.text2)
                                }
                            }
                            HLine()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    PendingNote(
                        "authorDnaUseCases.rebuild(authorId) / confirmFeature(featureId)",
                        "DNA 重建需要 LLM 与样本登记（registerSource），本轮未接入。",
                    )
                }

                else -> Column(Modifier.fillMaxWidth()) {
                    Text(
                        "决策模型把作者上下文解析成策略（P19 · stateless：只读作者上下文，不自行 decide）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text2,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (policies.isEmpty()) {
                        EmptyState("没有可展示的策略", "需要作者上下文（偏好 / 风格核心 / DNA）才能解析出策略")
                    } else {
                        policies.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (p.decisionType) {
                                        DecisionType.STORY_DIRECTION -> "故事方向"
                                        DecisionType.WRITING_STYLE -> "文风表达"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = palette.text,
                                    modifier = Modifier.weight(1f),
                                )
                                Tag(statusLabel(p.outcome.name), palette.accent)
                                Spacer(Modifier.width(6.dp))
                                Text(statusLabel(p.source.name), style = QianyanCodeStyle, color = palette.text3)
                            }
                            HLine()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionLabel("说明")
                    Text(
                        "策略由后端一次性产生并随 Checkpoint 持久化（FD-4）；UI 只做展示，不参与决策、不重算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text3,
                        fontWeight = FontWeight.Normal,
                    )
                    SourceNote("来源：decisionModelUseCases.decide(authorContext, types)（只读）")
                }
            }
        }
    }
}
