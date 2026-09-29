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
    api(project(":agent:runtime"))
    implementation(project(":agent:tool"))
    // I7：Skill Registry 的契约（Skill / SkillId / SkillMatch）在 :core:model；
    // 复用既有 Capability / IntentType / ToolName / AgentActionKind / ContextSourceKind（与 :agent:tool 同惯例）。
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}