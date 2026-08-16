# Qianyan Project Status Report

> 生成日期：2026-08-17
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
| P4 | TXT Pipeline | NOT STARTED | TXT 导入/章节解析/分析管线均未实现；`core:engine` 为空壳（文档 P5/P6/P12 未做） |
| P5 | Writing Workflow | NOT STARTED | 仅领域模型存在 `WorkflowState` 枚举（13 态）；无 Agent 调用链/Checkpoint/HITL 实现 |
| P6 | AI Provider | NOT STARTED | `provider` 模块为空壳；无 DeepSeek/Mock 真实调用（文档 P3 与 ISSUE-4 未落地） |
| P7 | Android UI | NOT STARTED | 仅 P0 占位：无 Activity、无 Compose UI；APK 可构建但不可启动（文档 P11/P15 未做） |
| P8 | Desktop UI | NOT STARTED | `app:desktop` 仅 `println` 占位（文档 P16 未做） |

**结论：当前真实完成到 P3 的 Application 子层；P3 的 Agent 集成及 P4 之后全部未开始。**

---

# 3. Architecture Status

模块定义见 [settings.gradle.kts](file:///workspace/settings.gradle.kts)（共 **13** 个模块）。注意：`:application` 是文档外新增模块（见 §DOCUMENT VS CODE DIFFERENCE-1）。

| 模块 | 当前职责（构建脚本注释） | 已实现内容 | 未实现内容 |
|---|---|---|---|
| `core:model` | 纯领域模型（V4.2 Hybrid） | 14 个包的完整模型 + 40 个强类型 ID + P1DomainModelTest(11) | 无（模型即阶段目标） |
| `core:engine` | 引擎（Novel/TXT/Analysis） | 无（仅 1 个 2+2 冒烟测试） | 全部引擎逻辑 |
| `agent:tool` | Tool System / 权限矩阵 | 无 | 全部 |
| `agent:runtime` | Agent Runtime | 无 | 全部 |
| `agent:agents` | 六个 Agent 定义 | 无 | 全部 |
| `agent:orchestration` | Orchestrator + 状态机 | 无 | 全部 |
| `provider` | AI Provider（DeepSeek/MiMo/Mock） | 无 | 全部 |
| `storage` | SQLDelight + SQLite 持久化 | 4 仓储接口 + Sqlite 实现 + Schema + 3 触发器 + Backup + StorageRepositoryTest(20) | 跨版本 Migration、FTS5、四层 Memory 持久化 |
| `application` | Application Use Case 层（DI/错误/用例） | ApplicationContainer + ErrorMapper + Novel/Override/Vocabulary/Memory 用例 + ApplicationIntegrationTest(11) | Workflow 编排、Backup 应用级导出用例 |
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
- **Schema（Version 1，7 张表）**：Novel / NovelVariant / EntityOverride / Vocabulary / VocabularyEntry / VocabularyRule / VocabularyCandidate / MemoryEntry（共 8 张，均为 TEXT 存强类型 ID、INTEGER 存 epoch 毫秒时间）。
- **Repository**：4 个接口（NovelRepository / VocabularyRepository / MemoryRepository / BackupStore）+ 4 个 Sqlite 实现 + StorageMappers 双向映射 + 3 个领域异常（OriginalImmutable / VariantBaseViolation / UniqueConflict）。
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

- **DI**：`ApplicationContainer`（手动 DI 组合根），构造器注入 4 仓储接口，`fromDriver(SqlDriver)` / `open(url)` 装配 Sqlite 实现。
- **Error handling**：`ApplicationError`（sealed，7 类）→ `ApplicationException` → `ErrorMapper` 将 StorageException 映射为领域错误（P3.4 错误边界）。
- **Application service / Use case**：
  - Novel：CreateOriginalNovel / CreateVariant / GetNovel / GetVariantContext
  - Override：AddOverride / RemoveOverride / ResolveVariantEntity（读穿透）/ overridesOf
  - Vocabulary：SaveVocabulary / saveEntry / QueryVocabulary
  - Memory：SaveMemoryEntry / QueryMemory
- **Context 传递**：统一 `VariantContext`（Original=variantId null；Variant=当前 variantId），Use Case 不自行判断 Variant。
- **测试**：ApplicationIntegrationTest 11 用例，全部通过。

**已连接**：Application ↔ storage（仓储接口）；Application ↔ core:model（领域类型）。
**未连接**：Application ↔ agent（agent 空壳）；Application ↔ app UI；Application ↔ provider。

---

# 8. AI Provider Status

**结论：Provider 仅接口占位，无任何真实 AI 调用。**

- `provider` 模块无 `src/main` 源码，仅 1 个 2+2 冒烟测试。
- 构建脚本注释："Agent 不直接依赖任何 Provider 实现"。
- 领域契约（`core:model`）无 Provider 相关模型。
- DeepSeek / MiMo / Mock 三类均**未实现**。计划 ISSUE-4 决定"P3 交付 DeepSeek + Mock，MiMo 占位"，但**该 P3 决定未落地**（本仓库执行的 P3 是 Application 层，非文档 P3 的 AI Provider）。

| 项 | 状态 |
|---|---|
| DeepSeek | 未实现（接口都无） |
| MiMo | 未实现（计划占位） |
| Mock | 未实现 |
| 真实调用 | 无 |

---

# 9. TXT Creation Pipeline Status

**重点检查 —— 全部 NOT IMPLEMENTED。**

| 能力 | 状态 |
|---|---|
| 上传 TXT | NOT IMPLEMENTED（无文件输入路径，`runtime` 空壳） |
| 解析章节 | NOT IMPLEMENTED（`core:engine` 空壳，无 TXT Engine） |
| 角色分析 | NOT IMPLEMENTED（仅 Character 模型存在） |
| 世界观分析 | NOT IMPLEMENTED（仅 World 模型存在） |
| 时间线分析 | NOT IMPLEMENTED（仅 Timeline 模型存在） |
| 知识库生成 | NOT IMPLEMENTED（仅 Knowledge 模型存在） |
| 词库生成 | NOT IMPLEMENTED（仅 Vocabulary 模型 + 存储存在；无提取/生成算法） |
| Original Novel 生成 | **Implemented（Application 层）**：`CreateOriginalNovel` + 存储落库 |
| Variant 生成 | **Implemented（Application 层）**：`CreateVariant` + Override 解析 |

> 即：**"从 TXT 文本到结构"的分析管线完全未实现**；仅"手工构造 Original/Variant 领域对象并落库"已打通。

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

本次会话实测（2026-08-17，环境 JDK 17）：

| 命令 | 结果 |
|---|---|
| `./gradlew test` | BUILD SUCCESSFUL（全模块） |
| `./gradlew build` | BUILD SUCCESSFUL（141 任务） |
| `./gradlew :app:android:assembleDebug` | BUILD SUCCESSFUL（APK 1.55MB） |

**测试统计（全部通过，0 failure / 0 error）：**

| 模块 | 测试类 | 用例数 |
|---|---|---|
| core:model | P1DomainModelTest + ModelSmokeTest | 11 + 1 |
| storage | StorageRepositoryTest + StorageSmokeTest | 20 + 1 |
| application | ApplicationIntegrationTest | 11 |
| agent:tool/runtime/agents/orchestration | 各 SmokeTest | 1×4 |
| provider / runtime / core:engine / test:e2e | 各 SmokeTest | 1×4 |
| app:android（debug+release） | AndroidAppSmokeTest | 2 |
| app:desktop | DesktopAppSmokeTest | 1 |

**总计 55 个测试，全部通过。**（其中 13 个为真实业务测试：P1 模型 11 + Application 11 + Storage 20 中 20 个真实；其余 14 个为 2+2 冒烟桩。）

> 注：55 = 13 真实类用例（P1 11 + storage 20 + application 11 = 42 真实业务）+ 14 冒烟。

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
- **无阻塞构建的问题**（`build`/`test`/`assembleDebug` 全绿）。但存在"产品级阻塞"：Agent / Provider / TXT / Workflow / UI 全部为空，产品无法使用。

## HIGH
- **Agent 系统纯空壳**：4 个 agent 模块无业务代码，`WorkflowState` 只有枚举，无任何可执行的写作 Agent 链路。
- **TXT 管线未实现**：无法上传/解析/分析 TXT，核心"小说化"价值链路断裂。
- **Provider 无真实 AI 调用**：DeepSeek/Mock 均未实现，任何 AI 能力不可用。
- **P1/P2/P3 全部未提交**：当前 Git 仅含 P0 提交 `132a469`；`core:model`、`storage`、`application` 三块核心实现均为**未跟踪（untracked）**文件，存在丢失风险。
- **无跨版本 Migration**：Schema v1 之后无迁移机制（TBD）。

## MEDIUM
- **`:application` 为文档外新增模块**：不在 master-plan/implementation-plan 的 12 模块清单内，规划文档未同步（见 §DIFFERENCE-1）。
- **文档阶段号与执行口径不一致**：implementation-plan 的 P3="AI Provider/Gateway"，实际执行的 P3="Application Integration"，阶段命名语义存在漂移。
- **Android 无启动 Activity**：APK 可构建但不可启动，无法做任何手工冒烟。
- **无测试覆盖率工具**：无法量化行覆盖率（当前以用例数为准）。
- **Backup restore 限制**：需恢复至空库（Original 行受写保护禁止 DELETE），非原库就地恢复。

## LOW
- `:runtime`（文件/网络抽象）空壳；desktop 依赖了空壳 runtime。
- 14 个 2+2 冒烟测试占测试总数 25%，信息量低。
- PacingProfile / 四层 Memory / 字段级 Override（TBD-1）/ PC UI 框架（TBD-3）等仍为 TBD。

---

# 15. Next Recommended Step

基于真实状态（P3 Application 层已完成、Agent/TXT/Provider/UI 未动）：

1. **先提交当前成果**：P1/P2/P3 三块未跟踪代码应立即纳入 Git，消除丢失风险（这是写代码前的必要卫生动作）。
2. **进入 P4（TXT 管线）而非重做 P0/P1**：`core:engine` 是下一个有真实价值的落点——从"构造领域对象"走向"从 TXT 文本提取结构"。步骤建议从小到大：
   - 先做 TXT 读取 + 章节切分（纯本地零 AI，TXT Engine）；
   - 再做角色/世界观/时间线/知识/词库的提取（可先手工 or Mock Provider）。
3. **在 P4 进行中同步接入 Provider 的 Mock**：不阻塞、可测试。

> 不建议：回退重做 P0/P1/P2，也不建议在 P3.5 基础上扩写 P3 之外的 Workflow（跨度过大）。

---

# 16. Project Health Score

| 维度 | 评分 | 依据 |
|---|---|---|
| Architecture | 78% | 13 模块分层清晰、依赖方向基本单向、V4.2 决策落地到位；但存在文档外模块 `:application`、文档阶段号漂移、多个空壳模块拉低一致性 |
| Domain | 90% | 领域模型完整且全部实现（含 V4.2 扩展），40 强类型 ID，序列化规范统一；缺 DraftState/HITLState 建模 |
| Implementation | 45% | 仅 core:model + storage + application 三个模块有真实实现；engine/agent/provider/runtime/UI 全部为空壳（10/13 模块无业务代码） |
| Testing | 55% | 55 用例全绿、42 个真实业务用例覆盖核心路径；但 14 个冒烟桩、无覆盖率工具、无 instrumented/UI 测试、无 E2E |
| Product Readiness | 8% | 无可用 UI、无 TXT 输入、无 AI 能力、无写作工作流；仅"手工构造 + 落库 + 用例查询"可演示 |

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

## 差异-4：Git 历史只含 P0，P1/P2/P3 全部未提交
- **文档状态**：无（纯代码卫生问题）。
- **实际代码状态**：仅 commit `132a469`（P0 bootstrap）；core:model、storage、application 的源码与测试均为 untracked，核心实现无版本记录。
- **推荐处理**：尽快将当前工作区提交（或至少 add 核心源码），再进入下一阶段。

---

*文档结束。本报告仅记录状态，未修改任何代码、架构与模块；未执行任何 Git 提交。*
