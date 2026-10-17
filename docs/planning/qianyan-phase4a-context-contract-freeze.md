# Qianyan｜Phase 4A Context Contract Freeze（决策记录）

> - 依据：`qianyan-dev-implementation-plan-v3.1.md` **§13 Phase 4 — Context + Retrieval**（行 561–627）；§23B（R3 Canonical/Draft 读策略，FINALIZED）；§25 明确禁止项 / **FD-10 生效中**
> - 基线：`feature/novel-ide` @ `85c4518`（Phase 2 读侧 + P2 Original 写保护 + Phase 3 世界模型写入链已入库）
> - 性质：**契约冻结文档**（Phase 4A 只读审计 + 决策）；**本轮未修改任何业务源码 / 测试 / schema / migration**。
> - 术语纪律：严格区分「**已实现能力**」（有代码/测试证据）／「**已实现但无消费方**」／「**未实现**」／「**需先实现后验证**」。凡无证据者不写成现有能力。
> - 交叉引用：Phase 3 Closure Decision Record（`qianyan-phase3-closure-decision-record.md`）的 **D-1…D-4**（H 节）与本文件 **D1–D4** 无冲突，且不修改其任何既有决策（详见 §5）。

---

## A. 冻结结论速览

| # | 决策 | 结论 |
|---|---|---|
| **D1** | ContextPack 与 ContextSnapshot 的关系 | **不引入独立 `ContextSnapshot` 类型**；`ContextPack` 即运行时上下文快照的唯一载体；未来扩展以"加字段/包装"方式演进，禁止并行第二套模型 |
| **D2** | 唯一 Context Entry 的语义 | 条目 = **带来源与版本的上下文片段**；唯一性按 **(source, refKey)** 维度；去重必须在 4B 实现（当前代码不保证） |
| **D3** | Memory 是来源还是索引类型 | Memory = **独立业务事实源**（已持久化、有生产读取方）；**不是**索引类型、**当前不是** ContextPack 来源；三概念禁止合并 |
| **D4** | `packVersion` / `isStale` 语义 | 冻结为"**输入指纹 + 重编比对**"；字段与算法已实现，但**无生产消费方**且**无增量失效**能力 ⇒ 不得声称"失效检测已生效" |

---

## B. D1 — ContextPack 与 ContextSnapshot 的关系

### 证据（代码事实）

| 事实 | 位置 |
|---|---|
| `ContextPack` 是"一次 Context 构建的**冻结结果**"：`packId` / `request` 副本 / `novelId` / `activeVariantId` / `items`（内容已物化）/ `budgetGuard` / `packVersion` / `createdAt` | `core/model/src/main/kotlin/com/qianyan/model/context/ContextPack.kt:189` |
| `packId` 为确定性标识（`ctx-<projectId>-<purpose>-<focusChapterId|none>-<version>`，**不含 UUID / 时间**） | `ContextEngineUseCases.kt:91-92` |
| `items` 内容在构建时物化（Draft 为正文原文，**不摘要/不截断**） | `ContextPack.kt:83-95`（`ContextItem.content` 注释）＋ `ContextSources.kt` DraftSource |
| **不存在** `ContextSnapshot` 类型 | 全仓 `grep ContextSnapshot`（application/core/app main 源集）= **0 命中** |
| `ContextPack` 的产物（packId/packVersion）**未持久化**：`grep contextPackId|contextPackVersion` 在 `application/src/main` + `storage/src/main` 中除 `NovelAgent.kt` 外**无命中** | `NovelAgent.kt:488-489`（写入 RunState）、`:780-781`（写入 Result） |
| §13 §9 明文：Storage/Migration **原则上不需要**（Snapshot 先运行时）；§13 §6 明文：**不新增第三套 Context** | 计划 行 561–627 |

### 决策

