# Qianyan｜Phase 4A Context Contract Freeze — Addendum: Typed Payload（Alt A）

> - 性质：`qianyan-phase4a-context-contract-freeze.md` **D2 的 additive 补充**。**原 D2 正文（`be1b755` 已提交）保持原文不变**；本文件不重写、不替换任何既有决策。
> - 依据：Phase 4B.1 阻断报告（2026-10-17，停止条件 §7.1）；本轮为**设计冻结**，**未修改任何业务源码 / 测试 / schema / migration**。
> - 基线：`feature/novel-ide` @ `be1b755`。
> - 修订：2026-10-17 —— 纳入 **J-1…J-4 正式裁决**（Vocabulary=A / 预算=A / Variant=A / Legacy=C），并修正 `FoundationPayload` 规范化字段与"编译期强制"措辞。
> - 修订（契约澄清，2026-10-17）：追加 §J-1.1 / §J-2.1（Vocabulary Source Dataset 与 Model Input Vocabulary Subset 分层）与 §J-6（Inspector 任务上下文来源，**拟议 + 待决策**）；**未修改业务代码或测试**；Phase 4B.1 仍未验收。
> - 术语：`payload` = 类型化结构化载荷（新增）；`content` = 既有渲染文本（**契约不变**）。

---

## A. 阻断与解除路径

Phase 4B.1 阻断的根因（已由真实代码证明）：`ContextItem.content` 是**渲染文本**，而 `PlanningContext` 的对应字段是**结构化数据**；且四类 SourceKind 没有任何目标字段。Alt A 的解除条件是：

1. `payload` 在 **Source 读取同一领域对象的同一处**与服务内容一并构造（不允许事后反解析 `content`）；
2. `PlanningContextAssembly` 对 pack 覆盖字段**只投影 payload**，不再读相应 Repository；
3. `payload` 进入 `packVersion` 指纹；
4. `content` 文本格式与既有消费者行为**完全不变**。

本 addendum 冻结上述 1–4 的设计。审计发现的 `VOCABULARY` 结构性问题已由 **J-1 裁决（= A：改为读取已确认 `VocabularyEntry`）** 处理；实施前必须按 §J-1 证明与既有 Prompt 消费集合**内容等价**。

---

## B. 审计：结构化数据在哪里被丢弃

### B.1 逐 SourceKind 证据表

| SourceKind | 读取 API（file:line） | 读取时持有的领域对象 | 压平为 `content` 的位置 | 可由同一对象构造 payload？ | `PlanningContext` 目标字段 | `content` 的其他消费者 | 对选择/排序/预算的影响 |
|---|---|---|---|---|---|---|---|
| PROJECT | `projects.projectOf`（`ContextSources.kt:76`） | `Project`（含 novelId） | `:81` | 无目标字段，不需要 | 无 | `WorkspaceProjections.preview(:74)`、`ContextPackVersion(:273-278)`、测试 | 无（新增字段不参与 selector） |
| PROJECT_STATE | `projects.state`（`:99`） | `ProjectState` | `:104-106` | 无目标字段，不需要 | 无 | 同上 | 无 |
| NOVEL | `novels.getNovel`（`:124`） | `Novel(title, genre: List<String>, synopsis, status, updatedAt)` | `:131-132` | **是** | `novelTitle` / `novelGenre` / `novelSynopsis`（`PlanningContext.kt:37-39`） | 同上 | 无（itemId/优先级/refKey 不变） |
| CHAPTER | `chapters.listByNovel`（`:155`） | `List<Chapter>` | `:173` | 无目标字段，不需要 | 无（`sourceChapter` 属续篇来源，由 `ContinuationResolver` 解析，非本来源） | 同上 | 无 |
| DRAFT | `chapters.findById` + `writer.latestDraft`（`:199-202`） | `Draft`（正文原文） | `:207` | 无目标字段，不需要 | 无（`sourceFinalDraft` 属续篇来源） | 同上 | 无 |
| STORY_FOUNDATION | `foundations.getStoryFoundation`（`:230`） | `StoryFoundation(genre: List<GenreId>, direction, audience, policy, version)` | `:236-239` | **是** | `foundation: ConfirmedStoryFoundationContext`（`PlanningContext.kt:75-79`，字段逐一对应） | 同上 | 无 |
| VOCABULARY | `vocabularies.findCandidatesByNovel`（`:257`） | `List<VocabularyCandidate>`（`suggested: VocabularyEntry`） | `:265-271` | **J-1=A：改为读取已确认 `VocabularyEntry`（须内容等价）** | `vocabulary: List<VocabularyLite>`（`PlanningContext.kt:47`） | 同上 | 无（数据源按 J-1 调整） |

### B.2 三类重点来源的逐项证明

#### B.2.1 NOVEL — 可无损构造

- 结构化对象在 `ContextSources.kt:124` 取得（`Novel`），随后**同一函数内**在 `:131-132` 压平为文本：
  `"《${novel.title}》 genre=${novel.genre.joinToString(",")} status=${novel.status.name} synopsis=${novel.synopsis}"`。
- `Novel.genre` 实际类型为 `List<String>`（`core/model/.../core/NovelModels.kt:34`），与 `PlanningContext.novelGenre: List<String>`（`:38`）一致；`title`/`synopsis` 一一对应。
- 结论：payload 可由 `:124` 的同一 `Novel` 构造；**不需要**改变选择、排序、优先级或截断（payload 只是同一 item 的附加字段）。

#### B.2.2 STORY_FOUNDATION — 可无损构造

- 结构化对象在 `ContextSources.kt:230` 取得（`StoryFoundation`），`:236-239` 压平。
- 目标模型 `ConfirmedStoryFoundationContext`（`PlanningContext.kt:75-79`）字段为：
  `confirmedGenre: List<GenreId>` / `direction: StoryDirection` / `audience: NarrativeProfile` / `writingPolicy: WritingPolicy`；
  与 `StoryFoundation.genre/direction/audience/policy`（`core/model/.../foundation/StoryFoundation.kt:55-58`）**逐字段一致**。
- 结论：payload 可由 `:230` 的同一对象构造，且 `PlanningContextAssembly.kt:89-96` 的映射代码可原位改为从 payload 投影（不再读 Repository）。**注意**：`:236-239` 中的 `theme/conflict/promise/pov/readerTone/rules` 是 **content 渲染模板字段**；`FoundationPayload` 的顶层结构字段仅为 `genre` / `direction` / `audience` / `policy`（§E.2）。

