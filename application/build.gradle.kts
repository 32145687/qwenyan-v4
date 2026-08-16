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
    // Application Use Case 层：仅依赖 Domain Model + Storage 接口（P3 约束）。
    // 用 api 暴露领域类型与仓储接口（仓储实现始终在 :storage，不在此模块）。
    api(project(":core:model"))
    api(project(":storage"))

    // 领域类型携带的序列化/时间类型（core:model 以 implementation 声明，需在此显式补充以编译/运行）。
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // 组合根需直接装配 SqlDriver / JdbcSqliteDriver（storage 以 implementation 声明，不对外传递）。
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}