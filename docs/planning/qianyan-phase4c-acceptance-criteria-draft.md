# Qianyan｜Phase 4C 验收标准草案（Acceptance Criteria Draft）

> - 依据：`qianyan-dev-implementation-plan-v3.1.md` **§13**（尤其第 10/12 项：测试与 Completion Gate，行 542–555 / 555），以及本目录 Phase 4A 的 `qianyan-phase4a-context-contract-freeze.md`（D1–D4）与 `qianyan-phase4a-architecture-audit.md`
> - 性质：**草案**。本文件是"待验证清单"，**不是验收结论**。每项都标注当前状态：`可验收`（现有能力即可验证）／`需先实现后验证`（当前链路不存在，必须先实现才能验证）。
> - 基线：`feature/novel-ide` @ `85c4518`。本草案**未修改任何代码**。
> - 反例纪律：任何条目**不得**以"计划中存在"或"模型已定义"当作已实现；`需先实现后验证` 的条目在 4B 完成后必须重写为可执行断言。
> - 契约澄清（2026-10-17）：AC-30 拆分为 **AC-30A**（Vocabulary Source Dataset）与 **AC-30B**（Model Input Vocabulary Subset）；AC-08 追加 A 澄清；新增 **AC-35（A，待决策）**。本轮不补测试、不修改业务代码；Phase 4B.1 仍未验收。

---

## A. 结构化检索支持边界（五类）

| ID | 验收条目 | 验证方式（测试类型） | 当前状态 |
|---|---|---|---|
| **AC-01** | Phase 4 的结构化检索**仅**覆盖五类：`NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`，与 `ProjectIndexEntryType` 完全一致（不多不少）；**注（J-1 作用范围）**：J-1 仅调整 Context Source `VOCABULARY` 的数据来源语义（与正式 Assembly 使用的 approved `VocabularyEntry` 集合一致），**不修改 `ProjectIndexEntryType` 定义、不改变 ProjectIndex 中 Vocabulary 类型的索引语义**，不得误解为 ProjectIndex Vocabulary 重构 | 单元测试：断言枚举值集合等于上述五项；负向：不存在 `CHARACTER`/`EVENT`/`TIMELINE`/`FORESHADOW`/`MEMORY` 成员 | **可验收**（`ProjectIndexModels.kt:51-57`；`ProjectIndexBuildTest`/`QueryTest` 8+3） |
| **AC-02** | 每类都能被**结构化取回**（by-ID 或 list-by-scope），且返回对象来自既有仓储、不复制内容 | 集成测试：对五类各取一条并断言 ID 一致、来源仓储一致 | **可验收**（`NovelRepository:24`、`ChapterRepository:25/28`、`DraftRepository:25/36`、`StoryFoundationRepository:25`、`VocabularyRepository:31/40`） |
| **AC-03** | 检索有**真实消费方**：检索结果能进入 Agent/上下文组装（而非只有测试调用） | 集成测试：从 Agent 入口出发断言检索结果出现在 `ContextPack.items` 或模型请求中 | **需先实现后验证**（`find:114`/`findByType:105` 目前仅测试调用） |
| **AC-04** | "Previous Chapters" 经 `ChapterId / Sequence / Relation` 获取，**不得**实现为 RAG/向量/全文 | 机械扫描（见 INV-3）+ 正向测试：按 order/horizon 取前文章节 | **可验收**（`ChapterRepository.listByNovel:28` + `ChapterContextProjector` 窗口 `ChapterContextPackTest:11`） |
| **AC-05** | `find(query)` 的语义边界明确且被断言：**仅**匹配索引条目标签/可检索词，**不**是全文检索 | 单元测试：命中/未命中样例（标签命中、正文词不命中） | **可验收**（`ProjectIndexUseCases.kt:50` 注释 + `ProjectIndexQueryTest:3`）；若 4B 改变语义需重写 |

---

