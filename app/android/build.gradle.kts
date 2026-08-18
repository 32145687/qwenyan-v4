plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qianyan.app.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.qianyan.app.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    // P0：仅占位依赖领域模型，无任何 UI 依赖（UI 见 P11/P15）
    implementation(project(":core:model"))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)

    // P7.2：Compose 基础设施（仅构建/编译能力，不含任何业务 UI）
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // P7.3：Android DI 装配（ApplicationContainer / DatabaseInitializer / MockLLMGateway / AndroidSqliteDriver）。
    // 只依赖上层组装所需模块，不直接触碰 Repository 实现与 SQL。
    implementation(project(":application"))
    implementation(project(":storage"))
    implementation(project(":provider:api"))
    implementation(project(":provider:impl"))
    implementation(libs.sqldelight.android.driver)
    // 测试用 JVM 驱动验证驱动无关装配链（生产路径使用 AndroidSqliteDriver）。
    testImplementation(libs.sqldelight.sqlite.driver)

    // P7.4：ViewModel 协程作用域 + 主线程调度器（viewModelScope / Dispatchers.Main）。
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // P7.5：Activity 生命周期协程作用域（lifecycleScope），SAF 文件读取在 IO 线程执行。
    implementation(libs.lifecycle.runtime.ktx)
    // 领域模型 createdAt 为 kotlinx.datetime.Instant（core:model 以 implementation 声明，需显式引入以格式化日期）。
    implementation(libs.kotlinx.datetime)

    // P7.4：ViewModel 单元测试（Dispatchers.setMain / runTest / advanceUntilIdle）。
    testImplementation(libs.kotlinx.coroutines.test)
    // 测试伪造 NovelRepository 实现 resolveOverride(JsonElement?)（storage 以 implementation 声明，需显式引入）。
    testImplementation(libs.kotlinx.serialization.json)
}