1. **`ContextPack` 就是本阶段的运行时上下文快照**，语义等同 §13 §8 的 "Snapshot 不可变（运行时可复现）"：内容物化 + 输入指纹 + 请求副本 ⇒ 同输入必得同 pack、同 `packVersion`。
2. **不新增 `ContextSnapshot` 类型**（不做类型别名、不做空壳）。理由：无任何消费方需要"快照"与"pack"两种语义；新增类型会立即形成第二套上下文模型（违反 §13 §6）。
3. **未来扩展保留方式**（写入契约供 4B 参考，本阶段不实现）：
   - 若出现**跨进程/审计/回放**的真实需求，以 `ContextPack` 为原型**加字段或包装**（如 `snapshotId`/`producedBy`），**禁止**平行新模型；
   - 一旦确需持久化，按 §13 §9 走 **additive** 迁移，并在 4C 增加"未超范围扩 Storage"的机械检查；
   - 当前**无**该需求证据（见 D1 证据表最后两行）。

### 被否决的替代方案

| 方案 | 否决理由 |
|---|---|
| 立即新建 `ContextSnapshot`（含/不含持久化） | §13 §6 禁止第三套 Context；§13 §9 默认不落库；无消费方 ⇒ 立刻成为死产物（与现状 `isStale` 无人消费同类风险） |
| 把 `ChapterContextPack` 与 `ContextPack` 合并为一种类型 | 两者输入与语义不同（LCL 窗口投影 vs 任务级选择结果），合并会破坏 §13 §8 要求的"降级为 Source/视图"路径；且 4A 不得改代码 |
| 让 `ContextPack` 直接持久化到新表 | 无跨进程需求证据；违反 §13 §9 默认假设；会引入"过期 pack 与当前数据并存"的一致性问题 |

### 当前不做（Non-goals）

- 不新增 Context 表 / 不新增 `ContextSnapshot` 类型 / 不实现上下文缓存；
- 不改 `ContextPack`、`ContextEngineUseCases`、`ContextSources`、`ChapterContextPack` 的任何实现；
- 不把 `ChapterContextPack` 合并或删除（其归并在 4B 按 §13 §8 处理）。

### 对 Phase 4B 的约束

- Target Context 的**唯一载体**是 `ContextPack`；4B 若需要"快照"语义，只能在 `ContextPack` 上扩展；
- 4B 必须把 `PlanningContextAssembly`、`ChapterContextCompileUseCases` **降级为 Source 或视图**（§13 §8），不得再让它们各自拼 prompt；
- 4B 不得新增第二套 Context 模型或持久化快照（除非先裁决并提供需求证据）。

---

## C. D2 — 唯一 Context Entry 的语义

### 证据（代码事实）

| 事实 | 位置 |
|---|---|
| 条目模型 `ContextItem`：`itemId` / `source` / `content` / `priority` / `estimatedSize` / `ref` / `sourceVersion` / `reason` | `ContextPack.kt:83-95` |
| `itemId` 格式 = `"<source 小写>:<refKey>"`，由来源侧声明（**不含 UUID / 时间**） | `ContextSources.kt:58` |
| 来源类型 `ContextSourceKind` = PROJECT / PROJECT_STATE / NOVEL / CHAPTER / DRAFT / STORY_FOUNDATION / VOCABULARY（**7 类**，无 MEMORY、无 EVENT/TIMELINE/FORESHADOW） | `ContextPack.kt:37-45` |
| 稳定引用 `ContextItemRef` = novelId / variantId / chapterId / draftId | `ContextPack.kt:66-71` |
| 优先级 `ContextPriority` = HIGH / MEDIUM / LOW，由 `ContextPriorities.of(kind, purpose)` 按任务类型给出 | `ContextPack.kt:52-63`；`ContextSources.kt:32`、`:82/107/133/208` |
| 选择/裁剪：`ContextSelector.select` 按 `priority.rank` **稳定排序** → 逐条累加估算尺寸 → 单条超预算淘汰并记录 | `ContextPack.kt:210-250` |
| **去重**：`ContextSelector` **不按 `itemId` 去重**，也不检测跨来源重复；来源各自生成 itemId | `ContextPack.kt:210-250`（无 dedupe 分支） |

