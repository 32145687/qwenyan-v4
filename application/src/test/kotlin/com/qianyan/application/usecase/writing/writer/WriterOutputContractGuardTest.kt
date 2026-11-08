package com.qianyan.application.usecase.writing.writer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O75-F · Writer 形态契约守卫（源码级）。
 *
 * T-O75-D 实测：3/3 真实样本的运行时 `answer` 已是完整正文（660–1011 字），
 * 失败点在 DraftParser 再次要求 `{"content": …}`。本守卫钉住两件事：
 *  1) WriterAgent 必须下发"只输出正文"的形态契约；
 *  2) WriterAgent **不得**把结构化 `{"content": …}` 当成输出要求（否则长正文转义必然抖动）。
 */
class WriterOutputContractGuardTest {

    private val source = File(
        System.getProperty("user.dir"),
        "src/main/kotlin/com/qianyan/application/usecase/writing/WriterAgent.kt",
    ).let { if (it.isFile) it else File("application/src/main/kotlin/com/qianyan/application/usecase/writing/WriterAgent.kt") }

    @Test
    fun `render input carries the free-form prose contract`() {
        assertTrue(source.isFile, "WriterAgent.kt 必须存在: ${source.absolutePath}")
        val text = source.readText()

        assertTrue(text.contains("【输出契约】"), "必须包含输出契约段")
        assertTrue(text.contains("只输出本章正文本身"), "必须要求只输出正文本身")
        assertTrue(text.contains("不要输出 JSON"), "必须禁止 JSON 包装")
        assertTrue(text.contains("Markdown 代码块"), "必须禁止 Markdown 围栏")
        assertTrue(text.contains("我无法…"), "必须禁止拒答式说明文字")
        assertTrue(text.contains("章节编号标题"), "必须禁止自造章节标题")
        assertTrue(text.contains("中文引号「」"), "必须约定引用使用中文引号")

        assertFalse(text.contains("{\"content\":"), "WriterAgent 不得要求 {\"content\":…} 结构化输出")

        val contract = text.indexOf("【输出契约】")
        val renderInputEnd = text.indexOf("}.trimEnd()")
        assertTrue(
            contract in 1 until renderInputEnd,
            "契约段必须写在 renderInput 的字符串构建内（当前 index=$contract，trimEnd=$renderInputEnd）",
        )
    }
}
