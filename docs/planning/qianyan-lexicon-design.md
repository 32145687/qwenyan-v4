# Qianyan 词库系统设计（术语库 + 风格词库）

> **版本**: v0.1-draft
> **日期**: 2026-08-16
> **状态**: 设计稿（待审阅）
>
> **架构约束（不可违反）**：本文档为**增强设计**，不修改 [qianyan-master-plan.md](qianyan-master-plan.md)（V4.1，冻结）的任何既有决策；**不新增 Agent、不重新设计系统**。所有落点均复用冻结架构中已有的模型、枚举与检查项；若确需新增枚举值或检查项分类，必须标记 `[IMPLEMENTATION ISSUE]` 并交由用户批准。
>
> **图例**: ✅ 复用以有机制 | 🆕 新增实现层细节（不改架构）| ⚠️ 需用户决定 | 🔮 Future

---

# 1. 目标与边界

## 1.1 目标

1. **术语一致性**：创作过程中人名/地名/功法/组织等专有名词**收敛到规范词**，避免"同一概念换着叫"（别称漂移）。
2. **风格收敛**：创作文本的**用词域、称谓、语感**符合项目设定风格，减少"风格忽左忽右"。

## 1.2 边界（明确不做）

- ❌ 不新增 Agent：只增强现有 Writer / Critic / Context Engine 的输入输出细节。
- ❌ 不修改 V4.1 分层与角色分离。
- 🔮 好词好句库（词藻库）不在第一版：单独使用易堆砌、占预算，须绑定"使用规则 + 密度控制"才有价值，归 Future（§6）。

---

# 2. 设计原则

```
P1 复用现有机制：词库不是新系统，而是现有 Knowledge / Context / Writer / Critic 的"输入素材"。
P2 走既有生命周期：术语与风格词条一律经 Knowledge Lifecycle 入库（Validator → FactLevel → 确认）。
P3 检索走 Context Engine：词库不单独注入，按相关性经 Context Ranking 进入 prompt（受 Token Budget 约束）。
P4 不新增枚举：术语归 KnowledgeCategory.WORLD_RULE 范畴，风格归 STYLE_PROFILE 范畴（均为已有枚举）。
P5 Critic 复用检查项：术语漂移归 KNOWLEDGE_CONSISTENCY，风格偏离归 QUALITY / PACING（均为已有 IssueCategory）。
P6 可关闭/可配置：规则强度可调，避免强规则扼杀创作。
```

---

# 3. 术语库（Term Glossary）

## 3.1 定位

管理项目内专有名词的**规范词 / 别名 / 分类**，作为 Writer 的"用词规范"与 Critic 的"漂移检查基准"。

## 3.2 数据模型（设计示意，非最终实现代码）

```kotlin
// 🆕 实现层模型：挂接在 Knowledge / StoryStore 之下，不新增顶层概念
data class TermEntry(
    val termId: ID,
    val novelId: NovelId,
    val term: String,                  // 规范词，如 "青云门"
    val aliases: List<String>,         // 别名，如 ["青云派", "青云宗"]
    val category: TermCategory,        // PERSON / PLACE / TECHNIQUE / FACTION / ITEM / CONCEPT
    val definition: String,            // 术语含义（可选）
    val relatedKnowledgeId: KnowledgeId?, // 关联既有知识条目（可选，复用 KNOWLEDGE）
    val factLevel: FactLevel,          // ✅ 复用 §2.1
    val status: KnowledgeStatus,       // ✅ 复用 §2.1（DRAFT/ACTIVE/SUPERSEDED/...）
    val confidence: Float
)

enum class TermCategory { PERSON, PLACE, TECHNIQUE, FACTION, ITEM, CONCEPT }
```

> 存储：术语条目作为 `WORLD_RULE` 类知识的一种细目（`knowledge_type = TERM`），写入 KnowledgeStore / StoryStore，**不新建独立顶层存储**（实现组织细节，P6 决定）。

## 3.3 来源与生命周期（✅ 复用 §3 Knowledge Lifecycle）

```
来源:
1. AnalysisPipeline 抽取（人物/地点/组织 → 提取规范词+别名）→ INFERRED，进待确认队列
2. 用户手工新增/编辑 → USER_CREATED
3. 用户对 INFERRED 确认 → USER_CONFIRMED（别名一并生效）
4. 用户批注反馈（把正文中的"非规范词"标为别名）→ 增量学习
```

- 每个术语条目的别名可多条；**别名收敛**：写作时 alias 一律映射为 term。
- 同一概念出现两个规范词（如"青云门" vs "青云宗"都被确认）→ 走现有 Conflict Detection（§13），由用户裁决保留哪个。

## 3.4 检索（🆕 ContextType 复用 WORLD_RULES）

- Context Engine 在构建上下文时，**按当前场景相关度**（出场人物、所在地点、涉及组织）收集术语表。
- 默认优先级：当前场景直接涉及的人物/地点 > 近 3 章出现过的 > 全局高频术语。
- 受 Token Budget 约束：只带与当前场景相关的术语（典型 10–30 条，不足时只带规范词列表）。

