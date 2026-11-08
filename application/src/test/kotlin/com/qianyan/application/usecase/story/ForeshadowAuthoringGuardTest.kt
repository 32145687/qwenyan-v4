package com.qianyan.application.usecase.story

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-O07-A · 作者伏笔入口的写入边界守卫（源码级）。
 *
 * 伏笔生命周期是**创作元数据**：本 UseCase 只经 StoryStateRepository 写 Foreshadow，
 * 不得依赖 Draft / Commit / Change（=零正文写入的编译期隔离）。
 */
class ForeshadowAuthoringGuardTest {

    private val source = File(
        System.getProperty("user.dir"),
        "src/main/kotlin/com/qianyan/application/usecase/story/ForeshadowAuthoringUseCases.kt",
    ).let {
        if (it.isFile) it else File("application/src/main/kotlin/com/qianyan/application/usecase/story/ForeshadowAuthoringUseCases.kt")
    }

    @Test
    fun `authoring use case never touches draft commit or change`() {
        assertTrue(source.isFile, "源文件必须存在: ${source.absolutePath}")
        val text = source.readText()
        listOf(
            "com.qianyan.model.writing",
            "com.qianyan.model.commit",
            "com.qianyan.model.change",
            "DraftRepository",
            "CommitUseCases",
            "ChangeUseCases",
            "commitApproved",
        ).forEach { forbidden ->
            assertFalse(text.contains(forbidden), "ForeshadowAuthoringUseCases 不得依赖 $forbidden（零正文写入）")
        }
        assertTrue(text.contains("transitionForeshadowState"), "必须复用既有条件 UPDATE（并发守卫）")
        assertTrue(text.contains("ForeshadowLifecycleRules"), "必须复用既有状态机")
    }
}
