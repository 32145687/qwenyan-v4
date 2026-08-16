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
    implementation(project(":provider"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}