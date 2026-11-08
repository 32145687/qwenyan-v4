package com.qianyan.application.usecase.writing.planning

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-O73-F · Planner 输出契约守卫（源码级）。
 *
 * T-O73-D 实测：Planner 的 prompt 从未下发 `ChapterPlan` 的字段契约，模型把 `answer` 当小说正文，
 * 7/7 样本在 `ChapterPlanParser` 处 offset 0 失败。本测试把「契约段必须存在且内容不退化」钉在源码上。
 */
class PlannerOutputContractGuardTest {

    private val source = File(
        System.getProperty("user.dir"),
        "src/main/kotlin/com/qianyan/application/usecase/writing/planning/PlannerAgent.kt",
    ).let { if (it.isFile) it else File("application/src/main/kotlin/com/qianyan/application/usecase/writing/planning/PlannerAgent.kt") }

    @Test
    fun `render input carries the explicit json output contract`() {
        assertTrue(source.isFile, "PlannerAgent.kt 必须存在: ${source.absolutePath}")
        val text = source.readText()

        assertTrue(text.contains("【输出契约（必须遵守）】"), "必须包含输出契约段标题")
        assertTrue(text.contains("禁止返回 tool 调用"), "必须禁止工具调用（无工具运行）")
        assertTrue(text.contains("禁止要求补充信息"), "必须禁止反问用户补信息")
        assertTrue(text.contains("直接输出规划 JSON 对象本身"), "必须要求顶层直接输出规划 JSON")
        assertTrue(text.contains("禁止再用"), "必须禁止再用 {\"answer\": …} 包裹（T-O73-F 实测失败点）")
        assertTrue(text.contains("answer 必须是字符串"), "若走 answer 协议，必须声明 answer 为字符串而非嵌套对象")
        assertTrue(text.contains("不要输出小说正文"), "必须显式禁止输出小说正文（T-O73-D 实测失败点）")
        assertTrue(text.contains("引号必须转义"), "必须要求字符串内引号转义（MiMo JSON 卫生）")
        assertTrue(
            text.contains("字符串值内禁止出现未转义的英文双引号"),
            "必须显式禁止值内裸英文双引号（T-O73-F3 实测失败点）",
        )
        assertTrue(text.contains("\\\"chapterGoal\\\""), "契约必须至少给出 chapterGoal 字段名（JSON 键）")
        assertTrue(text.contains("\\\"expectedEvents\\\""), "契约必须给出 expectedEvents 字段名（JSON 键）")
        assertTrue(text.contains("chapterGoal 必填非空"), "必须声明 chapterGoal 为唯一必填字段")

        val contract = text.indexOf("【输出契约（必须遵守）】")
        val renderInputEnd = text.indexOf("}.trimEnd()")
        val runtimeStart = text.indexOf("private val bareRuntime")
        assertTrue(
            contract in 1 until renderInputEnd,
            "契约段必须写在 renderInput 的字符串构建内（当前 index=$contract，trimEnd=$renderInputEnd）",
        )
        assertTrue(contract < runtimeStart, "契约段必须位于 renderInput 之内（早于 bareRuntime）")
    }
}
