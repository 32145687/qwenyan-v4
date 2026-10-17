# Qianyan｜Phase 4A 架构审计报告（Context / Retrieval）

> - 依据：`qianyan-dev-implementation-plan-v3.1.md` **§13**（行 561–627）；本次审计基线 `feature/novel-ide` @ `85c4518`
> - 性质：**只读审计**。本轮未修改任何业务源码 / 测试 / schema / migration；结论全部关联实际文件与符号（尽量给出行号）。
> - 术语纪律：区分「已实现且有生产调用方」／「已实现但无生产调用方」／「仅接口或模型」／「未实现」／「证据不足」。

---

## 1. 审计范围（本轮实际读取的符号）

| 领域 | 文件 / 符号 |
|---|---|
| 上下文模型 | `core/model/.../context/ContextPack.kt`（`ContextSourceKind:37`、`ContextPriority:52`、`ContextItemRef:66`、`ContextItem:83`、`ContextBudget:122`、`ContextBudgetGuard:134`、`ContextRequest:154`、`ContextSelection:173`、`ContextPack:189`、`ContextSelector:210`、`ContextPackVersion:254`）；`core/model/.../context/StoryWorldContext.kt:32`；`core/model/.../lcl/ChapterContextPack.kt:57-71` |
| 上下文构建 | `application/.../usecase/context/ContextEngineUseCases.kt`（`build:51`、`isStale:107`）；`usecase/context/ContextSources.kt`（`ContextPriorities:32`、7 个 Source `:71/94/119/150/190/225/252`、装配列表 `:291-299`、`itemId` 生成 `:58`） |
| 写作链消费 | `usecase/agent/NovelAgent.kt`（`contextEngine.build:478`、仅记录 id/version `:488-489`、`:780-781`）；`usecase/writing/planning/PlannerAgent.kt`（`renderInput:88`）；`usecase/writing/WriterAgent.kt`（`renderInput:87`） |
| 规划上下文 | `usecase/writing/planning/PlanningContextAssembly.kt`（`assemble:41/48`、字段装配 `:100-127`）；`usecase/writing/planning/PlanningContext.kt:32` |
| LCL 投影 | `usecase/lcl/ChapterContextCompileUseCases.kt`（`compileChapterContext:42`、`invalidatePack:77`） |
| 编辑器装载 | `usecase/writing/WriterFacade.kt`（`WriterGateway:141`、`loadContext:55`、`WriterChapterContext:119`） |
| 世界/记忆读侧 | `usecase/writing/context/StoryWorldContextResolver.kt`（`resolve:60`、memory 读取 `:66/68`）；`usecase/story/WorldModelReadUseCases.kt` |
| 索引 | `usecase/index/ProjectIndexUseCases.kt`（内存 store `:64`、`rebuild:81`、`entries:101`、`findByType:105`、`find:114`、`isStale:129`、`buildEntries:161`）；`core/model/.../projectindex/ProjectIndexModels.kt:51` |
| 索引消费 | `usecase/workspace/WorkspaceUseCases.kt`（`context():336-360`、`index.rebuild` 调用点 `:104/430/546`） |
| Memory | `core/model/.../memory/MemoryModels.kt:22`；`storage/.../db/Schema.sq:96`；`usecase/memory/MemoryUseCases.kt:31/53/59`；`KnowledgeUpdateExecutionUseCase.kt:70/76-81` |
| 五类读取 API | `NovelRepository:24/27/36`、`ChapterRepository:25/28`、`DraftRepository:25/28/33/36`、`StoryFoundationRepository:25`、`VocabularyRepository:31/40` |
| 装配 | `application/.../di/ApplicationContainer.kt`（`contextEngine:275`、`projectIndex:422`、`storyWorldContextResolver:583`、`chapterContextPack:610`、`planningContextAssembly:685`、`writerGateway:712`） |

---

## 2. 当前真实调用链（四条 + 索引）

