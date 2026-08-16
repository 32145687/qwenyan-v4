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
include(":provider")
include(":storage")
include(":application")
include(":runtime")
include(":app:android")
include(":app:desktop")
include(":test:e2e")