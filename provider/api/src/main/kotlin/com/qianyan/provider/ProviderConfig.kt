package com.qianyan.provider

/** Provider 调用超时配置（P6 provider:api 契约）。 */
data class TimeoutConfig(
    val connectMillis: Long = 10_000,
    val readMillis: Long = 60_000,
)

/** Provider 重试配置。 */
data class RetryConfig(
    val maxRetries: Int = 2,
    val backoffMillis: Long = 500,
)