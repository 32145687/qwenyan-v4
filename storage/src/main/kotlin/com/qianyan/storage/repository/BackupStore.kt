package com.qianyan.storage.repository

import com.qianyan.model.core.BackupPackage

/**
 * Storage 层基础 Backup/Restore 接口（最小，P2.13）。
 *
 * 约束：
 *  - 必须保持数据一致性（Restore 在单事务内完成）。
 *  - 必须尊重 Original / Variant 关系（按原样恢复，不重派生子关系）。
 *  - 必须尊重强类型 ID（以领域序列化持久化）。
 *  - 必须保留 Override。
 *  - 不擅自决定未来 Backup 格式；此处仅提供存储边界的基础快照能力。
 */
interface BackupStore {

    /** 当前数据库的领域校验版本。 */
    val schemaVersion: String

    /** 导出当前数据为 [BackupPackage]（content 为结构化 JsonElement，非 Map）。 */
    fun exportBackup(): BackupPackage

    /** 在单事务内清空并恢复数据。 */
    fun restoreBackup(package_: BackupPackage)
}