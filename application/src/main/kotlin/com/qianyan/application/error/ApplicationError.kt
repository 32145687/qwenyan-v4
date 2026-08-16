package com.qianyan.application.error

/**
 * Application 层领域操作错误（P3.4 错误边界）。
 *
 * 目的：Storage 层的 [com.qianyan.storage.repository.StorageException] 不直接泄露给 UI / Agent。
 * 本层把所有持久化/领域不变量错误归一为符合业务语义的错误代数，再由 ErrorMapper 统一转换。
 * 不携带 SQLite / SQLDelight 的原始技术细节（底层 cause 仅在 UnknownStorage 内保留）。
 */
sealed interface ApplicationError {

    /** Original 不可写：尝试修改/在 Original 作用域下写入被拒绝。 */
    data class ImmutableOriginal(val detail: String) : ApplicationError

    /** Variant 基座不合法：base 非 Original、或试图 Variant→Variant。 */
    data class VariantBaseInvalid(val detail: String) : ApplicationError

    /** 违反唯一约束（如 (targetId, variantId) 逻辑唯一键）。 */
    data class DuplicateTarget(val detail: String) : ApplicationError

    /** 目标实体不存在（Original / Variant 查询不到）。 */
    data class EntityNotFound(val detail: String) : ApplicationError

    /** 变更类型被当前上下文拒绝（如对 Original 添加 Override / 写入 Variant Memory）。 */
    data class InvalidOperation(val detail: String) : ApplicationError

    /** Variant 与上下文所属 Novel 不匹配。 */
    data class VariantMismatch(val detail: String) : ApplicationError

    /** 未知 / 其它 Storage 底层失败，保留 cause 供排查，但仍是领域错误边界。 */
    data class UnknownStorage(val cause: Throwable) : ApplicationError
}