#### B.2.3 VOCABULARY — 原结构性冲突（**J-1 已裁决 = A**）

| 项 | Pack 的 VOCABULARY Source | Assembly 的 `vocabulary` 字段 |
|---|---|---|
| 读取 API | `vocabularies.findCandidatesByNovel(novelId)`（`ContextSources.kt:257`） | `vocabularyRepository.findEntriesByVariant(variantId)` / `findEntriesByNovel(novelId)`（`PlanningContextAssembly.kt:72-76`） |
| 数据模型 | `VocabularyCandidate`（含 `suggested: VocabularyEntry`、`status=PENDING` 等；`core/model/.../vocabulary/...:71-81`） | `VocabularyEntry`（`core/model/.../vocabulary/...:39-50`，含 `status=APPROVED`） |
| 范围/顺序 | 按 `canonical` 排序，`take(candidateHorizon)`（默认 10） | 仓储返回的**全部** entry（无排序、无上限） |
| 语义 | **候选词**（自动抽取/待确认） | **已确认词条** |

⇒ 若直接按现状从 pack 投影 `vocabulary`，prompt 中的词库将从"全部已确认词条"变成"最多 10 条按 canonical 排序的候选词"——违反前置裁决 #7 与 4C NR-1。

**J-1 正式裁决（2026-10-17）：Vocabulary = A。** VOCABULARY Source 改为读取**与 `PlanningContextAssembly` 相同的已确认 `VocabularyEntry` 集合**，使 pack 与 prompt 消费同一数据；实施前必须完成 §J-1 的三查询核查并证明**内容等价**。本 addendum **只记录判据与核查项**，不提前声称 `findEntriesByVariant` / `findEntriesByNovel` / `findCandidatesByNovel` 语义相同。

### B.3 审计问题逐项回答

1. **从哪个 Repository/领域对象读取**：见 B.1 第 2 列。
2. **是否仍持有结构化对象**：是（各 Source 在构造 `content` 前均持有领域对象；`content` 在 `contextItem(...)` 调用处压平）。
3. **在哪一步丢弃**：在 `contextItem(source, refKey, content = <字符串模板>, …)` 的参数求值处（`ContextSources.kt:81/104/131/173/207/236/265`）；此后 item 只保留文本。
4. **能否从现有对象构造 payload**：NOVEL / STORY_FOUNDATION 可以（字段逐一对应）；VOCABULARY 可以（**J-1=A：改用已确认 entry**，须证明内容等价）；其余四类无目标字段。
5. **若不能，改哪个 Source/接口**：只需新增 payload 字段与三个工厂（不改读取 API）；**VOCABULARY 按 J-1 = A** 需把 `VocabularySource` 的读取 API 改为 `findEntriesBy…`（已确认 entry），并先按 §J-1 完成三查询核查与内容等价证明。
6. **其他消费者是否依赖 content 文本**：`ContextSelector`（尺寸估算，`ContextPack.kt:223`）、`ContextPackVersion`（指纹，`:273-278`）、`WorkspaceProjections.preview`（Inspector 预览，`WorkspaceProjections.kt:74`）、以及测试对 `content` 的断言（`ContextEngineBuildTest:34/132/139`、`ContextScopeTest:36-37`）——**均不解析 content**，新增 payload 不影响它们。
7. **是否改变候选筛选/排序/优先级/截断/淘汰**：**否**（payload 是同一 item 的附加字段；`itemId`/`source`/`priority`/`refKey`/`sourceVersion`/`content` 均不变；`ContextSelector` 逻辑不变）。

---

## C. 类型化载荷设计（冻结）

### C.1 类型体系（4B.1 新增文件，设计已冻结）

文件：`core/model/src/main/kotlin/com/qianyan/model/context/ContextPayload.kt`

```kotlin
@Serializable
sealed interface ContextPayload

@Serializable
data class NovelPayload(
    val title: String,
    val genre: List<String>,
    val synopsis: String,
) : ContextPayload

@Serializable
data class FoundationPayload(
    val genre: List<GenreId>,
    val direction: StoryDirection,
    val audience: NarrativeProfile,
    val policy: WritingPolicy,
) : ContextPayload

/** J-1 裁决（Vocabulary = A）：VOCABULARY Source 改为读取**已确认 VocabularyEntry**；
 *  payload 字段与目标 `VocabularyLite` 对齐；内容等价判据见 §J-1。 */
@Serializable
data class VocabularyPayload(
    val canonical: String,
    val aliases: List<String>,
    val replacement: String?,
) : ContextPayload
```

约束落实：无 `Any`；无 `Map<String, Any>`；无 JSON 字符串载荷；无通用 payload 桶；不新增 `MEMORY`/`CHARACTER` SourceKind；三个载荷分别对应明确 SourceKind、领域对象与目标字段（§D）。

### C.2 `ContextItem` 的 additive 扩展

```kotlin
data class ContextItem(
    /* 既有字段完全不变 */
    val payload: ContextPayload? = null,
)
```

- 默认 `null` 保持既有构造点与测试编译兼容；受覆盖的 Source **按约定**必须经专用工厂构造（C.3）。**注意：Kotlin 默认参数不会阻止其他代码直接构造无 payload 的 `ContextItem`**，因此这不是编译期保证；消费侧以 `requirePayload` 运行时 fail-fast 防御（C.3）。
- **D2 结构补充（明确）**：原 D2 的四个要素（`source` / `ref`+`sourceVersion` / `content` / `priority`）**全部保留**；本 addendum **只增加一个可空字段** `payload: ContextPayload? = null` ⇒ 条目契约为"**四个既有要素 + 一个可选 payload**"，不是对既有字段的重定义。
- **唯一性不变**：`payload` **不参与** `(source, refKey)` 唯一性判定；同一 `(source, refKey)` 的唯一性规则与 D2 原文一致。
- 原始冻结文件 `qianyan-phase4a-context-contract-freeze.md` **不修改**（D2 原文保持原样）。
- `ContextItem` 当前**不被序列化/持久化**（全仓 main 源集无 ContextPack 的 encode/decode/checkpoint 使用）⇒ 新增 `@Serializable` 字段无存储兼容影响；仅内存对象。

