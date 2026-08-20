# Qianyan Project Status Report

> ⚠️ **本文档为历史状态快照（生成于 2026-08-18，反映 P6 完成时的状态），已被现行状态取代。**
>
> 本文档使用 **旧的 P0–P8 阶段编号**（其中 `P7 = Android UI`、`P8 = Desktop UI`），与**现行唯一阶段口径不同**：
> - **现行路线**以 [README.md](../../README.md#current-development-roadmap现行路线唯一阶段口径) 的「Current Development Roadmap」为准：
>   `P8.1 = Task Storage` ✅ DONE · `P8.2 = Task Manager / State Machine` ✅ DONE · `P8.3 = Task Execution` ✅ DONE ·
>   `P9 = Real LLM Provider（DeepSeek / MiMo / LLMGateway / HTTP Transport / Provider Error Handling）` ✅ DONE ·
>   `P10 = Agent Runtime + Tool System` ⬜ NOT STARTED · `P11 = Writing Workflow / 完整小说创作 Pipeline` ⬜ NOT STARTED ·
>   `P12+ = 高级能力` 🔮 FUTURE。**Current Phase = P10**。
> - 请勿把本文档的 P 编号当现行阶段；当前进度请以正文中的「Current Development Roadmap」与各 completion report 为准。
>
> 本文档内容保留作为 **历史状态档案**，不依据旧编号推导当前状态。

> 生成日期：2026-08-18
> 性质：状态记录文档（非开发计划）
> 数据来源：真实读取代码仓库 / Git 状态 / Gradle 构建与测试结果 / 规划文档

---

# 1. Project Overview

## Qianyan 是什么？

Qianyan（千言）是一款**本地优先的长篇创作辅助工具**，定位为受控的 Agentic Writing 系统。目标用户是长篇网络小说/连载作者，核心诉求是把"大纲、世界观、人物、时间线、伏笔、词汇风格"等创作要素结构化沉淀，并借助 AI Agent 完成剧情重构、续写、风格统一等写作任务，同时保证作者对创作过程的完全控制（HITL，Human-in-the-loop）。

## 解决什么问题？

1. **长文记忆与一致性**：几十万字连载中的人物状态、时间线、伏笔、设定不冲突。
2. **受控 AI 写作**：AI 不直接写数据库、不越权修改原文，通过 Override 增量表达差异，Original 始终只读（V4.2 Hybrid 决策）。
3. **多版本重构**：同一本 Original 可派生多个 Variant（改线/续写/重写），差异以 EntityOverride 表达，不复制全文。

## 核心能力是什么？

- 领域模型层（Domain First）：Novel / NovelVariant / Character / Story / Knowledge / Vocabulary / Memory / Timeline 等完整模型。
- 存储基础：SQLDelight + SQLite（单 SQL 真源，JVM 可跑测试），Repository + 物理写保护触发器 + Backup。
- 应用层（Use Case）：通过 ApplicationContainer 装配仓储，提供 Novel / Override / Vocabulary / Memory 用例，统一 VariantContext 上下文与错误边界。
- 远期能力（尚未实现）：Agent 编排、TXT 分析管线、写作工作流、AI Provider、双端 UI。

---

# 2. Overall Progress

> 说明：用户口径的阶段表（P0–P8）与 [qianyan-implementation-plan.md](file:///workspace/docs/planning/qianyan-implementation-plan.md) 的 P0–P18 计划**不一致**（文档为 19 阶段细分计划）。下表按用户口径列出，并在 Notes 标注对应的文档阶段与真实证据。

| Phase | Name | Status | Notes |
|---|---|---|---|
| P0 | Foundation | DONE | 13 模块工程骨架 + Gradle/CI 全绿；唯一已提交 commit `132a469` |
| P1 | Core Domain Model | DONE | `core:model` 全部领域模型落地 + P1DomainModelTest（11 用例）；V4.2 的 Vocabulary/World/Knowledge.scope 已实现 |
| P2 | Storage | DONE | SQLDelight 单 SQL（TBD-4 已拍板落地）+ 4 仓储接口 + Sqlite 实现 + 3 守卫触发器 + Backup；StorageRepositoryTest 20 用例 |
| P3 | Application / Agent Integration | IN_PROGRESS | Application 层已完整落地（DI/错误/用例/集成测试 11 用例）；**Agent 集成未开始**（agent:* 模块为空壳） |
| P4 | TXT Pipeline | DONE | `core:engine` 确定性 TXT Pipeline 落地（Importer/Normalizer/ChapterDetector + 文本重建 + contentHash）；`Txt.sq` 三表 + SqliteTxtRepository.saveImport/getChapters/getBlocks；TxtPipelineTest + TxtRepositoryTest 全绿 |
| P5 | TXT → Application 集成 | DONE | `ImportTxtUseCase` 把 P4 管线接入 Application（TXT bytes→解析→去重→建 Original Novel→绑定 novelId→原子持久化→结构化结果+VariantContext(ORIGINAL)）；application→core:engine 依赖演进；TXT 错误归一（4 子类映射）；findByContentHash/findByNovelId；全绿 |
| P6 | AI Analysis Pipeline | DONE | `provider:api` / `provider:impl` 拆分 + `LLMGateway` 契约 + `MockLLMGateway`；`core:model/analysis`（AnalysisInput/AnalysisResult/AnalysisStatus，transient）；`core:engine/analysis/AnalysisInputBuilder`（确定性 TXT→AnalysisInput，零 AI/存储）；`application` `AnalysisUseCases`（TXT→Input→VariantContext(ORIGINAL)→Mock→Result→Validation→VocabularyCandidate(PENDING)）；错误链 ProviderException/AnalysisException→ApplicationError→ErrorMapper；`findCandidatesByNovel` 回读验证；AnalysisUseCaseTest 8 + AnalysisInputBuilderTest 5 + MockLLMGatewayTest 4 全绿 |
| P7 | Android UI | NOT STARTED | 仅 P0 占位：无 Activity、无 Compose UI；APK 可构建但不可启动（文档 P11/P15 未做） |
| P8 | Desktop UI | NOT STARTED | `app:desktop` 仅 `println` 占位（文档 P16 未做） |

**结论：当前真实完成到 P6（AI Analysis Pipeline：TXT → AnalysisInput → Mock Provider → AnalysisResult → Validation → VocabularyCandidate(PENDING)）；P7（Android UI）及更后阶段未开始。**

> **P6 关键界定**：AI Analysis 使用 **Mock Provider**；真实 **DeepSeek / MiMo Provider DEFER**；正式 **Knowledge / Character / Event / Timeline / World 持久化 DEFER**；**Variant Analysis DEFER**；`AnalysisResult` 为 transient（不建表）；AI 提取仅进入 PENDING `VocabularyCandidate`，不直接写正式 `VocabularyEntry`。Analysis 仅处理 `VariantContext(ORIGINAL)`。
>
> **Post-P6 Hardening / Known Follow-up**（不阻塞 P6 DONE）：`VocabularyCandidate` 批量持久化当前**不是跨候选的单事务**；写库中途失败的回滚测试**尚未补**。见 §14。

---

# 3. Architecture Status

模块定义见 [settings.gradle.kts](file:///workspace/settings.gradle.kts)（共 **14** 个模块）。注意：`:application` 是文档外新增模块（见 §DOCUMENT VS CODE DIFFERENCE-1）；`:provider` 已按 P6 拆分为 `:provider:api` + `:provider:impl`。

| 模块 | 当前职责（构建脚本注释） | 已实现内容 | 未实现内容 |
|---|---|---|---|
| `core:model` | 纯领域模型（V4.2 Hybrid） | 14 个包的完整模型 + 40 个强类型 ID + P1DomainModelTest(11) | 无（模型即阶段目标） |
| `core:engine` | 引擎（TXT / Analysis） | TXT Pipeline（Importer/Normalizer/ChapterDetector + 文本重建 + contentHash）+ AnalysisInputBuilder（确定性 TXT→AnalysisInput，零 AI/存储）+ TxtPipelineTest(20) + AnalysisInputBuilderTest(5) | 其余引擎逻辑（写作/检索等） |
| `agent:tool` | Tool System / 权限矩阵 | 无 | 全部 |
| `agent:runtime` | Agent Runtime | 无 | 全部 |
| `agent:agents` | 六个 Agent 定义 | 无 | 全部 |
| `agent:orchestration` | Orchestrator + 状态机 | 无 | 全部 |
| `provider:api` | AI Provider 抽象契约（P6） | LLMGateway + ProviderModels/ProviderConfig/ProviderException | DeepSeek / MiMo 真实实现（DEFER） |
| `provider:impl` | AI Provider 实现（P6） | MockLLMGateway + MockLLMGatewayTest(4) | DeepSeek / MiMo 实现、容错/重试语义（DEFER） |
| `storage` | SQLDelight + SQLite 持久化 | 5 仓储接口 + Sqlite 实现 + Schema + 3 触发器 + Backup + **findCandidatesByNovel（P6）** + StorageRepositoryTest(20) | 跨版本 Migration、FTS5、四层 Memory 持久化 |
| `application` | Application Use Case 层（DI/错误/用例） | ApplicationContainer + ErrorMapper + Novel/Override/Vocabulary/Memory/TXT 用例 + **P6 AnalysisUseCases** + ApplicationIntegrationTest(11) + TxtImportUseCaseTest(13) + AnalysisUseCaseTest(8) | Workflow 编排、Backup 应用级导出用例 |
| `runtime` | 平台 Runtime 抽象（文件/网络/DB） | 无 | 全部 |
| `app:android` | Android 应用 | `QianyanApplication` 占位 + Manifest（无 Activity） | 所有 UI/DI/业务 |
| `app:desktop` | Desktop 应用 | `Main.kt` println 占位 | 所有 UI/业务 |
| `test:e2e` | E2E 冒烟 | 1 个冒烟测试 | 真实 E2E |

---

# 4. Domain Model Status

来源：`core:model/src/main/kotlin/com/qianyan/model/`（全部 `@Serializable`，字段完整、非 stub）。

| Domain Model | 分类 | 说明 |
|---|---|---|
| Novel（Original 基座，只读） | Implemented | `isOriginal=true`，含 scope/status |
| NovelVariant + VariantBlueprint + StructureRef + VariantScopeSpec | Implemented | baseNovelId 指向 Original，v1 禁 Variant→Variant |
| EntityOverride + OverrideOperation + OverridableKind | Implemented | 实体级覆盖，唯一键 (targetId, variantId)，INHERIT/OVERRIDE/ADD/REMOVE |
| VariantContext | Implemented | variantId=null→Original；非空→Variant |
| Character / CharacterState / CharacterArc / CharacterArcProgress / Relationship | Implemented | 快照 + 长期轨迹 + 转折点 |
| Story Structure（StoryArc/Act/Chapter/ChapterPlan/Scene/ScenePlan/Beat/SceneState） | Implemented | 完整层级 |
| Story Intelligence（Conflict/Stakes/Foreshadowing/Payoff/EmotionalArc/TurningPoint/PacingProfile） | Implemented | `PacingProfile` 完整计算标注 P1 TBD |
| Knowledge（KnowledgeEntry/Evidence/SourceReference） | Implemented | 含 V4.2 新增 `scope`+`variantId`；知识生命周期 9 步未实现 |
| Vocabulary（Vocabulary/VocabularyEntry/VocabularyRule/VocabularyCandidate） | Implemented | 四级作用域 GLOBAL>NOVEL>VARIANT>TASK；解析算法未实现 |
| Memory（MemoryEntry/MemoryLayer/MemoryAccessMode） | Implemented（最小） | 四层完整分层与存取规则标注 `[TBD-4]` 扩展点 |
| World（World/WorldRule） | Implemented | 简洁实现 |
| Timeline（TimelineEntry/Event/StoryTime/TimelinePosition） | Implemented | 三类时间表达 |
| Task（Task/Checkpoint） | Implemented | revisionCount 上限 3；Checkpoint.snapshot 用 JsonObject |
| Context（ContextCandidate/UserWritingRequest/TargetRef） | Implemented | 检索评分字段齐全；检索算法未实现 |
| Spec（ValidationIssue/ConflictResolution/PendingConfirmation 等） | Implemented | 动态值用 JsonElement |
| Agent（AgentContract/Capability/AgentState/WorkflowState） | Implemented（模型） | 仅领域契约；AgentRuntime/Orchestration 行为未实现 |

**Missing**：无缺失模型（P1 目标为领域优先，已覆盖）。`DraftState` / `HITLState` 仍为 [TBD]（五状态族中未建模）。

---

# 5. Storage Status

来源：[storage](file:///workspace/storage) 模块真实代码。

- **数据库方案**：**SQLDelight + SQLite（JVM JDBC）**，单 SQL schema 真源（`storage/src/main/sqldelight/com/qianyan/storage/db/*.sq`）。TBD-4 存储选型已落地。
- **Schema（Version 1，11 张表）**：Novel / NovelVariant / EntityOverride / Vocabulary / VocabularyEntry / VocabularyRule / VocabularyCandidate / MemoryEntry / **TxtDocument / TxtChapter / TextBlock（P4）**（均 TEXT 存强类型 ID、INTEGER 存 epoch 毫秒时间）。
- **Repository**：5 个接口（NovelRepository / VocabularyRepository / MemoryRepository / BackupStore / **TxtRepository**，P5 新增 `findByContentHash` / `findByNovelId` 只读查询）+ 5 个 Sqlite 实现 + StorageMappers 双向映射 + 3 个领域异常（OriginalImmutable / VariantBaseViolation / UniqueConflict）。
- **写保护**：3 个原生 SQL 守卫触发器（Original UPDATE/DELETE 拒绝；Variant base 必须为 ORIGINAL）。`QianyanDbFactory.open()` 幂等初始化（P2.9 可重复打开）。
- **Backup**：`SqliteBackupStore` 全量导出结构化 JsonElement 快照，单事务 restore（需恢复至空库，因 Original 行受写保护）。
- **Migration**：**Not Started**——仅 initial schema 建表，无 `.sqm` 跨版本迁移、无 `migrations/` 目录。
- **测试**：StorageRepositoryTest 20 用例 + StorageSmokeTest 1（全部通过）。无覆盖率工具（jacoco/kover）。

| 检查项 | 状态 |
|---|---|
| Repository | Implemented |
| Schema / SQLite | Implemented |
| Backup / Restore | Implemented（最小） |
| Migration | Not Started |
| FTS5 全文检索 | Not Started |

---

# 6. Agent System Status

**结论：Agent 系统是"框架占位"（Framework），不是"可用的 AI 工作流"（Actual AI Workflow）。**

- `agent:tool` / `agent:runtime` / `agent:agents` / `agent:orchestration` **四个模块均无任何 `src/main` 源码**，仅有 2+2 冒烟测试。构建脚本已声明好依赖方向（如 orchestration 依赖 storage、runtime 依赖 provider）。
- 领域契约（`core:model/agent/AgentModels.kt`）已定义：`AgentState`（7 态）、`WorkflowState`（13 态，系统唯一工作流状态机）、`AgentContract`。
- 不存在：Tool 实现、Agent Runtime、6 个 Agent 定义、Orchestrator、Checkpoint、HITL 实现。

> 即：**Agent 框架存在（空壳）不代表 Agent 已经可以写小说。** 当前没有任何 Agent 能执行写作任务。

---

# 7. Application Layer Status

来源：[application](file:///workspace/application) 模块（P3 已实现，本次会话完成）。

- **DI**：`ApplicationContainer`（手动 DI 组合根），构造器注入 5 仓储接口 + `TxtPipeline`，`fromDriver(SqlDriver)` / `open(url)` 装配 Sqlite 实现。
- **Error handling**：`ApplicationError`（sealed，11 类成员）→ `ApplicationException` → `ErrorMapper` 将 StorageException 与引擎 `TxtException` 映射为领域错误（P3.4 + P5.2 错误边界）。
- **Application service / Use case**：
  - Novel：CreateOriginalNovel / CreateVariant / GetNovel / GetVariantContext
  - Override：AddOverride / RemoveOverride / ResolveVariantEntity（读穿透）/ overridesOf
  - Vocabulary：SaveVocabulary / saveEntry / QueryVocabulary
  - Memory：SaveMemoryEntry / QueryMemory
  - TXT（P5）：ImportTxtUseCase —— `TxtPipeline` 确定性解析 → contentHash 去重 → 创建 Original Novel → 回填绑定 novelId → 原子持久化 Document/Chapter/TextBlock → 返回结构化结果 + `VariantContext(ORIGINAL)`。去重命中时不产生第二个 Novel / 不写重复 TXT。
  - Analysis（P6）：AnalyzeTxtOriginal —— `AnalysisInputBuilder`（确定性 TXT→AnalysisInput，章节级、保留 sourceLocation，零 AI/存储）→ `VariantContext(ORIGINAL)` 显式校验 → `LLMGateway.chat`（Mock）→ 解析/校验 AI 输出 → transient `AnalysisResult` → 构建 PENDING `VocabularyCandidate` → 持久化。错误经 `guardAnalysis` + `ErrorMapper` 归一（ProviderUnavailable / InvalidAnalysisOutput / AnalysisFailed）。
- **Context 传递**：统一 `VariantContext`（Original=variantId null；Variant=当前 variantId），Use Case 不自行判断 Variant。
- **测试**：ApplicationIntegrationTest 11 用例 + TxtImportUseCaseTest 13 用例 + AnalysisUseCaseTest 8 用例 + TxtRepositoryP5QueryTest（storage）5 用例，全部通过。

**已连接**：Application ↔ storage（仓储接口）；Application ↔ core:model（领域类型）；**Application ↔ core:engine（TXT 管线，P5 依赖演进）**；**Application ↔ provider:api（LLM 契约，P6）**。
**未连接**：Application ↔ agent（agent 空壳）；Application ↔ app UI；Application ↔ provider:impl（仅测试 e2e / 测试装配 Mock）。

> **P5 依赖演进说明**：为把 P4 的确定性 TXT Pipeline 接入 Use Case，新增 `application → :core:engine`（application/build.gradle.kts）。依赖方向保持冻结 DAG 单向性：`application → core:engine → core:model` 且 `application → storage → core:model`；`core:engine` 不访问 Repository / Application / LLM，持久化仍走 storage 接口。

---

# 8. AI Provider Status

**结论：P6 已建立 Provider 契约（`:provider:api`）并实现 Mock（`:provider:impl`）；无任何真实 AI 调用。**

- `:provider:api`（`provider/api`）：`LLMGateway` 契约 + `ProviderModels`（ProviderRequest/ProviderResponse/ChatMessage/Usage/FinishReason/ModelProfile）+ `ProviderConfig` + `ProviderException`（sealed：Timeout/RateLimit/ProviderUnavailable/InvalidResponse/MalformedOutput/TokenLimit）。只依赖 `:core:model`。
- `:provider:impl`（`provider/impl`）：`MockLLMGateway` —— 确定性响应（相同请求→相同 JSON 输出 + 确定性 usage），可注入自定义响应/失败。只依赖 `:provider:api`。
- 调用方（application / agent:runtime）只依赖 `:provider:api`；`provider:impl` 仅测试与 `test:e2e` 绑定。
- DeepSeek / MiMo **均未实现（DEFER）**。

| 项 | 状态 |
|---|---|
| 契约（LLMGateway + 模型 + 异常） | **已实现（P6）** |
| Mock | **已实现（P6）** |
| DeepSeek | DEFER（未实现） |
| MiMo | DEFER（未实现） |
| 真实调用 | 无（未接入任何外部 API） |

---

# 9. TXT Creation Pipeline Status

**重点检查 —— 部分实现（P4/P6），正式分析能力仍未落地。**

| 能力 | 状态 |
|---|---|
| 上传 TXT | NOT IMPLEMENTED（无文件输入路径，`runtime` 空壳） |
| 解析章节 | **Implemented（P4）**：`core:engine` TXT Pipeline（Importer/Normalizer/ChapterDetector） |
| 角色分析 | NOT IMPLEMENTED（仅 Character 模型存在；**P6 DEFER**） |
| 世界观分析 | NOT IMPLEMENTED（仅 World 模型存在；**P6 DEFER**） |
| 时间线分析 | NOT IMPLEMENTED（仅 Timeline 模型存在；**P6 DEFER**） |
| 知识库生成 | NOT IMPLEMENTED（仅 Knowledge 模型存在；**P6 DEFER**） |
| 词库候选提取 | **Implemented（P6，部分）**：`AnalyzeTxtOriginal` + Mock Provider → PENDING `VocabularyCandidate`（AUTO_EXTRACT，不写正式词条） |
| 正式词库生成 | NOT IMPLEMENTED（PENDING 候选 → 确认 → 正式 `VocabularyEntry` 的流程未实现） |
| Original Novel 生成 | **Implemented（Application 层）**：`CreateOriginalNovel` + 存储落库 |
| Variant 生成 | **Implemented（Application 层）**：`CreateVariant` + Override 解析 |

> 即：**"从 TXT 文本到结构"的分析管线已部分打通（P6：章节解析 + 词汇候选提取）**；角色/世界观/时间线/知识 的正式分析、以及候选→正式词库的确认流程 **DEFER**。

---

# 10. Writing Workflow Status

**结论：仅存在状态枚举，无工作流实现。**

- **WorkflowState**：领域模型已定义 13 态枚举（INTENT_PARSING…CONFLICT_HITL…CANCELLED/PAUSED），为系统唯一工作流状态机（V4.2 [DECIDED]）。
- **Idea Mode / TXT Rewrite Mode / Continuation Mode / Novel Reconstruction Mode**：全部 NOT IMPLEMENTED（这些是上层工作流模式，当前无任何编排代码）。
- **Agent 调用链**：NOT IMPLEMENTED（agent 空壳）。
- **Checkpoint**：仅领域模型 `Task/Checkpoint` 存在；无执行/恢复逻辑。
- **HITL**：NOT IMPLEMENTED（无 `HITLState` 建模、无确认流程实现）。

---

# 11. UI Status

## Android（`:app:android`）

- **APK**：`android-debug.apk` 存在，**1,556,286 字节（约 1.55 MB）**，debug 构建成功。
- **是否可启动**：**否**——`AndroidManifest.xml` 无任何 `<activity>`（无启动 Activity），无 `INTERNET` 权限；安装后无入口界面。
- **是否有 Activity**：无。
- **是否有 Compose UI**：无（build.gradle 仅依赖 `core:model`，注释明确 UI 留给 P11/P15）。
- 当前内容：`QianyanApplication` 空 Application 类（占位）。

## Desktop（`:app:desktop`）

- `Main.kt` 仅 `println("Qianyan Desktop placeholder")`；`mainClass = com.qianyan.app.desktop.MainKt`。
- 无 Compose Desktop / JavaFX UI（UI 框架为 TBD-3，建议 Compose Desktop）。
- 依赖 `:core:model` + `:runtime`（`:runtime` 亦为空壳）。

---

# 12. Build & Test Status

本次 P6 会话实测（2026-08-18，环境 JDK 17；沙箱内已现场安装 Android SDK cmdline-tools + platforms;android-34 + build-tools;34.0.0 到 `/opt/android-sdk`，并为其配置 Gradle 全局代理以拉取 aapt2）：

| 命令 | 结果 |
|---|---|
| `gradle test --rerun-tasks`（全模块） | **BUILD SUCCESSFUL**（含 `:app:android:testDebugUnitTest` / `testReleaseUnitTest` 及全部 JVM 模块测试） |
| `gradle test assembleDebug --rerun-tasks` | **BUILD SUCCESSFUL**（109 actionable tasks；`assembleDebug` 产出 debug APK） |

> 说明：`./gradlew` 因 wrapper 发行包下载超时不可用，改用同版本系统 `gradle 8.14.5`。沙箱默认未装 Android SDK 且 `dl.google.com` 直连超时；已现场安装 SDK（命令 `sdkmanager --sdk_root=/opt/android-sdk "platforms;android-34" "build-tools;34.0.0"`）并在节点级 `~/.gradle/gradle.properties` 配置代理（`systemProp.https.proxyHost/Port=127.0.0.1:18080`）以经代理拉取 `aapt2`，从而完成 `assembleDebug`。该代理配置为节点级、不影响项目文件。`app:android` 不依赖 `application` 模块，因此 P5 的 `application` 改动不影响 Android 构建，且 Android 构建实测已通过。

**测试统计（P6 后，全部通过，0 failure / 0 error）：**

| 模块 | 测试类 | 用例数 |
|---|---|---|
| core:model | P1DomainModelTest + ModelSmokeTest | 11 + 1 |
| core:engine | TxtPipelineTest + EngineSmokeTest + **AnalysisInputBuilderTest(P6)** | 20 + 1 + 5 |
| storage | StorageRepositoryTest + StorageSmokeTest + TxtRepositoryTest + TxtRepositoryP5QueryTest | 20 + 1 + 4 + 5 |
| application | ApplicationIntegrationTest + TxtImportUseCaseTest + **AnalysisUseCaseTest(P6)** | 11 + 13 + 8 |
| agent:tool/runtime/agents/orchestration | 各 SmokeTest | 1×4 |
| provider:api | ProviderSmokeTest | 1 |
| provider:impl | **MockLLMGatewayTest(P6)** | 4 |
| runtime / test:e2e / app:desktop | 各 SmokeTest | 1×3 |

**P6 新增测试**：`core:engine` 的 AnalysisInputBuilderTest（正常构建/空章节/未绑定 Novel/章节顺序确定性/段落顺序确定性 5 用例）、`application` 的 AnalysisUseCaseTest（正常 Mock 全链路/Provider 失败/非法输出/空建议/Variant 拒绝/Novel 不匹配/文档不存在/确定性 8 用例）、`provider:impl` 的 MockLLMGatewayTest（确定性响应/自定义响应/失败注入/空 Prompt 4 用例）。

**总计 110 个测试，全部通过（0 failure / 0 error）。**（真实业务用例：P1 模型 11 + Engine 20+5 + Storage 24 + Application 24+8 + Provider 4 = 96；其余为冒烟/占位桩。）

**CI**：`.github/workflows/ci.yml` 存在，main push / PR 触发：JDK17 + Android SDK 34 + `./gradlew --no-daemon build` + `test`。

---

# 13. Completed Decisions

已冻结并（在代码中）落地的架构决策：

1. **Original Immutable**：Novel(scope=ORIGINAL) 只读，领域层 + 物理触发器双重写保护（P2.4）。
2. **Variant Hybrid**：NovelVariant 以 Original 为基座 + EntityOverride 增量表达差异；v1 禁止 Variant→Variant（单层）。
3. **EntityOverride 语义**：实体级覆盖，唯一键 (targetId, variantId)，INHERIT/OVERRIDE/ADD/REMOVE；读穿透（命中覆盖值、否则回退 Original、REMOVE→null）。
4. **VariantContext 统一上下文**：variantId=null→Original；非空→Variant；Agent 不自行判断 Variant（P3.3）。
5. **SQLDelight 单 SQL**：唯一 schema 真源，JVM driver 可 CI 跑测试（TBD-4 落地）。
6. **WorkflowState 唯一状态机**：五状态族分离（WorkflowState/TaskState/AgentState/DraftState[TBD]/HITLState[TBD]）（V4.2 ISSUE-1 [DECIDED]）。
7. **KnowledgeEntry 增加 scope+variantId**：区分 Original 只读知识 vs Variant 覆盖知识（V4.2 [MODIFY] 已实现）。
8. **Vocabulary 四层作用域**：GLOBAL > NOVEL > VARIANT > TASK，窄覆盖宽；与 Knowledge 正交（V4.2 [ADD] 已实现）。
9. **Scope 传播**：scope 是实体属性而非 ID 组成部分（Ids.kt）；全实体 `@Serializable` + kotlinx.datetime.Instant + JsonElement 表达动态值。
10. **纯 Kotlin/JVM 共享 Core**（ISSUE-2）；**6 Agent 冻结、不新增模块/Agent**（V4.2）。
11. **AI 永不直接写 DB**；**Revision ≤ 3**；**Checkpoint.snapshot 用受控 JsonObject**（ISSUE-5）。
12. **错误边界**：StorageException 不泄露给 UI/Agent，由 ErrorMapper 转换为 ApplicationError（P3.4）。

---

# 14. Remaining Problems

> 仅记录问题，不提出新设计。

## BLOCKER
- **无阻塞构建的问题**（`build`/`test`/`assembleDebug` 全绿）。但存在"产品级阻塞"：Agent / 真实 Provider / Workflow / UI 全部为空，产品无法使用。

## HIGH
- **Agent 系统纯空壳**：4 个 agent 模块无业务代码，`WorkflowState` 只有枚举，无任何可执行的写作 Agent 链路。
- **无跨版本 Migration**：Schema v1 之后无迁移机制（TBD）。

## MEDIUM
- **Post-P6 Hardening（不阻塞 P6 DONE）**：`VocabularyCandidate` 批量持久化当前**不是跨候选的单事务**，写库中途失败（如磁盘满）可能留下部分候选；**写库中途失败的回滚测试尚未补**。见 P6 Completion Report / P6 Post-Completion Audit。
- **`:application` 为文档外新增模块**：不在 master-plan/implementation-plan 的 12 模块清单内，规划文档未同步（见 §DIFFERENCE-1）。
- **文档阶段号与执行口径不一致**：implementation-plan 的 P3="AI Provider/Gateway"，实际执行的 P3="Application Integration"，阶段命名语义存在漂移。
- **Android 无启动 Activity**：APK 可构建但不可启动，无法做任何手工冒烟。
- **无测试覆盖率工具**：无法量化行覆盖率（当前以用例数为准）。
- **Backup restore 限制**：需恢复至空库（Original 行受写保护禁止 DELETE），非原库就地恢复。

## LOW
- `:runtime`（文件/网络抽象）空壳；desktop 依赖了空壳 runtime。
- 冒烟测试占测试总数比例仍偏高，信息量低。
- PacingProfile / 四层 Memory / 字段级 Override（TBD-1）/ PC UI 框架（TBD-3）等仍为 TBD。

---

# 15. Next Recommended Step

基于真实状态（P6 AI Analysis Pipeline 已完成，Agent / 真实 Provider / Workflow / UI 未动）：

1. **进入 P7（Android UI）**：`app:android` 仍是 P0 占位（无 Activity、无 Compose UI）。
2. 在投入 P7 前，可按需先消化 **Post-P6 Hardening**（候选批量持久化事务化 + 回滚测试）——该加固不阻塞 P6 DONE。
3. 真实 **DeepSeek / MiMo Provider**、正式 **Knowledge/Character/Event/Timeline/World 持久化**、**Variant Analysis** 仍在后续阶段（DEFER）。

> 不建议：回退重做 P0–P6，也不建议在当前 P6 之外提前扩写 Workflow（跨度过大）。

---

# 16. Project Health Score

| 维度 | 评分 | 依据 |
|---|---|---|
| Architecture | 80% | 14 模块分层清晰、依赖方向基本单向、V4.2 决策落地到位；但存在文档外模块 `:application`、文档阶段号漂移、多个空壳模块拉低一致性 |
| Domain | 90% | 领域模型完整且全部实现（含 V4.2 扩展），40 强类型 ID，序列化规范统一；缺 DraftState/HITLState 建模 |
| Implementation | 52% | core:model / storage / application / provider:api / provider:impl / core:engine(TXT+Analysis) 有真实实现；agent/runtime/UI 仍为空壳 |
| Testing | 68% | 110 用例全绿、96 个真实业务用例覆盖核心路径；但冒烟桩仍占比例、无覆盖率工具、无 instrumented/UI 测试、无 E2E |
| Product Readiness | 10% | 无可用 UI、无真实 AI 能力、无写作工作流；可演示"TXT 导入 + Mock 词汇候选提取 + 用例查询" |

---

# 附录：DOCUMENT VS CODE DIFFERENCE

> 规则：文档与代码不一致时，必须明确标记并说明差异。

## 差异-1：`:application` 模块为文档外新增
- **文档状态**：master-plan / implementation-plan 均定义 **12 模块**（P0 清单），无 `:application`。
- **实际代码状态**：仓库有 **13 模块**，`settings.gradle.kts` 第 27 行含 `include(":application")`，含 DI/错误/用例真实实现与 11 个集成测试。
- **推荐处理**：将 `:application` 模块及其职责（Application Use Case 层）回写进 master-plan/implementation-plan 的模块清单与架构图，保持文档与代码一致。

## 差异-2：V4.2 架构审查从"只读建议"变为"已实现"
- **文档状态**：qianyan-v4.2-architecture-review.md 结论为"KEEP + 加法式 MODIFY → 推荐方案 B"，其中 Vocabulary / NovelVariant / KnowledgeEntry.scope 均标注为新增/修改建议。
- **实际代码状态**：Vocabulary 与 World 领域模型、NovelVariant 与 EntityOverride、KnowledgeEntry 的 `scope`+`variantId` 均已**实现并落库**（含 SQLDelight `.sq` 表）。
- **推荐处理**：将该审查文档标注从"建议中"推进为"已采纳并实现"，或将 V4.2 落地情况单独立档。

## 差异-3：Implementation Plan 的 P3 与执行的 P3 语义不同
- **文档状态**：implementation-plan 中 P3 = "AI Provider/Gateway"（提前做，DeepSeek+Mock，MiMo 占位）。
- **实际代码状态**：本次执行的"P3"= Application Integration（DI/Use Case/错误边界），AI Provider 仍未实现。
- **推荐处理**：在实施计划中明确阶段定义冲突，或将 AI Provider 归入未来的 TXT/Agent 阶段，避免后续对"P3 完成度"产生歧义。

## 差异-4：模块数由 13 → 14（provider 拆分为 api/impl）
- **文档状态**：先前记录为"13 模块"（含单一 `:provider`）。
- **实际代码状态**：P6 将 `:provider` 拆为 `:provider:api` + `:provider:impl`，模块数变为 **14**；`settings.gradle.kts` 显式指定 `projectDir`（详情见 P6 commit）。

## 差异-5：Git 历史（已含 P0–P6，P6 提交发生于 2026-08-18）
- **文档状态**：先前记录"仅 P0 commit，P1–P3 未提交"。
- **实际代码状态**：Git 现含：`132a469`(P0 bootstrap) → `6fc6ac3`(P1–P3+P4) → `ccde587`(P0–P4 README) → `b53f985`(P1–P5 foundation & TXT pipeline) → `026e65b`(P5 status docs) → `139095d`(P6 AI analysis pipeline)。P1–P6 均已提交；P6 文档提交紧随其后。

---

*文档结束。本报告仅记录状态，未修改任何代码、架构与模块，仅同步项目状态至 P6。*
