# Qianyan｜Phase 4C 验收标准草案（Acceptance Criteria Draft）

> - 依据：`qianyan-dev-implementation-plan-v3.1.md` **§13**（尤其第 10/12 项：测试与 Completion Gate，行 542–555 / 555），以及本目录 Phase 4A 的 `qianyan-phase4a-context-contract-freeze.md`（D1–D4）与 `qianyan-phase4a-architecture-audit.md`
> - 性质：**草案**。本文件是"待验证清单"，**不是验收结论**。每项都标注当前状态：`可验收`（现有能力即可验证）／`需先实现后验证`（当前链路不存在，必须先实现才能验证）。
> - 基线：`feature/novel-ide` @ `85c4518`。本草案**未修改任何代码**。
> - 反例纪律：任何条目**不得**以"计划中存在"或"模型已定义"当作已实现；`需先实现后验证` 的条目在 4B 完成后必须重写为可执行断言。

---

## A. 结构化检索支持边界（五类）

| ID | 验收条目 | 验证方式（测试类型） | 当前状态 |
|---|---|---|---|
| **AC-01** | Phase 4 的结构化检索**仅**覆盖五类：`NOVEL` / `CHAPTER` / `DRAFT` / `STORY_FOUNDATION` / `VOCABULARY`，与 `ProjectIndexEntryType` 完全一致（不多不少） | 单元测试：断言枚举值集合等于上述五项；负向：不存在 `CHARACTER`/`EVENT`/`TIMELINE`/`FORESHADOW`/`MEMORY` 成员 | **可验收**（`ProjectIndexModels.kt:51-57`；`ProjectIndexBuildTest`/`QueryTest` 8+3） |
| **AC-02** | 每类都能被**结构化取回**（by-ID 或 list-by-scope），且返回对象来自既有仓储、不复制内容 | 集成测试：对五类各取一条并断言 ID 一致、来源仓储一致 | **可验收**（`NovelRepository:24`、`ChapterRepository:25/28`、`DraftRepository:25/36`、`StoryFoundationRepository:25`、`VocabularyRepository:31/40`） |
| **AC-03** | 检索有**真实消费方**：检索结果能进入 Agent/上下文组装（而非只有测试调用） | 集成测试：从 Agent 入口出发断言检索结果出现在 `ContextPack.items` 或模型请求中 | **需先实现后验证**（`find:114`/`findByType:105` 目前仅测试调用） |
| **AC-04** | "Previous Chapters" 经 `ChapterId / Sequence / Relation` 获取，**不得**实现为 RAG/向量/全文 | 机械扫描（见 INV-3）+ 正向测试：按 order/horizon 取前文章节 | **可验收**（`ChapterRepository.listByNovel:28` + `ChapterContextProjector` 窗口 `ChapterContextPackTest:11`） |
| **AC-05** | `find(query)` 的语义边界明确且被断言：**仅**匹配索引条目标签/可检索词，**不**是全文检索 | 单元测试：命中/未命中样例（标签命中、正文词不命中） | **可验收**（`ProjectIndexUseCases.kt:50` 注释 + `ProjectIndexQueryTest:3`）；若 4B 改变语义需重写 |

---

## B. ContextPack 构建与实际模型请求的连接

| ID | 验收条目 | 验证方式 | 当前状态 |
|---|---|---|---|
| **AC-06** | **存在单一入口**：任务级上下文只经 `ContextEngineUseCases.build(ContextRequest) → ContextPack` 产出；`PlanningContextAssembly` / `ChapterContextCompileUseCases` 已降级为 Source 或视图（不得各自拼 prompt） | 负向 + 结构测试：断言二者不再有独立 prompt 渲染路径；入口计数 = 1 | **需先实现后验证**（当前 4 条链路：审计 R1） |
| **AC-07** | `ContextPack` 的内容**真正进入**模型请求（可复现"AI 这次看到了什么"） | 集成测试：抓取发送给 `LLMGateway` 的请求，断言包含 pack 入选条目的内容片段（而非只有 packId/version） | **需先实现后验证**（当前 `NovelAgent.kt:488-489/780-781` 只记录 id/version） |
| **AC-08** | 同一任务输入下，Inspector 与写作链观察到**同一** Context（消除四套不一致） | 集成测试：两处入口对同 `ContextRequest` 得到相同 `packVersion` 与相同 itemId 序列 | **需先实现后验证** |

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
| **需先实现后验证（当前链路/能力不存在）** | AC-03、AC-06、AC-07、AC-08、AC-10、AC-11、AC-15、AC-16、AC-19 |
| **需在 4B 补显式用例** | AC-23（零候选空上下文） |
| **明确不做（本阶段）** | 新增 ContextSnapshot 类型 / 新增 Memory 索引类型 / 新增 Context 持久化表 / 任何 FTS5·向量·云检索 / Character·Event·Timeline·Foreshadow 索引（§13 §8 暂不要求） |

**草案结论**：§13 §12 Completion Gate 的正向三项（入口数=1、Snapshot 可复现且被消费、结构化检索覆盖五类并接入 Agent）**当前均不可验收**，须经 Phase 4B 实现后按本清单重写为可执行断言；负向项（INV-3）与不变量（INV-1/2/4/5/6）当前**已可机械验证**。
