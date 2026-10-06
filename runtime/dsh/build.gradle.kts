plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jvmTarget.get().toInt()) }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    // I1：Adapter 实现 Qianyan Runtime Contract（api 暴露，使组合根可直接引用契约类型）。
    api(project(":runtime:api"))

    // ACP 走 JSON-RPC over stdio，需要 JSON 序列化（复用项目既有 kotlinx.serialization.json）。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // 真实 DSH 验证：把 Gradle -D 参数转发给测试 JVM（仅这三个键；不影响普通测试）。
    listOf("qianyan.dsh.executable", "qianyan.dsh.args", "qianyan.dsh.workdir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}