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
    api(project(":core:model"))
    implementation(project(":provider"))
    implementation(project(":agent:tool"))
    implementation(project(":core:engine"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}