### 决策

**Context Entry = 带来源与版本的上下文片段**（不是纯文本块、不是裸对象引用、不是无来源的事实引用）。四要素分工：

1. **唯一性维度 = `(source, refKey)`**：`itemId` 由 `${source}:${refKey}` 构成；同一来源的同一 refKey 只应产生一个条目。**跨来源的唯一性当前无代码保证** ⇒ 见第 5 点。
2. **来源**由 `source`（7 类枚举）+ `ref`（最小 ID 元组）+ `sourceVersion`（来源记录状态指纹，如 Draft 的 status/format、Foundation 的 version）共同表达；**内容**由 `content`（物化文本）表达；**排序**由 `priority`（任务相关度）表达。
3. **同一对象的不同片段允许并存**：唯一性作用于 `(source, refKey)` 而不是"对象"，因此同一 Chapter 可由 CHAPTER 与 DRAFT 两个来源各出一条（两条 itemId 不同、`ref` 不同维度）；同来源不同 refKey（如同一 Novel 下不同 Chapter）天然并存。
4. **重复注入控制（必须由 4B 实现）**：当前代码不保证同一事实不被重复物化。两种可行路线（择一，4B 定稿）：
   - 路线 A：**来源层不重叠**（由 pipeline 保证每个 refKey 只由一个 Source 物化，如 Memory 只经 Memory 层一条路径）；
   - 路线 B：**选择层确定性去重**（`ContextSelector` 或 pipeline 在排序后按 `(source, refKey)`/内容指纹去重，保留优先级最高者，并把被去重项记入 `budgetGuard.omittedItemIds` 以保持可解释性）。
   > 本阶段只冻结"必须去重"，**不实现**（不得改代码）。
5. **追溯能力保留**：`itemId`（确定性身份）+ `source`/`ref`（来源定位）+ `sourceVersion`（当时状态）+ `reason`（入选依据）+ pack 级 `packId`/`request`/`packVersion`（整包来源与版本）。

### 被否决的替代方案

| 方案 | 否决理由 |
|---|---|
| 条目 = 纯文本内容块 | 丢失来源与版本 ⇒ 违反 4C 要求的"来源与版本可追溯"，也无法实现 D4 的失效判定（指纹依赖 `sourceVersion`） |
| 条目 = 业务对象引用（Novel/Chapter/Draft 的 ID） | 无法进入 prompt（模型需要文本），且迫使消费者各自再取内容（回到"多入口各自拼"的老问题） |
| 条目 = `itemId` 含 UUID / 时间戳 | 破坏确定性（现注释明确"不含 UUID / 时间"）；`packVersion` 将随每次构建变化，`isStale` 失去意义 |
| 引入"*事实*"粒度的独立模型（FactRef / ContextFact） | 属第二套事实/上下文模型；§13 §6 与 §3（事实源唯一性裁决）均禁止；世界模型的"事实"已有 Phase 3 的表与模型承载 |

### 当前不做（Non-goals）

- 不新增条目类型 / 不改 `ContextItem` 字段 / 不实现去重算法 / 不引入内容指纹字段；
- 不新增来源类型（尤其**不加** MEMORY / EVENT / TIMELINE / FORESHADOW 来源，见 D3 与 §13 §8 的五类限定）。

### 对 Phase 4B 的约束

- 条目必须保持"来源 + 版本 + 内容 + 优先级"四要素齐备；不得退化为纯文本或裸引用；
- 4B 必须实现并测试**去重规则**（路线 A 或 B），并在 4C 中作为可验证条目；
- 4B 不得为满足去重而删除来源（例如为省事去掉 `sourceVersion`）。

---

## D. D3 — Memory 是来源还是索引类型

### 证据（代码事实）