## B. ContextPack 构建与实际模型请求的连接

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-06** | **存在单一入口**：任务级上下文只经 `ContextEngineUseCases.build(ContextRequest) → ContextPack` 产出；`PlanningContextAssembly` / `ChapterContextCompileUseCases` 已降级为 Source 或视图（不得各自拼 prompt）。**调用次数口径（R3/R4）**：匹配 variant 的正常路径**至多调用一次** `ContextEngineUseCases.build`；J-3 mismatch 在调用前 fail-fast，该分支 build 调用次数 = **0**，不构成成功/失败的入口调用、不计入该次数；**不得**解释为每个 Agent Task 必须恰好调用一次 build；该计数指 `ContextEngineUseCases.build` 调用次数（不是 `NovelAgent.buildContext` 执行次数，也不是 Agent Task 启动次数）；无 Pack 的 `PlanningContextAssembly.assemble(...)` 重载是**受控兼容例外**，不属正式生产入口、不计入入口数，生产代码不得调用 | 负向 + 结构测试：断言二者不再有独立 prompt 渲染路径；入口计数 = 1（按上述口径） | **需先实现后验证**（当前 4 条链路：审计 R1） |
| **AC-07** | `ContextPack` 的内容**真正进入**模型请求（可复现"AI 这次看到了什么"）。**marker 约束（R2）**：marker 必须位于 J-2 关键来源 `NOVEL` / `STORY_FOUNDATION` / `VOCABULARY` 中**至少一个实际入选条目**；只要求实际入选条目被渲染，**不要求所有 ContextPack 条目进入 prompt**；测试预算必须足以让至少一个关键来源入选；极小预算或关键来源超预算的场景应**单独测试选择行为**，不得以"没有关键来源入选"作为 AC-07 marker 验证的前提；不要求三个关键来源同时入选 | 集成测试：抓取发送给 `LLMGateway` 的请求，断言包含 pack 入选条目的内容片段（而非只有 packId/version） | **需先实现后验证**（当前 `NovelAgent.kt:488-489/780-781` 只记录 id/version） |
| **AC-08** | 同一任务输入下，Inspector 与写作链观察到**同一** Context（消除四套不一致）。**A 澄清（2026-10-17）**：Inspector 的“任务上下文”必须是该任务实际使用的 `ContextPack` 快照，不得以重新构建的相似 Pack、当前 Repository 事实或仅相同 `packVersion` / itemId 冒充；无可用 Pack 时显示占位状态。详见 addendum §J-6 与 AC-35（**待决策**） | 集成测试：两处入口对同 `ContextRequest` 得到相同 `packVersion` 与相同 itemId 序列 | **需先实现后验证** |

---

## C. Context Entry 的唯一性、来源与版本

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-09** | 条目四要素齐备：`source`（7 类之一）+ `ref`/`sourceVersion`（来源与版本）+ `content`（物化文本）+ `priority`；缺任一即视为契约违约 | 单元测试：构造 pack 后逐条断言四要素非空/合法（`content` 允许空串但必须显式） | **可验收**（`ContextItem:83-95`） |
| **AC-10** | 唯一性按 `(source, refKey)`：`itemId` 形如 `<source>:<refKey>` 且**同一来源同一 refKey 不得重复入选** | 单元测试：注入两条同 `(source, refKey)` 候选 ⇒ 只入选一条，另一条记入 `budgetGuard.omittedItemIds` | **需先实现后验证**（当前 `ContextSelector:210-250` 无去重；审计 R7） |
| **AC-11** | 跨来源的**同一事实不得重复注入**（同 refKey 经两条路径物化时） | 集成测试：构造 Memory 双路径场景，断言 prompt 中该事实仅出现一次 | **需先实现后验证**（审计 R6；D2 路线 A/B 择一） |
| **AC-12** | 来源追溯可用：给定任一条目可回答"来自哪个对象、当时什么状态、为何入选" | 单元测试：`itemId` + `ref` + `sourceVersion` + `reason` 四者可解释；pack 级 `packId`/`request` 可解释整包 | **可验收**（字段齐备）；**端到端**追溯（进 prompt 后仍可对账）需先实现后验证（AC-07） |

---

## D. `packVersion` / `isStale` 的可验证语义

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-13** | `packVersion` = 输入指纹：同 request + 同入选/淘汰集合 ⇒ 同版本；**不含** `createdAt`、不含未入选候选 | 单元测试：同输入两次构建版本相同；改动 `createdAt`/无关数据版本不变；改动 request 任一字段版本变化 | **可验收**（`ContextPack.kt:254-285`；`ContextEngineBuildTest` 8 / `ContextEngineRequestTest` 6） |
| **AC-14** | `isStale(pack)` = "同请求在当前底层数据下重编即不同版本"；且**底层数据变化**（入选条目 content/sourceVersion 变化）判为过期 | 单元测试：改 Draft 内容 ⇒ `isStale` = true；重建后 = false | **可验收**（`ContextEngineUseCases.kt:107`；`ContextEngineBuildTest.kt:113/135/140`） |
| **AC-15** | `isStale` 在**生产路径**上被消费：过期有明确处置（丢弃/重编），且**成本有界**（不得每次请求无脑全量重编） | 集成测试 + 性能断言（重编次数上限） | **需先实现后验证**（当前无生产消费方；审计 R8/D4） |
| **AC-16** | 统一版本语义：不得存在两套并行的 pack 版本权威（`ContextPack` 与 `ChapterContextPack`） | 结构测试：`ChapterContextPack` 已降级为视图/来源，其版本不再被当作 Target Context 的判据 | **需先实现后验证**（审计 R5） |

