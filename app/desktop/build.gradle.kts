import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
