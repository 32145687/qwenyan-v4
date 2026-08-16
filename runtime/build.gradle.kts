plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jvmTarget.get().toInt()) }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    // 平台 Runtime 抽象：文件系统 / 网络 / DB 实现（见 P16/P17）
    api(project(":core:model"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}