### C.3 Source 生产责任（工厂约定 + 消费侧 `requirePayload` 双重保证）

在 `ContextSources.kt` 新增三个**约定必填 payload** 的专用工厂，原 `contextItem(...)` 继续用于无载荷来源：

```kotlin
internal fun novelItem(novel: Novel, priority: ContextPriority): ContextItem      // content 由既有模板渲染，payload = NovelPayload(...)
internal fun foundationItem(foundation: StoryFoundation, priority: ContextPriority): ContextItem
internal fun vocabularyItem(entry: VocabularyEntry, priority: ContextPriority): ContextItem // J-1：读取已确认 entry
```

- 三个工厂内部**同时**从同一对象生成 `content` 与 `payload`；禁止在别处单独构造其中任一。
- **术语准确性**：专用工厂是规定的正式构造路径；`requirePayload` 是消费侧运行时 fail-fast 防御。二者合起来是 **"工厂约定 + 消费侧防御"**，**不是 Kotlin 类型系统提供的编译期强制**（`ContextItem.payload` 有默认 `null`，其他代码仍可直接构造无 payload 的 item）。不新增第二套内容解析器，不改变既有 `content` 含义。
- 类型不匹配检测（消费侧）：`PlanningContextAssembly` 使用
  `fun <T : ContextPayload> ContextItem.requirePayload(expected: KClass<T>, kind: ContextSourceKind): T`，
  缺失/类型不符 → `ApplicationException(ApplicationError.InvalidOperation("Context payload 缺失或类型不匹配: itemId=..., kind=..., expected=..."))`（fail-fast，沿用项目既有 `InvalidOperation` 风格；**不新增错误码**）。
- 不提供 `Any`/反射式解析；不读取 `content` 反推结构。

---

## D. 映射表（SourceKind → Payload → PlanningContext）

| SourceKind | 原始来源（代码位置） | `content` 是否保留 | Payload 类型 | `PlanningContext` 目标 | 其他消费者 |
|---|---|---|---|---|---|
| NOVEL | `Novel`（`ContextSources.kt:124`） | 是（文本格式不变） | `NovelPayload` | `novelTitle` / `novelGenre` / `novelSynopsis` | Inspector 预览、指纹、测试（不解析文本） |
| STORY_FOUNDATION | `StoryFoundation`（`:230`） | 是 | `FoundationPayload` | `foundation`（`confirmedGenre/direction/audience/writingPolicy`） | 同上 |
| VOCABULARY | `VocabularyCandidate`（`:257`） | 是 | `VocabularyPayload`（J-1=A；须内容等价） | `vocabulary`（等价判据见 §J-1） | 同上 |
| PROJECT | `Project`（`:76`） | 是 | 无 | 当前无对应字段 | 同上 |
| PROJECT_STATE | `ProjectState`（`:99`） | 是 | 无 | 当前无对应字段 | 同上 |
| CHAPTER | `List<Chapter>`（`:155`） | 是 | 无 | 当前无对应字段 | 同上 |
| DRAFT | `Draft`（`:202`） | 是 | 无 | 当前无对应字段 | 同上 |

**无字段来源的处理**：不新增字段、不塞入错误字段、不删除既有条目（前置裁决与 §七 要求）。它们继续只作为 pack 条目供 Inspector/审计使用，不进入 Planner/Writer prompt（与今天行为一致）。

---

## E. `packVersion` 纳入 payload（冻结）

### E.1 决策

**payload 必须参与 `packVersion` 指纹**。理由：`PlanningContextAssembly` 消费 payload；仅 payload 变化时实际 Target Context 已不同，若指纹不变则破坏版本可复现性（D4）。

### E.2 规范化方案（确定、稳定、无歧义）

在 `ContextPackVersion.of(...)` 的条目循环中，**在既有 `content` 之后**追加 payload 规范串：

```text
既有： itemId # source # priority # sourceVersion # content ;
新增： itemId # source # priority # sourceVersion # content # <payloadCanonical> ;
```

`payloadCanonical` 由显式编码器生成（**不依赖 `toString()` / 对象地址 / 哈希迭代顺序 / 默认序列化**）。规范化契约版本：**canonical-v1**——任何编码规则变更都必须升级契约版本并记录为 `packVersion` 语义变更，不得静默改编码。

**基本编码单元（全部以 UTF-8 字节为准）**

```text
字符串 S(x)        = <decLen> ":" <utf8(x)>
                     // decLen = UTF-8 字节数（不是 Kotlin String.length）
                     // 十进制、无前导零（0 除外）；":" 后精确跟随 decLen 字节
null（仅可空字段） = "N"                    // 与空串 "0:"、空列表 "A0:" 均不同
列表 A(xs)         = "A" <count> ":" <elem1><elem2>...<elemCount>
                     // count = 十进制元素数；元素按声明顺序；空列表 = "A0:"
                     // 当前模型不允许 null 列表元素；若未来允许，须显式定义其编码
模型 M             = <TAG> <field1><field2>...
                     // 字段数量与类型由 TAG 固定；字段按 Kotlin 声明顺序
```

**类型标记与字段顺序（以项目现有模型声明为准）**

```text
ContextPayload? == null   → "P0"                          // 无字段
NovelPayload              → "PN" S(title) A(genre) S(synopsis)
FoundationPayload         → "PF" A(genre.map { it.value }) SD NP WP
StoryDirection            → "SD" S(theme) S(conflict) S(promise) S(storyType)
NarrativeProfile          → "NP" S(pov) S(readerTone)
WritingPolicy             → "WP" A(rules)
VocabularyPayload         → "PV" S(canonical) A(aliases) <replacement>
```

> **FoundationPayload 的顶层结构字段只有 `genre` / `direction` / `audience` / `policy`**（与 `StoryFoundation` 的真实字段一致）。`direction` / `audience` / `policy` 使用上表的嵌套 TAG（`SD` / `NP` / `WP`）递归编码；其内部字段名（`theme` / `conflict` / `promise` / `storyType` / `pov` / `readerTone` / `rules`）属于**嵌套模型自身**，不是 `FoundationPayload` 的顶层字段，也**不得**从 `content` 渲染模板反解析得到。

**Replacement 三态唯一编码（B1）**

