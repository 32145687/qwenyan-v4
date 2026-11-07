package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.write.messageFor
import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** L-2：新增三类 Provider 错误必须各有独立用户文案，不得继续共用"AI 服务暂不可用"。 */
class ProviderErrorMessageTest {

    @Test
    fun `new provider errors have distinct user messages`() {
        val timeout = messageFor(ApplicationException(ApplicationError.ProviderTimeout("t")))
        val rate = messageFor(ApplicationException(ApplicationError.ProviderRateLimited("r")))
        val token = messageFor(ApplicationException(ApplicationError.ProviderTokenLimit("k")))
        val unavailable = messageFor(ApplicationException(ApplicationError.ProviderUnavailable("u")))

        assertEquals(4, setOf(timeout, rate, token, unavailable).size, "四类文案不得相同")
        assertTrue("超时" in timeout, timeout)
        assertTrue("频繁" in rate, rate)
        assertTrue("过长" in token, token)
    }
}
