package com.qianyan.app.desktop

import com.qianyan.app.desktop.di.CustomModel
import com.qianyan.app.desktop.di.CustomModelCatalog
import com.qianyan.provider.ProviderType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M-1 · 自定义模型清单（models.properties）存取与过滤。 */
class CustomModelCatalogTest {

    private fun tempFile(): Path =
        Files.createTempDirectory("qianyan-model-catalog").resolve("models.properties")

    @Test
    fun `defaults contain real providers and exclude mock`() {
        val catalog = CustomModelCatalog(tempFile())
        val models = catalog.read()

        assertEquals(
            listOf(
                "deepseek-v4-flash",
                "deepseek-v4-pro",
                "mimo-v2.6-pro",
                "mimo-v2.6-flash",
                "mimo-v2.6-pro-ultraspeed",
            ),
            models.map { it.modelId },
        )
        assertTrue(models.none { it.modelId == "mock-v1" }, "默认清单不得包含 mock-v1")
        assertTrue(catalog.candidates(ProviderType.MOCK).isEmpty(), "离线示意没有模型候选")
        assertEquals(listOf("deepseek-v4-flash", "deepseek-v4-pro"), catalog.candidates(ProviderType.DEEPSEEK).map { it.modelId })
        assertEquals(3, catalog.candidates(ProviderType.MIMO).size)
    }

    @Test
    fun `add remove toggle persist and re-read consistently`() {
        val file = tempFile()
        val catalog = CustomModelCatalog(file)
        val added = CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-pro-x", "DeepSeek Pro X")

        assertTrue(catalog.add(added))
        assertEquals("DeepSeek Pro X", catalog.read().first { it.modelId == added.modelId }.label)

        assertTrue(catalog.setEnabled(ProviderType.DEEPSEEK, added.modelId, false))
        assertFalse(catalog.read().first { it.modelId == added.modelId }.enabled)
        assertTrue(catalog.candidates(ProviderType.DEEPSEEK).none { it.modelId == added.modelId })

        val text = file.toFile().readText()
        assertTrue("model.count" in text, "清单必须真实落盘")
        assertEquals(catalog.read(), CustomModelCatalog(file).read(), "重读必须一致")

        assertTrue(catalog.remove(ProviderType.DEEPSEEK, added.modelId))
        assertTrue(catalog.read().none { it.modelId == added.modelId })
    }

    @Test
    fun `mock entries are filtered on write and read`() {
        val file = tempFile()
        val catalog = CustomModelCatalog(file)

        assertTrue(
            catalog.write(
                listOf(
                    CustomModel(ProviderType.MOCK, "mock-v1", "Mock"),
                    CustomModel(ProviderType.DEEPSEEK, "deepseek-v4-pro", "Pro"),
                ),
            ),
        )
        assertEquals(listOf("deepseek-v4-pro"), catalog.read().map { it.modelId })
        assertEquals(listOf("deepseek-v4-pro"), catalog.candidates(ProviderType.DEEPSEEK).map { it.modelId })
    }

    @Test
    fun `unknown provider or empty file does not crash`() {
        val empty = tempFile()
        Files.createDirectories(empty.toAbsolutePath().parent)
        empty.toFile().writeText("")
        assertTrue(CustomModelCatalog(empty).read().isNotEmpty(), "空文件回落到默认条目")

        val unknown = tempFile()
        unknown.toFile().writeText(
            """
            model.count=3
            model.1.provider=NOPE
            model.1.id=x
            model.2.provider=DEEPSEEK
            model.2.id=deepseek-v4-pro
            model.3.provider=MIMO
            """.trimIndent(),
        )
        val models = CustomModelCatalog(unknown).read()
        assertEquals(listOf("deepseek-v4-pro"), models.map { it.modelId }, "未知 Provider / 缺 ID 的条目必须跳过")
    }
}
