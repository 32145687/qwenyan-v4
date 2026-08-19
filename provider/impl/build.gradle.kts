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
    // 仅依赖契约与领域模型；不依赖具体厂商 SDK。
    api(project(":provider:api"))
    implementation(project(":core:model"))
    // P9：真实 Provider 构造 OpenAI 兼容 JSON（复用项目既有序列化库，不新增第三方）。
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}