### 链路 1 · ContextEngine（任务级 Context）
```
NovelAgent.buildContext (NovelAgent.kt:472-490)
  → ContextEngineUseCases.build(ContextRequest)            (ContextEngineUseCases.kt:51)
      → 7 个 ContextSource.candidates(scope, request)       (ContextSources.kt:291-299 → :86)
      → ContextSelector.select(candidates, budget, estimator) (ContextPack.kt:210)
      → ContextPackVersion.of(...)                          (ContextPack.kt:254)
  → 仅记录 state.contextPackId / contextPackVersion          (NovelAgent.kt:488-489)
  → 结果对象只带 contextPackId / contextPackVersion           (NovelAgent.kt:780-781)
  ⇒ **pack.items 不进入任何模型请求**（无渲染路径）
```
另一消费方：`WorkspaceUseCases.context()`（Inspector Context Tab）把 pack 投影为视图（`WorkspaceUseCases.kt:353-360`）。

### 链路 2 · PlanningContextAssembly（写作链**实际**的 prompt 上下文）
```
NovelAgent.executeSkill (NovelAgent.kt:524 / :562)
  → PlanningContextAssembly.assemble(UserWritingRequest)     (PlanningContextAssembly.kt:48)
      ← Novel / Vocabulary / StoryWorldContextResolver / StoryFoundation / AuthorContextProjection
      → PlanningContext{characters: CharacterLite[], memories = worldContext.orderedVisible,
                        vocabulary, worldContext, foundation, authorContext, continuation refs} (…:100-127)
  → PlannerAgent.renderInput(context, policies)              (PlannerAgent.kt:88)
      渲染：novelTitle(:97)、foundation(:106)、authorContext(:115/:124)、characters(:132-134)、
            memories(:138-140)、vocabulary(:142-144)、continuation(:152-157)
  → WriterAgent.renderInput(context, plan, policies)          (WriterAgent.kt:87)
      渲染：request/novel/variant(:91-99)、foundation(:101)、memories(:135-137)、vocabulary(:139-141)
      未渲染：characters、worldContext
```

### 链路 3 · ChapterContextCompile（LCL 窗口投影，UI/查阅）
```
desktop StoryExtraTabs.kt:348
  → ChapterContextCompileUseCases.compileChapterContext(...)  (ChapterContextCompileUseCases.kt:42)
      ← StoryWorldContextResolver.resolve + NarrativeStateFold + ChapterRepository.listByNovel
  → ChapterContextPack（含 horizonWindow / activeNarrative / threads / foreshadows / recentEvents + TokenBudgetGuard）
  ⇒ 只读投影；**不是**写作 prompt 的来源
```

### 链路 4 · WriterGateway.loadContext（编辑器装载）
```
desktop WriterController.kt:150 / Android WriterViewModel.kt:78,134
  → WriterFacade.loadContext (WriterFacade.kt:55-69)
      ← Chapter / Novel.title / drafts.latestDraft / workflow.getChapterProgress / WorkingDraft 待审正文
  → WriterChapterContext（draft + phase + pendingWorkingContent）
  ⇒ 编辑器状态装载；**不是**供模型使用的上下文
```

### 索引与 Memory 路径
```
ProjectIndex：rebuild(ProjectIndexUseCases.kt:81) ← WorkspaceUseCases.kt:104/430/546（每次调用重建）
                                 ← TaskExecutor(PROJECT_INDEX_REBUILD)（ApplicationContainer.kt:456）
              查询：find:114 / findByType:105 —— **仅测试调用**
              存储：进程内 LinkedHashMap（:64），**不落库**；无 FTS5（storage/**/*.sq 无 CREATE VIRTUAL TABLE）
Memory：MemoryEntry(Schema.sq:96) ← KnowledgeUpdate 写入(KnowledgeUpdateExecutionUseCase.kt:76-81)
        → StoryWorldContextResolver 读取(:66/68) → StoryWorldContext.orderedVisible
        → PlanningContext.memories(PlanningContextAssembly.kt:118) → Planner/Writer prompt
        ⇒ 已进 prompt，但**不经 ContextEngine**、**不是索引类型**
```

