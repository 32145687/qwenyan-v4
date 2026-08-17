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

    // ---- P5 新增：TXT 导入相关错误（引擎 TxtException 归一，P4/P5 错误边界） ----
    // 不经 String message 判断类型；ErrorMapper 按 TxtException 具体子类映射。
    // 不携带引擎/字节级技术细节，detail 仅为业务可读文案。

    /** TXT 导入流程不变量被破坏（如已导入 TXT 未绑定到任何 Novel）。 */
    data class TxtImportFailed(val detail: String) : ApplicationError

    /** TXT 编码不受支持（P4 仅支持 UTF-8 家族）。 */
    data class UnsupportedEncoding(val detail: String) : ApplicationError

    /** TXT 解码后正文为空，无可导入内容。 */
    data class EmptyDocument(val detail: String) : ApplicationError

    /** TXT 不是合法文本（如非法 UTF-8 / 损坏字节），拒绝静默再解码。 */
    data class InvalidText(val detail: String) : ApplicationError

    /** TXT 确定性解析阶段失败。 */
    data class ParseFailed(val detail: String) : ApplicationError

            // ---- P6 新增：AI Analysis 相关错误（Provider / Analysis 异常归一，P6.6） ----
            // 小心映射：ProviderException 具体子类 → 对应领域错误；AnalysisException → 解析/流程错误。
            // 既不泄漏 Provider 底层细节，也不经 String message 判断类型。

            /** AI Analysis 提供者不可用（超时 / 限流 / 网关不可用 / token 上限）。 */
            data class ProviderUnavailable(val detail: String) : ApplicationError

            /** AI 输出无法按结构解析或结构非法，无可用结构化结果。 */
            data class InvalidAnalysisOutput(val detail: String) : ApplicationError

            /** AI Analysis 流程级失败（预检/编排/其它）。 */
            data class AnalysisFailed(val detail: String) : ApplicationError
        }