package com.qianyan.storage.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * 驱动无关的数据库初始化（P7.0；P8.1 扩展 v1→v2 迁移）。
 *
 * 把"建表 + 守卫触发器 + 版本迁移"从 JVM 专用 [QianyanDbFactory] 中抽离为独立入口，
 * 使 JVM（JdbcSqliteDriver）与 Android（AndroidSqliteDriver）共用同一初始化逻辑，
 * 而无需把任何模块迁移为 Kotlin Multiplatform。
 *
 * 幂等语义：
 *  - SQLDelight 生成的 [QianyanDb.Schema.create] / [QianyanDb.Schema.migrate] 是**裸 DDL**
 *    （不带 IF NOT EXISTS），重复执行会报"表已存在"，故先经 `sqlite_master` 判断当前状态：
 *    全新库（无 Novel）→ `Schema.create` 建出完整 v3 schema（含 Task / Checkpoint / ChapterDraft）；
 *    旧 v1 库（有 Novel 无 Task）→ `Schema.migrate(1, 2)` 应用 `1.sqm` 迁移（仅新增
 *    Task / Checkpoint，不删除/修改既有业务表，旧数据原样保留）；
 *    旧 v2 库（有 Task 无 ChapterDraft）→ `Schema.migrate(2, 3)` 应用 `2.sqm` 迁移（仅新增
 *    ChapterDraft，P11.3）；
 *    已 v3 → 跳过建表/迁移。
 *  - 每次建表/迁移都在单事务内执行并同步 `PRAGMA user_version`（与 AndroidSqliteDriver
 *    由 SQLiteOpenHelper 管理版本一致），保证原子性 + 版本簿记正确（供后续 P8.x 迁移）。
 *  - 守卫触发器一律使用 `CREATE TRIGGER IF NOT EXISTS`，天然幂等，每次初始化都执行，
 *    保证 JVM 与 Android 两平台守卫一致性。
 * `sqlite_master` 是 SQLite 标准系统目录，JVM 与 Android 的 SQLite 实现均支持。
 */
object DatabaseInitializer {

    /** v1 起始版本（迁移调用起点）。 */
    private const val V1 = 1L

    /** v2（P8.1 Task / Checkpoint）→ v3（P11.3 ChapterDraft）迁移起点。 */
    private const val V2 = 2L

    /** v3（P11.3 ChapterDraft）→ v4（P12.0：Chapter 表 + MemoryEntry.target/effective + ChapterDraft.previous_draft_id）。 */
    private const val V3 = 3L

    /** v4（P12.0 Chapter）→ v5（P12.1.1：6 张 Story State 表）迁移起点。 */
    private const val V4 = 4L

    /** v5（P12.1 Story State）→ v6（P12.2：Durable Workflow 5 表 + ChapterDraft chapter_id 索引）迁移起点。 */
    private const val V5 = 5L

    /** v6（P12.2 Workflow）→ v7（P13 LCL-A：NarrativeState + NarrativeDelta）迁移起点。 */
    private const val V6 = 6L

    /** v7（P13 LCL-A）→ v8（P13 LCL-C：Foreshadow 生命周期加列）迁移起点。 */
    private const val V7 = 7L

    /** v8（P13 LCL-C）→ v9（P13 LCL-D：Reveal 表 + scope 索引）迁移起点。 */
    private const val V8 = 8L

    /** v9（P13 LCL-D）→ v10（P14-F.2：StoryFoundation + FoundationOverride）迁移起点。 */
    private const val V9 = 9L

    /** v10（P14-F.2：StoryFoundation）→ v11（P16 AIL-1：AuthorProfile + AuthorPreference + AuthorEvidence）迁移起点。 */
    private const val V10 = 10L

    /** v11（P16 AIL-1：Author 三表）→ v12（P17：AuthorCore 独立表族）迁移起点。 */
    private const val V11 = 11L

    /** v12（P17：AuthorCore）→ v13（P18-A：AuthorObservation 独立表）迁移起点。 */
    private const val V12 = 12L

    /** v13（P18-A：AuthorObservation）→ v14（P18-B：AuthorCoreEvidenceLink 加 provenance_novel_id 列）迁移起点。 */
    private const val V13 = 13L

    /** v14（P18-B）→ v15（P18-C：AuthorDnaVersion + AuthorDnaFeature + AuthorDnaSource 独立表族）迁移起点。 */
    private const val V14 = 14L

    /** v15（P18-C）→ v16（P20-P2：ChapterDraft 加 format 列）迁移起点。 */
    private const val V15 = 15L