---

## 3. 真实已实现能力

| 能力 | 状态 | 证据 |
|---|---|---|
| 任务级 Context 构建（确定性选择 + 预算 + 版本指纹） | **已实现且有调用方**（NovelAgent / Inspector） | `ContextEngineUseCases.kt:51-101`；`ContextSelector`（`ContextPack.kt:210`）；`ContextPackVersion`（`:254`） |
| 7 类 Context 来源（PROJECT / PROJECT_STATE / NOVEL / CHAPTER / DRAFT / STORY_FOUNDATION / VOCABULARY） | **已实现**（Source 均有实现） | `ContextSources.kt:71/94/119/150/190/225/252` |
| 任务相关优先级（按 purpose 决定 HIGH/MEDIUM/LOW） | **已实现** | `ContextPriorities`（`ContextSources.kt:32`；调用点 `:82/107/133/208`） |
| 预算裁剪与淘汰可解释（omittedItemIds） | **已实现** | `ContextPack.kt:134-151`、`:210-250` |
| 输入指纹（request + 入选条目 + 淘汰集合） | **已实现**（无生产消费方） | `ContextPack.kt:254-285` |
| 失效判定（重编比对） | **已实现**（仅测试调用） | `ContextEngineUseCases.kt:107`；`ContextEngineBuildTest.kt:113/135/140` |
| 规划上下文组装（含 characters / memories / vocabulary / foundation / author / continuation） | **已实现且进入 prompt** | `PlanningContextAssembly.kt:100-127`；`PlannerAgent.kt:97-157`；`WriterAgent.kt:101-141` |
| LCL 章节窗口包（含 horizon / narrative / threads / foreshadows / events + 预算） | **已实现**（UI 消费） | `ChapterContextCompileUseCases.kt:42-70`；`ChapterContextPack.kt:57-71`；`StoryExtraTabs.kt:348` |
| 结构化索引（5 类条目，仅 ID 元组 + 标签） | **已实现**（构建/条目有调用方） | `ProjectIndexUseCases.kt:161-275`（五类 `ProjectIndexEntry`）；`ProjectIndexModels.kt:51-57` |
| 五类目标对象的读取能力 | **已实现**（by-ID / list-by-scope） | `NovelRepository:24/27`、`ChapterRepository:25/28`、`DraftRepository:25/28/33/36`、`StoryFoundationRepository:25`、`VocabularyRepository:31/40` |
| Memory 事实源（持久化 + 读写） | **已实现** | `Schema.sq:96`；`MemoryUseCases.kt:31/53/59`；`KnowledgeUpdateExecutionUseCase.kt:70-81` |
| 世界模型读侧（结构化 characters/characterStates/worldRules/events/timeline/foreshadows） | **已实现**（读侧） | `StoryWorldContext.kt:32-58`；`WorldModelReadUseCases.kt`；`get_world_context` 工具（`ProductTools.kt`） |
| 负向能力检查（无 FTS5 / 无 embedding / 无 vector） | **已满足**（本轮只读扫描） | `storage/src/main/sqldelight` 无 `CREATE VIRTUAL TABLE`；main 源集无 embedding/vector 表或 provider 调用（仅注释声明不使用） |

---

## 4. 未实现 / 已实现无消费方 / 证据不足