```text
replacement == null                  → "0:"
replacement != null && isEmpty()     → "1:" S("")        // 即 "1:0:"
replacement != null && isNotEmpty()  → "1:" S(value)     // 即 "1:<decLen>:<utf8>"
```

- null 与空串编码不同（`0:` vs `1:0:`）；空串与非空串不同（`1:0:` vs `1:<decLen>:...`）；
- 标记、长度与内容的边界由 `S` 的字节长度规则唯一确定，无歧义。

**嵌套模型递归规则（B2）**

> 所有嵌套模型必须递归应用同一套规范化规则：每个嵌套模型使用自己的稳定类型标记，字段按 Kotlin 数据模型声明顺序编码；其内部列表使用同样的"元素数量 + 逐元素编码"规则；嵌套模型之间、字段之间的边界由 TAG + 固定字段序列 + 自定界编码共同确定；任何更深层的嵌套继续递归应用本规则。**不允许对任何嵌套模型使用 `toString()` 或调试文本作为规范化表示。**

**唯一性要求**

- 相同 payload（相同字段值、null/空状态、列表结构与顺序、嵌套结构）在 **canonical-v1** 下必须产生相同字节序列；
- 不同 payload 不得因拼接边界不清产生相同字节序列：TAG 区分类型；`S` 的字节长度前缀自定界；`A` 的元素数量定界；模型字段数由 TAG 固定；`N` / `0:` / `A0:` 编码互异；
- `packVersion` 必须基于该规范化字节序列计算（而非解析 `content` 的结果）；
- **禁止**从 `ContextItem.content` 反解析/重建 payload；**禁止**把调试文本或 `toString()` 作为指纹输入。

**内容与分隔符安全（S3）**：内容即使包含冒号、`|`、`#`、`;`、换行、Unicode 或类似标记，也不得破坏唯一性——所有字符串均以 UTF-8 字节长度前缀包裹；**不得**依赖未转义分隔符直接拼接字段。

- 列表保持**声明顺序**（来源侧已确定性排序：NOVEL 单条；Foundation genre/rules 原序；Vocabulary 按 canonical 排序）；
- 相同 request + 候选 + content + payload + 淘汰集合 ⇒ 相同版本；**仅 payload 变化 ⇒ 版本必须变化**；
- payload 编码**不改变** `content` 文本。

### E.3 影响分析（不声称"无影响"）

| 影响面 | 事实 | 结论 |
|---|---|---|
| 所有 `packVersion` / `packId` | 新增 payload 段 ⇒ 即使 payload 为 null 也追加 `P0`，版本值整体改变 | 属**预期变更**；`packId` 含版本，故同步改变 |
| 既有测试断言 | 全仓无字面量版本常量；现有断言均为"同输入同版本/不同输入不同版本/前缀 `ctx-`"（`ContextEngineBuildTest`、`ContextEngineRequestTest:35`、`WorkspaceInspectorContextTest:29/105`、`NovelAgentRoutingTest:164`） | 预期**断言语义不破**；4B.1 必须重跑确认 |
| `isStale` | 重编比对逻辑不变；payload 变化会正确判为过期 | 语义增强，无额外接线（前置裁决：本阶段不接线） |
| 持久化 | `ContextPack` 不落库 | 无迁移、无兼容问题 |

**4B.1 必须新增的专项测试**：① 同输入两次构建 ⇒ 同版本；② 同 content 仅 payload 不同 ⇒ 版本不同；③ 仅 content 不同 ⇒ 版本不同；④ null payload 稳定（同输入同版本）；⑤ **payload 本身不影响 selector**（J-2 的两阶段预算规则是独立变更，按 AC-31/32 单独验收）。

---

## F. 错误处理策略（冻结）

| 场景 | 策略 |
|---|---|
| 必需 payload 缺失（covered 来源产出的 item 没有 payload） | **编译期**由专用工厂强制（无法构造无 payload 的 covered item）；消费侧再以 `requirePayload` 防御，缺失 → `InvalidOperation` fail-fast |
| payload 与 SourceKind 不匹配 | `requirePayload` 类型检查失败 → `InvalidOperation`（消息含 itemId/kind/expected/actual），**不降级** |
| 结构化数据不完整 | Source 侧：领域对象不可用 ⇒ 不产出该 item（与现状一致）；已产出 item 的 payload 必须完整（工厂签名强制） |
| Source 不能提供可靠结构化数据 | 不产出 item；不得生成"部分 payload" |
| payload 与 content 语义不一致 | **构造不变量**：二者在同一工厂、同一对象上一次性生成；不做运行时文本解析检测；4B.1 增加"同源字段一致性"测试（如 novel.title 同时出现在 content 与 payload，且 prompt 取自 payload） |

**禁止**：静默降级为解析 `content`；用 `payload = null` 默认参数偷偷回退到旧装配路径；新增错误码（复用 `ApplicationError.InvalidOperation`）。

---

## G. 兼容边界与影响

| 边界 | 结论 |
|---|---|
| `content` 文本 | **不变**（模板、字段顺序、分隔符全部保持；新增 payload 不重写文本） |
| 候选筛选/优先级/排序/截断/淘汰 | **payload 本身不改变**（itemId/refKey/priority/sourceVersion 不变；payload 不参与 selector 打分）；但 **J-2 会独立改变预算不足时的入选保障与 omission 顺序**（关键上下文优先），该行为变化见 §J-2 与 AC-31/32 |
| Inspector / Workspace 投影 | **不变**（`preview(item.content)` 继续读文本） |
| 持久化 / Schema / Migration | **无**（ContextPack 不序列化；无新表） |
| Planner/Writer 渲染代码 | **不变**（继续读 `PlanningContext` 的结构化字段；字段值改为由 payload 投影） |
| `characters` / `memories` | **不变**（Resolver 兼容来源，见 D3；不进 payload、不进 SourceKind） |
| `variantName` / `variantDirective` | **不在 pack 覆盖范围**（`ContextSources` 无 VARIANT 来源；来自 `NovelRepository.getVariant`）⇒ 4B.1 中 Assembly 可继续读取，但须在报告中标注其为"未覆盖字段" |
| `authorContext` | **不在 pack 覆盖范围**（AuthorContextProjection）；保持现状 |
| `worldContext` | 保持 Resolver 投影；其中 `worldSummary` 当前取自 `novel.title/synopsis`（`PlanningContextAssembly.kt:84`），4B.1 应改用 payload 的 title/synopsis，避免第二份 Novel 数据 |

