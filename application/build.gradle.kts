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
    // P7.5 演进：public API（TxtUseCases.importTxtAsOriginal）签名暴露引擎类型 TxtSource，
    // 故用 api 再导出，使 Android 上层能通过 :application 依赖消费（app → application 单向依赖不变）。
    api(project(":core:engine"))

    // P6 新增：Analysis Use Case 只依赖 LLM 契约（:provider:api），不依赖具体 Provider 实现。
    // Provider(DeepSeek/MiMo/Mock) 实现始终在 :provider:impl；测试期通过 :provider:impl 注入 Mock。
    implementation(project(":provider:api"))
    testImplementation(project(":provider:impl"))

    // P11.2 新增：Planning Use Case 复用 P10 的 AgentRuntime（经其调用 :provider:api 的 LLMGateway）。
    // 依赖方向符合架构硬约束：application → :agent:runtime / :agent:tool → :provider:api；不依赖具体 Provider/Storage.
    implementation(project(":agent:runtime"))
    // PlannerAgent 直接装配 ToolExecutor/ToolRegistry 并处理 ToolException，需显式依赖 :agent:tool
    // （:agent:runtime 以 implementation 暴露 :agent:tool，不对外传递，故在此补充）。
    implementation(project(":agent:tool"))

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