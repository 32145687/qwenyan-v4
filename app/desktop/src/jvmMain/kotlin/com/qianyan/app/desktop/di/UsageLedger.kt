package com.qianyan.app.desktop.di

import com.qianyan.provider.ProviderType
import com.qianyan.provider.Usage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 一条（日期 × 厂商 × 模型）聚合桶（账本读取投影）。 */
data class UsageBucket(
    val date: LocalDate,
    val provider: ProviderType,
    val modelId: String,
    val calls: Int,
    val prompt: Long,
    val completion: Long,
    val total: Long,
)

/** 单个桶的计数（与 usage.json 的字段一一对应）。 */
private data class UsageCounts(
    val calls: Int = 0,
    val prompt: Long = 0,
    val completion: Long = 0,
    val total: Long = 0,
)

/**
 * 本地用量账本（A-14）：`<appDir>/usage.json`，schema v1：
 * `{"version":1,"buckets":{"YYYY-MM-DD|PROVIDER|model":{"calls":n,"prompt":n,"completion":n,"total":n}}}`
 *
 * 约束：
 *  - 记账是旁路——任何失败都被吞掉，绝不抛进聊天链路；读取损坏先备份再重置；
 *  - 全部读写 synchronized；写入走 tmp + ATOMIC_MOVE（不支持的 FS 回退 REPLACE_EXISTING）；
 *  - 只保留 400 天；只记 token 数，不做金额估算。
 */
class UsageLedger(
    val path: Path,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    private var corruptNotice: String? = null

    /** 最近一次损坏重置的说明（供页面显示）；正常时 null。 */
    fun corruptNotice(): String? = synchronized(lock) { corruptNotice }

    fun record(provider: ProviderType, modelId: String?, usage: Usage) = synchronized(lock) {
        runCatching {
            val date = today()
            val model = modelId?.takeIf { it.isNotBlank() } ?: UNKNOWN_MODEL
            val key = "$date|${provider.name}|$model"
            val buckets = load()
            val old = buckets[key] ?: UsageCounts()
            val updated = old.copy(
                calls = old.calls + 1,
                prompt = old.prompt + usage.promptTokens,
                completion = old.completion + usage.completionTokens,
                total = old.total + usage.totalTokens,
            )
            write(prune(buckets + (key to updated), date))
        }
        Unit
    }

    /**
     * 读取聚合桶，按日期倒序。
     *
     * [days] = 7 表示「含今天在内的 7 个自然日」；null 表示全部。
     */
    fun summary(days: Int? = null): List<UsageBucket> = synchronized(lock) {
        val cutoff = days?.let { today().minusDays((it - 1).coerceAtLeast(0).toLong()) }
        load().entries
            .mapNotNull { (key, counts) -> parseBucket(key, counts) }
            .filter { cutoff == null || !it.date.isBefore(cutoff) }
            .sortedByDescending { it.date }
    }

    /** 聚合行数（不是调用次数）。 */
    fun recordCount(): Int = synchronized(lock) { load().size }

    /** 清空账本（写回空文件，页面立即显示 0 条）。 */
    fun clear() = synchronized(lock) {
        runCatching { write(emptyMap()) }
        Unit
    }

    private fun parseBucket(key: String, counts: UsageCounts): UsageBucket? {
        val parts = key.split('|', limit = 3)
        if (parts.size != 3) return null
        val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        val provider = runCatching { ProviderType.valueOf(parts[1]) }.getOrNull() ?: return null
        return UsageBucket(
            date = date,
            provider = provider,
            modelId = parts[2],
            calls = counts.calls,
            prompt = counts.prompt,
            completion = counts.completion,
            total = counts.total,
        )
    }

    private fun prune(buckets: Map<String, UsageCounts>, today: LocalDate): Map<String, UsageCounts> {
        val cutoff = today.minusDays(RETENTION_DAYS)
        return buckets.filterKeys { key ->
            val date = runCatching { LocalDate.parse(key.substringBefore('|')) }.getOrNull()
            date == null || !date.isBefore(cutoff)
        }
    }

    /** 读取 buckets；文件缺失 → 空；解析失败 → 备份 + 重置 + 留 notice。 */
    private fun load(): Map<String, UsageCounts> {
        val file = path.toFile()
        if (!file.exists()) return emptyMap()
        return try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject
                ?: throw IllegalArgumentException("usage.json 不是 JSON 对象")
            val bucketsNode = root["buckets"] as? JsonObject
                ?: throw IllegalArgumentException("usage.json 缺少 buckets")
            bucketsNode.mapNotNull { (key, value) ->
                val obj = value as? JsonObject ?: return@mapNotNull null
                key to UsageCounts(
                    calls = (obj["calls"] as? JsonPrimitive)?.intOrNull ?: 0,
                    prompt = (obj["prompt"] as? JsonPrimitive)?.longOrNull ?: 0,
                    completion = (obj["completion"] as? JsonPrimitive)?.longOrNull ?: 0,
                    total = (obj["total"] as? JsonPrimitive)?.longOrNull ?: 0,
                )
            }.toMap()
        } catch (e: Exception) {
            backupCorrupt(file)
            emptyMap()
        }
    }

    private fun backupCorrupt(file: java.io.File) {
        val backupName = "usage.json.corrupt-${System.currentTimeMillis()}"
        runCatching {
            Files.copy(
                file.toPath(),
                path.resolveSibling(backupName),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        runCatching { file.delete() }
        corruptNotice = "本地用量文件已损坏，已备份为 $backupName 并重置"
    }

    private fun write(buckets: Map<String, UsageCounts>) {
        path.parent?.let { Files.createDirectories(it) }
        val payload = buildJsonObject {
            put("version", 1)
            putJsonObject("buckets") {
                buckets.forEach { (key, counts) ->
                    putJsonObject(key) {
                        put("calls", counts.calls)
                        put("prompt", counts.prompt)
                        put("completion", counts.completion)
                        put("total", counts.total)
                    }
                }
            }
        }.toString()
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        tmp.toFile().writeText(payload)
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val RETENTION_DAYS = 400L
        const val UNKNOWN_MODEL = "unknown"
    }
}