| 概念 | 事实 | 位置 |
|---|---|---|
| ① 独立业务事实源 | **存在且持久化**：模型 `MemoryEntry`、表 `MemoryEntry`、仓储与 UseCase、事务写入 | `core/model/src/main/kotlin/com/qianyan/model/memory/MemoryModels.kt:22`；`storage/src/main/sqldelight/com/qianyan/storage/db/Schema.sq:96`；`application/.../memory/MemoryUseCases.kt:31/53` |
| ① 生产读取方 | `StoryWorldContextResolver` 读 `findOriginalBase` / `findEntriesByVariant`；Knowledge Update 读 `findEntriesByNovel` 作为抽取输入 | `StoryWorldContextResolver.kt:66/68`；`KnowledgeUpdateExecutionUseCase.kt:70` |
| ① 生产写入方 | Knowledge Update 事务内 `saveEntry` + 非 ADD 先 `deactivateByTarget`（历史保留） | `KnowledgeUpdateExecutionUseCase.kt:76-81` |
| ② 作为索引类型 | **不存在**：`ProjectIndexEntryType` 恰为 5 类（NOVEL / CHAPTER / DRAFT / STORY_FOUNDATION / VOCABULARY），无 MEMORY | `core/model/.../projectindex/ProjectIndexModels.kt:51-57` |
| ③ 作为 ContextPack 条目 | **当前不存在**：`ContextSourceKind` 7 类无 MEMORY；`ContextSources.kt` 无 Memory 源；`grep Memory application/.../usecase/context/` = 0 命中 | `ContextPack.kt:37-45`；`ContextSources.kt:291-299`（7 个 Source 列表） |
| ③ 但 Memory **已经**进入写作 prompt | 经 `StoryWorldContextResolver` → `StoryWorldContext.orderedVisible`（canon → worldState → facts → memories）→ `PlanningContext.memories` → Planner 与 Writer 的 `renderInput` 均渲染 `context.memories` | `StoryWorldContext.kt:32-58`（`orderedVisible`）；`PlanningContextAssembly.kt:118`；`PlannerAgent.kt:138-140`；`WriterAgent.kt:135-137` |

### 决策

1. **Memory 保持"独立业务事实源"**（概念①）：不做合并、不改语义、不改表；其"canon 优先"的确定性顺序（`orderedVisible`）是既有契约，4B 不得改写。
2. **Memory 不作为检索索引类型**（概念②）：本阶段**明确不新增** MEMORY 索引类型。§13 §8 已把第一阶段索引类型限定为五类；把 Memory 变成索引会与"事实源唯一性"以及索引的"派生自 Canonical"定位冲突（`ProjectIndexModels.kt` 明确索引只承载最小 ID 元组、不承载内容）。
3. **Memory 当前不作为 ContextPack 来源**（概念③）：现状是"经 Resolver 的 memory 层"进入 prompt。4B 若要把 Memory 纳入 `ContextSources`，必须先满足两个前置：① 按 §13 §8 把 `StoryWorldContextResolver` 降级为 Source 或视图；② 落实 D2 的去重规则，**避免同一 Memory 被两条路径重复注入**。在此之前**不得**新增 Memory source。
4. **三概念禁止合并表述**：文档、代码注释与验收条目中必须分别写 "Memory 事实源 / Memory 索引类型 / Memory 的 Context 条目" 三者状态，不得用"Memory 已接入 Context"一句话笼统描述（现状：**已接入 prompt，但未接入 ContextEngine/Pack**）。

### 被否决的替代方案

| 方案 | 否决理由 |
|---|---|
| 把 Memory 升格为索引类型（新增 MEMORY） | 超出 §13 §8 的五类限定；索引=派生 ID 元组，Memory 是内容型事实源，语义错配 |
| 新建 `MemorySource` 直接进入 `ContextSources` | 会与 `StoryWorldContextResolver` 的既有路径重复注入同一事实（D2 第 4 点）；且 4A 不得改代码 |
| 把 Memory 从 prompt 中移除（"统一到 Context"） | 会造成写作链能力回退，且 §13 §5 要求"按任务取用"而非移除既有事实输入；需先完成入口收敛（4B） |

### 当前不做（Non-goals）