---

## H. 对 Phase 4B.1 的指导（实施清单，本轮不执行）

0. **实施前必查（J-1）**：逐一核查 `findEntriesByVariant` / `findEntriesByNovel` / `findCandidatesByNovel` 的 scope 过滤、状态过滤（approved/pending）、返回类型与字段映射、数据来源、排序/截断，并证明 VOCABULARY 集合与当前 Assembly 输出**内容等价**；不能证明则停止报告。
1. 新增 `ContextPayload.kt`（§C.1）；`ContextItem.payload` 加默认 null。
2. `ContextSources`：NOVEL / STORY_FOUNDATION 改用专用工厂（content 不变 + payload）；**VOCABULARY 按 J-1 = A 改为读取已确认 `VocabularyEntry`**（content 文本模板保持，payload 为 entry 字段）。
3. `ContextPackVersion`：追加 payload 规范串（§E.2；FoundationPayload 仅用真实字段）。
4. `NovelAgent`：`buildContext` 的结果沿 `executeSkill(..., pack)` → `generateNewContent` / `planChapter` 传递**同一个 Pack 对象**（不得只传 id/version）；**在调用 `ContextEngine` 之前**按 J-3 做 Variant fail-fast 校验。
5. `PlanningContextAssembly.assemble(request, pack, continuation, resolved)`：NOVEL / STORY_FOUNDATION / VOCABULARY 从 payload 投影；**删除**对相应 Repository 的读取（Title/Genre/Synopsis、Foundation、Vocabulary entry）；`characters/memories/variant 元数据/authorContext/continuation` 保持既有来源。**按 J-4 保留无 pack 重载并标记 `@Deprecated`**（其内部实现留待 4B.1 确认，见 §J-4）。
6. `ContextSelector` 按 J-2 实现**两阶段选择**（关键上下文 NOVEL / STORY_FOUNDATION / VOCABULARY 先选，普通上下文后选；最终 items 仍按既有优先级顺序输出；预算不足行为见 §J-2），并明确记录该**行为变化**与验收标准；默认 budget/estimator 不变。
7. 测试：AC-06 / AC-07（marker 进入 Planner 与 Writer 的真实请求文本）+ payload 指纹专项（§E.3）+ J-1 内容等价 + J-2 预算边界/长 DRAFT/重复执行 + J-3 一致/不一致 + J-4 静态守卫 + 无双重注入 + 兼容字段不丢失。
8. 禁止：去重、`isStale` 接线、Memory 迁移、索引扩展、UI 改动、新表。

---

## I. 保持不变 / 由本 addendum 补充

**保持不变**（不重开）：
- D1：不引入独立 `ContextSnapshot`；`ContextPack` 为唯一运行时载体。
- D2：条目四要素（source/ref+sourceVersion/content/priority）**全部保留**、唯一性按 `(source, refKey)`（**payload 不参与**）、7 类 SourceKind、`content` 文本语义；本 addendum 仅**追加一个可选 `payload`**（"四个既有要素 + 一个可选 payload"），不重定义既有字段。
- D3：Memory 边界（独立事实源；非索引类型；非 ContextPack 来源；经 Resolver 进 prompt）。
- D4：`packVersion` 语义（本 addendum **追加** payload 输入）；`isStale` 仍不接线。
- B1 调用链与四项前置裁决；Phase 3 全部决策。
- 负向约束：无 FTS5 / embedding / vector / RAG / 云依赖；无新 Storage 表。

**由本 addendum 补充**：
- 类型化 payload 类型体系与 `ContextItem.payload` 字段；
- Source 的双产出责任（content + payload）与 **"工厂约定 + 消费侧 `requirePayload` 双重保证"**（非编译期强制）；
- payload 进入 `packVersion` 的规范化规则（FoundationPayload 仅使用真实结构字段）；
- 消费侧类型检查与 fail-fast 错误策略；
- **J-1**：VOCABULARY 与既有已确认 entry 集合内容等价（含实施前核查项）；
- **J-2**：关键上下文（NOVEL / STORY_FOUNDATION / VOCABULARY）的确定性入选保障与预算不足行为；
- **J-3**：Variant fail-fast 校验（ContextEngine 调用前）；
- **J-4**：legacy 无 pack 重载的受控豁免（deprecated + 静态守卫；内部实现留待 4B.1 确认）；
- 对 4B.1 的实施约束。

---

## J. 正式裁决与实施前核查（2026-10-17）

> 本节把原「未决风险」转为正式裁决：**J-1 = A、J-2 = A、J-3 = A、J-4 = C（受控豁免）**。本轮只记录裁决与核查要求，不实现任何代码。

### J-1 · Vocabulary = A（与既有已批准集合内容等价）

**裁决**：`VOCABULARY` Source 改为读取**与 `PlanningContextAssembly` 相同的已确认 `VocabularyEntry` 集合**（不再使用 candidate）。

**4B.1 开始编码前必须逐项核查**（本轮不修改 Repository、不声称三者语义相同）：

1. `findEntriesByVariant`
2. `findEntriesByNovel`
3. `findCandidatesByNovel`

核查维度：novel / variant 筛选范围；approved / pending 等状态过滤；返回类型与字段映射（canonical / aliases / replacement）；数据来源；排序与截断规则；与当前 `PlanningContextAssembly` 实际传给 Planner / Writer 的 vocabulary 集合之间的差异。

**内容等价判据（验收）**：

> 统一后的 `VocabularyEntry` 集合，与当前 `PlanningContextAssembly` 实际传给 Planner / Writer 的 vocabulary 集合，在**内容上等价**：属于同一批已确认词条，且对应字段值一致。

- **允许差异**：排序顺序不同（但必须确定性）；截断阈值不同（但必须在 J-2 预算契约中明确记录并通过验收测试）。
- **不允许差异**：已确认词条数量减少；已确认词条被 pending 或其他未批准状态替代；同一词条字段值变化；因 novel / variant 查询范围不同而静默遗漏；无明确契约依据改变 Planner / Writer 实际获得的词条集合。
- 若无法证明内容等价：**停止并报告具体差异**；不得选择较小集合、不得静默接受 Prompt 能力回退、不得修改 Prompt 文本掩盖差异。

