import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// P20-PC1 · PC Foundation：`:app:desktop` 从 JVM stub 迁移为 Compose Desktop 应用。
//
// 迁移来源：分支 `ui/desktop`（P14 时期原型）——以当前 `feature/p14-f` 为基准逐项迁移、适配、收口，
// 未 merge / rebase / cherry-pick 该分支，也未改动其依赖的任何共享核心模块。
//
// 形态：Kotlin Multiplatform（仅 jvm target）+ Compose Multiplatform（Desktop）。
// 业务能力全部来自 `:application`（ApplicationContainer）；本模块只提供 Desktop Adapter 与 UI。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Qianyan 核心（只读复用；PC 不重写任何业务能力）
                implementation(project(":core:model"))
                implementation(project(":core:engine"))
                implementation(project(":application"))
                implementation(project(":provider:api"))
                implementation(project(":provider:impl"))
                // Compose Desktop
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.foundation)
                implementation(compose.runtime)
                // Desktop Adapter 运行时
                // sqldelight-runtime：DesktopGraph 直接持有/关闭 SqlDriver（:storage 以 implementation 声明该类型，不对外传递）。
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqlite.jdbc)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.datetime)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                implementation(libs.junit.jupiter)
                implementation(libs.sqlite.jdbc)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.qianyan.app.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            windows {
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}

// JUnit 5（kotlin.test 经 junit5 映射）：KMP 下测试任务为 jvmTest，必须显式启用平台，否则用例会被静默跳过。
tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}