- 不新增 Memory 表/列/仓储/索引类型；不改 `MemoryEntry` 语义与 `deactivateByTarget` 行为；
- 不改 `StoryWorldContextResolver` 与 `PlanningContextAssembly`；
- 不把 Memory 写入 Canonical Story（Memory ≠ Canonical，二者边界由既有契约维持）。

### 对 Phase 4B 的约束

- 若 4B 需要 Memory 进入统一 Context：先降级 Resolver 路径并实现去重，再以 **Source** 形式接入；验收须包含"同一 Memory 不重复注入"的断言；
- 4B 不得把 Memory 变成事实的第二来源（Canonical Story 仍是唯一正典；Memory 是既有独立事实源）。

---

## E. D4 — `packVersion` / `isStale` 语义

### 证据（代码事实）

| 事实 | 位置 |
|---|---|
| `packVersion` = 输入指纹（FNV-1a 64），输入 = request 全字段（projectId / novelId / activeVariantId / purpose / sessionId / taskId / focusChapterId / budget.maxTokens / budget.estimator / candidateHorizon）+ **入选条目序列**（itemId / source / priority / sourceVersion / content）+ **淘汰集合**（omittedItemIds） | `ContextPack.kt:254-285`（`ContextPackVersion.of`） |
| `isStale(pack)` = 用 `pack.request` **重编一次**并比较 `packVersion`；不改动既有 pack | `ContextEngineUseCases.kt:107` |
| 并行实现（LCL 侧）：`ChapterContextPack.packVersion` + `invalidatePack(old, windowSize)` 同样"重编比对" | `core/model/.../lcl/ChapterContextPack.kt:57-71`；`ChapterContextCompileUseCases.kt:74-82` |
| **生产消费方**：`ContextEngineUseCases.isStale` 仅被测试调用 | `ContextEngineBuildTest.kt:113/135/140`（无 main 源集调用） |
| 请求侧字段不含 createdAt；pack 级 `createdAt` 明确"不参与选择与版本指纹" | `ContextPack.kt:189`（字段注释）、`:196` 附近（`createdAt` 说明） |

### 决策（冻结契约）

1. **`packVersion` = 输入指纹**（而非"内容摘要"或"时间戳"）：它随**请求**与**入选/淘汰结果**变化，不随构建时间变化。
2. **`isStale(pack)` = "同一请求在当前底层数据下重编会得到不同 pack"**；实现方式（重编比对）已是现行语义。
3. **会失效**：入选条目的 `content` 或 `sourceVersion` 变化；入选/淘汰集合变化（含候选增减导致选择不同）；request 任一字段变化（含 `budget`、`estimator`、`candidateHorizon`）；scope（novel/variant）变化。
4. **不应失效**：未被入选的候选变化（若不影响入选集合与淘汰集合）；`createdAt` / 系统时间；无关 Project 的数据变化；pack 的序列化往返（同 pack 重新读入应得同 `packVersion`）。
5. **当前能力边界（必须如实记录）**：
   - 指纹与重编比对逻辑**已实现**；
   - **无生产调用方**（`isStale` 仅测试）⇒ **不得声称"失效检测已在生产生效"**；
   - **无增量失效能力**：每次判定需全量重编（候选/章节越多成本越高），当前**没有**缓存或局部失效；
   - **两套并行版本语义**（`ContextPack.packVersion` 与 `ChapterContextPack.packVersion`）需在 4B 统一到 Target Context 一侧。
6. **结论**：D4 属于"**契约可冻结，实现部分待接线**"——4C 中相关条目标注为**需先实现后验证**。

### 被否决的替代方案

| 方案 | 否决理由 |
|---|---|
| `packVersion` = 时间戳 / 递增序列 | 破坏确定性与"同输入同版本"（§13 §10 第一条），且无法用于失效判定 |
| `isStale` 只比较 request（不比条目） | 底层数据变化（如 Draft 被修订）将无法识别 ⇒ 旧 pack 静默复用（违反 §13 §10 "AI 这次看到了什么可复现"的意图） |
| `isStale` 只比较条目内容（不比 request/budget） | budget/estimator/horizon 变化会改变选择边界却判为"未失效" |
| 现在就实现增量失效/缓存（例如按 refKey 建反向索引） | 属功能实现（4B/后续）；无消费方时优化无意义；4A 不得改代码 |

