package com.qianyan.application.error

import com.qianyan.engine.txt.TxtException
import com.qianyan.storage.repository.OriginalImmutableException
import com.qianyan.storage.repository.StorageException
import com.qianyan.storage.repository.UniqueConflictException
import com.qianyan.storage.repository.VariantBaseViolation

/**
 * 错误转换（P3.4 + P5）：Storage / 引擎异常 → Application 领域错误。
 *
 * 规则：
 *  - 已是 [ApplicationException] 的不再转换，直接透传；
 *  - [StorageException] 的具体子类映射到对应业务错误；
 *  - P5 新增：引擎 [TxtException] 的具体子类（UnsupportedEncoding / EmptyDocument /
 *    InvalidText / ParseFailed）映射到对应 TXT 领域错误 —— 不泄漏到 Application API 边界，不吞，
 *    不经 String message 判断类型；
 *  - 其它未知异常包一层 [ApplicationError.UnknownStorage]，绝不把原始 SQLite/驱动异常抛给 UI / Agent。
 */
object ErrorMapper {

    fun map(throwable: Throwable): ApplicationException = when (throwable) {
        is ApplicationException -> throwable
        is OriginalImmutableException -> ApplicationException(ApplicationError.ImmutableOriginal(throwable.message ?: ""))
        is VariantBaseViolation -> ApplicationException(ApplicationError.VariantBaseInvalid(throwable.message ?: ""))
        is UniqueConflictException -> ApplicationException(ApplicationError.DuplicateTarget(throwable.message ?: ""))
        is TxtException.UnsupportedEncoding -> ApplicationException(ApplicationError.UnsupportedEncoding(throwable.message ?: ""))
        is TxtException.EmptyDocument -> ApplicationException(ApplicationError.EmptyDocument(throwable.message ?: ""))
        is TxtException.InvalidText -> ApplicationException(ApplicationError.InvalidText(throwable.message ?: ""))
        is TxtException.ParseFailed -> ApplicationException(ApplicationError.ParseFailed(throwable.message ?: ""))
        is StorageException -> ApplicationException(ApplicationError.UnknownStorage(throwable))
        else -> ApplicationException(ApplicationError.UnknownStorage(throwable))
    }
}