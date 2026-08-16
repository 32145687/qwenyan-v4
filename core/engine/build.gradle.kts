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
    // 依赖 :core:model（领域模型）；engine 间无外部依赖（见 P5）
    api(project(":core:model"))

    // 领域类型携带的时间类型（core:model 以 implementation 声明，需在此显式补充以编译/运行）。
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}