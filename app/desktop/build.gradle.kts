plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(libs.versions.jvmTarget.get().toInt()) }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

application {
    mainClass.set("com.qianyan.app.desktop.MainKt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":runtime"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}