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
    // Application Use Case 层：依赖 Domain Model + Storage 接口 + TXT 引擎（P3 约束 + P5 演进）。
    //
    // P5 架构演进：为把 P4 的确定性 TXT Pipeline 接入 Use Case，新增 application → :core:engine。
    // 依赖方向保持冻结 DAG 单向性：application → core:engine → core:model；application → storage → core:model。
    // 禁止出现 core:engine → application / storage → application / model → application。
    // core:engine 只提供确定性解析（TxtPipeline），不访问 Repository / Application / LLM；持久化仍走 storage 接口。
    //
    // 用 api 暴露领域类型与仓储接口（仓储实现始终在 :storage，不在此模块）。
    api(project(":core:model"))
    api(project(":storage"))

    // P5 新增：TXT Use Case 经 TxtPipeline 做确定性解析（引擎不触库、不调 LLM）。
    implementation(project(":core:engine"))

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