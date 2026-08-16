package com.qianyan.storage.repository

/** Storage 层统一领域语义异常基类。 */
sealed class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 违反 P2.4 Original 不可变 / 写保护约束。 */
class OriginalImmutableException(message: String = "Original Novel is immutable") : StorageException(message)

/** 违反 P2.4 单层 Variant 约束（Variant 只能以 Original 为基座；禁止 Variant→Variant）。 */
class VariantBaseViolation(message: String = "Variant base must be an Original Novel") : StorageException(message)

/** 违反唯一约束（如 (targetId, variantId) 逻辑唯一键）。 */
class UniqueConflictException(message: String) : StorageException(message)