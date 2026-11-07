package com.qianyan.provider.impl

import com.qianyan.provider.BalanceLine
import com.qianyan.provider.ProviderBalance
import com.qianyan.provider.ProviderException
import com.qianyan.provider.impl.transport.HttpResponse
import java.io.IOException
import java.net.http.HttpTimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 余额查询的公共实现细节（provider:impl 内部）：传输异常映射、HTTP 状态码映射、两厂商的字段解析。
 *
 * 错误文案只带厂商标签、状态码与截断后的响应片段（≤200 字符），不含 API Key。
 */
internal object BalanceQuerySupport {

    private val Json = Json { ignoreUnknownKeys = true }

    private const val MAX_BODY_SNIPPET = 200

    /** 执行一次 GET，把传输层异常统一翻译为 [ProviderException]。 */
    fun <T> call(label: String, block: () -> T): T = try {
        block()
    } catch (e: HttpTimeoutException) {
        throw ProviderException.Timeout("$label 余额查询超时", e)
    } catch (e: IOException) {
        throw ProviderException.ProviderUnavailable("$label 余额查询传输错误: ${e.message}", e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ProviderException.ProviderUnavailable("$label 余额查询被中断", e)
    }

    /** 非 2xx → 类型化异常；2xx 直接返回。 */
    fun ensureSuccess(label: String, response: HttpResponse) {
        if (response.statusCode in 200..299) return
        val detail = "$label 余额查询 HTTP ${response.statusCode}: ${truncate(response.body)}"
        throw when (response.statusCode) {
            429 -> ProviderException.RateLimit(detail)
            401, 403 -> ProviderException.ProviderUnavailable("$detail（鉴权失败）")
            else -> ProviderException.ProviderUnavailable(detail)
        }
    }

    /** DeepSeek `/user/balance`：is_available + balance_infos[].{currency,total_balance,topped_up_balance,granted_balance}。 */
    fun parseDeepSeek(body: String): ProviderBalance = parse(
        label = "DeepSeek",
        body = body,
        totalField = "total_balance",
        cashField = "topped_up_balance",
        grantedField = "granted_balance",
    )

    /** MiMo `/v1/platform/balance`：is_available + balance_infos[].{currency,total_available,cash_balance,gift_balance}。 */
    fun parseMiMo(body: String): ProviderBalance = parse(
        label = "MiMo",
        body = body,
        totalField = "total_available",
        cashField = "cash_balance",
        grantedField = "gift_balance",
    )

    private fun parse(
        label: String,
        body: String,
        totalField: String,
        cashField: String,
        grantedField: String,
    ): ProviderBalance {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (e: Exception) {
            throw ProviderException.InvalidResponse("$label 余额响应不是合法 JSON", e)
        } ?: throw ProviderException.InvalidResponse("$label 余额响应不是 JSON 对象")

        val available = (root["is_available"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: throw ProviderException.InvalidResponse("$label 余额响应缺少 is_available")
        val infos = root["balance_infos"] as? JsonArray
            ?: throw ProviderException.InvalidResponse("$label 余额响应缺少 balance_infos")

        val lines = infos.map { element ->
            val line = element as? JsonObject
                ?: throw ProviderException.InvalidResponse("$label 余额明细条目不是 JSON 对象")
            BalanceLine(
                currency = requireString(label, line, "currency"),
                total = requireString(label, line, totalField),
                cash = optionalString(line, cashField),
                granted = optionalString(line, grantedField),
            )
        }
        return ProviderBalance(available = available, lines = lines)
    }

    private fun requireString(label: String, line: JsonObject, field: String): String =
        (line[field] as? JsonPrimitive)?.contentOrNull
            ?: throw ProviderException.InvalidResponse("$label 余额明细缺少 $field")

    private fun optionalString(line: JsonObject, field: String): String? =
        (line[field] as? JsonPrimitive)?.contentOrNull

    private fun truncate(body: String): String =
        if (body.length <= MAX_BODY_SNIPPET) body else body.take(MAX_BODY_SNIPPET) + "..."
}