| 能力 | 状态 | 证据 |
|---|---|---|
| 独立 `ContextSnapshot` 类型 | **不存在** | 全仓 main 源集 `grep ContextSnapshot` = 0 |
| ContextPack → 模型请求的连接 | **未实现**（pack 内容被丢弃） | `NovelAgent.kt:488-489`、`:780-781`（只存 id/version；无渲染点） |
| 结构化检索（按五类取回并供 Agent 使用） | **未实现** | `ProjectIndexUseCases.find/findByType` 仅测试调用（`ProjectIndexQueryTest.kt:32-44`、`ProjectIndexBuildTest.kt:177-178`）；无从 Agent 到索引的路径 |
| `worldContext` 结构化事实进入 prompt | **未实现**（World Model/Timeline/Foreshadow 无渲染路径） | `grep worldContext` 在 `PlannerAgent.kt`/`WriterAgent.kt` = 0 命中；`PlanningContext.worldContext` 无渲染消费方 |
| characters 进入 Writer prompt | **未实现**（仅 Planner 渲染） | `PlannerAgent.kt:132-134` 有；`WriterAgent.kt` 无 `characters` 命中 |
| 索引的检索式查询（关键词/全文/语义） | **未实现**（且**禁止**本阶段引入） | `find` 仅匹配标签与可检索词（`ProjectIndexUseCases.kt:50` 注释）；无 FTS5/向量 |
| Memory 作为 Context 来源 / 索引类型 | **未实现**（本阶段明确不做） | `ContextSourceKind` 无 MEMORY；`ProjectIndexEntryType` 无 MEMORY |
| 增量失效 / 缓存（`isStale` 优化） | **未实现** | `isStale` 全量重编（`ContextEngineUseCases.kt:107`） |
| 入口唯一（§13 Gate: 入口数 = 1） | **未达成**（当前 4 条） | §2 的四条链路 |
| `PlanningContextAssembly` / `ChapterContextCompileUseCases` 降级为 Source/视图 | **未达成** | 二者仍各自组装（`PlanningContextAssembly.kt:48`、`ChapterContextCompileUseCases.kt:42`） |
| Snapshot 持久化需求证据 | **证据不足** | `contextPackId/packVersion` 无持久化点（`application/src/main` + `storage/src/main` 除 `NovelAgent.kt` 外 0 命中）；无跨进程复现需求记录 |
| 单元 2 新增的 `get_world_context` 工具是否被 Agent 使用 | **证据不足**（工具已注册且有测试） | 工具授予 `CoreSkills`（Unit 2）；本轮未审计 Agent 实际调用点，标注为待 4B 核查 |

---

## 5. 架构风险与待办

