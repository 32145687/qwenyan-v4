package com.qianyan.application.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * I1 · Runtime 架构边界守卫（源码级 long-term guard）。
 *
 * 证明（不依赖运行时行为，直接读源码断言）：
 *  - **Application 只见契约**：可引用 `com.qianyan.runtime.contract`，**不得**引用 `com.qianyan.runtime.dsh`
 *    或任何 Adapter / ACP / 供应商类型；
 *  - **Domain / Provider / Storage / Change Layer 与 Runtime 隔离**：不出现 DSH / ACP 概念，
 *    也不依赖任何 Runtime 模块；
 *  - **契约自身 vendor-neutral**：`:runtime:api` 不出现 DSH / ACP / JSON-RPC 词汇；
 *  - **Adapter 才是唯一允许认识 DSH 的地方**：`:runtime:dsh` 必须包含协议实现。
 *
 * 这些断言是 I1 的架构执行机制（与项目既有 16 组"读源码断言"守卫同构）。
 */
class RuntimeArchitectureBoundaryTest {

    /** 供应商 / 协议专有标记：它们只允许出现在 Adapter 模块。 */
    private val vendorTokens = listOf(
        "DshRuntime", "DshJsonRpc", "DshProcess", "DshRuntimeConfig",
        "dshSessionId", "deepseek-harness", "com.qianyan.runtime.dsh",
        "session/request_permission", "session/prompt", "session/new", "session/cancel",
    )

    private val appMain = "src/main/kotlin/com/qianyan/application"
    private val coreModel = "../core/model/src/main/kotlin/com/qianyan/model"
    private val providerApiMain = "../provider/api/src/main/kotlin"
    private val providerImplMain = "../provider/impl/src/main/kotlin"
    private val storageMain = "../storage/src/main/kotlin/com/qianyan/storage"
    private val runtimeApi = "../runtime/api/src/main/kotlin/com/qianyan/runtime/contract"
    private val runtimeDsh = "../runtime/dsh/src/main/kotlin/com/qianyan/runtime/dsh"

    /** Domain / Provider / Storage 侧禁止出现的运行时专有标记（用复合标记，避免误伤普通单词）。 */
    private val forbiddenOutsideAdapter = listOf(
        "DshRuntime", "DshJsonRpc", "DshProcess", "dshSessionId", "deepseek-harness",
        "JsonRpc", "com.qianyan.runtime",
    )

    @Test
    fun `application sees only the runtime contract never the adapter`() {
        val code = sourceOf(appMain)
        assertTrue(
            "com.qianyan.runtime.contract" in code,
            "Application 必须只经契约使用 Runtime（应出现 com.qianyan.runtime.contract）",
        )
        vendorTokens.forEach { token ->
            assertTrue(token !in code, "Application 不得出现 Adapter / 供应商标记 '$token'")
        }
        // 依赖声明同样不得指向 Adapter 模块
        val build = File("build.gradle.kts").readText()
        assertTrue(":runtime:api" in build, "Application 必须声明依赖 :runtime:api")
        assertTrue(":runtime:dsh" !in build, "Application 不得依赖 :runtime:dsh（Adapter 由组合根注入）")
    }

    @Test
    fun `domain has no dsh or acp concept`() {
        val code = sourceOf(coreModel)
        (forbiddenOutsideAdapter + listOf("ACP", "AgentRuntimeGateway")).forEach { token ->
            assertTrue(token !in code, "core:model（Domain）不得出现运行时专有概念 '$token'")
        }
        // 绑定模型必须存在且保持厂商中立命名
        assertTrue("RuntimeSessionRef" in sourceOf("$coreModel/runtime"), "Domain 应提供厂商中立的 RuntimeSessionRef")
    }

    @Test
    fun `provider storage and change layer stay isolated from runtime`() {
        listOf(
            providerApiMain, providerImplMain, storageMain,
            "$appMain/usecase/change", "$appMain/usecase/commit",
        ).forEach { dir ->
            val code = sourceOf(dir)
            forbiddenOutsideAdapter.forEach { token ->
                assertTrue(token !in code, "$dir 不得依赖 / 出现 Runtime 与供应商概念 '$token'")
            }
        }
    }

    @Test
    fun `runtime contract is vendor neutral while adapter owns the protocol`() {
        val contract = sourceOf(runtimeApi)
        vendorTokens.forEach { token ->
            assertTrue(token !in contract, "Runtime 契约必须 vendor-neutral，不得出现 '$token'")
        }
        listOf("RuntimeError", "RuntimeUpdateKind", "RuntimeStopReason", "RuntimePermissionResponder").forEach { token ->
            assertTrue(token in contract, "契约应提供类型化抽象：'$token'")
        }

        val adapter = sourceOf(runtimeDsh)
        listOf("DshRuntimeClient", "session/prompt", "session/request_permission").forEach { token ->
            assertTrue(token in adapter, "Adapter 才是协议实现所在处（应含 '$token'）")
        }
    }

    /** 读源码（剔除注释行，避免"注释里提到 DSH"造成误判）。 */
    private fun sourceOf(relativeDir: String): String {
        val dir = File(relativeDir)
        assertTrue(dir.exists(), "找不到源码目录：${dir.absolutePath}")
        val files = if (dir.isDirectory) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("${File.separator}build${File.separator}") }
                .toList()
        } else {
            listOf(dir)
        }
        return files.joinToString("\n") { file ->
            file.readText().lines()
                .filterNot { line ->
                    val t = line.trimStart()
                    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
                }
                .joinToString("\n")
        }
    }
}