    /** v16（P20-P2：ChapterDraft.format）→ v17（P20-P4：ReadingProgress 阅读位置表）迁移起点。 */
    private const val V16 = 16L

    /** v17（P20-P4：ReadingProgress）→ v18（I1：ProjectState IDE/Agent 运行态）迁移起点。 */
    private const val V17 = 17L

    /** v18（I1：ProjectState）→ v19（I3：AgentSession 会话身份）迁移起点。 */
    private const val V18 = 18L

    /** v19（I3：AgentSession）→ v20（I4：Activity + ToolCallLog 活动事实记录）迁移起点。 */
    private const val V19 = 19L

    /** v20（I4：Activity + ToolCallLog）→ v21（I10：CommitHistory Canonical 提交审计记录）迁移起点。 */
    private const val V20 = 20L

    /** v21（I10：CommitHistory）→ v22（I13：TaskQueueItem 后台任务调度条目）迁移起点。 */
    private const val V21 = 21L

    /** v22（I13：TaskQueueItem）→ v23（P1-01：WorkflowHumanGate.workflow_id 放宽为可空）迁移起点。 */
    private const val V22 = 22L

    /** v23（P1-01）→ v24（P1-02j：CommitHistory 加可空 gate_id 列 + 反查索引）迁移起点。 */
    private const val V23 = 23L

    /** v24（P1-02j）→ v25（P1-02j.2：WorkflowStep 加可空 round_no 列）迁移起点。 */
    private const val V24 = 24L

    /** v25（P1-02j.2）→ v26（Phase 1：CommitHistory 加可空 previous_format / resulting_format 列）迁移起点。 */
    private const val V25 = 25L

    /** v26（Phase 1）→ v27（Phase 2：World Model 字段补全，Character 3 + CharacterState 7 + Event 7 列）迁移起点。 */
    private const val V26 = 26L

    /** v27（Phase 2）→ v28（Phase 3：CandidateFact 候选事实表，additive）迁移起点。 */
    private const val V27 = 27L

