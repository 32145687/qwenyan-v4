package com.qianyan.app.desktop

import java.io.File
import org.junit.jupiter.api.Disabled
import kotlin.test.Test

/**
 * 临时工具：桌面真实数据目录的状态核对 / 演示数据播种（幂等——已有作品则只报告）。
 * 结果写入 D:/qwenyan-v4/seed-result.txt（Gradle 会吞掉测试 stdout，故落盘）。
 */
@Disabled("dev 工具：需要为桌面应用播种演示数据时，手动移除本注解再运行 :app:desktop:jvmTest")
class SeedDemoDataTemp {

    private val log = File("D:/qwenyan-v4/seed-result.txt").apply { writeText("") }
    private fun w(s: String) = log.appendText(s + "\n")

    @Test
    fun seed() {
        val graph = com.qianyan.app.desktop.di.DesktopGraph.create()
        val dir = graph.appDir
        val dbPath = dir.resolve("qianyan.db")
        w("appDataDir = $dir")
        w("db exists  = ${dbPath.toFile().exists()}  (${dbPath.toFile().length()} bytes)")

        val c = graph.container

        val existing = c.novels.listOriginals()
        if (existing.isNotEmpty()) {
            w("SKIP: 已有 ${existing.size} 部作品 → ${existing.joinToString { it.title }}")
            existing.forEach { n ->
                val chs = c.chapters.listByNovel(n.novelId)
                w("  《${n.title}》 genre=${n.genre} chapters=${chs.size}")
                chs.forEach { ch ->
                    val p = c.workflowFacade.getChapterProgress(ch.chapterId)
                    w("    - ${ch.title} phase=${p.phase} waiting=${p.waitingForUser}")
                }
            }
            return
        }

        val id = c.novels.createOriginal(
            title = "长夜行舟",
            genre = listOf("东方幻想", "成长", "慢热"),
            synopsis = "一个少年带着师父的遗言走出雪山，在北境的变局中查明真相，也亲手改写了自己的身份。",
        )
        val ch1 = c.chapters.createNextChapter("第 1 章 · 雪夜遗言", id)
        val ch2 = c.chapters.createNextChapter("第 2 章 · 出关", id)

        val facade = c.workflowFacade
        var p = facade.startChapter(id, null, ch1.chapterId)
        var guard = 0
        while (!p.waitingForUser && p.phase.name != "COMPLETED" && p.phase.name != "FAILED" && guard++ < 15) {
            p = facade.advance(ch1.chapterId)
        }
        w("SEEDED: 《长夜行舟》 novelId=${id.value}")
        w("chapters = ${c.chapters.listByNovel(id).size}")
        w("ch1 = ${ch1.title}  phase=${p.phase}  waiting=${p.waitingForUser}  draftId=${p.draftId?.value}")
        w("ch2 = ${ch2.title}")
    }
}