---

## E. Memory 的明确边界

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-17** | Memory 保持**独立事实源**：既有表/模型/仓储不变，Knowledge Update 的写入与失效语义不变 | 单元 + 集成（既有测试回归）：`MemoryEntry` 往返、`deactivateByTarget` 语义 | **可验收**（`Schema.sq:96`、`MemoryUseCases:31/53`、`KnowledgeUpdateExecutionUseCase:76-81`） |
| **AC-18** | Memory **不是**索引类型：`ProjectIndexEntryType` 不得新增 MEMORY | 单元测试（同 AC-01 负向） | **可验收** |
| **AC-19** | 若 4B 将 Memory 接入 Context：必须经降级后的 Source 路径，且满足 AC-11（不重复注入） | 集成测试：Memory 条目只出现一次 | **需先实现后验证**（取决于 4B 是否接入） |
| **AC-20** | 文档与注释不得把三概念合并表述（事实源／索引类型／Context 条目） | 评审项（人工 + grep 检查） | **可验收**（本 4A 文档已按此纪律书写） |

---

## F. 排序确定性、空输入与来源缺失

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-21** | 排序确定性：同候选集 ⇒ 同 items 顺序（`priority.rank` 稳定排序，同级保持声明顺序） | 单元测试：同输入多次构建 items/itemId 序列完全一致 | **可验收**（`ContextSelector:210-250`；`ContextSelectionTest` 8） |
| **AC-22** | 预算确定性：同 budget/estimator ⇒ 同入选与淘汰集合，且淘汰可解释（`omittedItemIds`） | 单元测试：预算边界样例 + 淘汰记录断言 | **可验收**（`ContextBudgetGuard:134-151`） |
| **AC-23** | 空上下文行为明确：无任何候选 ⇒ 返回空 `items` 的合法 pack（不抛异常、不伪造条目） | 单元测试：空 scope 场景 | **可验收**（需在 4B 补一条显式用例；现有测试未覆盖"零候选"） |
| **AC-24** | 来源不存在/越界行为明确：Project/Session/焦点章节越界 ⇒ 类型化错误（不得静默降级为空上下文） | 单元测试：`EntityNotFound` / `InvalidOperation` 断言 | **可验收**（`ContextEngineUseCases.kt:57-76/109-115`；`ContextBoundaryTest` 3） |

---

## G. 必须保持的不变量（Invariants）

| ID | 不变量 | 验证方式 | 当前状态 |
|---|---|---|---|
| **INV-1** | Context 构建**全程只读**：不写 Canonical Story / Story State / Memory / Workflow / Task | 集成测试：构建前后关键表行数与内容不变（含 Original 只读触发器未被触发） | **可验收** |
| **INV-2** | **不新增第二套** Chapter / Draft / Story / Context / World Model 模型 | 结构检查：包与类型清单（人工 + grep） | **可验收** |
| **INV-3** | **不引入** FTS5 / 全文检索 / 语义检索 / RAG / Vector / Embedding / 云检索（§25、FD-10 生效中） | **机械扫描**：`storage/**/*.sq` 无 `CREATE VIRTUAL TABLE`；main 源集无 embedding/vector 表/索引/provider 调用与云 SDK 依赖 | **可验收**（本轮只读扫描已通过） |
| **INV-4** | **不修改** P2 Original 只读契约、CommitHistory Format Contract v26 四值域、Phase 3 Closure D-1…D-4 | 回归测试（既有）：`StoryStateOriginalProtectionTest`、`RevertLegacyFormatTest`、`WorldModelUpdatePipelineTest` 等保持通过 | **可验收** |
| **INV-5** | **不新增** Context/Snapshot 持久化表（除非出现跨进程复现的真实需求并先裁决） | 机械扫描：`.sq`/`.sqm` 无 Context/Snapshot 表；schema 版本未因本阶段变化 | **可验收** |
| **INV-6** | 检索不使用 LLM 做排序或摘要（上下文选择必须确定性） | 结构检查 + 单元测试：`ContextSelector`/`ContextSources` 无 LLMGateway 依赖 | **可验收** |