| # | 风险 / 待办 | 证据 | 影响 | 归属 |
|---|---|---|---|---|
| **R1** | **入口不唯一**（4 条链路并存），与 §13 Gate "入口数 = 1" 直接冲突 | §2 链路 1–4 | 同一事实可能以不同口径进入不同消费者（Inspector 看到 A、Agent 看到 B） | 4B（§13 §8 降级为 Source/视图） |
| **R2** | **ContextEngine 产物无人消费**：pack 只留 id/version | `NovelAgent.kt:488-489/780-781` | "按任务取 Context"目前是空转；写作链实际上下文来自 PlanningContextAssembly | 4B（打通 pack → 请求 或 明确废弃该路径，二者必选其一） |
| **R3** | **结构化世界事实不进 prompt**（events / timeline / foreshadows / worldRules / characterStates 全无渲染路径；characters 仅进 Planner） | `grep worldContext` = 0；`WriterAgent` 无 characters | §13 §5 "世界模型/Timeline/Foreshadowing 按需取用"未实现；写作链看不到世界状态 | 4B/后续（受 §13 §8 五类索引限定约束） |
| **R4** | **索引被当作对象目录使用且每次重建** | `WorkspaceUseCases.kt:104/430/546`（每次 `rebuild`） | 性能与一致性风险（每次交互重建全量索引） | 4B（缓存/失效策略；须与 D4 版本语义一致） |
| **R5** | **两套 packVersion 语义并行** | `ContextPack.packVersion`(`ContextPack.kt:254`) vs `ChapterContextPack.packVersion`(`ChapterContextPack.kt:64`) + `invalidatePack`(`ChapterContextCompileUseCases.kt:77`) | 失效判定口径分裂，"可复现"难以统一断言 | 4B（统一到 Target Context 一侧） |
| **R6** | **Memory 双路径投影风险**：现经 Resolver→planning memories；若 4B 直接加 Memory source 会重复注入 | `PlanningContextAssembly.kt:118`；`ContextSourceKind:37` 无 MEMORY | 同一事实重复占用预算、prompt 冗余 | 4B（D2 去重 + D3 前置） |
| **R7** | **去重缺失**：`ContextSelector` 不按 itemId/内容去重 | `ContextPack.kt:210-250` | 跨来源重复条目可能入选；D2 要求的唯一性无代码保证 | 4B（D2 路线 A/B） |
| **R8** | **`isStale` 无消费方 + 全量重编成本** | `ContextEngineUseCases.kt:107`；仅测试调用 | 4B 若接入需先定义成本边界，否则每次判定代价随规模增长 | 4B（D4 约束） |
| **R9** | **`find`/`findByType` 未接线**，但 `find` 已被文档/规划当作"检索" | `ProjectIndexUseCases.kt:105/114`；`ProjectIndexQueryTest` | 若 4B 直接接 `find` 当检索能力，会把"标签匹配"误当结构化检索 | 4B（明确 `find` 语义边界） |
| **R10** | **工具 `get_world_context` 与 Context 的关系未审计** | Unit 2 注册于 `CoreSkills`；测试 `GetWorldContextToolTest` 4 | 存在"第二条世界模型读路径"的可能，需在 4B 与 Context 收敛一并处理 | 4B（核查 Agent 调用点） |

---

## 6. 与 v3.1 §13 的差距对照（Gate 视角）

| §13 Gate（行 561–627） | 当前状态 |
|---|---|
| 正向：Context 入口数 = 1 | **未达成**（4 条，R1） |
| 正向：Snapshot 可复现 | **部分**：`ContextPack` 具备确定性（内容物化 + 指纹），但**产物不被消费**，无法端到端验证"AI 这次看到了什么"（R2） |
| 正向：结构化 Retrieval 覆盖五类现行索引 | **未达成**：五类索引条目已能构建，但**无检索入口接到 Agent**（R9、§4） |
| 正向：budget / `ContextPriorities` 仍生效 | **已具备**（`ContextPriorities:32`、`ContextSelector:210`） |
| 正向：未超范围扩 Storage | **满足**（无 Context/Snapshot 表；索引为内存派生） |
| 负向：未引入 §25 禁止能力（可机械验证） | **满足**（无 FTS5 虚拟表；无 embedding/vector 表与调用） |
| 判定式：正向 AND 负向 | **未达成**（正向 3 项未达成） |

---

## 7. 测试证据（**本轮未重跑**，引用既有 XML 计数）

本阶段为只读审计，未运行任何 Gradle 任务；下列计数取自本机现有 XML（`85c4518` 之前一轮全量运行产物），仅用于说明"当前能力有测试覆盖"，**不代表本轮执行结果**：

| 测试类 | tests | fail/err |
|---|---|---|
| `ContextEngineBuildTest` | 8 | 0 / 0 |
| `ContextSelectionTest` | 8 | 0 / 0 |
| `ContextEngineRequestTest` | 6 | 0 / 0 |
| `ContextScopeTest` | 4 | 0 / 0 |
| `ContextBoundaryTest` | 3 | 0 / 0 |
| `WorkspaceInspectorContextTest` | 7 | 0 / 0 |
| `ChapterContextPackTest` | 11 | 0 / 0 |
| `ForeshadowContextPackTest` | 3 | 0 / 0 |
| `PlanningContextAssemblyTest` | 5 | 0 / 0 |
| `PlanningContextWorldModelTest` | 4 | 0 / 0 |
| `PlannerAgentTest` / `WriterAgentTest` | 7 / 7 | 0 / 0 |
| `ProjectIndexBuildTest` / `QueryTest` / `BoundaryTest` | 8 / 3 / 5 | 0 / 0 |
| `DesktopWorkspaceTest` | 4 | 0 / 0 |

