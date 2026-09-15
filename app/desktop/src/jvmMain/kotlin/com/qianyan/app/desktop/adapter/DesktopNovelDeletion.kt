package com.qianyan.app.desktop.adapter

import app.cash.sqldelight.db.SqlDriver
import com.qianyan.storage.db.QianyanDb
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 作品删除（Desktop Adapter）。
 *
 * 背景：Original 作品在 qwenyan-v4 里原被数据库触发器 `novel_original_delete_protect` 保护、
 * 不可删除。经确认后该保护已移除（改写保护仍保留），删除能力在 **PC 侧** 实现，
 * 以避免改动 :application / :storage 的仓储与 UseCase 层。
 *
 * 两条安全措施：
 *  1. **删除前自动备份**：把作品的章节正文与元信息导出到
 *     `%APPDATA%\Qianyan\deleted-backups\<书名>-<时间戳>\`，误删可从这里找回；
 *  2. **事务内级联删除**：按子表→父表顺序清理全部关联数据，避免遗留孤儿行。
 *     （外键未启用 ON DELETE CASCADE，故必须显式逐表删除。）
 */
class DesktopNovelDeletion(
    private val db: QianyanDb,
    private val driver: SqlDriver,
    private val appDir: Path,
) {

    /** 备份条目：章节标题 + 正文。 */
    data class ChapterBackup(val order: Int, val title: String, val content: String)

    /**
     * 备份作品到 deleted-backups/。返回备份目录（失败返回 null，不阻断删除流程之外的调用方决策）。
     */
    fun backup(title: String, synopsis: String, chapters: List<ChapterBackup>): Path? = runCatching {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "untitled" }
        val dir = appDir.resolve("deleted-backups").resolve("$safeTitle-$stamp")
        Files.createDirectories(dir)

        val book = buildString {
            appendLine("《$title》")
            if (synopsis.isNotBlank()) appendLine(synopsis)
            appendLine("备份时间：$stamp")
            appendLine("章节数：${chapters.size}")
            appendLine("=".repeat(40))
        }
        Files.writeString(dir.resolve("00-元信息.txt"), book)

        chapters.sortedBy { it.order }.forEach { ch ->
            val name = "%03d-%s.txt".format(ch.order, ch.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "chapter" })
            Files.writeString(dir.resolve(name), ch.content)
        }
        dir
    }.getOrNull()

    /**
     * 级联删除作品及其全部关联数据（单事务）。返回各表删除行数。
     */
    fun deleteCascade(novelId: String): Map<String, Long> {
        val counts = linkedMapOf<String, Long>()
        // 单事务：中途任一语句失败，SqlDelight 的 transaction 会自动回滚，不留半删状态。
        db.transaction {
            // 子表 → 父表顺序
            exec(counts, "EntityOverride",
                "DELETE FROM EntityOverride WHERE variant_id IN (SELECT variant_id FROM NovelVariant WHERE novel_id = ?)", novelId)
            exec(counts, "NarrativeDelta", "DELETE FROM NarrativeDelta WHERE novel_id = ?", novelId)
            exec(counts, "NarrativeState", "DELETE FROM NarrativeState WHERE novel_id = ?", novelId)
            exec(counts, "CharacterState", "DELETE FROM CharacterState WHERE novel_id = ?", novelId)
            exec(counts, "Character", "DELETE FROM Character WHERE novel_id = ?", novelId)
            exec(counts, "WorldRule", "DELETE FROM WorldRule WHERE novel_id = ?", novelId)
            exec(counts, "Event", "DELETE FROM Event WHERE novel_id = ?", novelId)
            exec(counts, "TimelineEntry", "DELETE FROM TimelineEntry WHERE novel_id = ?", novelId)
            exec(counts, "Foreshadow", "DELETE FROM Foreshadow WHERE novel_id = ?", novelId)
            exec(counts, "Reveal", "DELETE FROM Reveal WHERE novel_id = ?", novelId)

            // Workflow 家族：Task/Checkpoint 先删（它们只归属这部作品的创作过程，
            // 依赖 WorkflowStepAttempt.task_id 反查，故必须排在 Attempt 之前）。
            val taskSub = "SELECT a.task_id FROM WorkflowStepAttempt a " +
                "JOIN WorkflowStep s ON s.step_id = a.step_id " +
                "JOIN Workflow w ON w.workflow_id = s.workflow_id " +
                "WHERE w.novel_id = ? AND a.task_id IS NOT NULL"
            exec(counts, "Checkpoint", "DELETE FROM Checkpoint WHERE task_id IN ($taskSub)", novelId)
            exec(counts, "Task", "DELETE FROM Task WHERE task_id IN ($taskSub)", novelId)

            // WorkflowStepAttempt 无 workflow_id，经 step_id → WorkflowStep → Workflow 关联。
            val stepSub = "SELECT step_id FROM WorkflowStep WHERE workflow_id IN " +
                "(SELECT workflow_id FROM Workflow WHERE novel_id = ?)"
            val wfSub = "SELECT workflow_id FROM Workflow WHERE novel_id = ?"
            exec(counts, "WorkflowStepAttempt", "DELETE FROM WorkflowStepAttempt WHERE step_id IN ($stepSub)", novelId)
            exec(counts, "WorkflowHumanGate", "DELETE FROM WorkflowHumanGate WHERE workflow_id IN ($wfSub)", novelId)
            exec(counts, "WorkflowContinuation", "DELETE FROM WorkflowContinuation WHERE workflow_id IN ($wfSub)", novelId)
            exec(counts, "WorkflowStep", "DELETE FROM WorkflowStep WHERE workflow_id IN ($wfSub)", novelId)
            exec(counts, "Workflow", "DELETE FROM Workflow WHERE novel_id = ?", novelId)

            exec(counts, "ChapterDraft", "DELETE FROM ChapterDraft WHERE novel_id = ?", novelId)
            exec(counts, "Chapter", "DELETE FROM Chapter WHERE novel_id = ?", novelId)

            exec(counts, "TextBlock",
                "DELETE FROM TextBlock WHERE document_id IN (SELECT document_id FROM TxtDocument WHERE novel_id = ?)", novelId)
            exec(counts, "TxtChapter",
                "DELETE FROM TxtChapter WHERE document_id IN (SELECT document_id FROM TxtDocument WHERE novel_id = ?)", novelId)
            exec(counts, "TxtDocument", "DELETE FROM TxtDocument WHERE novel_id = ?", novelId)

            exec(counts, "VocabularyCandidate",
                "DELETE FROM VocabularyCandidate WHERE vocabulary_id IN (SELECT vocabulary_id FROM Vocabulary WHERE novel_id = ?)", novelId)
            exec(counts, "VocabularyEntry",
                "DELETE FROM VocabularyEntry WHERE vocabulary_id IN (SELECT vocabulary_id FROM Vocabulary WHERE novel_id = ?)", novelId)
            exec(counts, "VocabularyRule",
                "DELETE FROM VocabularyRule WHERE vocabulary_id IN (SELECT vocabulary_id FROM Vocabulary WHERE novel_id = ?)", novelId)
            exec(counts, "Vocabulary", "DELETE FROM Vocabulary WHERE novel_id = ?", novelId)

            exec(counts, "MemoryEntry", "DELETE FROM MemoryEntry WHERE novel_id = ?", novelId)
            exec(counts, "NovelVariant", "DELETE FROM NovelVariant WHERE novel_id = ?", novelId)
            exec(counts, "Novel", "DELETE FROM Novel WHERE novel_id = ?", novelId)
        }
        return counts
    }

    private fun exec(counts: MutableMap<String, Long>, table: String, sql: String, novelId: String) {
        val affected = driver.execute(null, sql, 1) { bindString(0, novelId) }.value ?: 0L
        if (affected > 0) counts[table] = affected
    }
}
