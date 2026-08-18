# Qianyan（千言）

**本地优先的长篇创作辅助工具** —— 受控的 Agentic Writing 系统。

面向长篇网络小说 / 连载作者，把「大纲、世界观、人物、时间线、伏笔、词汇风格」等创作要素结构化沉淀，并借助 AI Agent 完成剧情重构、续写、风格统一等写作任务，同时通过 Human-in-the-loop（HITL）保证作者对创作过程的完全控制。

## 要解决的问题

1. **长文记忆与一致性**：几十万字连载中的人物状态、时间线、伏笔、设定不冲突。
2. **受控 AI 写作**：AI 不直接写数据库、不越权修改原文；通过 Override 增量表达差异，**Original 始终只读**（V4.2 Hybrid 决策）。
3. **多版本重构**：同一本 Original 可派生多个 Variant（改线 / 续写 / 重写），差异以 EntityOverride 表达，不复制全文。

## 核心设计原则

- **Local-first**：本地优先，数据存于本地 SQLite。
- **Deterministic Engine**：确定性引擎（如 TXT 解析）不调用 AI、不引入随机性。
- **Repository 隔离**：上层只面向仓储接口，不直接操作 Storage。
- **Agent 受控**：Agent 通过 Tool → Engine → Repository 消费能力，不反向耦合、不越权访问存储。

## 当前进度（P0–P8.1）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 13 模块工程骨架 + Gradle/CI 全绿 | ✅ |
| P1 | `core:model` 全量领域模型 + 强类型 ID + 测试 | ✅ |
| P2 | Storage：SQLDelight 单 SQL 真源 + 5 仓储 + 写保护触发器 + Backup | ✅ |
| P3 | Application：Use Case 层（DI / 错误边界 / 集成测试） | ✅ |
| P4 | TXT Pipeline：确定性 导入 → 规范化 → 章节识别 → 结构化 → 持久化 | ✅ |
| P5 | TXT Pipeline 接入 Application：TXT → 去重(contentHash) → Original Novel → novelId 绑定 → 原子持久化 → 结构化结果 + VariantContext(ORIGINAL) | ✅ |
| P6 | AI Analysis Pipeline：TXT → AnalysisInput → Provider(API/Impl) → AnalysisResult → Validation → VocabularyCandidate(PENDING) | ✅ |
| P7 | Android 功能闭环：Database 初始化 → Application API → Compose → DI → Novel List → TXT 导入 → Analysis → Vocabulary Candidate → 验收 | ✅ |
| P8.0 | Task System / Task Manager foundation：Task / Checkpoint persistence architecture | ✅ |
| P8.1 | Task / Checkpoint persistence：Schema v2 + migration + repository + transaction + tests | ✅ |

**当前阶段**：P7 = DONE（Android 功能闭环已完成并验收）；P8.0 = DONE；P8.1 = DONE；P8.2 = NOT STARTED。
**P8 说明**：P8（Task System / Task Manager）已开始，但仅完成 P8.0 / P8.1（Task / Checkpoint 持久化基础设施）；TaskManager 状态机属 P8.2，尚未实现。
**P6/P7 说明**：AI Analysis 使用 **Mock Provider（MockLLMGateway）**，仅用于验证完整应用调用链；真实 **DeepSeek / MiMo Provider DEFER**；正式 **Knowledge / Character / Event / Timeline / World 持久化 DEFER**；**Variant Analysis DEFER**；`AnalysisResult` 为 transient（不建表）；AI 提取仅进入 PENDING `VocabularyCandidate`，不直接写正式 `VocabularyEntry`；**Candidate 确认 / 转正式词条流程 DEFER**。
**尚未实现**：Task Manager 状态机（P8.2）、Agent 编排、Tool System、Agent Runtime、写作工作流（Workflow）、真实 DeepSeek / MiMo Provider、Knowledge / Character / Event / Timeline / World 正式持久化、Candidate 确认流程、Desktop UI、PC / Cloud 后端。

## P7 Android Functional Loop

P7 形成 Android 端第一条完整功能闭环：

```text
Database
    ↓
Application API
    ↓
Compose
    ↓
Android DI
    ↓
Novel List
    ↓
TXT Import
    ↓
Analysis
    ↓
Vocabulary Candidate
    ↓
Validation
```

### P7.0 — Storage 驱动无关数据库初始化