**覆盖缺口（审计发现）**：无测试断言"pack 内容进入模型请求"（因该链路不存在）；无测试断言"跨来源去重"；无测试断言"worldContext 结构化事实进 prompt"（因不存在渲染路径）。

---

## 8. 附：文件全路径对照（Legend）

上文正文中的短写（如 `ContextEngineUseCases.kt:51`）对应的仓库绝对相对路径如下，便于逐条核查：

| 短写 | 全路径 |
|---|---|
| `ContextPack.kt` | `core/model/src/main/kotlin/com/qianyan/model/context/ContextPack.kt` |
| `StoryWorldContext.kt` | `core/model/src/main/kotlin/com/qianyan/model/context/StoryWorldContext.kt` |
| `ChapterContextPack.kt` | `core/model/src/main/kotlin/com/qianyan/model/lcl/ChapterContextPack.kt` |
| `ProjectIndexModels.kt` | `core/model/src/main/kotlin/com/qianyan/model/projectindex/ProjectIndexModels.kt` |
| `MemoryModels.kt` | `core/model/src/main/kotlin/com/qianyan/model/memory/MemoryModels.kt` |
| `ContextEngineUseCases.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/context/ContextEngineUseCases.kt` |
| `ContextSources.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/context/ContextSources.kt` |
| `NovelAgent.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/agent/NovelAgent.kt` |
| `PlannerAgent.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/writing/planning/PlannerAgent.kt` |
| `WriterAgent.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/writing/WriterAgent.kt` |
| `PlanningContextAssembly.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/writing/planning/PlanningContextAssembly.kt` |
| `PlanningContext.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/writing/planning/PlanningContext.kt` |
| `ChapterContextCompileUseCases.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/lcl/ChapterContextCompileUseCases.kt` |
| `WriterFacade.kt`（`WriterGateway`） | `application/src/main/kotlin/com/qianyan/application/usecase/writing/WriterFacade.kt` |
| `StoryWorldContextResolver.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/writing/context/StoryWorldContextResolver.kt` |
| `WorldModelReadUseCases.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/story/WorldModelReadUseCases.kt` |
| `ProjectIndexUseCases.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/index/ProjectIndexUseCases.kt` |
| `WorkspaceUseCases.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/workspace/WorkspaceUseCases.kt` |
| `MemoryUseCases.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/memory/MemoryUseCases.kt` |
| `KnowledgeUpdateExecutionUseCase.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/writing/knowledgeupdate/KnowledgeUpdateExecutionUseCase.kt` |
| `ApplicationContainer.kt` | `application/src/main/kotlin/com/qianyan/application/di/ApplicationContainer.kt` |
| `ProductTools.kt` | `application/src/main/kotlin/com/qianyan/application/usecase/tool/ProductTools.kt` |
| `Schema.sq` | `storage/src/main/sqldelight/com/qianyan/storage/db/Schema.sq` |
| `StoryExtraTabs.kt` | `app/desktop/src/jvmMain/kotlin/com/qianyan/app/desktop/ui/pages/StoryExtraTabs.kt` |
| `ContextEngineBuildTest.kt` | `application/src/test/kotlin/com/qianyan/application/usecase/context/ContextEngineBuildTest.kt` |
| `ProjectIndexQueryTest.kt` | `application/src/test/kotlin/com/qianyan/application/usecase/index/ProjectIndexQueryTest.kt` |
| `ProjectIndexBuildTest.kt` | `application/src/test/kotlin/com/qianyan/application/usecase/index/ProjectIndexBuildTest.kt` |
