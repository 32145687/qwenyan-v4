package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-14 装配与页面守卫（源码级）：记账必须经装配口的装饰器，余额查询不得把 MOCK 送进去。
 */
class UsageWiringGuardTest {

    private val graph = File("src/jvmMain/kotlin/com/qianyan/app/desktop/di/DesktopGraph.kt").readText()
    private val page = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/UsageSettings.kt").readText()
    private val settings = File("src/jvmMain/kotlin/com/qianyan/app/desktop/ui/settings/SettingsWindow.kt").readText()

    @Test
    fun `desktop graph records usage through the assembler seam`() {
        assertTrue("UsageRecordingAssembler(" in graph, "装配口必须包一层记账装饰器")
        assertTrue(
            "UsageSink { provider, modelId, usage -> usageLedger.record(provider, modelId, usage) }" in graph,
            "sink 必须落到本地账本",
        )
        assertTrue(
            "fromDriver(handle.driver, recording, configuration)" in graph,
            "容器必须用包装后的 assembler 装配（单一装配口）",
        )
        assertTrue("val usageLedger = UsageLedger(appDir.resolve(\"usage.json\"))" in graph, "账本落 appDir")
    }

    @Test
    fun `balance section never sends mock or unconfigured providers to queryBalance`() {
        assertTrue(
            "if (type == ProviderType.MOCK || !isConfigured(type)) return null" in graph,
            "查询接缝必须在 MOCK / 未配置时返回 null",
        )
        assertTrue(
            "if (type != ProviderType.MOCK && configured) refresh(type, force = false)" in page,
            "进页自动查询必须跳过 MOCK 与未配置",
        )
        assertTrue("state.graph.balanceQuery(type) ?: return" in page, "UI 只经接缝取查询器")
        assertTrue("离线示意不查询余额" in page, "MOCK 行必须明说")
        assertTrue("未配置 API Key" in page, "未配置行必须引导到 Key 录入")
        assertTrue(
            "state.openSettingsSection(SETTINGS_PROVIDER_SECTION, type)" in page,
            "未配置引导必须定位到该厂商的 AI Provider 分区",
        )
        assertFalse("queryBalance()" in graph, "装配层不得直接调用余额查询")
    }

    @Test
    fun `usage page is reachable from settings navigation`() {
        assertTrue("SettingsItem(SETTINGS_USAGE_SECTION, \"用量与余额\")" in settings, "AI 组必须有「用量与余额」")
        assertTrue("SETTINGS_USAGE_SECTION -> UsageSettings(state)" in settings, "分区必须渲染用量页")
        assertTrue("还没有记录：真实模型调用完成后才会计数" in page, "空态文案必须存在")
        assertTrue("离线示意·不计费" in page, "MOCK 用量行必须标注不计费")
    }
}
