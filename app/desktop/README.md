# 千言 PC（Qianyan Desktop）

Kotlin + **Compose Multiplatform** 桌面客户端。业务能力全部来自 `qwenyan-v4` 的 `ApplicationContainer`，
PC 侧只提供 **Desktop Adapter**（装配、文件、凭证）与 UI，不重写任何后端能力。

## 运行

```bash
./gradlew :app:desktop:run          # 启动桌面应用
./gradlew :app:desktop:jvmTest      # 运行测试（编译 / 装配 / 端到端流程）
./gradlew :app:desktop:packageMsi   # 打包 Windows 安装包（nativeDistributions 已配置 Msi + Exe）
```

要求：JDK 17、Windows（当前唯一验证平台）。

## 架构

```
Main.kt（Compose 窗口 + 侧边栏导航）
   └── DesktopAppState（UI 状态 + 协程编排；错误统一转用户语言）
         └── DesktopGraph（组合根）
               ├── QianyanDbFactory.open("jdbc:sqlite:%APPDATA%/Qianyan/qianyan.db")
               ├── FileProviderCredentialStore        // PC 凭证（明文落盘，MVP 取舍）
               ├── DesktopProviderAssembler           // MOCK → 离线网关；真实 Provider 透传
               └── ApplicationContainer.fromDriver(...)   // 与 Android 同一装配链
```

数据目录：`%APPDATA%\Qianyan\`（`qianyan.db` + `credentials.properties` + `provider-selection.properties`）。

## 页面与真实能力的接线

| 页面 | 接线到的真实 UseCase | 状态 |
|---|---|---|
| 01 首页 | `novels.createOriginal` / `listOriginals` / `getNovel`；`txts.importTxtAsOriginal`；`vocabularies.*`；`analysis.analyzeTxtOriginal`；`genres.availableGenres`（P14-A） | 真实 |
| 02 故事创作 | —— | 设计保留 · 标注开发中（P14/P15 规划） |
| 03 小说规划 | `chapters.listByNovel` + `workflowFacade.getChapterProgress` | 真实（可视化画布属后续阶段） |
| 04 正文创作 | `chapters.createNextChapter`；`workflowFacade.startChapter/advance/approve`；`draftRepository.latestByChapter`；`confirmations.confirmFinalDraft` | 真实 |
| 05 故事管理 | `storyState.list*`；`narrativeState.getNarrativeState`；`chapterContextPack.compileChapterContext` | 真实（只读面板） |
| 06 成书 | `chapters.*` + `draftRepository.latestByChapter` | 真实 |
| 07 作者智能 | —— | 设计保留 · 标注开发中（P16–P19 规划） |

## 作品删除

删除是**真删除**：物理删除作品及其全部关联数据（章节、草稿、TXT 文档、词库、故事状态六类、伏笔、Reveal、叙事账本、Workflow/Step/Attempt/Gate/Continuation、Task/Checkpoint）。

- **删除前自动备份**：每章正文 + 元信息导出到 `%APPDATA%\Qianyan\deleted-backups\<书名>-<时间戳>\`。
- 实现在 PC 侧 `DesktopNovelDeletion`（单事务级联，失败自动回滚），未改 `:application` / `:storage` 的仓储与 UseCase。
- **对共享核心的唯一改动**：`storage/.../DatabaseInitializer.kt` 移除了 P2.4 的 `novel_original_delete_protect` 触发器（并显式 DROP 以清理既有库）。Original 的**改写**保护（`novel_original_update_protect`）与 Variant→Variant 禁止仍然保留。
- 覆盖测试：`DesktopDeletionTest`（级联彻底性 + 备份落盘 + 旁观作品不受影响 + 改写保护仍生效）。

## AI Provider 边界（重要）

- `ProviderType.MOCK` → `DesktopOfflineLlmGateway`：**离线示意稿**。
  原因：上游自带的 `MockLLMGateway` 默认响应只覆盖 P6 的词汇分析，Planner / Writer / Critic /
  KnowledgeUpdater 期望的结构不在其中，直接用会在 PLANNING 阶段抛 `InvalidPlanningOutput`。
  本类用官方 `MockLLMGateway(responseFor = …)` 注入 seam 在 PC 侧补齐，**未修改任何共享核心**。
- `ProviderType.DEEPSEEK` / `MIMO` → 透传真实网关（需在设置中配置 API Key）。

无论哪种 Provider：**Task / Workflow / Checkpoint / Draft / Repository / SQLite 全部是真实实现**，
Mock 只影响 LLM 生成的内容本身。

## 测试

| 测试 | 覆盖 |
|---|---|
| `DesktopGraphSmokeTest` | 容器装配 + 作品/章节读写 |
| `DesktopFlowE2ETest` | 建作品 → 建章节 → Workflow 推进到人工门 → approve → **重开库验证持久化** → 规划产出 ChapterPlan |
| `SeedDemoDataTemp` | `@Disabled` dev 工具：为桌面数据目录播种演示数据（需要时手动移除注解） |

## 尚未实现（UI 已保留设计 / 无假按钮）

- 02 故事创作全流程、07 作者智能 —— 后端 P14–P19 规划中。
- Foreshadow 生命周期流转、Reveal 创建、Story State 写入 —— **Variant-only**，Original 只读；
  等 Variant 工作台阶段开放入口。
- 伏笔泳道状态机可视化、剧情网络、三层时间轴 —— 原型已设计，待后续阶段实现。
- PC 凭证加密存储（当前明文落盘，P20 加固项）。
