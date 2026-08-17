package com.qianyan.application.usecase

import com.qianyan.application.error.ApplicationException
import com.qianyan.application.error.ErrorMapper
import com.qianyan.storage.repository.StorageException
import java.util.UUID

/**
 * Use Case 基座：统一的异常边界执行（P3.4）。
 *
 * [guard] 把 Storage 层的 [StorageException]（及其子类）经 [ErrorMapper] 转换为
 * Application 领域错误 [ApplicationException]，业务代码无需逐处 try/catch。
 *
 * P5 演进：`errorMapper` 从 private 放开为 protected，供 TXT Use Case 在 guard 之外
 * 把引擎层 [com.qianyan.engine.txt.TxtException] 同样归一为领域错误（引擎异常不是 StorageException）。
 */
abstract class UseCase(protected val errorMapper: ErrorMapper) {

    protected fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: ApplicationException) {
            throw e
        } catch (e: StorageException) {
            throw errorMapper.map(e)
        }

    /** 全局唯一 ID 生成（P2.3：ID 全局唯一，不进入 scope）。主要供创建 Use Case 装配领域模型。 */
    protected fun nextId(): String = UUID.randomUUID().toString()
}