### 当前不做（Non-goals）

- 不改 `ContextPackVersion`、`ContextEngineUseCases.isStale`、`ChapterContextCompileUseCases.invalidatePack`；
- 不新增缓存、不新增失效元数据表、不引入 `snapshotVersion` 等平行字段。

### 对 Phase 4B 的约束

- 4B 若在请求路径上使用 `isStale`，必须同时给出**成本边界**（何时不重编）与**消费语义**（过期后如何处置旧 pack：丢弃 or 重编），并补测试；
- 4B 统一版本语义时，`ChapterContextPack` 侧必须按 §13 §8 降级为 Source/视图，不得保留与之并行的"第二个 packVersion 权威"；
- 4B 不得把 `isStale` 的"实现存在"当作"已被消费"来报告。

---

## F. 与 Phase 3 Closure Decision Record 的关系（交叉引用声明）

| Phase 3 决策 | 本文件关系 |
|---|---|
| **D-1 / U1+U8**（抽取失败记录：B 类吸收记标记、C/D 上抛） | **无冲突**：属 Commit→World Model 写入链的失败语义，本文件不涉及；4B 不得借 Context 收敛改动 Phase 3 写入链 |
| **D-2 / U2**（Human Gate 本阶段不接入） | **无冲突**：4B **不得**借 Context/Retrieval 实现 Human Gate 接线 |
| **D-3 / U3**（维持 `EVENT + ADD` 过渡语义） | **无冲突但相关**：世界模型事实的**写入**仍限 ADD；4B 若要**读取** world model 进 Context，只能读既有已落地事实，不得要求扩展 UPDATE/REMOVE |
| **D-4 / U5**（§12.5「规则」= 现有确定性能力） | **无冲突**：本文件的确定性要求（去重/排序/指纹）与"现有确定性能力"解释一致 |

- 本文件**不修改** `qianyan-phase3-closure-decision-record.md` 的任何内容（该文件在 `85c4518` 中已入库，仍为权威）。
- Phase 3 明确暂停的四项后续工作（失败诊断载体、Human Gate 链路、UPDATE/REMOVE 契约、候选队列消费方）在 Phase 4A/4B 期间**继续挂起**，不并入本阶段。

---

## G. Phase 4B 契约约束汇总（冻结项）

1. **唯一入口**：Target Context 一律经 `ContextEngineUseCases.build(ContextRequest) → ContextPack`；`PlanningContextAssembly` 与 `ChapterContextCompileUseCases` 降级为 Source/视图（§13 §8）。
2. **唯一载体**：`ContextPack`（不新增 `ContextSnapshot`）。
3. **条目语义**：来源 + 版本 + 内容 + 优先级四要素齐备；唯一性按 `(source, refKey)`；必须实现去重。
4. **检索范围**：仅五类现行索引（NOVEL / CHAPTER / DRAFT / STORY_FOUNDATION / VOCABULARY）；`Character/Event/Timeline/Foreshadowing` 索引**不属本阶段要求**（§13 §8/§12 Gate）。
5. **禁止项**（§13 §6/§25，机械可验证）：FTS5 虚拟表、embedding/vector 表与 provider 调用、语义/全文检索、RAG、云检索与云依赖。
6. **Memory 边界**：独立事实源；不得新增 MEMORY 索引类型；接入 Pack 前必须先降级 Resolver 路径并去重。
7. **版本语义**：`packVersion` = 输入指纹；`isStale` = 重编比对；不得把"已实现"写成"已在生产生效"。
8. **只读保证**：Context 构建全程只读（不写 Canonical / Story State / Memory / Workflow / Task）。
9. **不新增模型**：不新增第二套 Chapter / Draft / Story / Context / World Model。
