package com.qianyan.app.desktop.ui.settings

import com.qianyan.app.desktop.di.UsageBucket
import com.qianyan.provider.BalanceLine
import com.qianyan.provider.ProviderType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 用量表的一行：按 厂商 × 模型 聚合（纯数据，可单测）。 */
internal data class UsageRow(
    val provider: ProviderType,
    val modelId: String,
    val calls: Int,
    val prompt: Long,
    val completion: Long,
    val total: Long,
)

/**
 * 把逐日桶聚合为「厂商 × 模型」行，按合计 token 倒序（同值按厂商/模型名稳定排序）。
 *
 * 只做求和与排序，不做金额换算、不补样本。
 */
internal fun aggregateUsage(buckets: List<UsageBucket>): List<UsageRow> =
    buckets.groupBy { it.provider to it.modelId }
        .map { (key, rows) ->
            UsageRow(
                provider = key.first,
                modelId = key.second,
                calls = rows.sumOf { it.calls },
                prompt = rows.sumOf { it.prompt },
                completion = rows.sumOf { it.completion },
                total = rows.sumOf { it.total },
            )
        }
        .sortedWith(
            compareByDescending<UsageRow> { it.total }
                .thenBy { it.provider.name }
                .thenBy { it.modelId },
        )

/** 千分位格式化（不依赖 Locale，避免不同机器显示不一致）。 */
internal fun formatTokens(value: Long): String {
    val digits = value.coerceAtLeast(0).toString()
    val out = StringBuilder(digits.length + digits.length / 3)
    digits.forEachIndexed { index, c ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
        out.append(c)
    }
    return out.toString()
}

/** 余额刷新时间（本地时区 HH:mm:ss）。 */
internal fun formatRefreshTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("HH:mm:ss")
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** 单条币种明细的展示串：`CNY 110.00`（可选追加 现金 / 赠送）。 */
internal fun formatBalanceLine(line: BalanceLine): String = buildString {
    append(line.currency)
    append(' ')
    append(line.total)
    line.cash?.let { append(" · 现金 ").append(it) }
    line.granted?.let { append(" · 赠送 ").append(it) }
}

/** 余额/用量页的类型化错误文案（不含响应体、不含 Key、不做金额估算）。 */
internal fun balanceErrorText(error: Throwable): String = when (error) {
    is com.qianyan.provider.ProviderException.Timeout -> "查询超时，请稍后重试"
    is com.qianyan.provider.ProviderException.RateLimit -> "请求被限流，请稍后重试"
    is com.qianyan.provider.ProviderException.CredentialMissing -> "未配置 API Key"
    is com.qianyan.provider.ProviderException.ProviderUnavailable ->
        if (error.message?.contains("鉴权失败") == true) "鉴权失败，请检查 API Key" else "服务不可用，请稍后重试"

    is com.qianyan.provider.ProviderException.InvalidResponse -> "响应格式不符合预期"
    else -> "查询失败（${error.javaClass.simpleName}）"
}
