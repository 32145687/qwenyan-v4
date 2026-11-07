package com.qianyan.application.error

import com.qianyan.provider.ProviderException
import kotlin.test.Test
import kotlin.test.assertIs

/** L-2：四类 Provider 异常必须映射到四个不同的 ApplicationError 类型，不得再压成一句。 */
class ErrorMapperProviderSplitTest {

    @Test
    fun `provider exceptions map to four distinct application errors`() {
        assertIs<ApplicationError.ProviderTimeout>(
            ErrorMapper.map(ProviderException.Timeout("t")).error,
        )
        assertIs<ApplicationError.ProviderRateLimited>(
            ErrorMapper.map(ProviderException.RateLimit("r")).error,
        )
        assertIs<ApplicationError.ProviderTokenLimit>(
            ErrorMapper.map(ProviderException.TokenLimit("k")).error,
        )
        assertIs<ApplicationError.ProviderUnavailable>(
            ErrorMapper.map(ProviderException.ProviderUnavailable("u")).error,
        )
    }
}
