# 词库归属裁决记录（V1–V3）

> 状态：**FINALIZED**（2026-10-28，规划层裁决；依据 T-V0 只读盘点）
> 取代：`qianyan-dev-implementation-plan-v3.1.md` §11A 的**禁止条款**与 AUTHOR/SERIES/BOOK 作用域模型。
> 保留：p20 `FD-6`、Phase 4A `J-1`、Phase 4C `AC-30A/B` / `NR-1`。
> 本文件只追加、不改写历史文档；§11A 仅加指针。

## 1. 冲突事实（T-V0 实测）

同一领域存在两组互相矛盾的正式文档：

| 方向 | 出处 | 主张 |
|---|---|---|
| 禁止独立域 | v3.1 §11A（`:496`） | 不建第二套事实源；**不新增词库表 / `VocabularyUseCase` / `VocabularyContext` / 独立 Vocabulary 领域层**；作用域 = AUTHOR/SERIES/BOOK |
| 承认现行域 | `p20-architecture-freeze.md:64-66` FD-6 | 复用既有 `Vocabulary / VocabularyCandidate`，**同一 Vocabulary 表族**，状态"已实现（P1/P6 起）" |
| 承认现行域 | `qianyan-phase4a-context-contract-freeze-addendum-payload.md:67` J-1 | 裁决 `Vocabulary = A`；`VocabularySource` 读 **approved `VocabularyEntry`** |
| 承认现行域 | `qianyan-phase4c-acceptance-criteria-draft.md:94,129-136` | NR-1 / AC-30A/B 以现行 Vocabulary 数据集与 prompt 子集为验收对象 |

代码事实：独立 `Vocabulary / VocabularyEntry / VocabularyRule / VocabularyCandidate` 表族、独立 `VocabularyUseCases`、独立 `VocabularySource`/`VocabularyPayload` 均已存在并有生产链路（分析→候选→确认→prompt）。

## 2. 文档优先级裁决

**FD-6 / J-1 / AC-30A/B 优先于 v3.1 §11A 的禁止条款。**

理由（按文档性质与形成时间，不以"代码已存在"倒推动机）：

1. §11A 是**规划期意图**，写于实施之前；
2. FD-6 是**架构冻结记录**、J-1 是**Phase 4A payload 裁决**、AC-30 是**阶段验收标准**——三者均形成于实施之后，且代码与测试已按其闭环；
3. 后形成的正式裁决取代先前的规划条文，这是本项目已确立的文档层级惯例（同 R1/R3/FD-10 的处理方式）。

**不予采纳"用现状反推架构动机"**：本裁决不主张独立词库域是"更好的设计"，只主张它已是**被冻结记录承认的既定事实**，因此让文档与事实对齐，而不是反向迁移。

## 3. V1 — 归属：**V1-A**

承认现行独立 Vocabulary 域。§11A 的修订口径：

- 删除"不新增词库表 / `VocabularyUseCase` / `VocabularyContext` / 独立 Vocabulary 领域层"的禁止条款；
- 改为："词库是本项目**承认的独立域**；同一表族唯一，**不得再新增第二套词库模型**"；
- 保留"不建立第二套事实源"的精神——即词库仍是独立域，但**不得再出现平行词库实现**。

不采纳 V1-B（迁移到 §11A 的 AUTHOR/SERIES/BOOK 或 v0.1 的 Knowledge 挂载）：无迁移设计、无产品动机证据，且会破坏已冻结的 Phase 4 契约与 AC-30；v0.1 设计仍是 `draft/待审阅`，不能作为目标基线。

## 4. V2 — 作用域：**V2-①（两轴 + 变体继承）**

- **真实查询轴 = `novelId` + `variantId`**；`scope_level` 降级为**标注**（保留字段，不参与查询、不用于覆盖优先级）。
- **VARIANT 读取必须合并 NOVEL 基座**：先取 NOVEL，再用同 `canonical` 的 VARIANT 条目覆盖；排序保持确定性（`canonical, entryId`）。
- `GLOBAL` / `TASK` **标注为未启用**（保留枚举值，文档明确无查询/写入路径）；不假装实现。
- **不做** BOOK/SERIES/AUTHOR 映射：项目内无 Series/Author 实体，无产品动机；`NOVEL` 即 BOOK 的等价物。

## 5. V3 — MVP 范围：**V3-A（只做别名收敛进 prompt）**

- Planner / Writer 渲染改为包含别名：`canonical（别名：a、b）-> replacement`；
- 同步修复"aliases 计入 Context 预算却不进 prompt"的不一致（E2）；
- **不做** `VocabularyRule` 执行（无消费者、无执行语义定义）→ 登记 O-37；
- **不做** Critic/Revision 别名漂移检查（新能力，需独立契约）→ 登记 O-38；
- **不做** TEMPLATE / 词条编辑 UI / 手工建库 → 登记为后续产品项。

## 6. 后果与边界

| 项 | 结论 |
|---|---|
| Schema 迁移 | **无**（V1-A 不动表结构） |
| 冻结契约 | FD-6 / J-1 / AC-30A/B 全部保留有效 |
| 代码改动（V3-A） | `PlannerAgent` / `WriterAgent` 渲染 + 预算一致性 + 测试 |
| 代码改动（V2-①） | `VocabularySource` 的 VARIANT 分支合并 NOVEL + 测试（独立单元） |
| 文档改动 | §11A 加指针指向本文件；本文件为权威口径 |
| 明确不做 | 迁移、第二套词库、BOOK/SERIES/AUTHOR、Rule 执行、Critic 漂移、TEMPLATE、UI 扩展 |

## 7. 不做的事（防止误读）

- 本裁决**不**声称现行词库功能已完整：别名未进 Agent prompt、Rule 无消费者、Critic 无漂移检查、UI 无词条列表，均是**已知缺口**，按 V3-A 与 O-37/O-38 处理。
- 本裁决**不**授权新增任何词库表或第二个 `VocabularyUseCases`。
- 本裁决**不**改变 R3 / FD-10 / Phase 4 的任何既有语义。