- `DatabaseInitializer.initializeDatabase(driver)`：建表 + 3 个守卫触发器（Original 只读写保护、禁止 Variant→Variant）+ 幂等初始化。
- Android 使用 `AndroidSqliteDriver → DatabaseInitializer`；JVM 使用 `QianyanDbFactory.open()`（JDBC 专用入口）。两者共用同一套驱动无关初始化。

### P7.1 — Application 查询 API

- `NovelUseCases.listOriginals()`（Android 首页小说列表入口）。
- `VocabularyUseCases.findCandidatesByNovel(novelId)`（候选词查询入口）。

### P7.2 — Android Compose 构建基础

- Kotlin Compose plugin + Compose Runtime / Foundation / Material3 / Activity Compose / Lifecycle Compose / ViewModel Compose。

### P7.3 — Android DI

```text
QianyanApplication
    ↓
AndroidSqliteDriver
    ↓
DatabaseInitializer
    ↓
MockLLMGateway
    ↓
ApplicationContainer
    ↓
Application Use Cases
```

### P7.4 — 第一版功能 UI

- Novel List：`NovelListScreen` / `NovelListViewModel` / `NovelListUiState`（Loading / Empty / Success / Error）+ 重试。
- Compose Theme / Color / Type（Material3）+ `MainActivity` 装配。

> **当前 UI 是功能验证版本，不是最终 UI 设计。** 采用 Apple-inspired 基础视觉方向（中性色 / 大标题 / 留白 / 圆角卡片，Material3 基础设施），最终 Apple-inspired UI/UX 将在后续 UI 重设计阶段完成。

### P7.5 — TXT 导入（Android SAF）

```text
SAF
 ↓
Uri
 ↓
ByteArray + displayName
 ↓
NovelListViewModel
 ↓
TxtUseCases.importTxtAsOriginal
 ↓
TXT Pipeline
 ↓
Novel + Document
```

- 使用 Android SAF（`OpenDocument`），**无需存储权限**。
- **Uri 只存在 Android 平台层**，不进入 Application / Domain。
- 支持重复导入检测（contentHash → isDuplicate）、UTF-8 / 空文档错误处理（中文提示）。

### P7.6 — Analysis（Mock）

```text
Novel
 ↓
TxtDocument
 ↓
Novel Vocabulary
 ↓
AnalysisUseCases
 ↓
MockLLMGateway
 ↓
AnalysisOutput
 ↓
VocabularyCandidate
```

- `AnalysisScreen` / `AnalysisViewModel` / `AnalysisUiState`（Idle / Loading / Success / SuccessWithWarnings / Error）已完成。
- `AnalysisViewModel` 通过 `TxtUseCases.findDocumentsByNovel` → `VocabularyUseCases.getOrCreateNovelVocabulary`（创建/复用 NOVEL 词库）→ `AnalysisUseCases.analyzeTxtOriginal` → `findCandidatesByNovel` 完成候选查询与展示。
- **当前仍然使用 `MockLLMGateway`**；**真实 DeepSeek / MiMo Provider 不属于 P7**。

### P7.7 — 最终验收

- 架构边界审计、错误处理审计、全量测试、Android Unit Test、assembleDebug、Git 审计。

### P7 边界（未在 P7 实现）

- ❌ 真实 DeepSeek / MiMo Provider　❌ Agent / Orchestration / Workflow　❌ Knowledge / Character / Event 正式系统　❌ Candidate 确认流程　❌ Desktop UI　❌ PC / Cloud Backend　❌ Hilt / Koin / Room / DataStore / Navigation Framework

## P8.1 Task / Checkpoint Persistence

P8.1 将领域层已存在的 `Task` / `Checkpoint` 模型落地为可持久化数据层（**不含 TaskManager 状态机**）：

- **core:model**：`Checkpoint` 补齐 `revision`（1..3）与 `createdAt`（持久化必需的最小调整）。
- **Schema v2**：新增 `Task` / `Checkpoint` 表（TEXT 强类型 ID、INTEGER epoch 毫秒、枚举名状态、JSON snapshot）。
- **v1 → v2 migration**（`1.sqm`）：仅新增两表，不删除/修改既有 P0–P7 表，旧数据兼容，可重复幂等初始化。
- **`TaskRepository` / `SqliteTaskRepository`**：`create / findById / update / delete / saveCheckpoint / findCheckpoints / findLatestCheckpoint`。
- **Task ↔ Checkpoint 映射**（`StorageMappers`）与 Task 存储异常（`TaskNotFoundException` / `RevisionLimitExceededException`）。
- **Checkpoint 持久化**：`saveCheckpoint` 同事务同步 Task `revision_count` / `updated_at`。
- **revision 1..3 约束**：领域 / 仓储校验 + DB `CHECK`（`revision BETWEEN 1 AND 3`、`revision_count BETWEEN 0 AND 3`）。
- **事务原子性**：create / update / delete / saveCheckpoint / migration 均为单事务，不留半成品数据。
- **测试**：`TaskRepositoryTest`（14 用例）+ `TaskMigrationTest`（1 用例）+ 全量回归通过。

