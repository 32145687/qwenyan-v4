package com.qianyan.application.error

/**
 * Application 层统一业务异常。UI / Agent 只处理它，而不接触 Storage 原始异常。
 * 由 [ErrorMapper] 从 Storage 层的 [com.qianyan.storage.repository.StorageException] 转换而来；
 * Use Case 内部校验失败也直接抛出此异常。
 */
class ApplicationException(
    val error: ApplicationError,
) : RuntimeException(error.toString())