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
    // 端到端测试：MockProvider 跑 Vertical Slice（见 P18）。P0 仅冒烟。
    implementation(project(":agent:orchestration"))
    implementation(project(":agent:agents"))
    // P6：e2e 要跑通 Mock 全链路，绑定具体实现（:provider:impl）。
    implementation(project(":provider:impl"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}