## 3.5 Writer 集成（不改 Agent，只改其输入模板）

- Writer 的 prompt 增加一段"术语规范"：

```
【术语规范】以下术语在正文中必须使用规范词，禁止使用别名：
- 青云门（别名：青云派、青云宗）
- 林默（别名：林师弟）
...
```

- 目的：从生成端主动收敛用词，而不是事后靠 Critic 补救。

## 3.6 Critic 集成（✅ 复用 KNOWLEDGE_CONSISTENCY 检查项）

- 新增检查规则（实现层）：扫描 Draft 文本，命中术语 `aliases` 但未用 `term` → 产出 `CritiqueIssue(category=KNOWLEDGE_CONSISTENCY, severity=MINOR/WARNING)`。
- 若用户**明确要求某别名在此处使用**（对话称呼"林师弟"是合理语境）→ 允许在 ChapterPlan.constraints / 用户批注中豁免该处（P6 规则：豁免机制）。
- 严重度可配置（P5 原则）：默认 WARNING，不阻塞；可调为 ERROR。

---

# 4. 风格词库（Style Lexicon）

## 4.1 定位

把"风格"从一段摘要（原架构仅 `STYLE_PROFILE` 摘要）**结构化为可操作的约束 + 限用词域 + few-shot 示范**，让 Writer 的风格收敛可执行、可检查。

## 4.2 数据模型（🆕 结构化 StyleProfile，挂接既有 STYLE_PROFILE 上下文）

```kotlin
// 🆕 实现层：将架构中"Style Profile 摘要"扩展为结构化对象（不改架构，只细化其内容形态）
data class StyleProfile(
    val styleId: ID,
    val novelId: NovelId,
    val name: String,                  // 风格名，如 "冷峻武侠"
    val description: String,           // 原摘要（保留）
    val pacing: PacingProfile?,        // ✅ 复用 §10.6
    val sentenceLength: SentenceLevel, // SHORT / MEDIUM / LONG / MIXED
    val adjectiveDensity: DensityLevel,// LOW / MEDIUM / HIGH
    val dialogueRatio: FloatRange,     // 对话占比区间
    val lexiconRefs: List<StyleLexiconRef>, // 限用词域引用
    val examples: List<StyleExample>   // few-shot 示范文段（1-3 段）
)

data class StyleLexiconEntry(
    val lexiconId: ID,
    val styleId: ID,
    val scope: LexiconScope,           // 用词域 / 称谓 / 意象 / 语气词
    val allowedTerms: List<String>,    // 可用词（限定域）
    val forbiddenTerms: List<String>,  // 禁用词（越界）
    val weight: Float                  // 该约束强度
)
```

> 原则 P4：风格词库整体挂接在既有 `ContextType.STYLE_PROFILE` 下，不新增 ContextType 枚举。

## 4.3 检索

- `ContextType.STYLE_PROFILE` 在构建 Writer/Planner 上下文时默认带上（已在架构 §11.1 的固定预算 300 token 内，改为按需携带结构化风格约束 + 1 段示例）。
- 示例文段计入预算；仅当预算充足时携带，否则只带结构化约束（降级策略，见 ISSUE-3 语境）。

## 4.4 Writer 集成（🆕 few-shot + 用词域约束）

- Writer prompt 追加：

```
【风格规范】
- 句子偏短，形容词克制；对话占比约 30%–40%。
- 用词域：可使用「山、剑、霜、铁、夜」等冷峻意象词。
- 禁用词：避免「璀璨、流光溢彩、晶莹」等华丽堆砌词。
【风格示范】(示例文段 1 段)
...
```

- 目的：给出"该风格长什么样"（示范）+ "该用/禁用哪些词"（约束），比孤立词库有效（对应 §创作能力评价）。

## 4.5 Critic 集成（✅ 复用 QUALITY / PACING 检查项）

- 检查规则（实现层，默认宽松）：
  - 命中 `forbiddenTerms` → `CritiqueIssue(category=QUALITY, severity=INFO/WARNING)`；
  - 句子平均长度 / 形容词密度 / 对话占比偏离 StyleProfile 区间超过阈值 → `CritiqueIssue(category=QUALITY 或 PACING, severity=INFO/WARNING)`。
- 所有阈值可配置，第一版默认仅 INFO（不阻塞），避免扼杀创作（P5/P6 原则）。

---

# 5. 与 V4.1 架构的对应关系（不改架构的论证）

