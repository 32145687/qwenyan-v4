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

## 当前进度（P0–P4）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 13 模块工程骨架 + Gradle/CI 全绿 | ✅ |
| P1 | `core:model` 全量领域模型 + 强类型 ID + 测试 | ✅ |
| P2 | Storage：SQLDelight 单 SQL 真源 + 5 仓储 + 写保护触发器 + Backup | ✅ |
| P3 | Application：Use Case 层（DI / 错误边界 / 集成测试） | ✅ |
| P4 | TXT Pipeline：确定性 导入 → 规范化 → 章节识别 → 结构化 → 持久化 | ✅ |

**尚未实现**：Agent 编排、TXT 语义分析、写作工作流、AI Provider、Android / Desktop UI、云端后端。

## 模块结构

```
core/model         领域模型（纯数据，零依赖）
core/engine        TXT 确定性引擎（纯 JVM，零 AI 依赖）
agent/tool         工具系统 / 权限矩阵（占位）
agent/runtime      Agent Runtime（占位）
agent/agents       六个 Agent 定义（占位）
agent/orchestration  Agent 编排（占位）
provider           AI Provider 抽象（占位）
storage            SQLDelight + SQLite / Repository / Backup
application        Use Case 层（DI 容器 + 错误边界）
runtime            平台 Runtime 抽象（占位）
app/android        Android 客户端（占位）
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
- SQLDelight + SQLite（JDBC driver，JVM 可跑测试）
- kotlinx.serialization / kotlinx.datetime
- JUnit 5 + kotlin.test

## 构建与测试

```bash
# 全量测试
./gradlew test

# 关键模块测试（P4 TXT Pipeline / Storage）
./gradlew :core:engine:test :storage:test :application:test

# Android Debug APK
./gradlew :app:android:assembleDebug
```

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
