pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "qianyan"

include(":core:model")
include(":core:engine")
include(":agent:tool")
include(":agent:runtime")
include(":agent:agents")
include(":agent:orchestration")
// P6 演进：LLM 契约与实现边界拆分（v4.2 [RECOMMENDED]）。
// - provider:api：纯 LLM 契约（接口/请求/响应/异常/config），只依赖 core:model。
// - provider:impl：Provider 实现（Mock），只依赖 provider:api。
// 调用方（agent / application / e2e）只依赖 provider:api，禁止依赖具体实现。
include(":provider:api")
include(":provider:impl")
// 显式指定 projectDir：api 在 provider/api/，impl 在 provider/impl/。
// （注意：不可把 api 指向根 provider/，否则与 Gradle 自动创建的中间父项目 :provider 共用目录，导致资源/打包任务冲突。）
project(":provider:api").projectDir = file("provider/api")
project(":provider:impl").projectDir = file("provider/impl")
include(":storage")
include(":application")
include(":runtime")
include(":app:android")
include(":app:desktop")
include(":test:e2e")