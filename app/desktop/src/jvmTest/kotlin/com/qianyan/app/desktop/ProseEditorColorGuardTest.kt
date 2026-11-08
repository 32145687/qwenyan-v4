package com.qianyan.app.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O77 · 编辑器文字颜色守卫（源码级）。
 *
 * 缺陷：`WritePage` 的正文编辑器用 `textStyle = prose`（`qianyanProseStyle` 不含 color）⇒ 深色主题下默认黑字不可读。
 * 守卫：所有 `BasicTextField` 的 textStyle 必须显式带 `color = palette.text`；正文编辑器必须 `prose.copy(color = palette.text)`。
 */
class ProseEditorColorGuardTest {

    private fun mainRoot(): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, "src/jvmMain/kotlin"),
            File(userDir, "app/desktop/src/jvmMain/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("无法定位 desktop jvmMain（user.dir=$userDir）")
    }

    private fun sources(): List<File> =
        mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `prose editor carries an explicit text color`() {
        val page = sources().single { it.name == "WritePage.kt" }.readText()
        assertTrue(
            page.contains("textStyle = prose.copy(color = palette.text)"),
            "正文编辑器的 textStyle 必须显式指定 palette.text（否则深色主题默认黑字）",
        )
        assertFalse(
            Regex("""textStyle\s*=\s*prose\s*[,)]""").containsMatchIn(page),
            "不得出现裸 prose（未带 color）的 textStyle",
        )
    }

    @Test
    fun `every basic text field gives its text an explicit color`() {
        val offenders = mutableListOf<String>()
        sources().forEach { file ->
            val text = file.readText()
            var index = text.indexOf("BasicTextField(")
            while (index >= 0) {
                val next = text.indexOf("BasicTextField(", index + 1)
                val block = text.substring(index, if (next > index) next else minOf(text.length, index + 1500))
                if (!block.contains("color = palette.text")) {
                    val line = text.take(index).count { it == '\n' } + 1
                    offenders += "${file.name}:$line"
                }
                index = next
            }
        }
        assertTrue(offenders.isEmpty(), "以下 BasicTextField 的 textStyle 缺少显式 color: $offenders")
    }
}