#### J-1.1 Vocabulary Source Dataset 与 Model Input Vocabulary Subset（2026-10-17 追加澄清）

> **状态**：拟议追加澄清（待用户确认后生效）；不删除、不替换上文 J-1 的裁决、允许差异与不允许差异。
> **目的**：明确“完整来源数据集”与“经过 ContextSelector 预算选择后的模型输入集合”是两个不同层次；两者不能互相证明，也不能混用“词库等价”一词。

**层次 1：Vocabulary Source Dataset（完整来源数据集）**

定义：在明确 novel / variant 查询范围、确认状态、字段映射与数据来源的前提下，从既有 `VocabularyEntry` 读取的完整已确认词条集合；它是 Context Source 的输入，不经过 `ContextSelector` 的 token budget。

契约：

1. 复用现有 `VocabularyEntry`，不新增第二套词库模型。
2. 数据源集合不因 `ContextSelector` 的 token budget 而改变；预算只影响模型输入子集。
3. 不得静默改变 `canonical` / `aliases` / `replacement`。
4. 待确认 `VocabularyCandidate` 不得替代正式 `VocabularyEntry`；未批准条目不得因查询路径变化混入正式来源数据集。
5. 查询范围、确认状态、字段映射、数据来源、排序规则必须有明确契约；排序差异必须是确定性的。
6. 历史 `PlanningContextAssembly` 路径与新 ContextPack 路径若存在差异，必须明确比较的是哪一层：来源数据集、展示顺序，还是预算后的模型输入；不得笼统称为“词库等价”。

**层次 2：Model Input Vocabulary Subset（模型输入词条子集）**

定义：完整来源数据集进入 `ContextSelector` 后，按 J-2 的两阶段预算规则实际入选，并进入 `ContextPack` payload（进而进入 Planner / Writer prompt）的词条集合。

契约：见 §J-2.1；本层允许受控、确定性的省略，且不改变来源数据集本身。

**三个不能混同的问题**

| 问题 | 所属层次 | 允许差异 |
|---|---|---|
| 完整来源数据集是否等价 | Vocabulary Source Dataset | 仅确定性排序；不得数量减少或字段变化 |
| 来源数据集的展示 / 排序是否一致 | Vocabulary Source Dataset → ContextPack 条目顺序 | 确定性排序（J-1 原允许差异） |
| 经过预算选择后的模型输入是否等价 | Model Input Vocabulary Subset | J-2 预算截断；不要求与旧路径最终集合完全相同 |

**对“不允许差异：已确认词条数量减少”的分层澄清**

- 在 **Vocabulary Source Dataset** 层成立：数据源集合不得因切换查询路径、预算或其他机制而减少；不得用“预算”作为数据源漏读的解释。
- 在 **Model Input Vocabulary Subset** 层不成立：模型输入受 J-2 预算规则约束，允许确定性截断；被省略条目仍属于来源数据集，不表示来源数据丢失。
- 因此 J-1 与 J-2 不冲突：**J-1 保证来源数据集完整，J-2 保证模型输入选择确定性**；两者作用于不同层。
- 本节的分层解释优先于上文 J-1 中未分层的“实际传给 Planner / Writer 的集合”表述；原文保留用于历史追溯，不得再用该句同时证明来源数据集与模型输入子集。

**A/B 比较的层次要求**

- 旧 `assembleLegacy` 与新 `VocabularySource` 的 A/B：只比较 **Vocabulary Source Dataset**（AC-30A）。
- 新来源数据集进入 `ContextSelector` 后的选择与 prompt：比较 **Model Input Vocabulary Subset**（AC-30B）。
- 不得用预算裁剪后的 ContextPack 结果证明来源数据集等价；也不得用来源数据集等价宣称 Planner / Writer 实际输入已等价。

### J-2 · 上下文预算 = A（确定性入选保障）

**关键上下文范围（严格限定）**：`NOVEL` / `STORY_FOUNDATION` / `VOCABULARY`。
`DRAFT` 是正文全文，**不属于**关键保障集合，仍按既有预算规则处理；**不得**设为不可淘汰（否则长正文会挤出其他必要上下文）。

**确定性选择规则（4B.1 实现；本轮只冻结语义）**：

1. 保持既有 budget / estimator 不变，采用**两阶段选择**：
   - **阶段 1（关键上下文）**：按声明顺序 `NOVEL → STORY_FOUNDATION → VOCABULARY` 逐条尝试；同一来源内保持来源产出顺序（VOCABULARY 保持 entry 的确定性排序）。能放入剩余预算 ⇒ 入选；单条超过剩余预算 ⇒ **淘汰该条并继续**（沿用既有"超预算即淘汰继续"规则）。
   - **阶段 2（普通上下文）**：`PROJECT` / `PROJECT_STATE` / `CHAPTER` / `DRAFT` 按既有 `priority.rank` 稳定排序，使用剩余预算选择；规则与现状一致。
2. **最终 `items` 顺序**：仍按既有 comparator（priority rank + 同级声明顺序）输出，不改为"关键优先"排列（保持 prompt 顺序契约）。
3. **关键上下文总量超过预算**：按阶段 1 的确定性顺序"放得下就选、放不下即淘汰并继续"；允许部分关键条目缺失，但结果与顺序**完全确定**；不承诺无限预算，不得静默重排。
4. **`omittedItemIds`**：先记录阶段 1 淘汰的关键条目（关键顺序），再记录阶段 2 淘汰的普通条目（既有顺序）；**去重时保留第一次出现的位置，不得用无序集合重新排序；相同输入顺序必须产生相同 omission 顺序**；该规则只定义顺序稳定性，不改变选择结果。`isTruncated` = 任一淘汰；`estimatedTokens` 语义不变。
5. **确定性**：同一输入重复执行 ⇒ 相同入选、相同顺序、相同 omission。
6. **行为变化（必须记录并验收）**：预算紧张时，关键上下文优先于普通上下文（现状可能被 DRAFT 等高优先级条目挤出）；预算充足时，入选集合与 `items` 顺序与现状一致。**默认 budget / estimator 不变。**
7. **实现注记（设计）**：`ContextSelector` 需要接受"关键 kind 集合"参数（4B.1 实现，本轮不改）。
8. **验收测试（4B.1）**：关键上下文充足/不足；关键总量超预算；长 DRAFT 挤压；关键来源缺失（无 Foundation / 无 Vocabulary）；普通条目竞争；重复执行稳定性；预算充足时与改造前集合/顺序一致。
9. **禁止**：用"提高 priority""增加测试预算""尽量保留关键上下文"声称问题已解决；不得静默修改默认 budget / estimator / 选择算法。

