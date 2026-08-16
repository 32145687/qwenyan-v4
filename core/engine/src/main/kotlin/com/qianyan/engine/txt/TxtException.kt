package com.qianyan.engine.txt

/**
 * TXT Engine 确定性失败（引擎层领域错误）。
 *
 * Application 层经 [com.qianyan.application.error.ErrorMapper] 归入 ApplicationError 代数，
 * 不携带平台/技术细节。本引擎不读取文件、不调用 LLM：只接收字节并做确定性处理。
 */
sealed class TxtException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 检测到不受支持的编码（P4 仅支持 UTF-8 家族）；明确报错，不静默猜测解码。 */
    class UnsupportedEncoding(message: String) : TxtException(message)

    /** 解码后正文为空（无可导入内容）。 */
    class EmptyDocument(message: String) : TxtException(message)

    /** 文本不是合法 UTF-8 等非法输入；拒绝损坏文本（strict decode）。 */
    class InvalidText(message: String, cause: Throwable? = null) : TxtException(message, cause)

    /** 解析阶段失败（预期外的确定性错误）。 */
    class ParseFailed(message: String, cause: Throwable? = null) : TxtException(message, cause)
}