---

## H. 禁止回归项（No-Regression）

| ID | 禁止回归 | 验证 |
|---|---|---|
| **NR-1** | 写作链既有上下文输入不得减少：`PlanningContext.memories`（canon 优先顺序）/`characters`/`vocabulary`/`foundation`/`authorContext` 仍进入 Planner（并保持 Writer 既有 `memories`/`vocabulary`/`foundation`） | `PlannerAgentTest` 7 / `WriterAgentTest` 7 / `PlanningContextAssemblyTest` 5 / `PlanningContextWorldModelTest` 4 |
| **NR-2** | LCL 章节窗口包能力不得退化（horizon / narrative / threads / foreshadows / events + 预算） | `ChapterContextPackTest` 11 / `ForeshadowContextPackTest` 3 |
| **NR-3** | 编辑器装载语义不得被 Context 收敛改动（`WriterGateway.loadContext` 仍返回 draft/phase/待审正文） | `DesktopWriterTest` 11 / `WriterViewModelTest`（android） |
| **NR-4** | Inspection 视图不得因降级而丢失既有字段 | `WorkspaceInspectorContextTest` 7 / `DesktopWorkspaceTest` 4 |
| **NR-5** | 索引构建确定性不得退化（同数据 ⇒ 同 entries 顺序/version） | `ProjectIndexBuildTest` 8 / `ProjectIndexBoundaryTest` 5 |
| **NR-6** | Phase 3 写入链与 Original 写保护不得被 4B 触及 | `WorldModelUpdatePipelineTest` 13 / `StoryStateOriginalProtectionTest` 3 / `DatabaseInitializerTest` 4 |

> 计数为**既有一轮 XML 的引用值**（本草案未重跑测试）；4B/4C 执行时必须重新实测并替换为当轮数字。

---

## I. 状态汇总

| 分类 | 条目 |
|---|---|
| **可验收（现有能力即可验证）** | AC-01、AC-02、AC-04、AC-05、AC-09、AC-12（字段级）、AC-13、AC-14、AC-17、AC-18、AC-20、AC-21、AC-22、AC-24、INV-1…INV-6、NR-1…NR-6 |
| **需先实现后验证（当前链路/能力不存在）** | AC-03、AC-06、AC-07、AC-08、AC-10、AC-11、AC-15、AC-16、AC-19、AC-30A、AC-30B、AC-35（A 待决策） |
| **需在 4B 补显式用例** | AC-23（零候选空上下文） |
| **明确不做（本阶段）** | 新增 ContextSnapshot 类型 / 新增 Memory 索引类型 / 新增 Context 持久化表 / 任何 FTS5·向量·云检索 / Character·Event·Timeline·Foreshadow 索引（§13 §8 暂不要求） |

**草案结论**：§13 §12 Completion Gate 的正向三项（入口数=1、Snapshot 可复现且被消费、结构化检索覆盖五类并接入 Agent）**当前均不可验收**，须经 Phase 4B 实现后按本清单重写为可执行断言；负向项（INV-3）与不变量（INV-1/2/4/5/6）当前**已可机械验证**。

---

## J. 增补（2026-10-17）：payload 契约相关验收项（**最小增量，保留上文章节语义**）