#### J-2.1 Model Input Vocabulary Subset（2026-10-17 追加澄清）

> **状态**：拟议追加澄清（待用户确认后生效）；不删除、不替换上文 J-2 的规则与优先级。

1. **定义**：完整来源数据集进入 `ContextSelector` 后，按 J-2 的阶段 1 / 阶段 2 规则实际入选并进入 `ContextPack` payload 的词条子集；它是 Planner / Writer 实际可见的词库集合。
2. **受保护来源不等于全部保留**：`NOVEL` / `STORY_FOUNDATION` / `VOCABULARY` 受保护只表示它们在阶段 1 先于普通来源尝试；**不保证所有词条进入模型输入**。
3. **预算不足时**：按 J-2 原文规则逐条尝试，“放得下就选、放不下即淘汰并继续”；允许部分关键条目缺失；省略与最终选择必须确定、可复现；省略条目必须通过既有 `omittedItemIds` / `ContextBudgetGuard` 反映。
4. **默认值不变**：本轮不修改默认 `ContextBudget`、token estimator 或选择算法。
5. **与 J-1 的关系**：J-1 约束 Vocabulary Source Dataset 的完整性与字段；J-2 约束 Model Input Vocabulary Subset 的选择。模型输入子集的确定性截断不构成 J-1 的来源数据丢失。
6. **验收**：AC-30B；不得仅用 `ContextSelector` 单元测试宣称 Planner / Writer 实际输入已通过验收。

### J-3 · Variant 范围 = A（ContextEngine 之前 fail-fast）

- **数据来源**：`ChapterRepository.findById(request.activeChapterId)` → `Chapter.variantId`；`ProjectUseCases.projectOf(request.projectId)` → `Project.state.activeVariantId`。
- **时机**：必须在调用 `ContextEngineUseCases.build`（`NovelAgent.buildContext`）**之前**执行，与既有 `requireProject` / `requireFocusChapter` 同层。
- **正常路径**：二者同为 `null`（Original）或为同一 `VariantId`。
- **不一致路径**：`RunAbort(NovelAgentErrorCodes.SCOPE_MISMATCH, …)` 中止任务（复用既有错误码与模式）；**不得**回退到 active Variant、章节 Variant 或其他 Variant 做静默兜底，不得静默归一化。
- **验收测试（4B.1）**：一致路径正常构建；不一致路径在 ContextEngine 调用前中止，且断言未构建 pack / 无状态副作用。
- 本轮只更新文档，不实现校验代码。

### J-4 · Legacy 装配路径 = C（受控豁免）

1. **保留**现有无 Pack 的 `PlanningContextAssembly.assemble(...)` 重载签名。
2. 为该重载添加 `@Deprecated` 与清晰原因（"Agent Task 必须使用 `assemble(request, pack, ...)` 的 B1 链路；本重载不得用于生产"）。
3. 添加**守卫测试/静态检查**：检查 `application/src/main` 下不存在对该无 Pack 重载的调用。**实现方式留待 Phase 4B.1 选择**（源文件扫描 / Gradle task / Detekt / Lint 等），文档阶段不锁定具体技术。**已知局限**：静态守卫只覆盖其覆盖范围内的静态调用，**不能单独证明所有运行时路径绝不会调用**；4B.1 实施报告必须如实记录该局限。
4. 不为当前没有生产调用方的 legacy 路径修改 **7 个测试文件**。
5. 不将无 Pack 重载视为第二条允许使用的正式生产链路。
6. 未来如需恢复相关 legacy 生产路径，必须单独审计并明确迁移方案。
7. **风险记录**：旧重载仍可能被其他代码调用；Kotlin deprecation 只是编译提示，**不能自动禁止所有运行时误用**；静态守卫测试只能覆盖 `application/src/main` 的直接调用，不能单独证明任意运行时路径都不可能调用。当前通过 **deprecation + 守卫测试 + 代码审查** 共同控制风险。
8. **内部实现留待 4B.1 确认（本轮不选）**：实施时必须明确选择并记录：
   - **方案 A**：保留签名，内部直接 fail-fast（如抛 `UnsupportedOperationException`），说明生产必须使用带 pack 的重载；
   - **方案 B**：保留旧实现，明确记录风险与调用约束，并增加适当的运行时告警。

### J-5 · 其他说明（不变）

- `PROJECT` / `PROJECT_STATE` / `CHAPTER` / `DRAFT` 无 payload（无目标字段），其 content 仍仅供 Inspector/审计；这是**设计选择**，不是遗漏。
- content/payload 一致性为**构造不变量**（同一工厂、同一对象一次性生成），不做运行时文本反解析检测。

### J-6 · Inspector 的任务上下文来源（2026-10-17，拟议 + 待决策）

> **状态标记**：本节是 **拟议契约修订 + 待决策事项**，不是已冻结契约，也不是已批准实现。Phase 4B.1 不因本节通过验收。
> **结论先行**：当前对象生命周期无法在不新增共享状态 / 持久化 / 结果模型职责的前提下，向 Inspector 提供 Agent 任务实际使用的 `ContextPack`；本轮不选择实现方案，只记录边界与待决策项。

#### J-6.1 产品语义：三个不同概念

Inspector 的“任务上下文”应表达：**Agent 在执行某个任务时实际使用的 ContextPack 快照**，并能追溯到该任务（task / session）的归属。

它不应表达：

- Inspector 为当前选中对象重新构建的相似上下文；
- 当前 Repository 中的最新事实；
- 仅因两次构建的 `packVersion` / itemId 恰好相同而推断的“同一快照”。

| 概念 | 含义 | 允许进入“任务上下文”展示？ |
|---|---|---|
| 实际任务 ContextPack 快照 | 某次 Agent Task 实际构建并消费的 Pack | 是；必须带任务归属 |
| 当前 Repository 事实 | 当前章节 / 人物 / 资料的最新读取结果 | 只能作为“当前事实”单独标识，不得混入任务快照 |
| 独立构建的上下文 | 为其他目的重新 build 的 Pack | 否；不得冒充任务快照 |