> 明确：P8.1 **不实现** TaskManager / Task 状态机 / start-pause-resume-cancel-complete-fail 的 Application 管理（属 P8.2）。

## 模块结构

```
core/model         领域模型（纯数据，零依赖）
core/engine        TXT 确定性引擎（纯 JVM，零 AI 依赖）
core/engine/analysis 确定性 TXT → AnalysisInput 构建（P6，零 AI/存储依赖）
agent/tool         工具系统 / 权限矩阵（占位）
agent/runtime      Agent Runtime（占位）
agent/agents       六个 Agent 定义（占位）
agent/orchestration  Agent 编排（占位）
provider/api       AI Provider 抽象契约（LLM 契约 + 请求/响应 + 异常，P6）
provider/impl      AI Provider 实现（MockLLMGateway，P6）
storage            SQLDelight + SQLite / Repository / Backup / Task·Checkpoint persistence（P8.1）
application        Use Case 层（DI 容器 + 错误边界；含 P6 AnalysisUseCases）
runtime            平台 Runtime 抽象（占位）
app/android        Android 客户端（P7 功能验证 UI：列表 / TXT 导入 / Analysis）
app/desktop        Desktop 客户端（占位）
test/e2e           E2E 测试（占位）
```

### 分层架构（冻结 V4.1）

```
UI → Application → Task Manager → Agent Orchestrator → 6 Agent
     → Tool Layer → Core Engines（确定性）→ Knowledge / Memory → Storage（SQLite）
```

核心为 **Kotlin/JVM 共享 Core**，Android 与 PC 通过不同 Runtime Adapter 复用同一套 Domain / Agent / Workflow / Tool / Provider 契约。

## 技术栈

- Kotlin / JVM（toolchain 17）
- Gradle（Version Catalog 统一依赖）
- SQLDelight + SQLite（JDBC driver，JVM 可跑测试；Android driver，P7.3）
- Android Compose（Material3 / Activity Compose / Lifecycle Compose / ViewModel Compose，P7）
- kotlinx.serialization / kotlinx.datetime
- JUnit 5 + kotlin.test

## 构建与测试

```bash
# 全量测试（P8.1 验证通过：133 tests / 0 failures / 0 errors）
./gradlew test

# 关键模块测试（P4 TXT Pipeline / Storage）
./gradlew :core:engine:test :storage:test :application:test

# Android 单元测试（P8.1：20 tests）
./gradlew :app:android:testDebugUnitTest

# Android Debug APK
./gradlew :app:android:assembleDebug
```

> 注：P7.7 验收实测 `./gradlew test`、`:app:android:testDebugUnitTest`、`:app:android:assembleDebug` 均 BUILD SUCCESSFUL；APK 生成于 `app/android/build/outputs/apk/debug/android-debug.apk`。

## TXT Pipeline（P4）

```
TXT 文件 → TxtImporter（编码/BOM）→ TextNormalizer（确定性规范化）
        → ChapterDetector（章节识别）→ Structured Document（章节/段落块）
        → TxtRepository → SQLite（SQLDelight）
```

- 支持 UTF-8 / UTF-8 BOM；CRLF / CR / LF 归一；空行折叠；段落边界保留。
- 章节识别：`第X章` / `卷一 第一章` / `Chapter 1` / `序章` / `尾声` / `番外` 等常见格式。
- 原文永不修改（`originalText` 保留），`reconstruct()` 可从结构化结果恢复正文。
- 完全确定性：相同输入 + 相同规则版本 → 相同结果（`contentHash` + `ruleVersion` 校验）。

## 文档

- [实现计划](docs/planning/qianyan-implementation-plan.md)
- [总体设计](docs/planning/qianyan-master-plan.md)
- [架构评审](docs/planning/qianyan-v4.2-architecture-review.md)
- [项目状态](docs/status/qianyan-project-status.md)
