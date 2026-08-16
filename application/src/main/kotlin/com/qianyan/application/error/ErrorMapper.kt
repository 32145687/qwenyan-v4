package com.qianyan.application.error

import com.qianyan.storage.repository.OriginalImmutableException
import com.qianyan.storage.repository.StorageException
import com.qianyan.storage.repository.UniqueConflictException
import com.qianyan.storage.repository.VariantBaseViolation

/**
 * 错误转换（P3.4）：Storage 层异常 → Application 领域错误。
 *
 * 规则：
 *  - 已是 [ApplicationException] 的不再转换，直接透传；
 *  - [StorageException] 的具体子类映射到对应业务错误；
 *  - 其它未知异常包一层 [ApplicationError.UnknownStorage]，绝不把原始 SQLite/驱动异常抛给 UI / Agent。
 */
object ErrorMapper {

    fun map(throwable: Throwable): ApplicationException = when (throwable) {
        is ApplicationException -> throwable
        is OriginalImmutableException -> ApplicationException(ApplicationError.ImmutableOriginal(throwable.message ?: ""))
        is VariantBaseViolation -> ApplicationException(ApplicationError.VariantBaseInvalid(throwable.message ?: ""))
        is UniqueConflictException -> ApplicationException(ApplicationError.DuplicateTarget(throwable.message ?: ""))
        is StorageException -> ApplicationException(ApplicationError.UnknownStorage(throwable))
        else -> ApplicationException(ApplicationError.UnknownStorage(throwable))
    }
}