#### J-6.2 Inspector 四状态

| 状态 | 条件 | 用户语义 / 文案 | 数据规则 |
|---|---|---|---|
| 一 | 从未执行过 Agent 任务 | `暂无任务上下文` | 不得为填充 Inspector 自动构建 ContextPack |
| 二 | 执行过任务，但实际 Pack 当前不可获取（例如进程重启） | `上次任务上下文已失效` | 不得用当前重新构建的 Pack 冒充历史快照 |
| 三 | 实际任务 Pack 可获取 | 展示真实 Pack + 任务归属（task / session / 时间等既有标识） | 必须能追溯到该任务实际构建的 Pack |
| 四 | 新任务正在执行 | 若当前任务尚未产生可展示 Pack：`当前任务尚未生成上下文`；若已有：展示当前任务 Pack 并标识为当前；上一任务 Pack 不得显示为当前任务 Pack | 不得把上一任务 Pack 误标为当前任务 |

> 状态三在当前代码中**不可达**（无 Pack 来源，见 §J-6.3）；状态一、二、四的占位语义可在不新增共享状态的前提下实现，但属于“中间态”，需用户裁决是否接受（§J-6.4）。

#### J-6.3 对象生命周期审计（只读证据）

| 事实 | 证据 |
|---|---|
| Pack 在 `NovelAgent.buildContext` 内创建，`run` 内以局部 `builtPack` 持有 | `NovelAgent.kt:168`、`:185-188`、`:482` |
| Pack 只在本次 run 内传给 `PlanningContextAssembly.assemble(request, pack)` | `NovelAgent.kt:543`、`:582`；`PlanningContextAssembly.kt:55-153` |
| `NovelAgentResult` 只返回 `contextPackId` / `contextPackVersion`，**不复制 ContextPack 大对象** | `NovelAgentModels.kt` 结果类型注释；`NovelAgent.kt:800-801` |
| `RunState` 只保存 Pack 的 id / version | `NovelAgent.kt:817-820` |
| UI 调用方 `launchAgentTask` 只消费结果 toast，不保留 Pack | `Common.kt:274` 及其后 |
| 无 ContextPack Store / 查询接口；`ContextPack` 未序列化 / 持久化 | 4A 审计 `:229`；生产代码检索无 store |
| `Task.Checkpoint` 有 `snapshot: JsonObject?`，但 `NovelAgent` 未调用 `saveCheckpoint` | `TaskModels.kt:43-51`；`NovelAgent.kt` 无 checkpoint 调用 |
| `AgentSession` 只有 task / workflow 引用，无 Pack 字段 | `AgentSessionModels.kt:60-73` |
| Inspector 当前独立 build，不复用任务 Pack | `WorkspaceUseCases.kt:340-360`、`:353` |

补充：`WorkspaceController.openInspector`（`WorkspaceController.kt:97`）只切换 tab；当前 build 由 `Main.kt:166` 的 selection / refresh 触发 `load()`，且 `inspector()` 无条件调用 `context()`。因此仅修改“打开 Tab”逻辑不足以消除 build，后续实现必须处理 selection / refresh 路径。

生命周期结论：Pack 是 **一次 run 的局部对象**；run 返回后无引用；进程重启后更不可获取；当前没有现成的 task / session → Pack 查询映射，也没有可复用的 Pack Store。

#### J-6.4 A 的实现边界结论

**方案 A（复用现有生命周期）不能作为已批准的最小方案**：要展示“实际任务 Pack”，至少需要把 Pack 暴露到结果对象、共享状态或 Store 中；其中暴露到 `NovelAgentResult` / UI 会改变领域结果职责或让 UI 依赖 Agent 内部对象，增加新的跨层引用。现有生命周期无法同时满足方案 A 的全部条件。

**方案 B（新增共享状态 / Pack Store / 持久化 / 查询映射）不在本轮授权范围**：任何此类能力都需要单独的范围决策与实施提示词；本轮不引入、不实现。

**中间态（需用户裁决）**：在现有生命周期下，Inspector 可以**不重新 build**，只显示 `暂无任务上下文` / `上次任务上下文已失效` / `当前任务尚未生成上下文` 等占位；这不会新增共享状态，但无法展示真实任务 Pack。该中间态是否接受，由用户裁决，**不得由本轮自行认定**。

#### J-6.5 当前事实与任务快照的边界

- 若 Inspector 继续展示当前章节、人物或其他资料的最新事实，必须明确标识为“**当前事实**”，来源是现有 Repository / read API；
- 读取当前事实**不得调用** `ContextEngineUseCases.build`；
- 不得把实时 Repository 数据与历史 ContextPack 混成一个无法区分的列表；
- 本轮不新增复杂 Inspector 页面、Dashboard 或任务管理系统。

#### J-6.6 待决策事项

1. Inspector 的“任务上下文”是否必须展示真实任务 Pack（若必须，则进入方案 B 的范围决策）；
2. 若接受中间态，采用哪些占位文案与状态边界（§J-6.2）；
3. Inspector 是否保留“当前事实”展示；若保留，其标识与读取路径；
4. AC-06 的“入口计数”是否需要在 A 裁决后进一步限定 Inspector 的 build 权限。

---

## K. 本轮验证

- 2026-10-17 修订轮：**零代码修改**；仅修订本 addendum（J-1…J-4 裁决、FoundationPayload 字段修正、措辞修正）并对 AC 草案与 4A 审计报告做**追加式**同步（不改原文条款）。
- `git diff --check` 结果见 Completion Report。
- 未修改 `qianyan-phase4a-context-contract-freeze.md`（D2 原文）、Phase 3 Decision Record、P2 Original 只读契约、CommitHistory Format Contract v26。
- 未新增/修改任何测试代码；J-4 的守卫测试、J-2 的 selector 改造、J-3 的校验代码均留待 Phase 4B.1。
- 2026-10-17 契约修订轮（本轮）：追加 §J-1.1、§J-2.1 与 §J-6，澄清 Vocabulary 的“完整来源数据集 / 模型输入子集”两层语义与 Inspector 的 Pack 来源边界；**仅修改规划文档，未修改业务代码或测试**；J-1.1 / J-2.1 为拟议分层澄清，A 的实现边界待决策；**Phase 4B.1 仍未验收**。
