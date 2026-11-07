package com.qianyan.provider

import kotlinx.serialization.Serializable

/**
 * 余额里的单条币种明细（provider:api 契约）。
 *
 * 只承载厂商返回的**字符串金额**，不换算、不估算、不做价格表推演：
 * UI 按原币种原样展示，金额格式与精度由厂商决定。
 */
@Serializable
data class BalanceLine(
    val currency: String,
    val total: String,
    val cash: String? = null,
    val granted: String? = null,
)

/** 某 Provider 的账户余额快照（available = 厂商声明的账户可用状态）。 */
@Serializable
data class ProviderBalance(
    val available: Boolean,
    val lines: List<BalanceLine>,
)

/**
 * 余额查询接缝（只读；真实网关实现）。
 *
 * 失败抛 [ProviderException] 具体子类，不得把原始 HTTP 异常或凭证泄漏到调用方。
 */
interface BalanceQuery {

    fun queryBalance(): ProviderBalance
}

/**
 * 用量记账接缝（旁路）：一次成功补全后把 [Usage] 交给宿主记入本地账本。
 *
 * 实现方必须保证自身失败不影响聊天链路（记账是可观测旁路，不是业务前置）。
 */
fun interface UsageSink {

    fun record(provider: ProviderType, modelId: String?, usage: Usage)
}