> 依据：`qianyan-phase4a-context-contract-freeze-addendum-payload.md`（Alt A）。以下条目为 AC-06/AC-07 的补充，不替换既有条目。

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-25** | **payload 类型与 SourceKind 严格对应**：NOVEL 条目携带 `NovelPayload`、STORY_FOUNDATION 携带 `FoundationPayload`、VOCABULARY 携带 `VocabularyPayload`；其余 SourceKind 不得携带 payload | 单元测试：逐 item 断言 payload 类型；负向：人为注入错类型 ⇒ 类型化 `InvalidOperation` | **需先实现后验证** |
| **AC-26** | **payload 参与 `packVersion`**：同 request/content、仅 payload 不同 ⇒ 版本不同；同 request/content/payload ⇒ 版本相同；null payload 稳定 | 单元测试：构造两种 payload 的同一 item，比较版本；重复构建比较稳定 | **需先实现后验证** |
| **AC-27** | **payload 不影响既有选择语义**：`content` 文本、`itemId`、`priority` 与加 payload 前逐项一致（J-2 的预算规则变化见 AC-31/AC-32，二者分离验收） | 回归测试：固定夹具下对比"无 payload 基线" | **需先实现后验证** |
| **AC-28** | **Pack 覆盖字段只来自 payload，且不双重注入**：NOVEL/FOUNDATION/VOCABULARY 对应 prompt 段落中的 marker 只出现一次；Assembly 不再读取相应 Repository | 集成测试：请求捕获、marker 计数 = 1；结构检查（Assembly 构造依赖中移除相应仓储） | **需先实现后验证** |
| **AC-29** | **兼容字段不丢失**：`characters` / `memories`（Resolver）与 `variantName/variantDirective`、`authorContext`、continuation 仍按既有语义进入 prompt | 既有测试回归 + marker 断言 | **需先实现后验证** |
| **AC-30A (J-1)** | **Vocabulary Source Dataset 等价（完整来源数据集）**：统一后的 `VocabularyEntry` 完整来源数据集与旧 `PlanningContextAssembly` 路径在**同一夹具、同一明确查询范围**下属于**同一批已确认词条且字段值一致**。允许差异仅限“确定性排序”；J-2 的预算截断属于 AC-30B 层，**不得用经过预算裁剪的 ContextPack 结果证明本项**。若旧路径无显式状态过滤而新路径增加 APPROVED 过滤，必须在明确有效数据前提下做 A/B，并**单独验证未批准条目不得进入模型输入**；不能仅以“新 Source 等于 APPROVED 集合”证明等价。**作用范围（R5）**：J-1 仅改变 Context Source `VOCABULARY` 的数据来源语义（approved `VocabularyEntry` 集合），**不修改 `ProjectIndexEntryType` 定义、不改变 ProjectIndex 中 Vocabulary 类型的索引语义**；不得误解为 ProjectIndex Vocabulary 重构 | 集成测试：同一夹具、同一查询范围下，分别经旧 Assembly 完整来源数据集与新 Vocabulary Source 完整来源数据集取得集合，按 (canonical, aliases, replacement) 做集合等价断言，并断言不存在静默漏读 / 漏查或字段变化；负向：pending 条目不得替代 approved、未批准条目不得进入模型输入 | **需先实现后验证** |
| **AC-30B (J-2)** | **Model Input Vocabulary Subset 与预算选择**：来源数据集进入 `ContextSelector` 后：(1) 预算充足时选择结果符合既定契约；(2) 预算紧张时优先级与省略规则符合 J-2；(3) 选择结果可重复、顺序稳定；(4) 被省略条目可从 omitted 信息识别；(5) 实际进入 Planner / Writer 的词条集合与选定 `ContextPack` payload 一致；(6) 不得仅以 `ContextSelector` 单元测试宣称 Planner / Writer 实际输入已验收 | 集成测试 / 请求捕获：预算边界、长 DRAFT 挤压、关键来源缺失、重复执行、omitted 列表、Planner / Writer 实际请求与 pack payload 对照。当前无 prompt 级测试 ⇒ 标记验收缺口，本轮不补测试 | **需先实现后验证** |
| **AC-31 (J-2)** | **关键上下文确定性入选**：NOVEL / STORY_FOUNDATION / VOCABULARY 在两阶段选择中先于普通条目；关键总量超预算时按"逐条放得下即选、放不下即淘汰并继续"的确定性规则处理；`omittedItemIds` 顺序为"关键淘汰（关键顺序）+ 普通淘汰（既有顺序）"；同输入重复执行结果完全一致。**本项与 AC-30B 同属 Model Input Vocabulary Subset 层，不替代 AC-30A 的来源数据集等价** | 单元/集成测试：预算边界、长 DRAFT 挤压、关键来源缺失、重复执行稳定性、omission 列表断言 | **需先实现后验证** |
| **AC-32 (J-2)** | **预算行为变化被明示且可验证**：预算充足时入选集合与 `items` 顺序与改造前一致；预算紧张时关键上下文优先于普通上下文；默认 budget/estimator 未变。**本项与 AC-30B 同属 Model Input Vocabulary Subset 层，不替代 AC-30A** | 回归测试：固定预算下的前后对比；`ContextBudget` 默认值断言 | **需先实现后验证** |
| **AC-33 (J-3)** | **Variant fail-fast**：`chapter.variantId` 与 `project.state.activeVariantId` 一致时正常；不一致时在 `ContextEngine.build` **之前**以 `SCOPE_MISMATCH` 中止，且未构建 pack、无状态副作用。**调用次数口径（R3）**：该分支 `ContextEngineUseCases.build` 调用次数 = **0**，不构成成功/失败的入口调用、不计入 AC-06 的 build 次数；匹配 variant 的正常路径仍按 AC-06 执行 | 集成测试：一致/不一致两条路径；不一致路径断言 ContextEngine 未被调用 | **需先实现后验证** |
| **AC-34 (J-4)** | **Legacy 受控豁免**：无 pack 的 `assemble(...)` 重载保留并标注 `@Deprecated`；`application/src/main` 下**无**对该重载的调用（静态守卫检查）；不改 7 个既有测试文件；其内部实现（fail-fast 或旧实现+告警）在 4B.1 明确选择并记录。**生产边界（R4）**：该重载是**受控兼容例外**，不属正式生产入口、不计入 AC-06 的入口数，生产代码不得调用；AC-06 的 build 调用次数约束只适用于使用 `ContextPack` 的正式入口。**静态守卫（S2）**：必须存在检查机制；具体实现（源文件扫描 / Gradle task / Detekt / Lint）留待 4B.1 选择，文档阶段不锁定；**已知局限**：静态守卫只覆盖其覆盖范围内的静态调用，不能单独证明所有运行时路径绝不调用，4B.1 报告须如实记录 | 静态扫描/守卫测试 + 代码审查 | **需先实现后验证**（本轮仅冻结要求） |

