package com.qianyan.app.desktop

import com.qianyan.app.desktop.ui.shortTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * O-111 · KU 面板时间按本地时区显示（修复前 `Instant.toString()` 显示 UTC）。
 *
 * 断言用固定时区（Asia/Shanghai = UTC+8 / UTC = +0），不依赖宿主机时区。
 */
class ShortTimeLocalTimeTest {

    @Test
    fun `displays local time not utc`() {
        assertEquals(
            "2026-11-14 19:46",
            shortTime(Instant.parse("2026-11-14T11:46:00Z"), TimeZone.of("Asia/Shanghai")),
            "UTC+8 下 11:46Z 必须显示为 19:46（修复前显示 11:46）",
        )
    }

    @Test
    fun `utc zone shows utc clock`() {
        assertEquals(
            "2026-11-14 11:46",
            shortTime(Instant.parse("2026-11-14T11:46:00Z"), TimeZone.UTC),
        )
    }

    @Test
    fun `date rolls over when crossing midnight in local zone`() {
        assertEquals(
            "2026-11-15 00:30",
            shortTime(Instant.parse("2026-11-14T16:30:00Z"), TimeZone.of("Asia/Shanghai")),
            "UTC 16:30 在 UTC+8 是次日 00:30（跨日边界）",
        )
    }

    @Test
    fun `format keeps yyyy-mm-dd hh-mm shape`() {
        val out = shortTime(Instant.parse("2026-11-14T11:46:00Z"), TimeZone.of("Asia/Shanghai"))
        assertTrue(out.length == 16 && out[10] == ' ', out)
        assertTrue(!out.contains('T'), "不得残留 ISO 的 T 分隔符")
    }
}
