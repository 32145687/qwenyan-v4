# DSH Runtime Adapter 契约（DSH-04）

> 代码位置：`application/src/main/kotlin/com/qianyan/application/usecase/dsh/`
> 状态：纯契约 + 内存假件，**不接任何生产路径**（不进 `ApplicationContainer`）。
> 原则：本包只描述"运行时能做什么"，不得出现小说领域概念（Novel / Chapter / Canonical / HumanGate / WorldModel）。

## 1. 类型与签名

```kotlin
interface DshRuntime {
    fun openSession(workflowRef: String): RuntimeSession   // workflowRef 为不透明字符串
}

interface RuntimeSession {
    val sessionId: String
    fun dispatch(task: RuntimeTask): RuntimeHandle
}

interface RuntimeHandle {
    fun events(): Sequence<RuntimeEvent>
    fun cancel()
}

data class RuntimeTask(
    val input: String,
    val allowedTools: List<String>,   // 工具名，不含业务类型
    val maxRounds: Int,
    val budgetMillis: Long,
)

data class RuntimeEvent(
    val kind: RuntimeEventKind,
    val name: String,
    val detailSummary: String,        // ≤ 80 字；超长经 RuntimeEvent.summarize 截断
    val resultCount: Int? = null,
    val durationMillis: Long? = null,
    val ok: Boolean? = null,
)

data class PermissionRequest(val requestId: String, val action: String, val summary: String)
enum class PermissionResponse { APPROVED, DENIED, TIMEOUT }
```

**事件通道为什么用 `Sequence`**：事件由适配器/假件同步拉取，测试可精确控制消费进度
（消费 N 条后取消）；不引入协程或 Flow 依赖；取消语义由 `RuntimeHandle.cancel()` 显式表达。

## 2. 事件表（固定枚举 `RuntimeEventKind`，共 10 种）

| kind | 含义 | 典型 name | 常用字段 |
| --- | --- | --- | --- |
| `PHASE_START` | 一次运行开始 | `session` | — |
| `PHASE_END` | 一次运行正常结束 | `session` | `ok = true` |
| `SKILL_SELECTED` | 选中技能 | 技能名 | — |
| `TOOL_CALL` | 发起工具调用 | 工具名 | `resultCount` 可空 |
| `TOOL_RESULT` | 工具返回 | 工具名 | `resultCount`、`ok` |
| `MODEL_CALL` | 模型调用 | 模型名 | `durationMillis` |
| `CANDIDATE_READY` | 候选产物就绪 | `candidate` | `resultCount`、`ok = true` |
| `GATE_WAIT` | 等待人工裁决 | 动作名 | `ok = null` |
| `CANCELLED` | 已取消（终止事件） | `session` | `ok = false` |
| `FAILED` | 运行失败（终止事件） | `session` | `ok = false`，摘要截断 |

摘要约束：`detailSummary` 构造时强制 ≤ 80 字；超长必须先用 `RuntimeEvent.summarize(text)`
（保留前 79 字 + `…`）。`FAILED` 的摘要同样遵守该上限。

## 3. 权限模型

| 阶段 | 动作 | 结果 |
| --- | --- | --- |
| 请求 | 假件登记 `PermissionRequest(requestId, action, summary)` | 进入 `pendingPermissions()` |
| 应答 | `InMemoryDshRuntime.respond(requestId, response)` | 回传 `APPROVED` / `DENIED` / `TIMEOUT` 之一 |
| 回传后 | 从待应答列表移除并记录 | 再次应答同一 `requestId` → `null`；未知 id → `null`（不伪造） |

三态语义：`APPROVED` 允许执行；`DENIED` 拒绝执行；`TIMEOUT` 视为未在时限内获得裁决（同样不放行）。

## 4. 取消语义

- `cancel()` 幂等；可在事件流消费前或消费中调用。
- 事件流以 `CANCELLED` 作为**最后一个**事件；`CANCELLED` 之后不再产生任何事件。
- 已正常跑完（`PHASE_END` 已发出）的句柄不会被追加 `CANCELLED`。

## 5. 失败语义

- 失败不抛到调用方：事件流内追加 `FAILED`（`ok = false`），摘要 ≤ 80 字。
- `FAILED` 是终止事件：出现在 `PHASE_END` 位置，其后无事件。
- 假件通过构造参数 `failureSummary` 模拟失败；正常编排保持可重复回放。

## 6. 与代码一致性自查

- 事件枚举 10 项与 `RuntimeEventKind` 逐项一致；
- 字段名/可空性与 `RuntimeEvent` / `RuntimeTask` 一致；
- 权限三态与 `PermissionResponse` 一致，应答规则与 `InMemoryDshRuntime.respond` 一致；
- 取消/失败终止规则与 `InMemoryDshRuntime.Handle.events()` 一致（有契约测试硬断言）。

## 7. 非目标（本单元不做）

- 不接生产路径、不进容器、不改任何既有用例或 UI；
- 不新建 Gradle 模块（统计口径保持三模块）。
