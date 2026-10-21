import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 签名解析：两条通道（本地 keystore.properties / CI 环境变量），共用这一份实现。
 * 解析不到时返回 null；只有 release 任务才会因此失败 —— debug 构建不受影响。
 */
val releaseSigningProps: Properties? = run {
    val propsFile = rootProject.file("keystore.properties")
    val fromFile = if (propsFile.exists()) {
        Properties().apply { propsFile.inputStream().use(::load) }
    } else {
        null
    }
    fromFile ?: System.getenv("GITPOCKET_KEYSTORE_FILE")?.let { path ->
        Properties().apply {
            setProperty("storeFile", path)
            setProperty("storePassword", System.getenv("GITPOCKET_STORE_PASSWORD").orEmpty())
            setProperty("keyAlias", System.getenv("GITPOCKET_KEY_ALIAS").orEmpty())
            setProperty("keyPassword", System.getenv("GITPOCKET_KEY_PASSWORD").orEmpty())
        }
    }
}

// 版本号唯一真源：git tag（v1.2.3 -> versionName 1.2.3 / versionCode 10203）。
// 无 tag 时（本地验收包）用构建时间戳，保证每次交付的包可区分、versionCode 单调递增，
// 这样覆盖安装不会被系统拒绝。
val gitTag: String = System.getenv("GIT_TAG").orEmpty()
val buildStamp: String = SimpleDateFormat("yyMMdd.HHmm", Locale.US)
    .format(Date())
val versionNameValue: String = if (gitTag.isNotBlank()) {
    gitTag.removePrefix("v")
} else {
    "0.1.0-dev.$buildStamp"
}
// versionCode 始终取构建时间戳（yyMMddHH）：tag 派生的数字（v1.0.0 -> 10000）
// 会小于开发包（26xxxxxx），导致正式包无法覆盖安装。versionName 仍由 tag 决定。
val versionCodeValue: Int = SimpleDateFormat("yyMMddHH", Locale.US)
    .format(Date())
    .toInt()

android {
    namespace = "app.gitpocket"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gitpocket"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue
    }

    signingConfigs {
        if (releaseSigningProps != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProps.getProperty("storeFile"))
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningProps != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        // 版本升级提示属于发布节奏决策，不应阻断构建
        disable += setOf(
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "NewerVersionAvailable",
            // adaptive-icon 必须放在 -v26 目录，lint 会误报"目录多余"
            "ObsoleteSdkInt",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 产物命名：release 带版本号（GitPocket-1.0.1.apk），debug 保持简短（GitPocket-debug.apk）
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // AGP 8.9 的公开 VariantOutput 还没有 outputFileName，只能用 impl 设置
            (output as com.android.build.api.variant.impl.VariantOutputImpl).outputFileName.set(
                if (variant.buildType == "debug") {
                    "GitPocket-debug.apk"
                } else {
                    "GitPocket-$versionNameValue.apk"
                }
            )
        }
    }
}

// release 守卫：请求 release 构建但缺少签名配置时直接失败，绝不静默产出未签名包。
listOf("assembleRelease", "bundleRelease", "packageRelease").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        doFirst {
            if (releaseSigningProps == null) {
                throw GradleException(
                    "缺少 release 签名配置：请提供 keystore.properties 或 GITPOCKET_KEYSTORE_FILE 等环境变量"
                )
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:git"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:security"))
    implementation(project(":core:data"))
    implementation(project(":feature:repos"))
    implementation(project(":feature:repo"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:commit"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
