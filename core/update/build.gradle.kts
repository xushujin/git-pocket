plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.gitpocket.core.update"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    // FileProvider：把私有目录里的 APK 授权给系统安装器
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    // 单测跑在 JVM 上，Android 自带的 org.json 不可用，这里补一份实现（版本与 Android 行为一致）
    testImplementation(libs.json)
}