| 落点 | 架构依据（冻结） | 处理 | 是否改架构 |
|------|----------------|------|:---:|
| 术语存储 | §2.1 KnowledgeCategory.WORLD_RULE；§27.2 Knowledge/Story Store | 作为 WORLD_RULE 细目，无新枚举 | 否 |
| 术语生命周期 | §3 Knowledge Lifecycle | 完全复用 | 否 |
| 术语冲突 | §13 Conflict Detection | 完全复用 | 否 |
| 术语检索 | §11 ContextType.WORLD_RULES | 复用该枚举 | 否 |
| 风格约束/示例 | §11 ContextType.STYLE_PROFILE | 复用该枚举，细化内容形态 | 否 |
| 节奏约束 | §10.6 PacingProfile | 复用 | 否 |
| Critic 术语漂移 | §21.5 IssueCategory.KNOWLEDGE_CONSISTENCY | 复用该分类 | 否 |
| Critic 风格偏离 | §21.5 IssueCategory.QUALITY / PACING | 复用该分类 | 否 |
| 术语/风格维护 UI | §14 User Confirmation + §15 Android 计划 | 走待确认队列 + 项目设置 | 否 |

> 结论：本设计**不修改任何既有架构决策、不新增 Agent、不新增顶层概念**。所有"🆕"仅指实现层的字段细化与检查规则，可在不触碰冻结文档的前提下落地。

---

# 6. MVP / Future 划分

## 6.1 第一版必须实现（✅）

| 项 | 说明 |
|----|------|
| 术语库 | 抽取（INFERRED）+ 确认 + 别名收敛 + Writer 约束 + Critic 漂移检查（WARNING 级） |
| 术语豁免 | 语境合理处（对话称呼）可豁免，经 constraints/批注 |
| 风格约束（结构化）| 用词域 + 禁用词 + 句长/形容词密度/对话占比约束 |
| 风格示范 | StyleProfile 携带 1 段 few-shot 示例 |
| 规则可配置 | 严重度/阈值可调，默认宽松 |

## 6.2 暂缓 / Future（🔮）

| 项 | 说明 |
|----|------|
| 好词好句库 | 需绑定使用规则 + 密度控制；否则堆砌/占预算 |
| 风格自动学习 | 从用户修改自动提炼 StyleProfile（架构 §31 Future 已有）|
| 跨项目词库复用 | 世界设定库复用（架构 §31 Future 已有）|
| 术语自动合并 | 跨章节别名自动合并建议（依赖更高可信度）|

---

# 7. 落地到实现计划（作为任务，不新增 Phase）

| 阶段 | 新增任务 | 说明 |
|------|---------|------|
| **P6** | T6.6 术语抽取与入库 | AnalysisPipeline 输出术语（INFERRED）→ 待确认队列；TermEntry 落 Knowledge Store |
| **P7** | T7.6 术语/风格上下文 | Context Engine 收集当前场景术语表 + 结构化 StyleProfile（预算内）|
| **P13** | T13.x Writer 术语/风格约束 | Writer prompt 增加术语规范 + 风格约束 + few-shot 示例 |
| **P13/P14** | T13.y Critic 术语漂移 + 风格偏离检查 | 复用 KNOWLEDGE_CONSISTENCY / QUALITY / PACING，默认宽松 |
| **P15** | T15.x 术语/风格维护 UI | 项目设置页：术语列表增删改、风格约束编辑、豁免管理 |

> 说明：上述任务会在实施阶段并入 [qianyan-implementation-plan.md](qianyan-implementation-plan.md) 的对应 Phase（需用户批准后再改该计划）。

---

# 8. 验收标准（Definition of Done）

1. **术语一致性**：导入含别名的小说 → 抽取术语 → 确认后，"继续写下一章"产出正文中别名收敛为规范词；Critic 能对注入的"别称漂移"样本报 `KNOWLEDGE_CONSISTENCY`。
2. **风格收敛**：设置风格约束后，Writer 产出文本中 `forbiddenTerms` 命中率 < 阈值，且句长/密度/对话占比落在配置区间。
3. **不破坏架构**：无新增 Agent、无新增顶层概念、qianyan-master-plan.md 零改动。
4. **可配置**：严重度/阈值可调；豁免机制可用。
5. **测试通过**：术语抽取、别名收敛、漂移检查、风格约束、豁免、可配置性各有 Unit/Integration Test（含 MockProvider 的 E2E 用例）。

---

# 9. 风险与开放问题

| 项 | 说明 | 处理 |
|----|------|------|
| 误报漂移 | 对话语境中别名是合理称呼 | 豁免机制 + 默认 WARNING 不阻塞 |
| 风格约束过强 | 扼杀创作 | 默认 INFO + 阈值可配置 + 可整体关闭 |
| 预算占用 | 术语表+示例占用 token | 按相关性裁剪；示例仅预算充足时携带（降级策略）|
| 术语冲突 | 两个规范词并存 | 复用 Conflict Detection + 用户裁决 |
| ⚠️ 待用户决定 | 是否把上述任务并入实现计划 P6/P7/P13/P15 | 见文末 |

---

*本设计为增强稿，不修改 V4.1 冻结架构；若需新增 ContextType/IssueCategory 枚举等结构性变化，将另行标记 [IMPLEMENTATION ISSUE] 并交由用户批准。*