**AC-30 编号变更记录（2026-10-17）**：原 AC-30 拆分为 **AC-30A**（Vocabulary Source Dataset 等价）与 **AC-30B**（Model Input Vocabulary Subset 与预算选择）；原编号保留追溯，不再单独使用。现有测试仅部分覆盖 AC-30A（新 Source 对 approved 集合），未覆盖旧/新 A/B、AC-30B 的预算 / prompt 证据；本轮不补测试，作为验收缺口记录。

---

### K. Inspector 任务上下文（A，2026-10-17 拟议，待决策）

> 依据 addendum §J-6。本节不表示 A 已批准，也不表示 Phase 4B.1 已验收。

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-35 (A)** | **Inspector 任务上下文来源与状态**：Inspector 的“任务上下文”只能展示某次 Agent Task 实际使用的 `ContextPack` 快照，并能追溯 task / session 归属；不得用重新 build 的相似 Pack、当前 Repository 事实或仅相同 `packVersion` / itemId 冒充。必须区分四状态：① 无 Agent 任务 → `暂无任务上下文`；② 任务 Pack 不可获取 → `上次任务上下文已失效`；③ 实际 Pack 可获取 → 展示真实 Pack + 任务归属；④ 新任务执行中 → 当前任务尚未生成 / 展示当前任务 Pack，上一任务不得伪装为当前。当前事实必须单独标识且不得调用 `ContextEngine.build`。 | 结构与状态测试：断言 Inspector 不在对象选择 / Tab 切换时调用 `ContextEngineUseCases.build`；断言四状态文案与数据来源；断言历史 Pack 不可用时不回退重建。当前无实现，证据缺口。 | **待决策（需用户裁决）** |

**未决（阻断 4B.1）**：~~VOCABULARY 数据源~~（已裁决 J-1=A，实施前须证明内容等价）、~~预算淘汰~~（已裁决 J-2=A，规则见 addendum §J-2）、~~Variant 作用域~~（已裁决 J-3=A）、~~legacy 入口~~（已裁决 J-4=C，内部实现待 4B.1 确认）。**无新增未决项（截至当时）。**

**2026-10-17 本轮追加状态**：

- **B（词库两层语义）**：addendum §J-1.1 / §J-2.1 与 AC-30A / AC-30B 为**拟议修订**，待用户确认后生效；现有测试未覆盖 AC-30B 的 prompt 级证据，标记为验收缺口，本轮不补测试。
- **A（Inspector 任务上下文）**：来源、生命周期与四状态见 addendum §J-6 / AC-35，**待决策**；当前无可用任务 Pack 来源，禁止以重新构建的 Pack 冒充实际任务快照；**Phase 4B.1 仍未验收**。
