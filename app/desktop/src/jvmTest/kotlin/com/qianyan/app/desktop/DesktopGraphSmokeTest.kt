package com.qianyan.app.desktop

import com.qianyan.provider.InMemoryProviderCredentialStore
import com.qianyan.provider.ProviderConfiguration
import com.qianyan.provider.ProviderType
import com.qianyan.provider.impl.DefaultProviderAssembler
import com.qianyan.application.di.ApplicationContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 桌面装配冒烟测试（JVM，无 GUI）：
 * 验证 ApplicationContainer + DefaultProviderAssembler + JVM 内存库链路在桌面模块中可完整工作。
 * 持久化文件路径装配由 GUI 启动路径覆盖（DesktopGraph.create()）。
 */
class DesktopGraphSmokeTest {

    @Test
    fun `container wires on jvm with mock provider`() {
        val container = ApplicationContainer.open(
            providerAssembler = DefaultProviderAssembler(InMemoryProviderCredentialStore()),
            configuration = ProviderConfiguration(ProviderType.MOCK),
        )
        assertTrue(container.novels.listOriginals().isEmpty())
        val id = container.novels.createOriginal(title = "测试作品", genre = listOf("东方幻想"), synopsis = "冒烟测试")
        val novels = container.novels.listOriginals()
        assertEquals(1, novels.size)
        assertEquals("测试作品", novels.first().title)
        val chapter = container.chapters.createNextChapter("第一章 · 开始", id)
        assertNotNull(container.chapters.findById(chapter.chapterId))
    }
}