    /** Schema 建好后仍需追加执行的守卫 DDL（每项一个完整语句）。 */
    private val GUARD_DDL: List<String> = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS novel_original_update_protect
        BEFORE UPDATE ON Novel
        WHEN OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Novel is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS novel_original_delete_protect
        BEFORE DELETE ON Novel
        WHEN OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Novel is immutable');
        END;
        """.trimIndent(),
        // 禁止 "Variant → Variant"：base 必须以 scope=ORIGINAL 的 Novel 为基座。
        // base 缺失时子查询返回 NULL，`NULL IS NOT 'ORIGINAL'` 亦为真，同样被拦截。
        """
        CREATE TRIGGER IF NOT EXISTS variant_base_must_be_original
        BEFORE INSERT ON NovelVariant
        WHEN (SELECT scope FROM Novel WHERE novel_id = NEW.base_novel_id) IS NOT 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Variant base must be an Original Novel');
        END;
        """.trimIndent(),
        // P2 收口（v3.1 §11.10）：Story State 六表的 Original 写保护。
        // Original 判定（StoryState.sq 表契约）：variant_id IS NULL 且 scope='ORIGINAL'。
        // 三条路径全覆盖：
        //   · UPDATE / DELETE：直接拦截；
        //   · INSERT（overwrite）：本仓储写路径均为 INSERT OR REPLACE——在 recursive_triggers=OFF（默认）
        //     下 REPLACE 的隐式 DELETE 不触发 DELETE 触发器，故必须用 BEFORE INSERT + EXISTS 显式拦截
        //     "同 PK 已存在 Original 行"的覆盖；新 Original 行首插与 Variant 行按契约重存不受影响。
        // Variant 行（含 Foreshadow 生命周期 transitionForeshadowState 的 UPDATE）不在保护范围。
        // Reveal / NarrativeState 不在本轮范围（Reveal 为 insert-only 运行时 fact，另裁决）。
        """
        CREATE TRIGGER IF NOT EXISTS character_original_update_protect
        BEFORE UPDATE ON Character
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Character is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS character_original_delete_protect
        BEFORE DELETE ON Character
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Character is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS character_original_overwrite_protect
        BEFORE INSERT ON Character
        WHEN EXISTS (SELECT 1 FROM Character WHERE character_id = NEW.character_id AND variant_id IS NULL AND scope = 'ORIGINAL')
        BEGIN
            SELECT RAISE(ABORT, 'Original Character cannot be overwritten');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS character_state_original_update_protect
        BEFORE UPDATE ON CharacterState
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original CharacterState is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS character_state_original_delete_protect
        BEFORE DELETE ON CharacterState
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original CharacterState is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS character_state_original_overwrite_protect
        BEFORE INSERT ON CharacterState
        WHEN EXISTS (SELECT 1 FROM CharacterState WHERE state_id = NEW.state_id AND variant_id IS NULL AND scope = 'ORIGINAL')
        BEGIN
            SELECT RAISE(ABORT, 'Original CharacterState cannot be overwritten');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS event_original_update_protect
        BEFORE UPDATE ON Event
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Event is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS event_original_delete_protect
        BEFORE DELETE ON Event
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Event is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS event_original_overwrite_protect
        BEFORE INSERT ON Event
        WHEN EXISTS (SELECT 1 FROM Event WHERE event_id = NEW.event_id AND variant_id IS NULL AND scope = 'ORIGINAL')
        BEGIN
            SELECT RAISE(ABORT, 'Original Event cannot be overwritten');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS world_rule_original_update_protect
        BEFORE UPDATE ON WorldRule
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original WorldRule is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS world_rule_original_delete_protect
        BEFORE DELETE ON WorldRule
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original WorldRule is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS world_rule_original_overwrite_protect
        BEFORE INSERT ON WorldRule
        WHEN EXISTS (SELECT 1 FROM WorldRule WHERE rule_id = NEW.rule_id AND variant_id IS NULL AND scope = 'ORIGINAL')
        BEGIN
            SELECT RAISE(ABORT, 'Original WorldRule cannot be overwritten');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS timeline_entry_original_update_protect
        BEFORE UPDATE ON TimelineEntry
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original TimelineEntry is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS timeline_entry_original_delete_protect
        BEFORE DELETE ON TimelineEntry
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original TimelineEntry is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS timeline_entry_original_overwrite_protect
        BEFORE INSERT ON TimelineEntry
        WHEN EXISTS (SELECT 1 FROM TimelineEntry WHERE timeline_id = NEW.timeline_id AND variant_id IS NULL AND scope = 'ORIGINAL')
        BEGIN
            SELECT RAISE(ABORT, 'Original TimelineEntry cannot be overwritten');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS foreshadow_original_update_protect
        BEFORE UPDATE ON Foreshadow
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Foreshadow is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS foreshadow_original_delete_protect
        BEFORE DELETE ON Foreshadow
        WHEN OLD.variant_id IS NULL AND OLD.scope = 'ORIGINAL'
        BEGIN
            SELECT RAISE(ABORT, 'Original Foreshadow is immutable');
        END;
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS foreshadow_original_overwrite_protect
        BEFORE INSERT ON Foreshadow
        WHEN EXISTS (SELECT 1 FROM Foreshadow WHERE foreshadow_id = NEW.foreshadow_id AND variant_id IS NULL AND scope = 'ORIGINAL')
        BEGIN
            SELECT RAISE(ABORT, 'Original Foreshadow cannot be overwritten');
        END;
        """.trimIndent(),
    )

    /**
     * 初始化数据库（幂等）：
     *  - 全新库：事务内 `Schema.create` 建出当前 schema（含 v2 的 Task / Checkpoint）并记录版本；
     *  - 旧 v1 库（有 Novel 无 Task）：事务内 `Schema.migrate` 应用 1.sqm 迁移，旧数据原样保留，
     *    并同步版本；
     *  - 已 v2：仅幂等确保守卫 DDL 存在。
     * 任意路径后均执行守卫 DDL（IF NOT EXISTS，安全幂等）。
     *
     * @param driver 任意 SQLDelight [SqlDriver]（JVM 或 Android），不绑定 JDBC。
     */
    fun initializeDatabase(driver: SqlDriver) {
        // P12.4-M01：SQLite 外键约束为 connection-level、默认关闭；此处每次连接建立时显式开启，
        // 使 DDL 中的 FOREIGN KEY 在 JVM（JdbcSqliteDriver）与 Android（AndroidSqliteDriver）两条路径都真正生效。
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        when {
            !tableExists(driver, "Novel") -> withTransaction(driver) {
                QianyanDb.Schema.create(driver)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Task") -> withTransaction(driver) {
                // v1 → v2：仅新增 Task / Checkpoint；不删除/修改既有业务表，旧数据原样保留。
                QianyanDb.Schema.migrate(driver, V1, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "ChapterDraft") -> withTransaction(driver) {
                // v2 → v3：仅新增 ChapterDraft；不删除/修改既有业务表，旧数据原样保留（P11.3）。
                QianyanDb.Schema.migrate(driver, V2, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Chapter") -> withTransaction(driver) {
                // v3 → v4：新增 Chapter 表 + MemoryEntry(target/effective) + ChapterDraft(previous_draft_id)（P12.0）。
                QianyanDb.Schema.migrate(driver, V3, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Foreshadow") -> withTransaction(driver) {
                // v4 → v5：新增 6 张 Story State 表（Character/CharacterState/WorldRule/Event/TimelineEntry/Foreshadow）（P12.1.1）。
                QianyanDb.Schema.migrate(driver, V4, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Workflow") -> withTransaction(driver) {
                // v5 → v6：新增 Durable Workflow 5 表 + ChapterDraft(chapter_id) 索引（P12.2）。
                QianyanDb.Schema.migrate(driver, V5, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "NarrativeState") -> withTransaction(driver) {
                // v6 → v7：新增 LCL-A NarrativeState + NarrativeDelta（P13）。
                QianyanDb.Schema.migrate(driver, V6, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "Foreshadow", "state") -> withTransaction(driver) {
                // v7 → v8：Foreshadow 加生命周期列 + 回填（P13 LCL-C）。
                QianyanDb.Schema.migrate(driver, V7, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Reveal") -> withTransaction(driver) {
                // v8 → v9：新增 Reveal 表 + scope 索引（P13 LCL-D）。
                QianyanDb.Schema.migrate(driver, V8, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "StoryFoundation") -> withTransaction(driver) {
                // v9 → v10：新增 StoryFoundation + FoundationOverride（P14-F.2）。
                QianyanDb.Schema.migrate(driver, V9, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "AuthorProfile") -> withTransaction(driver) {
                // v10 → v11：新增 Author Intelligence 独立存储（AuthorProfile / AuthorPreference / AuthorEvidence）（P16 AIL-1）。
                QianyanDb.Schema.migrate(driver, V10, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "AuthorCore") -> withTransaction(driver) {
                // v11 → v12：新增 Author Core 独立表族（AuthorCore/Pattern/Candidate/EvidenceLink/Learning）（P17）。
                QianyanDb.Schema.migrate(driver, V11, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "AuthorObservation") -> withTransaction(driver) {
                // v12 → v13：新增 AuthorObservation 独立表（P18-A）。
                QianyanDb.Schema.migrate(driver, V12, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "AuthorCoreEvidenceLink", "provenance_novel_id") -> withTransaction(driver) {
                // v13 → v14：AuthorCoreEvidenceLink 加 provenance_novel_id（P18-B Global 多书门槛）。
                QianyanDb.Schema.migrate(driver, V13, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "AuthorDnaVersion") -> withTransaction(driver) {
                // v14 → v15：新增 Author DNA 独立表族（AuthorDnaVersion / AuthorDnaFeature / AuthorDnaSource）（P18-C）。
                QianyanDb.Schema.migrate(driver, V14, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "ChapterDraft", "format") -> withTransaction(driver) {
                // v15 → v16：ChapterDraft 加 format 列（P20-P2 FD-1：正文格式标识；旧行为 format=null 纯文本兼容）。
                QianyanDb.Schema.migrate(driver, V15, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "ReadingProgress") -> withTransaction(driver) {
                // v16 → v17：新增 ReadingProgress 表（P20-P4 FD-9：阅读位置 / 进度；additive，不动既有表）。
                QianyanDb.Schema.migrate(driver, V16, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "ProjectState") -> withTransaction(driver) {
                // v17 → v18：新增 ProjectState 表（I1：IDE/Agent 运行态引用；additive，不动既有表）。
                QianyanDb.Schema.migrate(driver, V17, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "AgentSession") -> withTransaction(driver) {
                // v18 → v19：新增 AgentSession 表（I3：Agent 工作会话身份；additive，不动既有表）。
                QianyanDb.Schema.migrate(driver, V18, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "Activity") -> withTransaction(driver) {
                // v19 → v20：新增 Activity + ToolCallLog 表（I4：活动/调用事实记录；additive，不动既有表）。
                QianyanDb.Schema.migrate(driver, V19, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "CommitHistory") -> withTransaction(driver) {
                // v20 → v21：新增 CommitHistory 表（I10：Canonical Commit / Revert 的不可变审计记录；additive，不动既有表）。
                QianyanDb.Schema.migrate(driver, V20, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "TaskQueueItem") -> withTransaction(driver) {
                // v21 → v22：新增 TaskQueueItem 表（I13：后台任务调度条目；additive，不动既有表）。
                QianyanDb.Schema.migrate(driver, V21, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            columnIsNotNull(driver, "WorkflowHumanGate", "workflow_id") -> withTransaction(driver) {
                // v22 → v23：WorkflowHumanGate.workflow_id 放宽为可空（P1-01：NULL = 独立 Human Gate /
                // Writer"保存即批准"；非空既有 Gate 语义与外键不变；见 22.sqm）。列已可空则跳过（幂等）。
                QianyanDb.Schema.migrate(driver, V22, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "CommitHistory", "gate_id") -> withTransaction(driver) {
                // v23 → v24：CommitHistory 加可空 gate_id 列 + 反查索引（P1-02j B1：Gate ↔ Commit 持久关联；
                // 历史旧行 gate_id = NULL，不回填；见 23.sqm）。列已存在则跳过（幂等）。
                QianyanDb.Schema.migrate(driver, V23, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "WorkflowStep", "round_no") -> withTransaction(driver) {
                // v24 → v25：WorkflowStep 加可空 round_no 列（P1-02j.2：轮次编号，轮次级修订预算；
                // 历史旧行 round_no = NULL，按首轮 0 解释、不回填；见 24.sqm）。列已存在则跳过（幂等）。
                QianyanDb.Schema.migrate(driver, V24, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "CommitHistory", "previous_format") ||
                !columnExists(driver, "CommitHistory", "resulting_format") -> withTransaction(driver) {
                // v25 → v26：CommitHistory 加可空 previous_format / resulting_format 列（Phase 1 revert 格式契约；
                // 值域 'markdown:controlled:v1' / 'legacy' / 'none' / NULL=未知，不对旧行猜测性回填；
                // 仅做可证明回填：resulting 侧载体行存在 ⇒ 读行 format；previous 侧仅 previous_draft_id 非空且行存在 ⇒
                // 读行 format；首个 COMMIT ⇒ 'none'；同 row 旧行保持 NULL（未知）；见 25.sqm）。列已存在则跳过（幂等）。
                QianyanDb.Schema.migrate(driver, V25, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !columnExists(driver, "Character", "goals") ||
                !columnExists(driver, "CharacterState", "source") ||
                !columnExists(driver, "Event", "fact_level") -> withTransaction(driver) {
                // v26 → v27：World Model 字段补全（Phase 2，v3.1 §11.9）：Character +3（goals/fears/description）、
                // CharacterState +7（location/current_motivation/relationships/known_facts/unknown_facts/abilities/source）、
                // Event +7（what/where_json/cause/consequence/participants/evidence/fact_level）。
                // 全部 additive；新列 DEFAULT 与领域默认值一致，旧行无需回填（字段此前从未持久化）；见 26.sqm。
                QianyanDb.Schema.migrate(driver, V26, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }

            !tableExists(driver, "CandidateFact") -> withTransaction(driver) {
                // v27 → v28：Phase 3 候选事实表（v3.1 §12.9，additive）：
                // CandidateFact 承载 Pending/Accepted/Rejected/Conflict 生命周期与 Commit 来源引用；
                // 纯新增表，不改六表/既有列；见 27.sqm。表已存在则跳过（幂等）。
                QianyanDb.Schema.migrate(driver, V27, QianyanDb.Schema.version)
                setVersion(driver, QianyanDb.Schema.version)
            }
        }
        GUARD_DDL.forEach { driver.execute(null, it, 0) }
    }

    /** 单事务执行建表/迁移 + 版本簿记，任一步失败整体回滚，不留半成品 schema。 */
    private fun withTransaction(driver: SqlDriver, block: () -> Unit) {
        // 复用 SQLDelight Transacter 的事务（BEGIN/COMMIT/ROLLBACK），驱动无关（JVM / Android）。
        QianyanDb(driver).transaction {
            block()
        }
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$table' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    /** 判断某表某列是否存在（用于"既有表加列"迁移分支判定，如 Foreshadow.state）。 */
    private fun columnExists(driver: SqlDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    /**
     * 判断某表某列是否仍为 `NOT NULL`（用于"约束放宽为可空"迁移分支判定，如 WorkflowHumanGate.workflow_id）。
     * 表或列不存在时返回 false（此类库由更早的建表分支处理）。
     */
    private fun columnIsNotNull(driver: SqlDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT 1 FROM pragma_table_info('$table') WHERE name='$column' AND \"notnull\" = 1 LIMIT 1",
            { cursor -> QueryResult.Value(cursor.next().value) },
            0,
        ).value

    /** 同步 schema 版本（与 AndroidSqliteDriver / SQLiteOpenHelper 的版本簿记语义一致）。 */
    private fun setVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
