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
    // I1 Runtime Integration：契约**刻意零依赖**（不依赖 core:model / provider:api / DSH）。
    // 只描述"外部 Agent Runtime 会话"的最小抽象，保证 Application 依赖它不会传递出任何实现或领域概念。
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}